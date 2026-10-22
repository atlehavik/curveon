package com.curveon.phone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import com.curveon.protocol.PhoneErrorCode
import com.curveon.protocol.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shared state between the notification listener (which sees notifications and media sessions)
 * and whoever shows it: the companion's Bluetooth link to the tablet, or Curveon itself when it
 * runs on the phone and is its own companion.
 */
object PhoneHub {

    /** Marks the companion's test notifications, which are mirrored although they are its own. */
    const val EXTRA_TEST = "com.curveon.companion.TEST"


    data class Status(
        val serviceRunning: Boolean = false,
        val tabletName: String? = null,
        val lastEvent: String = "",
        val mirroredNotifications: Int = 0,
        val mediaTitle: String? = null
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Where messages go: one per connected tablet, or Curveon's own screen. */
    private val tablets = CopyOnWriteArrayList<(Message) -> Unit>()

    /** Curveon on a tablet that is linked to a phone: it hears the groups (which it runs itself) but not the phone's notifications and media. */
    private val groupListeners = CopyOnWriteArrayList<(Message) -> Unit>()
    private val notifications = ConcurrentHashMap<String, Message.NotificationPosted>()

    @Volatile var media: Message.MediaState? = null
        private set
    @Volatile var art: Message.MediaArt? = null
        private set
    @Volatile var mediaSources: Message.MediaSources? = null
        private set

    /** Set by [CurveonNotificationListener]; handles commands that need the listener service. */
    @Volatile var listener: CurveonNotificationListener? = null

    /** Set by [CompanionService]; handles voice replies, which need a running foreground service. */
    @Volatile var voice: VoiceReplier? = null

    /** Set by [CompanionService]; contacts, call log and dialing. */
    @Volatile var phoneBook: PhoneBook? = null

    /** Set by [CompanionService]; navigation sounds from the tablet, played in the rider's headset. */
    @Volatile var cues: CuePlayer? = null

    /** Set by [CompanionService]; spoken navigation from the tablet. */
    @Volatile var speaker: Speaker? = null

    fun update(block: (Status) -> Status) {
        _status.value = block(_status.value)
    }

    // ---- connections ----

    /** Start sending to [sink], beginning with what is showing right now. */
    fun addTablet(sink: (Message) -> Unit, name: String) {
        tablets += sink
        update { it.copy(tabletName = name, lastEvent = "Connected to $name") }
        snapshot().forEach { runCatching { sink(it) } }
        GroupHub.sendSnapshot(sink)
        GroupHub.tabletsChanged()
    }

    fun removeTablet(sink: (Message) -> Unit) {
        tablets -= sink
        if (tablets.isEmpty()) update { it.copy(tabletName = null, lastEvent = "Tablet disconnected") }
        GroupHub.tabletsChanged()
    }

    /** Hears what the groups tell, and starts them; for a tablet that rides with a phone and still keeps its own friends and groups. */
    fun addGroupListener(sink: (Message) -> Unit) {
        groupListeners += sink
        GroupHub.sendSnapshot(sink)
        GroupHub.tabletsChanged()
    }

    fun removeGroupListener(sink: (Message) -> Unit) {
        groupListeners -= sink
        GroupHub.tabletsChanged()
    }

    /** How many tablets (or Curveon's own screen) are listening; the group link is kept up only while someone is. */
    val tabletCount: Int get() = tablets.size + groupListeners.size

    /** Whether a tablet is connected over Bluetooth right now (not Curveon's own screen). */
    val hasTablet: Boolean get() = tablets.isNotEmpty()

    /** What the groups tell: to the tablets, and to a tablet's own group listener. */
    fun broadcastGroup(message: Message) {
        broadcast(message)
        for (t in groupListeners) runCatching { t(message) }.onFailure { groupListeners -= t }
    }

    fun broadcast(message: Message) {
        for (t in tablets) runCatching { t(message) }.onFailure { tablets -= t }
    }

    private fun snapshot(): List<Message> = buildList {
        art?.let { add(it) }
        media?.let { add(it) }
        mediaSources?.let { add(it) }
        addAll(notifications.values.sortedBy { it.timeMs })
    }

    // ---- notifications ----

    fun postNotification(n: Message.NotificationPosted) {
        notifications[n.key] = n
        update { it.copy(mirroredNotifications = notifications.size, lastEvent = "${n.app}: ${n.sender}") }
        broadcast(n)
    }

    fun removeNotification(key: String) {
        if (notifications.remove(key) != null) {
            update { it.copy(mirroredNotifications = notifications.size) }
            broadcast(Message.NotificationRemoved(key))
        }
    }

    // ---- media ----

    fun setMedia(state: Message.MediaState?, newArt: Message.MediaArt?) {
        if (newArt != null && newArt.artId != art?.artId) {
            art = newArt
            broadcast(newArt)
        }
        media = state
        update { it.copy(mediaTitle = state?.let { s -> "${s.title} – ${s.artist}" }) }
        broadcast(state ?: Message.MediaStopped)
    }

    fun setMediaSources(sources: List<Message.MediaSource>) {
        val message = Message.MediaSources(sources)
        mediaSources = message
        broadcast(message)
    }

    // ---- commands from the tablet ----

    fun handle(message: Message) {
        when (message) {
            is Message.MediaCommand -> listener?.mediaCommand(message.command)
            is Message.PlayMediaApp -> listener?.playApp(message.packageName)
            is Message.DismissNotification -> listener?.dismiss(message.key)
            is Message.InvokeAction -> listener?.invokeAction(message.key, message.actionIndex)
            is Message.SendReply -> if (message.key.startsWith(Message.SMS_KEY_PREFIX)) {
                phoneBook?.sendSms(message.key, message.key.removePrefix(Message.SMS_KEY_PREFIX), message.text)
                    ?: broadcast(Message.VoiceReplyState(message.key, Message.VoiceState.ERROR, "SMS unavailable", PhoneErrorCode.SMS_UNAVAILABLE))
            } else listener?.reply(message.key, message.text)
            is Message.StartVoiceReply -> voice?.start(message.key)
                ?: broadcast(Message.VoiceReplyState(message.key, Message.VoiceState.ERROR, "Voice reply unavailable", PhoneErrorCode.VOICE_UNAVAILABLE))
            is Message.CancelVoiceReply -> voice?.cancel()
            is Message.RequestPhoneBook -> phoneBook?.sendPhoneBook()
            is Message.RequestContactPhotos -> phoneBook?.sendPhotos(message.keys)
            is Message.PlaceCall -> phoneBook?.placeCall(message.number)
            is Message.PlayCue -> cues?.play(message.cue, message.side)
            is Message.Speak -> speaker?.speak(message.text, message.language, message.interrupt) { cues?.play(message.cue, message.side) }
            else -> GroupHub.handle(message) // group messages; GroupHub ignores anything else
        }
    }

    // ---- image helpers ----

    fun encodeJpeg(drawable: Drawable?, maxSize: Int, quality: Int = 80): String? {
        drawable ?: return null
        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: run {
            val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: maxSize
            val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: maxSize
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                drawable.setBounds(0, 0, w, h)
                drawable.draw(Canvas(b))
            }
        }
        return encodeJpeg(bitmap, maxSize, quality)
    }

    /** App icons keep their transparency, so PNG rather than JPEG. */
    fun encodePng(drawable: Drawable, size: Int): String {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun encodeJpeg(bitmap: Bitmap?, maxSize: Int, quality: Int = 80): String? {
        bitmap ?: return null
        val scale = minOf(1f, maxSize.toFloat() / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
