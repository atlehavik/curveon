package com.curveon.phone

import android.app.Notification
import android.os.Build
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.curveon.protocol.Message
import java.security.MessageDigest

/**
 * Sees every notification and, through the notification-listener permission, the active media
 * sessions. Mirrors messages, calls and "now playing" to the tablet via [PhoneHub].
 */
class CurveonNotificationListener : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())
    private val active = HashMap<String, StatusBarNotification>()
    private var controller: MediaController? = null
    private var sessionManager: MediaSessionManager? = null
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { onSessionsChanged(it.orEmpty()) }

    override fun onListenerConnected() {
        PhoneHub.listener = this
        activeNotifications?.forEach(::onNotificationPosted)
        sessionManager = getSystemService(MediaSessionManager::class.java)?.also { msm ->
            val me = ComponentName(this, CurveonNotificationListener::class.java)
            msm.addOnActiveSessionsChangedListener(sessionsListener, me, main)
            onSessionsChanged(msm.getActiveSessions(me))
        }
        PhoneHub.update { it.copy(lastEvent = "Notification access active") }
    }

    override fun onListenerDisconnected() {
        if (PhoneHub.listener === this) PhoneHub.listener = null
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        controller?.unregisterCallback(mediaCallback)
        watchers.forEach { (c, cb) -> c.unregisterCallback(cb) }
        watchers.clear()
    }

    // ---------------------------------------------------------------- notifications

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        NavWatch.onPosted(sbn)
        val message = runCatching { toMessage(sbn) }.onFailure { Log.w(TAG, "Skipping ${sbn.key}", it) }.getOrNull() ?: return
        synchronized(active) { active[sbn.key] = sbn }
        PhoneHub.postNotification(message)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NavWatch.onRemoved(sbn)
        synchronized(active) { active.remove(sbn.key) }
        PhoneHub.removeNotification(sbn.key)
    }

    private fun toMessage(sbn: StatusBarNotification): Message.NotificationPosted? {
        val n = sbn.notification
        // Our own notifications are ignored, except the test scenarios.
        if (sbn.packageName == packageName && !n.extras.getBoolean(PhoneHub.EXTRA_TEST)) return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val isCall = n.category == Notification.CATEGORY_CALL
        val replyAction = findReplyAction(n)
        val isMessage = n.category == Notification.CATEGORY_MESSAGE || replyAction != null ||
            n.extras.getString(Notification.EXTRA_TEMPLATE) == "android.app.Notification\$MessagingStyle"
        if (!isCall && !isMessage) return null
        if (sbn.isOngoing && !isCall) return null

        val extras = n.extras
        @Suppress("DEPRECATION")
        val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(extras.getParcelableArray(Notification.EXTRA_MESSAGES))
        val last = messages.lastOrNull()
        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val sender = last?.senderPerson?.name?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: appLabel(sbn.packageName)
        val text = last?.text?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: return null

        // Calls get a big photo (the tablet shows it full-card), preferably the phone-book original,
        // since dialer notifications often carry only a small thumbnail.
        val px = if (isCall) CALL_PX else AVATAR_PX
        val avatar = (if (isCall) contactPhoto(sender, px) else null)
            ?: last?.senderPerson?.icon?.loadDrawable(this)?.let { PhoneHub.encodeJpeg(it, px) }
            ?: n.getLargeIcon()?.loadDrawable(this)?.let { PhoneHub.encodeJpeg(it, px) }
            ?: contactPhoto(sender, px)

        return Message.NotificationPosted(
            key = sbn.key,
            app = appLabel(sbn.packageName),
            sender = if (conversation != null && conversation != sender) "$sender ($conversation)" else sender,
            text = text,
            timeMs = sbn.postTime,
            avatarJpegBase64 = avatar,
            canReply = replyAction != null,
            isCall = isCall,
            actions = if (isCall) n.actions?.map { it.title.toString() }.orEmpty() else emptyList(),
            callOngoing = isCall && callAnswered(n)
        )
    }

    /** An answered call: the notification's call type says so (Android 12+), else it counts up the call time. */
    private fun callAnswered(n: Notification): Boolean {
        val type = if (Build.VERSION.SDK_INT >= 31) n.extras.getInt(Notification.EXTRA_CALL_TYPE, 0) else 0
        return if (type != 0) type == Notification.CallStyle.CALL_TYPE_ONGOING
        else n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
    }

    private fun findReplyAction(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }

    private fun appLabel(pkg: String): String = MediaApps.label(this, pkg)

    /** Falls back to the phone book so the tablet shows real faces, like Android Auto does. */
    private fun contactPhoto(name: String, px: Int = AVATAR_PX): String? {
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        val uri: Uri = contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.PHOTO_URI, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI),
            "${ContactsContract.Contacts.DISPLAY_NAME} = ? COLLATE NOCASE", arrayOf(name), null
        )?.use { c -> if (c.moveToFirst()) (c.getString(0) ?: c.getString(1))?.let(Uri::parse) else null } ?: return null
        return runCatching {
            contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
        }.getOrNull()?.let { PhoneHub.encodeJpeg(it, px, if (px > AVATAR_PX) 85 else 80) }
    }

    fun dismiss(key: String) = main.post { runCatching { cancelNotification(key) } }

    fun invokeAction(key: String, index: Int) {
        val sbn = synchronized(active) { active[key] } ?: return
        runCatching { sbn.notification.actions?.getOrNull(index)?.actionIntent?.send() }
    }

    /** Sends [text] through the notification's own reply action (works for SMS, WhatsApp, Signal, …). */
    fun reply(key: String, text: String) {
        val sbn = synchronized(active) { active[key] }
        val action = sbn?.let { findReplyAction(it.notification) }
        if (action == null) {
            PhoneHub.broadcast(Message.VoiceReplyState(key, Message.VoiceState.ERROR, "This message can't be replied to"))
            return
        }
        val intent = Intent()
        val results = Bundle()
        action.remoteInputs.filter { it.allowFreeFormInput }.forEach { results.putCharSequence(it.resultKey, text) }
        RemoteInput.addResultsToIntent(action.remoteInputs, intent, results)
        try {
            action.actionIntent.send(this, 0, intent)
            PhoneHub.broadcast(Message.VoiceReplyState(key, Message.VoiceState.SENT, text))
        } catch (e: PendingIntent.CanceledException) {
            PhoneHub.broadcast(Message.VoiceReplyState(key, Message.VoiceState.ERROR, "Reply failed"))
        }
    }

    // ---------------------------------------------------------------- media

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = pushMedia()
        override fun onPlaybackStateChanged(state: PlaybackState?) = pushMedia()
        override fun onSessionDestroyed() {
            controller = null
            PhoneHub.setMedia(null, null)
        }
    }

    /** Every media app with a session, playing or paused; each one watched for starting to play. */
    private var sessions: List<MediaController> = emptyList()
    private val watchers = HashMap<MediaController, MediaController.Callback>()

    private fun onSessionsChanged(controllers: List<MediaController>) {
        watchers.forEach { (c, cb) -> c.unregisterCallback(cb) }
        watchers.clear()
        sessions = controllers
        controllers.forEach { c ->
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    // The app that started playing last is the one the tablet shows and controls.
                    if (state?.state == PlaybackState.STATE_PLAYING && c.sessionToken != controller?.sessionToken) follow(c)
                    pushSources()
                }
            }
            c.registerCallback(cb, main)
            watchers[c] = cb
        }
        val current = controllers.firstOrNull { it.sessionToken == controller?.sessionToken }
        val best = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: current ?: controllers.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) follow(best)
        pushSources()
    }

    private fun follow(c: MediaController?) {
        controller?.unregisterCallback(mediaCallback)
        controller = c
        c?.registerCallback(mediaCallback, main)
        pushMedia()
    }

    private val icons = HashMap<String, String?>()
    private var lastSources: List<Message.MediaSource>? = null

    private fun pushSources() {
        val list = sessions.distinctBy { it.packageName }.map { c ->
            Message.MediaSource(
                c.packageName, appLabel(c.packageName), c.playbackState?.state == PlaybackState.STATE_PLAYING,
                icons.getOrPut(c.packageName) {
                    runCatching { PhoneHub.encodePng(packageManager.getApplicationIcon(c.packageName), ICON_PX) }.getOrNull()
                }
            )
        }
        if (list == lastSources) return
        lastSources = list
        PhoneHub.setMediaSources(list)
    }

    /**
     * The rider picked this app on the tablet: pause whatever else plays and start it. A favourite
     * that isn't running (no media session) gets started through [MediaApps.start].
     */
    fun playApp(pkg: String) = main.post {
        sessions.filter { it.packageName != pkg && it.playbackState?.state == PlaybackState.STATE_PLAYING }
            .forEach { it.transportControls.pause() }
        val target = sessions.firstOrNull { it.packageName == pkg }
        if (target != null) {
            follow(target)
            target.transportControls.play()
        } else {
            MediaApps.start(this, pkg) { sessions.firstOrNull { it.packageName == pkg } }
        }
    }

    private var lastArtId: String? = null
    private val periodicPush = object : Runnable {
        override fun run() {
            pushMedia()
        }
    }

    private fun pushMedia() {
        main.removeCallbacks(periodicPush)
        val c = controller ?: return PhoneHub.setMedia(null, null)
        val md = c.metadata
        val ps = c.playbackState
        val playing = ps?.state == PlaybackState.STATE_PLAYING
        val position = ps?.let {
            if (playing) it.position + ((android.os.SystemClock.elapsedRealtime() - it.lastPositionUpdateTime) * it.playbackSpeed).toLong()
            else it.position
        } ?: 0L
        val bitmap: Bitmap? = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        var art: Message.MediaArt? = null
        val artJpeg = bitmap?.let { PhoneHub.encodeJpeg(it, ART_PX, 85) }
        val artId = artJpeg?.let { sha1(it) }
        if (artJpeg != null && artId != lastArtId) art = Message.MediaArt(artId!!, artJpeg)
        lastArtId = artId
        PhoneHub.setMedia(
            Message.MediaState(
                title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "",
                artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "",
                album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
                app = appLabel(c.packageName),
                durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
                positionMs = position.coerceAtLeast(0L),
                playing = playing,
                artId = artId
            ),
            art
        )
        // Re-sync the position now and then so the tablet's progress bar doesn't drift.
        if (playing) main.postDelayed(periodicPush, 15_000)
    }

    fun mediaCommand(command: Message.MediaAction) = main.post {
        // Nothing playing (no active session): a "play" key press makes Android resume the app
        // that played last, just like pressing play on a headset.
        val controls = controller?.transportControls ?: run {
            if (command == Message.MediaAction.PLAY_PAUSE) {
                val am = getSystemService(android.media.AudioManager::class.java)
                for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
                    am.dispatchMediaKeyEvent(android.view.KeyEvent(action, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
                }
            }
            return@post
        }
        when (command) {
            Message.MediaAction.PLAY_PAUSE ->
                if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
            Message.MediaAction.NEXT -> controls.skipToNext()
            Message.MediaAction.PREVIOUS -> controls.skipToPrevious()
        }
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    companion object {
        fun isEnabled(context: android.content.Context): Boolean {
            val me = ComponentName(context, CurveonNotificationListener::class.java).flattenToString()
            return android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")?.contains(me) == true
        }

        private const val TAG = "CurveonListener"
        private const val AVATAR_PX = 128
        private const val CALL_PX = 512
        private const val ART_PX = 360
        private const val ICON_PX = 96
    }
}
