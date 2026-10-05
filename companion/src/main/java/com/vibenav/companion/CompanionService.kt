package com.vibenav.companion

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.vibenav.protocol.Message
import com.vibenav.protocol.MessageReader
import com.vibenav.protocol.MessageWriter
import com.vibenav.protocol.Protocol
import kotlin.concurrent.thread

/**
 * Foreground service that accepts the tablet's Bluetooth RFCOMM connection and relays messages.
 * Started from the app while it is visible, which also allows it to use the microphone for
 * voice replies later.
 */
class CompanionService : Service() {

    @Volatile private var running = false
    private var serverSocket: BluetoothServerSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (!running) {
            running = true
            CompanionHub.voice = VoiceReplier(this)
            CompanionHub.update { it.copy(serviceRunning = true, lastEvent = "Waiting for the tablet…") }
            thread(name = "vibenav-bt-server", isDaemon = true) { acceptLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        runCatching { serverSocket?.close() }
        CompanionHub.voice = null
        CompanionHub.update { it.copy(serviceRunning = false, tabletName = null, lastEvent = "Stopped") }
        super.onDestroy()
    }

    @SuppressLint("MissingPermission") // checked by MainActivity before starting the service
    private fun acceptLoop() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        while (running) {
            try {
                if (adapter == null || !adapter.isEnabled) {
                    CompanionHub.update { it.copy(lastEvent = "Bluetooth is off") }
                    Thread.sleep(5_000)
                    continue
                }
                val server = adapter.listenUsingRfcommWithServiceRecord(Protocol.SERVICE_NAME, Protocol.SERVICE_UUID)
                serverSocket = server
                val socket = server.accept()
                runCatching { server.close() } // one tablet at a time; reopen after it leaves
                serve(socket)
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "Bluetooth server error", e)
                    CompanionHub.update { it.copy(lastEvent = "Bluetooth: ${e.message}") }
                    Thread.sleep(3_000)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun serve(socket: BluetoothSocket) {
        val name = runCatching { socket.remoteDevice.name }.getOrNull() ?: "tablet"
        val writer = MessageWriter(socket.outputStream)
        try {
            writer.write(Message.Hello(Build.MODEL))
            CompanionHub.addTablet(writer, name)
            val reader = MessageReader(socket.inputStream)
            while (running) {
                val msg = reader.read() ?: break
                when (msg) {
                    is Message.Ping -> writer.write(Message.Ping)
                    is Message.Hello -> Unit
                    else -> CompanionHub.handle(msg)
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "Tablet connection ended: ${e.message}")
        } finally {
            CompanionHub.removeTablet(writer)
            runCatching { socket.close() }
        }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tablet link", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("VibeNav Companion")
            .setContentText("Sharing music and messages with your tablet")
            .setSmallIcon(R.drawable.ic_companion)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        val types = if (hasMicPermission()) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        startForeground(NOTIFICATION_ID, notification, types)
    }

    private fun hasMicPermission() =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "CompanionService"
        private const val CHANNEL = "link"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.vibenav.companion.STOP"

        fun start(context: Context) = context.startForegroundService(Intent(context, CompanionService::class.java))
        fun stop(context: Context) = context.startService(Intent(context, CompanionService::class.java).setAction(ACTION_STOP))
    }
}
