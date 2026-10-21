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
import com.curveon.phone.CurveonNotificationListener
import com.curveon.phone.GroupHub
import com.curveon.phone.MediaApps
import com.curveon.phone.PhoneBook
import com.curveon.phone.PhoneHub
import com.curveon.phone.VoiceReplier
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

/**
 * A media app on the phone the rider can switch to. [running] is false for a favorite that has no
 * media session yet; picking it makes the phone start it.
 */
data class MediaApp(val packageName: String, val name: String, val playing: Boolean, val icon: Bitmap?, val running: Boolean = true)

/** A spoken reply (or new SMS, see [isSms]) in progress: recorded on the phone, confirmed on the tablet. */
data class VoiceReply(
    val notificationKey: String,
    val senderName: String,
    val state: Message.VoiceState,
    val text: String = ""
) {
    val isSms get() = notificationKey.startsWith(Message.SMS_KEY_PREFIX)
}

/**
 * Tablet side of the phone link. Connects to the Curveon Companion app on the paired phone over
 * Bluetooth RFCOMM and keeps reconnecting while the app runs. Or, with "This device" chosen (Curveon
 * running on the phone itself), reads the same messages, calls and music straight from this
 * device through [PhoneHub], with no companion and no Bluetooth.
 */
class PhoneCompanionManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val prefs = context.getSharedPreferences("phone_link", Context.MODE_PRIVATE)

    /** Messages from this device's own listener, handled in order on the main thread. */
    private val localSink: (Message) -> Unit = { msg -> scope.launch(Dispatchers.Main) { handle(msg) } }

    /** The relay through the phone (see [com.curveon.phone.RelayTunnelClient]): used by the groups when this tablet has no internet itself. */
    private val relayTunnel = com.curveon.phone.RelayTunnelClient { m ->
        val w = writer
        w != null && runCatching { w.write(m) }.isSuccess
    }

    /** Straight to the relay when there is internet here, else through the phone if it is linked; the group never knows which. */
    private val relayTransports = com.curveon.groups.TransportFactory { url, listener ->
        if (viaPhone && !hasInternet()) relayTunnel.open(url, listener) else GroupHub.directTransports.open(url, listener)
    }

    private val mirrorOut = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "curveon-group-mirror").apply { isDaemon = true } }

    /** The linked companion shows how the active group is doing and a friend code it asked for; in order, and off the group thread. */
    private val companionMirror: (Message) -> Unit = { m ->
        if (m is Message.GroupList || m is Message.GroupConnection || m is Message.FriendInvite || m is Message.GroupError) {
            mirrorOut.execute { writer?.let { w -> runCatching { w.write(m) } } }
        }
    }

    private fun hasInternet(): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** This device is its own phone: no companion, no Bluetooth. */
    private val _thisDevice = MutableStateFlow(prefs.getBoolean(KEY_THIS_DEVICE, false))
    val thisDevice: StateFlow<Boolean> = _thisDevice.asStateFlow()
    private val isThisDevice get() = _thisDevice.value

    private val _connectionState = MutableStateFlow(CompanionConnectionState.DISCONNECTED)
    val connectionState: StateFlow<CompanionConnectionState> = _connectionState.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow(prefs.getString(KEY_NAME, null) ?: "")
    val connectedDeviceName: StateFlow<String> = _connectedDeviceName.asStateFlow()

    private val _currentTrack = MutableStateFlow<MusicTrack?>(null)
    val currentTrack: StateFlow<MusicTrack?> = _currentTrack.asStateFlow()

    private val _albumArt = MutableStateFlow<Bitmap?>(null)
    val albumArt: StateFlow<Bitmap?> = _albumArt.asStateFlow()

    /**
     * What the music card switches between: the phone's media apps that are running (playing or
     * paused) plus the rider's favorites, running or not. Sorted by name so swiping keeps its order.
     */
    private val _mediaApps = MutableStateFlow<List<MediaApp>>(emptyList())
    val mediaApps: StateFlow<List<MediaApp>> = _mediaApps.asStateFlow()

    /** Media apps with a session on the phone right now. */
    private var sessionApps: List<MediaApp> = emptyList()

    /** Every media app installed on the phone; kept (without icons) while the phone is away. */
    private val _installedApps = MutableStateFlow(loadInstalledApps())
    val installedApps: StateFlow<List<MediaApp>> = _installedApps.asStateFlow()

    private val _favoriteApps = MutableStateFlow(prefs.getStringSet(KEY_FAVORITE_APPS, emptySet())!!.toSet())
    val favoriteApps: StateFlow<Set<String>> = _favoriteApps.asStateFlow()

    /** Model, battery and access on the phone; null while not connected. */
    private val _phoneStatus = MutableStateFlow<Message.PhoneStatus?>(null)
    val phoneStatus: StateFlow<Message.PhoneStatus?> = _phoneStatus.asStateFlow()

    /** When the current connection came up (wall clock), or null. */
    private val _connectedSince = MutableStateFlow<Long?>(null)
    val connectedSince: StateFlow<Long?> = _connectedSince.asStateFlow()

    private val _notifications = MutableStateFlow<List<NotificationItem>>(emptyList())
    val notifications: StateFlow<List<NotificationItem>> = _notifications.asStateFlow()

    private val _voiceReply = MutableStateFlow<VoiceReply?>(null)
    val voiceReply: StateFlow<VoiceReply?> = _voiceReply.asStateFlow()

    /** A place name being spoken into "Where to?" (recorded on the phone like a reply); its text is the name heard. */
    private val _voiceSearch = MutableStateFlow<VoiceReply?>(null)
    val voiceSearch: StateFlow<VoiceReply?> = _voiceSearch.asStateFlow()

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

    /** Group rides: what the phone tells about the group, and the rider's commands to it. */
    val groups = com.curveon.data.groups.GroupManager(scope) { send(it) }

    @Volatile private var writer: MessageWriter? = null
    @Volatile private var socket: BluetoothSocket? = null
    private var linkJob: Job? = null
    private var tickerJob: Job? = null
    private val artCache = HashMap<String, Bitmap>()

    /** Last song and cover seen from each media app, so switching to it shows at once. */
    private val lastByApp = HashMap<String, Pair<MusicTrack, Bitmap?>>()

    /** App just switched to: until the phone catches up, ignore updates from the one we left. */
    @Volatile private var switchingTo: String? = null
    @Volatile private var switchingUntil = 0L

    /**
     * Messages dismissed on the tablet: key -> text at the time. Dismissing only hides them here
     * (like Android Auto); they stay unread on the phone. A new message in the same chat
     * changes the text and brings it back.
     */
    private val hidden = HashMap<String, String>()

    /** Cuts a reconnect wait short: the phone may have just come within reach. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** The rider asked to connect now; also counts if it comes in during an attempt. */
    @Volatile private var retryRequested = false

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

    /**
     * The rider tapped "not connected" (e.g. just turned sharing on): try at once, forget the
     * back-off, and if an attempt is under way, go again right after it.
     */
    fun retryNow() {
        val state = _connectionState.value
        if (state == CompanionConnectionState.CONNECTED || state == CompanionConnectionState.DEMO) return
        if (isThisDevice) return restart() // nothing to wait for: start over
        retryRequested = true
        if (state == CompanionConnectionState.DISCONNECTED || state == CompanionConnectionState.UNREACHABLE) {
            _connectionState.value = CompanionConnectionState.CONNECTING
        }
        wake.trySend(Unit)
    }

    // ------------------------------------------------------------------ connection

    fun selectedPhoneAddress(): String? = if (isThisDevice) null else prefs.getString(KEY_ADDRESS, null)


    fun useThisDevice() {
        prefs.edit().putBoolean(KEY_THIS_DEVICE, true).putBoolean(KEY_DEMO, false).remove(KEY_INSTALLED_APPS).apply()
        _thisDevice.value = true
        _contacts.value = null
        _recentCalls.value = null
        _photos.value = emptyMap()
        synchronized(requestedPhotos) { requestedPhotos.clear() }
        restart()
    }

    @SuppressLint("MissingPermission")
    fun pairedPhones(): List<PairedPhone> {
        if (!hasPermission()) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return adapter.bondedDevices.orEmpty()
            .filter { it.bluetoothClass?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.PHONE || it.bluetoothClass == null }
            .map { PairedPhone(it.name ?: it.address, it.address) }
            .sortedBy { it.name }
    }

    /**
     * Stop using this phone: no more connecting, and its contacts, calls and music apps are
     * forgotten. It stays paired in Android (unpairing needs Android's Bluetooth settings).
     */
    fun forgetPhone() {
        prefs.edit().remove(KEY_ADDRESS).remove(KEY_NAME).remove(KEY_INSTALLED_APPS).remove(KEY_THIS_DEVICE).apply()
        _thisDevice.value = false
        _connectedDeviceName.value = ""
        _contacts.value = null
        _recentCalls.value = null
        _photos.value = emptyMap()
        synchronized(requestedPhotos) { requestedPhotos.clear() }
        _installedApps.value = emptyList()
        restart()
    }

    fun selectPhone(phone: PairedPhone) {
        val wasThisDevice = isThisDevice
        prefs.edit().putString(KEY_ADDRESS, phone.address).putString(KEY_NAME, phone.name).putBoolean(KEY_DEMO, false)
            .remove(KEY_THIS_DEVICE).apply()
        _thisDevice.value = false
        if (wasThisDevice) { _contacts.value = null; _recentCalls.value = null; _photos.value = emptyMap(); _installedApps.value = emptyList() }
        _connectedDeviceName.value = phone.name
        restart()
    }

    /** Curveon sleeps out of sight: let go of the phone until [restart]. */
    fun pause() = stopLink()

    /** Re-evaluate after permissions were granted or Bluetooth was switched on. */
    fun restart() {
        stopLink()
        _installedApps.value = loadInstalledApps() // demo mode swaps in its own list
        if (prefs.getBoolean(KEY_DEMO, false)) startDemo() else start()
    }

    private fun start() {
        if (isThisDevice) return startThisDevice()
        startLocalGroups()
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
                        runCatching { connectAndServe(address) { isActive } }.onFailure { Log.i(TAG, "Phone link: ${it.message}") }
                    }
                }
                // Stopped while connected (another phone or this device was picked): closing the
                // socket ends the read above, but what comes next belongs to the new choice.
                if (!isActive) break
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
                if (retryRequested) {
                    retryRequested = false
                    failures = 0
                    slowFailures = 0
                    continue
                }
                // Calling a phone that isn't there every few seconds for hours is what preceded the
                // X7's Bluetooth getting stuck (twice). Back off while it stays away; the screen
                // turning on, Bluetooth coming on or the phone's link appearing wakes us at once.
                wake.tryReceive()
                withTimeoutOrNull(retryDelay(failures)) { wake.receive() }
                if (retryRequested) { retryRequested = false; failures = 0; slowFailures = 0 }
            }
        }
    }

    // ------------------------------------------------------------------ this device

    private fun startThisDevice() {
        PhoneHub.phoneBook = PhoneBook(context)
        PhoneHub.voice = VoiceReplier(context)
        GroupHub.transports = GroupHub.directTransports // this device is the phone
        GroupHub.start(context)
        _connectionState.value = CompanionConnectionState.CONNECTED
        _connectedSince.value = System.currentTimeMillis()
        _connectedDeviceName.value = Build.MODEL // "Nothing playing on Pixel 9a"
        PhoneHub.addTablet(localSink, Build.MODEL)
        refreshThisDevice()
        send(Message.RequestPhoneBook)
        startTicker()
    }

    /**
     * Friends and groups live in Curveon itself, linked to a phone or not: a code can be shown and a group started with no
     * phone near, and the phone is only the way to the internet when this tablet has none (see the relay tunnel).
     */
    private fun startLocalGroups() {
        GroupHub.transports = relayTransports
        GroupHub.start(context)
        PhoneHub.addGroupListener(localSink)
    }

    private fun stopThisDevice() {
        PhoneHub.removeTablet(localSink)
        PhoneHub.removeGroupListener(localSink)
        GroupHub.stop()
        PhoneHub.voice?.cancel()
        PhoneHub.voice = null
        PhoneHub.phoneBook?.shutdown()
        PhoneHub.phoneBook = null
    }

    /**
     * Access may have just been granted (back from Android's settings): check it again, list the
     * music apps, and fetch contacts and calls.
     */
    fun refreshThisDevice() {
        if (!isThisDevice || _connectionState.value != CompanionConnectionState.CONNECTED) return
        scope.launch(Dispatchers.IO) {
            localSink(Message.PhoneStatus(Build.MODEL, -1, false, CurveonNotificationListener.isEnabled(context), ""))
            localSink(MediaApps.installed(context))
            PhoneHub.phoneBook?.sendPhoneBook()
        }
    }

    private fun stopLink() {
        groups.stopDemo()
        stopThisDevice()
        linkJob?.cancel()
        tickerJob?.cancel()
        runCatching { socket?.close() }
        socket = null
        writer = null
        clearPhoneState()
    }

    @SuppressLint("MissingPermission")
    private fun connectAndServe(address: String, active: () -> Boolean) {
        if (!active()) return
        // Keep showing "can't reach" while retrying; it clears once a connection works.
        if (_connectionState.value != CompanionConnectionState.UNREACHABLE) _connectionState.value = CompanionConnectionState.CONNECTING
        val adapter = context.getSystemService(BluetoothManager::class.java)!!.adapter
        val device = adapter.getRemoteDevice(address)
        val s = device.createRfcommSocketToServiceRecord(Protocol.SERVICE_UUID)
        socket = s
        try {
            s.connect()
            if (!active()) return
            val w = MessageWriter(s.outputStream)
            writer = w
            w.write(Message.Hello(Build.MODEL))
            w.write(Message.RequestPhoneBook) // so the phone screen opens with fresh lists
            // Photos asked for on a link that then dropped never came; ask again when shown.
            synchronized(requestedPhotos) { requestedPhotos.retainAll(_photos.value.keys) }
            _connectionState.value = CompanionConnectionState.CONNECTED
            _connectedSince.value = System.currentTimeMillis()
            _connectedDeviceName.value = device.name ?: address
            startTicker()
            PhoneHub.addGroupListener(companionMirror)
            if (!hasInternet()) GroupHub.reconnectNow() // the groups can use the phone's internet from now on
            val reader = MessageReader(s.inputStream)
            while (true) {
                val msg = reader.read() ?: break
                if (!active()) break
                handle(msg, fromPhone = true)
            }
        } finally {
            writer = null
            PhoneHub.removeGroupListener(companionMirror)
            relayTunnel.linkLost()
            tickerJob?.cancel()
            runCatching { s.close() }
        }
    }

    /** [fromPhone]: came over Bluetooth. The groups are Curveon's own, so what a phone says about groups is not heard. */
    private fun handle(msg: Message, fromPhone: Boolean = false) {
        when (msg) {
            is Message.Hello -> _connectedDeviceName.value = _connectedDeviceName.value.ifEmpty { msg.deviceName }
            is Message.MediaState -> {
                val target = switchingTo
                if (target != null && System.currentTimeMillis() < switchingUntil && msg.app != target) return
                switchingTo = null
                _currentTrack.value = MusicTrack(
                    title = msg.title, artist = msg.artist, album = msg.album,
                    durationSeconds = (msg.durationMs / 1000).toInt(),
                    currentPositionSeconds = (msg.positionMs / 1000).toInt(),
                    isPlaying = msg.playing, sourceApp = msg.app, artId = msg.artId
                )
                _albumArt.value = msg.artId?.let { artCache[it] }
                rememberForApp()
            }
            is Message.MediaArt -> decode(msg.jpegBase64)?.let { bmp ->
                if (artCache.size > 20) artCache.clear()
                artCache[msg.artId] = bmp
                if (_currentTrack.value?.artId == msg.artId) { _albumArt.value = bmp; rememberForApp() }
            }
            Message.MediaStopped -> { _currentTrack.value = null; _albumArt.value = null }
            is Message.MediaSources -> {
                sessionApps = msg.sources.map { src ->
                    MediaApp(src.packageName, src.app, src.playing, knownIcon(src.packageName) ?: src.iconPngBase64?.let(::decode))
                }
                publishMediaApps()
            }
            is Message.InstalledMediaApps -> {
                _installedApps.value = msg.apps.map { src ->
                    MediaApp(src.packageName, src.app, false, knownIcon(src.packageName) ?: src.iconPngBase64?.let(::decode), running = false)
                }
                saveInstalledApps()
                publishMediaApps()
            }
            is Message.PhoneStatus -> _phoneStatus.value = msg
            is Message.NotificationPosted -> {
                if (hidden[msg.key] == msg.text) return
                hidden.remove(msg.key)
                val item = NotificationItem(
                    id = msg.key, appName = msg.app, senderName = msg.sender, message = msg.text,
                    timestampMs = msg.timeMs, avatar = msg.avatarJpegBase64?.let(::decode),
                    canReply = msg.canReply, isIncomingCall = msg.isCall, isOngoingCall = msg.callOngoing, actions = msg.actions
                )
                _notifications.value = listOf(item) + _notifications.value.filterNot { it.id == msg.key }
            }
            is Message.NotificationRemoved -> {
                if (_notifications.value.any { it.id == msg.key && it.isIncomingCall }) Log.i(TAG, "Phone removed call ${msg.key}")
                hidden.remove(msg.key)
                _notifications.value = _notifications.value.filterNot { it.id == msg.key }
            }
            is Message.VoiceReplyState -> if (msg.key == VOICE_SEARCH_KEY) {
                _voiceSearch.value?.let { _voiceSearch.value = it.copy(state = msg.state, text = msg.text.ifEmpty { it.text }) }
            } else {
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
            is Message.RelayOpened, is Message.RelayText, is Message.RelayClosed -> relayTunnel.onMessage(msg)
            // What the companion scanned, or a friend code it wants to show: the groups are here, so they are answered here.
            is Message.GroupJoin, is Message.RequestFriendInvite -> if (fromPhone) GroupHub.handle(msg)
            // Curveon as its own companion speaks on this device too.
            is Message.SpeechVoice -> _speechVoice.value = VoiceStatus(msg.language, msg.availability, onThisDevice = isThisDevice)
            else -> if (!fromPhone) groups.onMessage(msg) // group rides; ignores anything else
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
        if (_notifications.value.any { it.isIncomingCall }) Log.i(TAG, "Link dropped during a call")
        _currentTrack.value = null
        _mediaApps.value = emptyList()
        sessionApps = emptyList()
        _phoneStatus.value = null
        _connectedSince.value = null
        _albumArt.value = null
        _notifications.value = emptyList()
        _voiceReply.value = null
        hidden.clear()
    }

    // ------------------------------------------------------------------ commands

    private fun send(message: Message) {
        if (_connectionState.value == CompanionConnectionState.DEMO) return demoCommand(message)
        if (GroupHub.accepts(message)) return GroupHub.handle(message) // friends and groups are here, whatever the phone does
        if (isThisDevice) { scope.launch(Dispatchers.IO) { PhoneHub.handle(message) }; return }
        val w = writer ?: return
        scope.launch(Dispatchers.IO) { runCatching { w.write(message) } }
    }

    fun togglePlayPause() {
        _currentTrack.value?.let { _currentTrack.value = it.copy(isPlaying = !it.isPlaying) } // feels instant
        send(Message.MediaCommand(Message.MediaAction.PLAY_PAUSE))
    }

    /** Sounds played on this device: Curveon on the phone, or no phone linked to the tablet. */
    private val localCues by lazy { com.curveon.phone.CuePlayer(context) }

    /**
     * A navigation sound for the rider's headset: the linked phone plays it when there is one
     * (the tablet's own speaker is no use on the bike), otherwise this device does.
     */
    fun playCue(cue: Message.NavCue, side: Message.CueSide) {
        if (viaPhone) send(Message.PlayCue(cue, side)) else localCues.play(cue, side)
    }

    private val localSpeaker by lazy { com.curveon.phone.Speaker(context) { lang, a -> _speechVoice.value = VoiceStatus(lang, a, onThisDevice = true) } }

    /** How the voice was last found where speech plays, for Settings to suggest downloading it. */
    data class VoiceStatus(val language: String, val availability: Message.VoiceAvailability, val onThisDevice: Boolean)

    private val _speechVoice = MutableStateFlow<VoiceStatus?>(null)
    val speechVoice: StateFlow<VoiceStatus?> = _speechVoice.asStateFlow()

    /** Spoken navigation, where [playCue] plays; [cue] is played instead when there is no voice for [language]. */
    fun speak(text: String, language: String, interrupt: Boolean, cue: Message.NavCue, side: Message.CueSide) {
        if (viaPhone) send(Message.Speak(text, language, interrupt, cue, side))
        else localSpeaker.speak(text, language, interrupt) { localCues.play(cue, side) }
    }

    private val viaPhone get() = !isThisDevice && _connectionState.value == CompanionConnectionState.CONNECTED && writer != null

    fun skipNext() = send(Message.MediaCommand(Message.MediaAction.NEXT))

    fun skipPrevious() = send(Message.MediaCommand(Message.MediaAction.PREVIOUS))

    /**
     * Listen to this app instead (the phone pauses the others). Shows its last known song right
     * away; the phone takes the better part of a second to report it.
     */
    fun playApp(packageName: String) {
        val app = _mediaApps.value.firstOrNull { it.packageName == packageName }
        if (app != null) {
            val starting = MusicTrack(title = app.name, artist = if (app.running) "" else context.getString(com.curveon.R.string.media_starting), sourceApp = app.name)
            val (track, art) = lastByApp[app.name] ?: (starting to null)
            val before = _currentTrack.value to _albumArt.value
            _currentTrack.value = track.copy(isPlaying = true)
            _albumArt.value = art
            switchingTo = app.name
            // Starting an app that wasn't running takes a few seconds on the phone.
            switchingUntil = System.currentTimeMillis() + if (app.running) 2_000 else START_TIMEOUT_MS
            // No session from it in time: the phone couldn't start it. Show what played before.
            if (!app.running && _connectionState.value == CompanionConnectionState.CONNECTED) scope.launch {
                delay(START_TIMEOUT_MS)
                if (switchingTo != app.name || _mediaApps.value.any { it.packageName == packageName && it.running }) return@launch
                switchingTo = null
                _currentTrack.value = before.first
                _albumArt.value = before.second
                _phoneErrors.tryEmit(context.getString(com.curveon.R.string.media_start_failed, app.name))
            }
        }
        send(Message.PlayMediaApp(packageName))
    }

    fun setFavoriteApp(packageName: String, favorite: Boolean) {
        val set = if (favorite) _favoriteApps.value + packageName else _favoriteApps.value - packageName
        _favoriteApps.value = set
        prefs.edit().putStringSet(KEY_FAVORITE_APPS, set).apply()
        publishMediaApps()
    }

    private fun publishMediaApps() {
        val state = _connectionState.value
        if (state != CompanionConnectionState.CONNECTED && state != CompanionConnectionState.DEMO) return
        val favorites = _installedApps.value.filter { it.packageName in _favoriteApps.value && sessionApps.none { s -> s.packageName == it.packageName } }
        _mediaApps.value = (sessionApps + favorites).sortedBy { it.name.lowercase() }
    }

    private fun knownIcon(pkg: String): Bitmap? =
        (sessionApps + _installedApps.value).firstOrNull { it.packageName == pkg }?.icon

    private fun loadInstalledApps(): List<MediaApp> = runCatching {
        val a = org.json.JSONArray(prefs.getString(KEY_INSTALLED_APPS, "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { MediaApp(it.getString("pkg"), it.getString("name"), false, null, running = false) } }
    }.getOrDefault(emptyList())

    private fun saveInstalledApps() {
        val a = org.json.JSONArray(_installedApps.value.map { org.json.JSONObject().put("pkg", it.packageName).put("name", it.name) })
        prefs.edit().putString(KEY_INSTALLED_APPS, a.toString()).apply()
    }

    private fun rememberForApp() {
        val t = _currentTrack.value ?: return
        if (t.sourceApp.isNotEmpty()) lastByApp[t.sourceApp] = t to _albumArt.value
    }

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
        val name = _notifications.value.firstOrNull { it.id == id }?.senderName
            ?: _voiceReply.value?.takeIf { it.notificationKey == id }?.senderName // Say again on an SMS
            ?: return
        _voiceReply.value = VoiceReply(id, name, Message.VoiceState.LISTENING)
        send(Message.StartVoiceReply(id))
    }

    /** Listen for a place name for the search. Returns false when there is no phone to listen with. */
    fun startVoiceSearch(): Boolean {
        val state = _connectionState.value
        if (state != CompanionConnectionState.CONNECTED && state != CompanionConnectionState.DEMO) return false
        _voiceSearch.value = VoiceReply(VOICE_SEARCH_KEY, "", Message.VoiceState.LISTENING)
        send(Message.StartVoiceReply(VOICE_SEARCH_KEY))
        return true
    }

    fun cancelVoiceSearch() {
        if (_voiceSearch.value?.state.let { it == Message.VoiceState.LISTENING || it == Message.VoiceState.PARTIAL }) send(Message.CancelVoiceReply(VOICE_SEARCH_KEY))
        _voiceSearch.value = null
    }

    /** A new SMS to [number], spoken like a reply. Returns false when there is no phone to send it with. */
    fun startSms(number: String, name: String): Boolean {
        val state = _connectionState.value
        if (state != CompanionConnectionState.CONNECTED && state != CompanionConnectionState.DEMO) return false
        val key = Message.SMS_KEY_PREFIX + number
        _voiceReply.value = VoiceReply(key, name, Message.VoiceState.LISTENING)
        send(Message.StartVoiceReply(key))
        return true
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
        sessionApps = listOf(
            MediaApp("com.spotify.music", "Spotify", true, null),
            MediaApp("com.google.android.apps.podcasts", "Podcasts", false, null)
        )
        _installedApps.value = DEMO_INSTALLED_APPS
        _phoneStatus.value = Message.PhoneStatus("Pixel 9a", 78, false, true, "1.0")
        _connectedSince.value = System.currentTimeMillis()
        publishMediaApps()
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
        groups.startDemo()
        startTicker()
    }

    private fun demoCommand(message: Message) {
        when (message) {
            is Message.SosStart, Message.SosCancel, is Message.GroupSharing -> groups.demoCommand(message)
            is Message.PlayMediaApp -> {
                if (sessionApps.none { it.packageName == message.packageName }) {
                    _installedApps.value.firstOrNull { it.packageName == message.packageName }?.let { sessionApps = sessionApps + it.copy(running = true) }
                }
                sessionApps = sessionApps.map { it.copy(playing = it.packageName == message.packageName) }
                publishMediaApps()
                val app = _mediaApps.value.firstOrNull { it.packageName == message.packageName }?.name.orEmpty()
                _currentTrack.value = _currentTrack.value?.copy(sourceApp = app, isPlaying = true)
            }
            is Message.MediaCommand -> if (message.command != Message.MediaAction.PLAY_PAUSE) {
                _currentTrack.value = _currentTrack.value?.copy(currentPositionSeconds = 0)
            }
            is Message.StartVoiceReply -> if (message.key == VOICE_SEARCH_KEY) scope.launch {
                delay(1200)
                _voiceSearch.value = _voiceSearch.value?.copy(state = Message.VoiceState.PARTIAL, text = "Geir")
                delay(1200)
                _voiceSearch.value = _voiceSearch.value?.copy(state = Message.VoiceState.RESULT, text = "Geiranger")
            } else scope.launch {
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
                _notifications.value = listOf(NotificationItem("democall", "Phone", name, "Calling…", isIncomingCall = true, isOngoingCall = true,
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
        private const val START_TIMEOUT_MS = 10_000L

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
        private const val KEY_THIS_DEVICE = "this_device"
        private const val KEY_FAVORITE_APPS = "favorite_media_apps"
        private const val KEY_INSTALLED_APPS = "installed_media_apps"

        private val DEMO_INSTALLED_APPS = listOf(
            "com.google.android.apps.podcasts" to "Podcasts", "no.nrk.mobil.radio" to "NRK Radio",
            "com.spotify.music" to "Spotify", "com.google.android.apps.youtube.music" to "YouTube Music"
        ).map { (pkg, name) -> MediaApp(pkg, name, false, null, running = false) }

        /** The voice reply key of a spoken search (see [voiceSearch]). */
        private const val VOICE_SEARCH_KEY = "search"

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
