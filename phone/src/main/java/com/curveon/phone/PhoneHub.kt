package com.curveon.phone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
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

    fun update(block: (Status) -> Status) {
        _status.value = block(_status.value)
    }

    // ---- connections ----

    /** Start sending to [sink], beginning with what is showing right now. */
    fun addTablet(sink: (Message) -> Unit, name: String) {
        tablets += sink
        update { it.copy(tabletName = name, lastEvent = "Connected to $name") }
        snapshot().forEach { runCatching { sink(it) } }
    }

    fun removeTablet(sink: (Message) -> Unit) {
        tablets -= sink
        if (tablets.isEmpty()) update { it.copy(tabletName = null, lastEvent = "Tablet disconnected") }
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
            is Message.SendReply -> listener?.reply(message.key, message.text)
            is Message.StartVoiceReply -> voice?.start(message.key)
                ?: broadcast(Message.VoiceReplyState(message.key, Message.VoiceState.ERROR, "Voice reply unavailable"))
            is Message.CancelVoiceReply -> voice?.cancel()
            is Message.RequestPhoneBook -> phoneBook?.sendPhoneBook()
            is Message.RequestContactPhotos -> phoneBook?.sendPhotos(message.keys)
            is Message.PlaceCall -> phoneBook?.placeCall(message.number)
            else -> Unit
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
