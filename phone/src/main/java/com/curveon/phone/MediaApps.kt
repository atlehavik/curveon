package com.curveon.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
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
                    runCatching { PhoneHub.encodePng(pm.getApplicationIcon(pkg), ICON_PX) }.getOrNull()
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
    private const val BROWSER_WAIT_MS = 3_000L
    /** The tablet gives up after 10 s; stop a little before. */
    private const val GIVE_UP_MS = 9_000L

    /**
     * Start [pkg], which has no media session right now. First through its media browser (asking
     * for "what played last", as Android's own media resumption does). Most apps only let Android
     * Auto and the system in (Spotify, NRK Radio and YouTube Music all refuse), so then a play key
     * is sent to the app. Spotify plays from that. Others only wake up and create a session without
     * playing: once it appears it is told to play. YouTube Music keeps its session inactive, which
     * hides it from us, so it can't be started this way (repeated keys and "play from search" with
     * "Display over other apps" were tried; the latter opens the app behind the lock screen, no sound).
     * [session] is the app's media session once it has one.
     */
    fun start(context: Context, pkg: String, session: () -> MediaController?) {
        val service = browserService(context, pkg)
        Log.i(TAG, "Starting $pkg (browser: ${service?.className})")
        main.removeCallbacksAndMessages(STEPS) // a start still under way for another app
        browser?.disconnect()
        browser = null
        val t0 = SystemClock.elapsedRealtime()
        var keySent = false
        var toldSession = false
        lateinit var check: Runnable
        check = Runnable {
            val c = session()
            val waited = SystemClock.elapsedRealtime() - t0
            when {
                c?.playbackState?.state == PlaybackState.STATE_PLAYING ->
                    return@Runnable done(pkg, if (keySent) "play key" else "browser")
                c != null && !toldSession -> {
                    Log.i(TAG, "$pkg: has a session after $waited ms (${c.playbackState?.state}), play")
                    toldSession = true
                    c.transportControls.play()
                }
                !keySent && waited >= BROWSER_WAIT_MS -> {
                    keySent = true
                    Log.i(TAG, "$pkg: not playing, sending a play key")
                    sendPlayKey(context, pkg)
                }
                waited >= GIVE_UP_MS -> {
                    Log.i(TAG, "$pkg: didn't start") // the tablet notices and tells the rider
                    return@Runnable done(pkg, null)
                }
            }
            main.postAtTime(check, STEPS, SystemClock.uptimeMillis() + 500)
        }
        main.postAtTime(check, STEPS, SystemClock.uptimeMillis() + 500)
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
                    if (!keySent) {
                        keySent = true
                        Log.i(TAG, "$pkg: sending a play key")
                        sendPlayKey(context, pkg)
                    }
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
