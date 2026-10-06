package com.curveon.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import android.util.Log
import com.curveon.data.model.MusicTrack
import com.curveon.data.model.NotificationItem
import com.curveon.protocol.Message
import com.curveon.protocol.MessageReader
import com.curveon.protocol.MessageWriter
import com.curveon.protocol.Protocol
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CompanionConnectionState {
    NO_PHONE_SELECTED,
    NO_PERMISSION,
    BLUETOOTH_OFF,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    DEMO
}

data class PairedPhone(val name: String, val address: String)

/** A spoken reply in progress: recorded on the phone, confirmed on the tablet. */
data class VoiceReply(
    val notificationKey: String,
    val senderName: String,
    val state: Message.VoiceState,
    val text: String = ""
)

/**
 * Tablet side of the phone link. Connects to the Curveon Companion app on the paired phone over
 * Bluetooth RFCOMM and keeps reconnecting while the app runs.
 */
class PhoneCompanionManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val prefs = context.getSharedPreferences("phone_link", Context.MODE_PRIVATE)

    private val _connectionState = MutableStateFlow(CompanionConnectionState.DISCONNECTED)
    val connectionState: StateFlow<CompanionConnectionState> = _connectionState.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow(prefs.getString(KEY_NAME, null) ?: "")
    val connectedDeviceName: StateFlow<String> = _connectedDeviceName.asStateFlow()

    private val _currentTrack = MutableStateFlow<MusicTrack?>(null)
    val currentTrack: StateFlow<MusicTrack?> = _currentTrack.asStateFlow()

    private val _albumArt = MutableStateFlow<Bitmap?>(null)
    val albumArt: StateFlow<Bitmap?> = _albumArt.asStateFlow()

    private val _notifications = MutableStateFlow<List<NotificationItem>>(emptyList())
    val notifications: StateFlow<List<NotificationItem>> = _notifications.asStateFlow()

    private val _voiceReply = MutableStateFlow<VoiceReply?>(null)
    val voiceReply: StateFlow<VoiceReply?> = _voiceReply.asStateFlow()

    @Volatile private var writer: MessageWriter? = null
    @Volatile private var socket: BluetoothSocket? = null
    private var linkJob: Job? = null
    private var tickerJob: Job? = null
    private val artCache = HashMap<String, Bitmap>()

    /**
     * Messages dismissed on the tablet: key -> text at the time. Dismissing only hides them here
     * (like Android Auto); they stay unread on the phone. A new message in the same chat
     * changes the text and brings it back.
     */
    private val hidden = HashMap<String, String>()

    init {
        if (prefs.getBoolean(KEY_DEMO, false)) startDemo() else start()
    }

    // ------------------------------------------------------------------ connection

    fun selectedPhoneAddress(): String? = prefs.getString(KEY_ADDRESS, null)

    @SuppressLint("MissingPermission")
    fun pairedPhones(): List<PairedPhone> {
        if (!hasPermission()) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return adapter.bondedDevices.orEmpty()
            .filter { it.bluetoothClass?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.PHONE || it.bluetoothClass == null }
            .map { PairedPhone(it.name ?: it.address, it.address) }
            .sortedBy { it.name }
    }

    fun selectPhone(phone: PairedPhone) {
        prefs.edit().putString(KEY_ADDRESS, phone.address).putString(KEY_NAME, phone.name).putBoolean(KEY_DEMO, false).apply()
        _connectedDeviceName.value = phone.name
        restart()
    }

    /** Re-evaluate after permissions were granted or Bluetooth was switched on. */
    fun restart() {
        stopLink()
        if (prefs.getBoolean(KEY_DEMO, false)) startDemo() else start()
    }

    private fun start() {
        linkJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val address = selectedPhoneAddress()
                when {
                    !hasPermission() -> _connectionState.value = CompanionConnectionState.NO_PERMISSION
                    address == null -> _connectionState.value = CompanionConnectionState.NO_PHONE_SELECTED
                    !bluetoothOn() -> _connectionState.value = CompanionConnectionState.BLUETOOTH_OFF
                    else -> runCatching { connectAndServe(address) }.onFailure { Log.i(TAG, "Phone link: ${it.message}") }
                }
                clearPhoneState()
                if (_connectionState.value == CompanionConnectionState.CONNECTED || _connectionState.value == CompanionConnectionState.CONNECTING) {
                    _connectionState.value = CompanionConnectionState.DISCONNECTED
                }
                delay(RETRY_MS)
            }
        }
    }

    private fun stopLink() {
        linkJob?.cancel()
        tickerJob?.cancel()
        runCatching { socket?.close() }
        socket = null
        writer = null
        clearPhoneState()
    }

    @SuppressLint("MissingPermission")
    private fun connectAndServe(address: String) {
        _connectionState.value = CompanionConnectionState.CONNECTING
        val adapter = context.getSystemService(BluetoothManager::class.java)!!.adapter
        val device = adapter.getRemoteDevice(address)
        val s = device.createRfcommSocketToServiceRecord(Protocol.SERVICE_UUID)
        socket = s
        try {
            s.connect()
            val w = MessageWriter(s.outputStream)
            writer = w
            w.write(Message.Hello(Build.MODEL))
            _connectionState.value = CompanionConnectionState.CONNECTED
            _connectedDeviceName.value = device.name ?: address
            startTicker()
            val reader = MessageReader(s.inputStream)
            while (true) {
                val msg = reader.read() ?: break
                handle(msg)
            }
        } finally {
            writer = null
            tickerJob?.cancel()
            runCatching { s.close() }
        }
    }

    private fun handle(msg: Message) {
        when (msg) {
            is Message.Hello -> _connectedDeviceName.value = _connectedDeviceName.value.ifEmpty { msg.deviceName }
            is Message.MediaState -> {
                _currentTrack.value = MusicTrack(
                    title = msg.title, artist = msg.artist, album = msg.album,
                    durationSeconds = (msg.durationMs / 1000).toInt(),
                    currentPositionSeconds = (msg.positionMs / 1000).toInt(),
                    isPlaying = msg.playing, sourceApp = msg.app, artId = msg.artId
                )
                _albumArt.value = msg.artId?.let { artCache[it] }
            }
            is Message.MediaArt -> decode(msg.jpegBase64)?.let { bmp ->
                if (artCache.size > 20) artCache.clear()
                artCache[msg.artId] = bmp
                if (_currentTrack.value?.artId == msg.artId) _albumArt.value = bmp
            }
            Message.MediaStopped -> { _currentTrack.value = null; _albumArt.value = null }
            is Message.NotificationPosted -> {
                if (hidden[msg.key] == msg.text) return
                hidden.remove(msg.key)
                val item = NotificationItem(
                    id = msg.key, appName = msg.app, senderName = msg.sender, message = msg.text,
                    timestampMs = msg.timeMs, avatar = msg.avatarJpegBase64?.let(::decode),
                    canReply = msg.canReply, isIncomingCall = msg.isCall, actions = msg.actions
                )
                _notifications.value = listOf(item) + _notifications.value.filterNot { it.id == msg.key }
            }
            is Message.NotificationRemoved -> {
                hidden.remove(msg.key)
                _notifications.value = _notifications.value.filterNot { it.id == msg.key }
            }
            is Message.VoiceReplyState -> {
                val current = _voiceReply.value
                if (current?.notificationKey == msg.key) {
                    _voiceReply.value = current.copy(state = msg.state, text = msg.text.ifEmpty { current.text })
                    if (msg.state == Message.VoiceState.SENT) scope.launch { delay(2500); if (_voiceReply.value?.state == Message.VoiceState.SENT) _voiceReply.value = null }
                }
            }
            else -> Unit
        }
    }

    /** Moves the progress bar between the phone's (infrequent) position updates. */
    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(1000)
                val t = _currentTrack.value ?: continue
                if (t.isPlaying && t.currentPositionSeconds < t.durationSeconds) {
                    _currentTrack.value = t.copy(currentPositionSeconds = t.currentPositionSeconds + 1)
                }
            }
        }
    }

    private fun clearPhoneState() {
        _currentTrack.value = null
        _albumArt.value = null
        _notifications.value = emptyList()
        _voiceReply.value = null
        hidden.clear()
    }

    // ------------------------------------------------------------------ commands

    private fun send(message: Message) {
        if (_connectionState.value == CompanionConnectionState.DEMO) return demoCommand(message)
        val w = writer ?: return
        scope.launch(Dispatchers.IO) { runCatching { w.write(message) } }
    }

    fun togglePlayPause() {
        _currentTrack.value?.let { _currentTrack.value = it.copy(isPlaying = !it.isPlaying) } // feels instant
        send(Message.MediaCommand(Message.MediaAction.PLAY_PAUSE))
    }

    fun skipNext() = send(Message.MediaCommand(Message.MediaAction.NEXT))

    fun skipPrevious() = send(Message.MediaCommand(Message.MediaAction.PREVIOUS))

    /** Hides a message on the tablet only; it stays unread on the phone. */
    fun dismissNotification(id: String) {
        _notifications.value.firstOrNull { it.id == id }?.let { hidden[id] = it.message }
        _notifications.value = _notifications.value.filterNot { it.id == id }
    }

    fun invokeAction(id: String, index: Int) = send(Message.InvokeAction(id, index))

    fun startVoiceReply(id: String) {
        val n = _notifications.value.firstOrNull { it.id == id } ?: return
        _voiceReply.value = VoiceReply(id, n.senderName, Message.VoiceState.LISTENING)
        send(Message.StartVoiceReply(id))
    }

    fun cancelVoiceReply() {
        _voiceReply.value?.let { send(Message.CancelVoiceReply(it.notificationKey)) }
        _voiceReply.value = null
    }

    fun confirmVoiceReply() {
        val r = _voiceReply.value ?: return
        if (r.text.isBlank()) return
        send(Message.SendReply(r.notificationKey, r.text))
    }

    // ------------------------------------------------------------------ demo mode

    val isDemo get() = prefs.getBoolean(KEY_DEMO, false)

    /** Sample phone data so the dashboard can be tried without the phone (e.g. in the emulator). */
    fun setDemo(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEMO, enabled).apply()
        restart()
    }

    private fun startDemo() {
        _connectionState.value = CompanionConnectionState.DEMO
        _connectedDeviceName.value = "Demo phone"
        _currentTrack.value = MusicTrack("Riders on the Storm", "The Doors", "L.A. Woman", 434, 142, true, "Spotify", "demo")
        _albumArt.value = runCatching {
            BitmapFactory.decodeResource(context.resources, com.curveon.R.drawable.motorcycle_album_art_1790798891980)
        }.getOrNull()
        _notifications.value = listOf(
            NotificationItem("demo1", "WhatsApp", "Kari Nordmann", "Er på Dombås om 20 min, møtes på Statoil? ☕",
                System.currentTimeMillis() - 120_000, canReply = true),
            NotificationItem("demo2", "Messages", "MC-klubben", "Søndagsturen går fra Lillehammer kl 10.",
                System.currentTimeMillis() - 900_000, canReply = true)
        )
        startTicker()
    }

    private fun demoCommand(message: Message) {
        when (message) {
            is Message.MediaCommand -> if (message.command != Message.MediaAction.PLAY_PAUSE) {
                _currentTrack.value = _currentTrack.value?.copy(currentPositionSeconds = 0)
            }
            is Message.StartVoiceReply -> scope.launch {
                delay(1500)
                _voiceReply.value = _voiceReply.value?.copy(state = Message.VoiceState.PARTIAL, text = "Ja det passer")
                delay(1500)
                _voiceReply.value = _voiceReply.value?.copy(state = Message.VoiceState.RESULT, text = "Ja det passer fint, sees der")
            }
            is Message.SendReply -> scope.launch {
                _voiceReply.value = _voiceReply.value?.copy(state = Message.VoiceState.SENT)
                _notifications.value = _notifications.value.filterNot { it.id == message.key }
                delay(2500)
                _voiceReply.value = null
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun hasPermission() = Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun bluetoothOn() = context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    private fun decode(b64: String): Bitmap? = runCatching {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    companion object {
        private const val TAG = "PhoneLink"
        private const val RETRY_MS = 8_000L
        private const val KEY_ADDRESS = "address"
        private const val KEY_NAME = "name"
        private const val KEY_DEMO = "demo"
    }
}
