package com.curveon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process

/**
 * Keeps Curveon alive with GPS while another app is in front (e.g. Google Maps on the hotspot).
 * The actual work lives in the ViewModel; this service only holds the foreground priority and
 * the "while in use" location access Android requires for background GPS.
 *
 * It runs while Curveon is awake: on screen, navigating or downloading maps. Out of sight
 * otherwise, Curveon sleeps and the service stops, so nothing uses the battery. Its notification
 * offers to close Curveon.
 */
class RideService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        running = true
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_QUIT -> { quit(); return START_NOT_STICKY }
            ACTION_NAV -> getSystemService(NotificationManager::class.java).notify(ID, notification())
            else -> if (!startForeground()) { stopSelf(); return START_NOT_STICKY }
        }
        // Not sticky: brought back by Android after the process died, there is no screen or ViewModel for it to keep
        // alive, and a location service started from the background is refused (it crashed the Pixel on 2026-10-21).
        return START_NOT_STICKY
    }

    /** False when Android won't have it in the foreground (started while Curveon is out of sight). */
    private fun startForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.service_channel), NotificationManager.IMPORTANCE_LOW))
        nm.deleteNotificationChannel(OLD_WARNING_CHANNEL) // "still using GPS" warnings: sleeping instead now
        return runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(ID, notification())
        }.onFailure { android.util.Log.w("RideService", "Not allowed in the foreground now", it) }.isSuccess
    }

    /** Says whether a trip is being navigated, which the companion's battery test reads (NavWatch). */
    private fun notification() = Notification.Builder(this, CHANNEL)
        .setContentTitle("Curveon")
        .setContentText(getString(if (navigating) R.string.service_navigating else R.string.service_running))
        .setSmallIcon(R.drawable.ic_nav_arrow)
        .setContentIntent(openApp())
        .addAction(quitAction())
        .setOngoing(true)
        .addExtras(android.os.Bundle().apply {
            putBoolean(com.curveon.phone.NavWatch.EXTRA_NAVIGATING, navigating)
        })
        .build()

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE
    )

    private fun quitAction() = Notification.Action.Builder(
        null, getString(R.string.quit_app),
        PendingIntent.getService(this, 1, Intent(this, RideService::class.java).setAction(ACTION_QUIT), PendingIntent.FLAG_IMMUTABLE)
    ).build()

    /** Close Curveon altogether: the screen, GPS, the phone link, OBD – so nothing drains the battery. */
    private fun quit() {
        com.curveon.data.routing.ActiveTrip.forget(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        MainActivity.current?.get()?.finishAndRemoveTask()
        // Give the screen a moment to close (the ride trace is written then), then end the process.
        handler.postDelayed({ Process.killProcess(Process.myPid()) }, 600)
    }

    companion object {
        private const val CHANNEL = "ride"
        private const val OLD_WARNING_CHANNEL = "background"
        private const val ID = 1
        private const val ACTION_QUIT = "com.curveon.QUIT"
        private const val ACTION_NAV = "com.curveon.NAV"

        @Volatile private var running = false
        @Volatile private var navigating = false

        /** A trip is (or isn't) being navigated: shown in the notification. */
        fun navigation(context: Context, navigating: Boolean) {
            if (navigating == this.navigating) return
            this.navigating = navigating
            tell(context, Intent(context, RideService::class.java).setAction(ACTION_NAV))
        }

        fun start(context: Context) = runCatching {
            context.startForegroundService(Intent(context, RideService::class.java))
        }

        /** Curveon goes to sleep: no more foreground priority or background GPS. */
        fun stop(context: Context) {
            if (running) runCatching { context.stopService(Intent(context, RideService::class.java)) }
        }

        // Only to the running service: starting it here would need the location permission first.
        private fun tell(context: Context, intent: Intent) {
            if (running) runCatching { context.startService(intent) }
        }
    }
}
