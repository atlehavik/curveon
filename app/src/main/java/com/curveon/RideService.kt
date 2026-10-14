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
 * Its notification offers to close Curveon. And when Curveon has been out of sight for a while
 * without a trip to navigate, a second notification (with a sound) says it is still using GPS and
 * battery, and offers the same.
 */
class RideService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val warn = Runnable { showBackgroundWarning() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(warn)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_QUIT -> { quit(); return START_NOT_STICKY }
            ACTION_SHOWN -> {
                handler.removeCallbacks(warn)
                getSystemService(NotificationManager::class.java).cancel(WARNING_ID)
            }
            ACTION_NAV -> getSystemService(NotificationManager::class.java).notify(ID, notification())
            ACTION_HIDDEN -> {
                handler.removeCallbacks(warn)
                // Navigating with another app in front is what the service is for: no warning then.
                if (!intent.getBooleanExtra(EXTRA_NAVIGATING, false)) handler.postDelayed(warn, BACKGROUND_WARN_MS)
            }
            else -> startForeground()
        }
        return START_STICKY
    }

    private fun startForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.service_channel), NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(ID, notification())
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

    private fun showBackgroundWarning() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(WARNING_CHANNEL, getString(R.string.background_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val minutes = (BACKGROUND_WARN_MS / 60_000).toInt()
        nm.notify(WARNING_ID, Notification.Builder(this, WARNING_CHANNEL)
            .setContentTitle(getString(R.string.background_title))
            .setContentText(getString(R.string.background_text, minutes))
            .setStyle(Notification.BigTextStyle().bigText(getString(R.string.background_text, minutes)))
            .setSmallIcon(R.drawable.ic_nav_arrow)
            .setContentIntent(openApp())
            .addAction(quitAction())
            .setAutoCancel(true)
            .build())
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE
    )

    private fun quitAction() = Notification.Action.Builder(
        null, getString(R.string.quit_app),
        PendingIntent.getService(this, 1, Intent(this, RideService::class.java).setAction(ACTION_QUIT), PendingIntent.FLAG_IMMUTABLE)
    ).build()

    /** Close Curveon altogether: the screen, GPS, the phone link, OBD – so nothing drains the battery. */
    private fun quit() {
        handler.removeCallbacks(warn)
        getSystemService(NotificationManager::class.java).cancel(WARNING_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        MainActivity.current?.get()?.finishAndRemoveTask()
        // Give the screen a moment to close (the ride trace is written then), then end the process.
        handler.postDelayed({ Process.killProcess(Process.myPid()) }, 600)
    }

    companion object {
        private const val CHANNEL = "ride"
        private const val WARNING_CHANNEL = "background"
        private const val ID = 1
        private const val WARNING_ID = 2
        private const val ACTION_QUIT = "com.curveon.QUIT"
        private const val ACTION_SHOWN = "com.curveon.SHOWN"
        private const val ACTION_HIDDEN = "com.curveon.HIDDEN"
        private const val ACTION_NAV = "com.curveon.NAV"
        private const val EXTRA_NAVIGATING = "navigating"
        /** Out of sight this long without a trip: warn that Curveon is still using GPS and battery. */
        private const val BACKGROUND_WARN_MS = 10 * 60_000L

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

        /** Curveon is on screen again: no warning. */
        fun shown(context: Context) = tell(context, Intent(context, RideService::class.java).setAction(ACTION_SHOWN))

        /** Curveon went out of sight; [navigating] = a trip is being navigated (no warning then). */
        fun hidden(context: Context, navigating: Boolean) =
            tell(context, Intent(context, RideService::class.java).setAction(ACTION_HIDDEN).putExtra(EXTRA_NAVIGATING, navigating))

        // Only to the running service: starting it here would need the location permission first.
        private fun tell(context: Context, intent: Intent) {
            if (running) runCatching { context.startService(intent) }
        }
    }
}
