package com.curveon.companion

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.media.MediaBrowserService
import android.util.Log
import android.view.KeyEvent
import com.curveon.protocol.Message

/**
 * The phone's music and podcast apps, and starting one that isn't running yet.
 *
 * An app counts as a media app when it offers a media browser (what Android Auto and the
 * Bluetooth/car media screens use) and can be opened from the launcher. The same browser lets us
 * start it without opening it on screen, which Android wouldn't allow from the background anyway.
 */
object MediaApps {

    private const val TAG = "CurveonMedia"
    private const val ICON_PX = 96

    /** Offer a media browser but aren't something to listen to while riding. */
    private val IGNORED = setOf("com.google.android.googlequicksearchbox")

    fun installed(context: Context): Message.InstalledMediaApps {
        val pm = context.packageManager
        val apps = pm.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE), 0)
            .map { it.serviceInfo.packageName }
            .distinct()
            .filter { it != context.packageName && it !in IGNORED && pm.getLaunchIntentForPackage(it) != null }
            .map { pkg ->
                Message.MediaSource(
                    pkg, label(context, pkg), false,
                    runCatching { CompanionHub.encodePng(pm.getApplicationIcon(pkg), ICON_PX) }.getOrNull()
                )
            }
            .sortedBy { it.app.lowercase() }
        return Message.InstalledMediaApps(apps)
    }

    fun label(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun browserService(context: Context, pkg: String): ComponentName? =
        context.packageManager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(pkg), 0)
            .firstOrNull()?.serviceInfo?.let { ComponentName(it.packageName, it.name) }

    private val main = Handler(Looper.getMainLooper())
    private var browser: MediaBrowser? = null
    /** Token for the pending steps of a start, so a new start cancels them. */
    private val STEPS = Any()

    /**
     * Start [pkg], which has no media session right now. First through its media browser (asking
     * for "what played last", as Android's own media resumption does); if that doesn't get it
     * playing, with a play key sent to the app.
     * [isPlaying] tells whether the app has started playing by now.
     */
    fun start(context: Context, pkg: String, isPlaying: () -> Boolean) {
        val service = browserService(context, pkg)
        Log.i(TAG, "Starting $pkg (browser: ${service?.className})")
        main.removeCallbacksAndMessages(STEPS) // a start still under way for another app
        browser?.disconnect()
        browser = null
        val t0 = SystemClock.elapsedRealtime()
        val giveUp = Runnable {
            if (isPlaying()) return@Runnable done(pkg, "play key")
            Log.i(TAG, "$pkg: didn't start") // the tablet notices and tells the rider
            done(pkg, null)
        }
        val playKey = Runnable {
            if (isPlaying()) return@Runnable done(pkg, "browser")
            Log.i(TAG, "$pkg: not playing, sending a play key")
            sendPlayKey(context, pkg)
            main.postAtTime(giveUp, STEPS, SystemClock.uptimeMillis() + 4_000)
        }
        // Give the browser a moment; one that refuses (Spotify does) goes straight to the play key.
        main.postAtTime(playKey, STEPS, SystemClock.uptimeMillis() + 4_000)
        if (service != null) {
            val hints = Bundle().apply { putBoolean(MediaBrowserService.BrowserRoot.EXTRA_RECENT, true) }
            lateinit var b: MediaBrowser
            b = MediaBrowser(context, service, object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    Log.i(TAG, "$pkg: browser connected after ${SystemClock.elapsedRealtime() - t0} ms, play")
                    runCatching { MediaController(context, b.sessionToken).transportControls.play() }
                        .onFailure { Log.w(TAG, "$pkg: play through browser failed", it) }
                }
                override fun onConnectionFailed() {
                    Log.i(TAG, "$pkg: browser refused the connection")
                    main.removeCallbacksAndMessages(STEPS)
                    playKey.run()
                }
            }, hints)
            browser = b
            runCatching { b.connect() }.onFailure { Log.w(TAG, "$pkg: browser connect failed", it) }
        }
    }

    private fun done(pkg: String, how: String?) {
        if (how != null) Log.i(TAG, "$pkg: playing (started by $how)")
        // Once it plays it has its own session and keeps running; the browser isn't needed.
        main.postDelayed({ browser?.disconnect(); browser = null }, 2_000)
    }

    private fun sendPlayKey(context: Context, pkg: String) {
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY))
            runCatching { context.sendBroadcast(intent) }
        }
    }
}
