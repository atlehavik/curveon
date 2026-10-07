package com.curveon.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CompanionConnectionState {
    NO_PHONE_SELECTED,
    NO_PERMISSION,
    BLUETOOTH_OFF,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    /** The phone's radio doesn't answer at all (each try runs into the ~5 s page timeout). */
    UNREACHABLE,
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

    /** The phone book; null until the phone has sent it. Kept while the phone is away. */
    private val _contacts = MutableStateFlow<List<Message.Contact>?>(null)
    val contacts: StateFlow<List<Message.Contact>?> = _contacts.asStateFlow()

    private val _recentCalls = MutableStateFlow<List<Message.RecentCall>?>(null)
    val recentCalls: StateFlow<List<Message.RecentCall>?> = _recentCalls.asStateFlow()

    /** False when the companion isn't allowed to read contacts / the call log. */
    private val _contactsAllowed = MutableStateFlow(true)
    val contactsAllowed: StateFlow<Boolean> = _contactsAllowed.asStateFlow()
    private val _callLogAllowed = MutableStateFlow(true)
    val callLogAllowed: StateFlow<Boolean> = _callLogAllowed.asStateFlow()

    /** Contact photos by photo key, fetched as the phone screen shows them. */
    private val _photos = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val photos: StateFlow<Map<String, Bitmap>> = _photos.asStateFlow()
    private val requestedPhotos = HashSet<String>()
    private val pendingPhotos = LinkedHashSet<String>()
    private var photoJob: Job? = null

    /** Things that went wrong on the phone (e.g. a call that couldn't be placed), for a short toast. */
    private val _phoneErrors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val phoneErrors: SharedFlow<String> = _phoneErrors

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

    /** Cuts a reconnect wait short: the phone may have just come within reach. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val relevant = when (intent.action) {
                Intent.ACTION_SCREEN_ON -> true
                BluetoothAdapter.ACTION_STATE_CHANGED ->
                    intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    device?.address == selectedPhoneAddress()
                }
                else -> false
            }
            if (relevant) wake.trySend(Unit)
        }
    }

    init {
        ContextCompat.registerReceiver(context, wakeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (prefs.getBoolean(KEY_DEMO, false)) startDemo() else start()
    }

    /** Try to reach the phone now instead of waiting (e.g. the dashboard came to the front). */
    fun reconnectSoon() { wake.trySend(Unit) }

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
            var slowFailures = 0
            var failures = 0
            while (isActive) {
                val t0 = System.currentTimeMillis()
                var attempted = false
                val address = selectedPhoneAddress()
                when {
                    !hasPermission() -> _connectionState.value = CompanionConnectionState.NO_PERMISSION
                    address == null -> _connectionState.value = CompanionConnectionState.NO_PHONE_SELECTED
                    !bluetoothOn() -> _connectionState.value = CompanionConnectionState.BLUETOOTH_OFF
                    else -> {
                        attempted = true
                        runCatching { connectAndServe(address) }.onFailure { Log.i(TAG, "Phone link: ${it.message}") }
                    }
                }
                clearPhoneState()
                // A phone that is on but not sharing refuses at once; one whose radio doesn't answer
                // (or a tablet whose Bluetooth got stuck, seen on the X7) fails after the ~5 s page
                // timeout. Several of those in a row get their own state, with a hint what to do.
                val wasConnected = _connectionState.value == CompanionConnectionState.CONNECTED
                slowFailures = if (attempted && !wasConnected && System.currentTimeMillis() - t0 >= PAGE_TIMEOUT_MS) slowFailures + 1 else 0
                if (attempted) {
                    _connectionState.value = if (slowFailures >= UNREACHABLE_AFTER) CompanionConnectionState.UNREACHABLE
                        else CompanionConnectionState.DISCONNECTED
                }
                failures = if (attempted && !wasConnected) failures + 1 else 0
                // Calling a phone that isn't there every few seconds for hours is what preceded the
                // X7's Bluetooth getting stuck (twice). Back off while it stays away; the screen
                // turning on, Bluetooth coming on or the phone's link appearing wakes us at once.
                wake.tryReceive()
                withTimeoutOrNull(retryDelay(failures)) { wake.receive() }
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
        // Keep showing "can't reach" while retrying; it clears once a connection works.
        if (_connectionState.value != CompanionConnectionState.UNREACHABLE) _connectionState.value = CompanionConnectionState.CONNECTING
        val adapter = context.getSystemService(BluetoothManager::class.java)!!.adapter
        val device = adapter.getRemoteDevice(address)
        val s = device.createRfcommSocketToServiceRecord(Protocol.SERVICE_UUID)
        socket = s
        try {
            s.connect()
            val w = MessageWriter(s.outputStream)
            writer = w
            w.write(Message.Hello(Build.MODEL))
            w.write(Message.RequestPhoneBook) // so the phone screen opens with fresh lists
            // Photos asked for on a link that then dropped never came; ask again when shown.
            synchronized(requestedPhotos) { requestedPhotos.retainAll(_photos.value.keys) }
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
            is Message.Contacts -> { _contacts.value = msg.contacts; _contactsAllowed.value = msg.granted }
            is Message.RecentCalls -> { _recentCalls.value = msg.calls; _callLogAllowed.value = msg.granted }
            is Message.ContactPhotos -> {
                val decoded = msg.photos.mapNotNull { p -> p.jpegBase64?.let(::decode)?.let { p.key to it } }
                if (decoded.isNotEmpty()) _photos.value = _photos.value + decoded
            }
            is Message.PhoneError -> _phoneErrors.tryEmit(msg.text)
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

    /** Hides a message on the tablet only (swipe); it stays unread on the phone. */
    fun hideNotification(id: String) {
        _notifications.value.firstOrNull { it.id == id }?.let { hidden[id] = it.message }
        _notifications.value = _notifications.value.filterNot { it.id == id }
    }

    /** Clears the message on the phone too (the Dismiss button). */
    fun dismissNotification(id: String) {
        _notifications.value = _notifications.value.filterNot { it.id == id }
        send(Message.DismissNotification(id))
    }

    // ------------------------------------------------------------------ phone screen

    /** Fresh contacts and call log, e.g. when the phone screen opens. */
    fun refreshPhoneBook() = send(Message.RequestPhoneBook)

    /** Asks for a contact photo once; requests from one screenful go together. */
    fun requestPhoto(key: String) {
        synchronized(requestedPhotos) { if (!requestedPhotos.add(key)) return }
        synchronized(pendingPhotos) { pendingPhotos += key }
        if (photoJob?.isActive == true) return
        photoJob = scope.launch {
            delay(120)
            val keys = synchronized(pendingPhotos) { pendingPhotos.toList().also { pendingPhotos.clear() } }
            if (keys.isNotEmpty()) send(Message.RequestContactPhotos(keys))
        }
    }

    /** Dials on the phone. Returns false when there is no phone to dial with. */
    fun placeCall(number: String): Boolean {
        val state = _connectionState.value
        if (state != CompanionConnectionState.CONNECTED && state != CompanionConnectionState.DEMO) return false
        send(Message.PlaceCall(number))
        return true
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
        _contacts.value = DEMO_CONTACTS
        _recentCalls.value = listOf(
            Message.RecentCall("+47 912 34 567", "Kari Nordmann", "Mobile", null, Message.CallKind.MISSED, System.currentTimeMillis() - 25 * 60_000, 2),
            Message.RecentCall("+47 22 33 44 55", "Verksted Otta", "Work", null, Message.CallKind.OUTGOING, System.currentTimeMillis() - 3 * 3600_000),
            Message.RecentCall("+47 400 00 000", null, "", null, Message.CallKind.INCOMING, System.currentTimeMillis() - 26 * 3600_000),
            Message.RecentCall("+47 934 56 789", "Per Hansen", "Mobile", null, Message.CallKind.REJECTED, System.currentTimeMillis() - 4 * 86400_000)
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
            is Message.PlaceCall -> {
                val name = _contacts.value?.firstOrNull { c -> c.numbers.any { it.number == message.number } }?.name ?: message.number
                _notifications.value = listOf(NotificationItem("democall", "Phone", name, "Calling…", isIncomingCall = true,
                    actions = listOf("Hang up"))) + _notifications.value
            }
            is Message.InvokeAction -> if (message.key == "democall") {
                _notifications.value = _notifications.value.filterNot { it.id == "democall" }
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
        private const val PAGE_TIMEOUT_MS = 4_000L
        private const val UNREACHABLE_AFTER = 4
        private const val RETRY_MS = 8_000L

        /** Quick retries for the first couple of minutes, then less and less often. */
        internal fun retryDelay(failures: Int): Long = when {
            failures <= 8 -> RETRY_MS
            failures <= 14 -> 30_000L
            failures <= 24 -> 60_000L
            else -> 120_000L
        }
        private const val KEY_ADDRESS = "address"
        private const val KEY_NAME = "name"
        private const val KEY_DEMO = "demo"

        private val DEMO_CONTACTS = listOf(
            "Anne Lise Berg" to false, "Bjørn Dahl" to true, "Eirik Solberg" to false, "Ingrid Moe" to true,
            "Kari Nordmann" to true, "Lars Haugen" to false, "MC-klubben leder" to false, "Nils Bakke" to false,
            "Ola Nordmann" to true, "Per Hansen" to false, "Siri Lunde" to false, "Tor Eriksen" to false,
            "Verksted Otta" to true, "Åse Strand" to false
        ).mapIndexed { i, (name, starred) ->
            Message.Contact(i.toLong(), name, starred, null, buildList {
                add(Message.PhoneNumber("+47 9%02d 34 567".format(i), "Mobile"))
                if (i % 4 == 0) add(Message.PhoneNumber("+47 22 %02d 44 55".format(i), "Work"))
            })
        }
    }
}
