package com.curveon.companion

import com.curveon.phone.*
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.curveon.protocol.Message
import com.curveon.protocol.MessageReader
import com.curveon.protocol.MessageWriter
import com.curveon.protocol.Protocol
import kotlin.concurrent.thread

/**
 * Foreground service that accepts the tablet's Bluetooth RFCOMM connection and relays messages.
 * Started from the app while it is visible, which also allows it to use the microphone for
 * voice replies later.
 */
class CompanionService : Service() {

    @Volatile private var running = false
    private var serverSocket: BluetoothServerSocket? = null
    /** The tablet's connection; closed on stop so the tablet hears at once and nothing more comes through. */
    @Volatile private var clientSocket: BluetoothSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        BatteryTest.resume(this)
        if (!running) {
            running = true
            PhoneHub.voice = VoiceReplier(this)
            PhoneHub.phoneBook = PhoneBook(this)
            PhoneHub.cues = CuePlayer(this)
            PhoneHub.speaker = Speaker(this) { lang, availability -> PhoneHub.broadcast(Message.SpeechVoice(lang, availability)) }
            GroupHub.start(this)
            PhoneHub.update { it.copy(serviceRunning = true, lastEvent = "Waiting for the tablet…") }
            thread(name = "curveon-bt-server", isDaemon = true) { acceptLoop() }
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        runCatching { serverSocket?.close() }
        runCatching { clientSocket?.close() }
        runCatching { unregisterReceiver(batteryReceiver) }
        PhoneHub.voice = null
        PhoneHub.phoneBook?.shutdown()
        PhoneHub.phoneBook = null
        PhoneHub.cues?.shutdown()
        PhoneHub.cues = null
        PhoneHub.speaker?.shutdown()
        PhoneHub.speaker = null
        GroupHub.stop()
        PhoneHub.update { it.copy(serviceRunning = false, tabletName = null, lastEvent = "Stopped") }
        super.onDestroy()
    }

    // ---- status for the tablet's Settings → Phone ----

    @Volatile private var lastStatus: Message.PhoneStatus? = null

    /** Fires on every small battery change; the tablet only hears when something it shows changed. */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = phoneStatus(intent)
            if (status == lastStatus) return
            lastStatus = status
            PhoneHub.broadcast(status)
        }
    }

    private fun phoneStatus(battery: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))): Message.PhoneStatus {
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return Message.PhoneStatus(
            model = Build.MODEL,
            batteryPercent = if (level < 0 || scale <= 0) -1 else level * 100 / scale,
            charging = plugged,
            notificationAccess = CurveonNotificationListener.isEnabled(this),
            appVersion = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        )
    }

    @SuppressLint("MissingPermission") // checked by MainActivity before starting the service
    private fun acceptLoop() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        while (running) {
            try {
                if (adapter == null || !adapter.isEnabled) {
                    PhoneHub.update { it.copy(lastEvent = "Bluetooth is off") }
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
                    PhoneHub.update { it.copy(lastEvent = "Bluetooth: ${e.message}") }
                    Thread.sleep(3_000)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun serve(socket: BluetoothSocket) {
        clientSocket = socket
        if (!running) runCatching { socket.close() }
        val name = runCatching { socket.remoteDevice.name }.getOrNull() ?: "tablet"
        val writer = MessageWriter(socket.outputStream)
        val sink: (Message) -> Unit = writer::write
        try {
            writer.write(Message.Hello(Build.MODEL))
            PhoneHub.addTablet(sink, name)
            writer.write(phoneStatus().also { lastStatus = it })
            writer.write(MediaApps.installed(this))
            val reader = MessageReader(socket.inputStream)
            while (running) {
                val msg = reader.read() ?: break
                if (!running) break
                when (msg) {
                    is Message.Ping -> writer.write(Message.Ping)
                    is Message.Hello -> Unit
                    else -> PhoneHub.handle(msg)
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "Tablet connection ended: ${e.message}")
        } finally {
            PhoneHub.removeTablet(sink)
            runCatching { socket.close() }
            clientSocket = null
        }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tablet link", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Curveon Companion")
            .setContentText("Sharing music, messages and calls with your tablet")
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
        const val ACTION_STOP = "com.curveon.companion.STOP"

        fun start(context: Context) = context.startForegroundService(Intent(context, CompanionService::class.java))
        fun stop(context: Context) = context.startService(Intent(context, CompanionService::class.java).setAction(ACTION_STOP))
    }
}
