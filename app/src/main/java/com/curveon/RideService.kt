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
import android.os.IBinder

/**
 * Keeps Curveon alive with GPS while another app is in front (e.g. Google Maps on the hotspot).
 * The actual work lives in the ViewModel; this service only holds the foreground priority and
 * the "while in use" location access Android requires for background GPS.
 */
class RideService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Riding", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Curveon")
            .setContentText("GPS and navigation are running")
            .setSmallIcon(R.drawable.ic_nav_arrow)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(ID, notification)
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "ride"
        private const val ID = 1

        fun start(context: Context) = runCatching {
            context.startForegroundService(Intent(context, RideService::class.java))
        }
    }
}
