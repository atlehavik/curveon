package com.curveon.data.groups

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.curveon.data.location.GpsFix
import com.curveon.protocol.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/** Someone in the active group. [photo] is decoded once per change; without it the marker shows the icon or initials. */
data class GroupRider(val id: String, val nickname: String, val colour: Int, val icon: String?, val photo: Bitmap?)

/**
 * The tablet's side of group rides: it holds what the phone tells it (the groups, who is where, SOS)
 * and sends what the rider does. The phone keeps the keys and talks to the relay; this holds none.
 * [send] reaches the phone over Bluetooth, or Curveon's own phone side on a phone.
 */
class GroupManager(private val scope: CoroutineScope, private val send: (Message) -> Unit) {

    private val _groups = MutableStateFlow<List<Message.GroupInfo>>(emptyList())
    val groups: StateFlow<List<Message.GroupInfo>> = _groups.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    /** False while the rider has paused sharing; the group stays. */
    private val _sharing = MutableStateFlow(true)
    val sharing: StateFlow<Boolean> = _sharing.asStateFlow()

    private val _connection = MutableStateFlow<Message.GroupConnection?>(null)
    val connection: StateFlow<Message.GroupConnection?> = _connection.asStateFlow()

    private val _members = MutableStateFlow<Map<String, GroupRider>>(emptyMap())
    val members: StateFlow<Map<String, GroupRider>> = _members.asStateFlow()

    /** The rider themself: the first entry of the member list. */
    private val _myId = MutableStateFlow<String?>(null)
    val myId: StateFlow<String?> = _myId.asStateFlow()

    private val _positions = MutableStateFlow<Map<String, Message.GroupPositionInfo>>(emptyMap())
    val positions: StateFlow<Map<String, Message.GroupPositionInfo>> = _positions.asStateFlow()

    /** Active SOS calls in the active group, by who sent them. */
    private val _sos = MutableStateFlow<Map<String, Message.GroupSos>>(emptyMap())
    val sos: StateFlow<Map<String, Message.GroupSos>> = _sos.asStateFlow()

    /** The latest invite the phone made, to show as a QR code. */
    private val _invite = MutableStateFlow<String?>(null)
    val invite: StateFlow<String?> = _invite.asStateFlow()

    /** A short-lived link for the rider's own other device, to show as a QR code. */
    private val _deviceLink = MutableStateFlow<String?>(null)
    val deviceLink: StateFlow<String?> = _deviceLink.asStateFlow()

    private val _errors = MutableSharedFlow<Message.GroupErrorCode>(extraBufferCapacity = 4)
    val errors: SharedFlow<Message.GroupErrorCode> = _errors

    /** A route or track a rider shared: the screen offers to open or save it. */
    private val _routes = MutableSharedFlow<Message.GroupRoute>(extraBufferCapacity = 4)
    val routes: SharedFlow<Message.GroupRoute> = _routes

    private var demoJob: Job? = null
    private var lastPositionSent = 0L
    @Volatile private var lastFix: GpsFix? = null

    val active: Boolean get() = _activeId.value != null

    // ---- phone -> tablet ----

    fun onMessage(m: Message) {
        when (m) {
            is Message.GroupList -> {
                _groups.value = m.groups
                if (_activeId.value != m.activeId) clearLive()
                _activeId.value = m.activeId
                _sharing.value = m.sharing
            }
            is Message.GroupConnection -> if (m.groupId == _activeId.value) _connection.value = m
            is Message.GroupMembers -> if (m.groupId == _activeId.value) {
                _myId.value = m.members.firstOrNull()?.id
                val old = _members.value
                _members.value = m.members.associate { i ->
                    val keep = old[i.id]?.takeIf { it.nickname == i.nickname && it.colour == i.colour && it.icon == i.icon }
                    i.id to (keep ?: GroupRider(i.id, i.nickname, i.colour, i.icon, i.photoJpegBase64?.let(::decode)))
                }
                // Someone who left takes their dot with them.
                _positions.value = _positions.value.filterKeys { it in _members.value }
            }
            is Message.GroupPositions -> if (m.groupId == _activeId.value) _positions.value = _positions.value + m.positions.associateBy { it.id }
            is Message.GroupSos -> if (m.groupId == _activeId.value || !m.active) {
                _sos.value = if (m.active) _sos.value + (m.from to m) else _sos.value - m.from
            }
            is Message.GroupRoute -> _routes.tryEmit(m)
            is Message.GroupInvite -> _invite.value = m.uri
            is Message.GroupDeviceLink -> _deviceLink.value = m.uri
            is Message.GroupError -> _errors.tryEmit(m.code)
            else -> Unit
        }
    }

    /** The link to the phone dropped: what is on screen is now out of date. */
    fun onLinkLost() {
        _connection.value = _connection.value?.copy(status = Message.GroupStatus.OFFLINE)
    }

    private fun clearLive() {
        _connection.value = null
        _members.value = emptyMap()
        _myId.value = null
        _positions.value = emptyMap()
        _sos.value = emptyMap()
        _invite.value = null
        _deviceLink.value = null
    }

    // ---- the rider's commands ----

    fun requestGroups() = send(Message.RequestGroups)
    fun create(name: String?) = send(Message.GroupCreate(name))
    fun join(invite: String) = send(Message.GroupJoin(invite))
    fun leave(groupId: String) = send(Message.GroupLeave(groupId))
    fun select(groupId: String?) = send(Message.GroupSelect(groupId))
    fun setSharing(on: Boolean) = send(Message.GroupSharing(on))
    fun setProfile(nickname: String, colour: Int, icon: String?, photoJpegBase64: String?) =
        send(Message.GroupProfile(nickname, colour, icon, photoJpegBase64))
    fun requestInvite(groupId: String, validDays: Int? = null) = send(Message.RequestGroupInvite(groupId, validDays))
    fun requestDeviceLink(groupId: String) = send(Message.RequestDeviceLink(groupId))
    /** The link is secret: do not leave it around once it has been shown. */
    fun forgetDeviceLink() { _deviceLink.value = null }
    fun removeRider(groupId: String, riderId: String) = send(Message.GroupRemoveRider(groupId, riderId))
    fun shareRoute(name: String, gpxGz: ByteArray) = send(Message.ShareRoute(name, Base64.encodeToString(gpxGz, Base64.NO_WRAP)))

    fun sosStart() {
        val fix = lastFix ?: return
        send(Message.SosStart(fix.latitude, fix.longitude))
    }

    fun sosCancel() = send(Message.SosCancel)
    fun sosAnswer(sosRiderId: String, coming: Boolean) = send(Message.SosAnswer(sosRiderId, coming))

    /** The tablet's GPS, passed on while a group is active and sharing. The phone decides what goes out and how often. */
    fun onFix(fix: GpsFix) {
        lastFix = fix
        if (!active || !_sharing.value) return
        val now = System.currentTimeMillis()
        if (now - lastPositionSent < MIN_POSITION_GAP_MS) return
        lastPositionSent = now
        send(Message.MyPosition(fix.latitude, fix.longitude, fix.speedMps ?: 0f, fix.bearingDeg ?: 0f, fix.accuracyMeters ?: 0f, fix.timeMillis))
    }

    // ---- demo (no phone, no relay): a group of three riders drifting around the rider ----

    fun startDemo() {
        if (demoJob != null) return
        demoJob = scope.launch {
            val gid = "demo"
            val names = listOf(Triple("kari", "Kari", 0xFFFF5252.toInt()), Triple("per", "Per", 0xFF69F0AE.toInt()), Triple("siri", "Siri", 0xFFFFD740.toInt()))
            onMessage(Message.GroupList(listOf(Message.GroupInfo(gid, "Demo ride", 4, true)), gid, true))
            onMessage(Message.GroupConnection(gid, Message.GroupStatus.CONNECTED, 4))
            onMessage(Message.GroupMembers(gid, listOf(Message.GroupMemberInfo("me", "Me", 0xFF00E5FF.toInt(), "helmet", null)) +
                names.map { Message.GroupMemberInfo(it.first, it.second, it.third, null, null) }))
            onMessage(Message.GroupInvite(gid, "curveon://join#v=1&demo"))
            var t = 0.0
            while (isActive) {
                val c = lastFix
                if (c != null) {
                    onMessage(Message.GroupPositions(gid, names.mapIndexed { i, (id, _, _) ->
                        val a = t + i * 2.1
                        val r = 0.0016 + 0.0009 * i
                        Message.GroupPositionInfo(
                            id, c.latitude + r * sin(a), c.longitude + r * 2 * cos(a), ((Math.toDegrees(a) + 90) % 360).toFloat(), 18f,
                            System.currentTimeMillis() - if (id == "siri") 200_000 else 0, false, null, 70 - i * 10
                        )
                    }))
                }
                t += 0.05
                delay(2000)
            }
        }
    }

    fun stopDemo() {
        if (demoJob == null) return
        demoJob?.cancel()
        demoJob = null
        _groups.value = emptyList()
        _activeId.value = null
        clearLive()
    }

    /** What the demo does with the rider's commands: a rider's SOS is answered by the others after a moment. */
    fun demoCommand(m: Message) {
        when (m) {
            is Message.SosStart -> scope.launch {
                val me = _myId.value ?: "me"
                fun sos(acks: List<Message.SosAckInfo>) = Message.GroupSos("demo", me, m.lat, m.lon, System.currentTimeMillis(), true, true, acks, 4)
                onMessage(sos(emptyList()))
                delay(1500); onMessage(sos(listOf(Message.SosAckInfo("kari", false))))
                delay(1500); onMessage(sos(listOf(Message.SosAckInfo("kari", false), Message.SosAckInfo("per", false))))
                delay(2500); onMessage(sos(listOf(Message.SosAckInfo("kari", true), Message.SosAckInfo("per", false))))
            }
            is Message.SosCancel -> _sos.value[_myId.value ?: "me"]?.let { onMessage(it.copy(active = false)) }
            is Message.GroupSharing -> onMessage(Message.GroupList(_groups.value, _activeId.value, m.on))
            else -> Unit
        }
    }

    /** DEV: Per sends an SOS from near the rider, to try the alert. */
    fun demoIncomingSos() {
        val c = lastFix ?: return
        onMessage(Message.GroupSos("demo", "per", c.latitude + 0.004, c.longitude + 0.003, System.currentTimeMillis(), true, false, emptyList(), 4))
    }

    private fun decode(b64: String): Bitmap? = runCatching {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private companion object {
        /** The tablet's GPS runs at 1 Hz; the phone shares less often, so there is no point sending every fix over Bluetooth. */
        const val MIN_POSITION_GAP_MS = 2_000L
    }
}
