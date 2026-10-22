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
data class GroupRider(val id: String, val nickname: String, val colour: Int, val icon: String?, val photo: Bitmap?, val photoBase64: String? = null)

/**
 * The tablet's side of group rides: it holds what the phone tells it (the groups, who is where, SOS)
 * and sends what the rider does. The phone keeps the keys and talks to the relay; this holds none.
 * [send] reaches the phone over Bluetooth, or Curveon's own phone side on a phone.
 */
class GroupManager(private val scope: CoroutineScope, private val send: (Message) -> Unit) {

    /** The phone side's relay address and data saver; null until the phone has told (so not shown without a phone). */
    private val _settings = MutableStateFlow<Message.GroupSettings?>(null)
    val settings: StateFlow<Message.GroupSettings?> = _settings.asStateFlow()

    /** The rider's own name, colour and picture, set also with no group (null until the phone has told). */
    private val _profile = MutableStateFlow<Message.GroupProfile?>(null)
    val profile: StateFlow<Message.GroupProfile?> = _profile.asStateFlow()

    private val _friends = MutableStateFlow<List<Message.FriendInfo>>(emptyList())
    val friends: StateFlow<List<Message.FriendInfo>> = _friends.asStateFlow()

    /** The switch for letting friends see where the rider is (which ones: [Message.FriendInfo.seesMe]). */
    private val _friendSharing = MutableStateFlow(false)
    val friendSharing: StateFlow<Boolean> = _friendSharing.asStateFlow()

    /** Where the friends who share with the rider were last, by friend id. */
    private val _friendPositions = MutableStateFlow<Map<String, Message.GroupPositionInfo>>(emptyMap())
    val friendPositions: StateFlow<Map<String, Message.GroupPositionInfo>> = _friendPositions.asStateFlow()

    /** The friends as markers (the picture decoded once per change). */
    private val _friendRiders = MutableStateFlow<Map<String, GroupRider>>(emptyMap())
    val friendRiders: StateFlow<Map<String, GroupRider>> = _friendRiders.asStateFlow()

    /** The code (a link) a friend can scan or be sent to become a friend; for one friend. */
    private val _friendInvite = MutableStateFlow<String?>(null)
    val friendInvite: StateFlow<String?> = _friendInvite.asStateFlow()

    /** Invites to ride groups that friends have sent and the rider has not answered. */
    private val _offers = MutableStateFlow<List<Message.GroupInviteOffer>>(emptyList())
    val offers: StateFlow<List<Message.GroupInviteOffer>> = _offers.asStateFlow()

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
    private val _selfLink = MutableStateFlow<String?>(null)
    /** The link for the rider's other device, while it is on show. */
    val selfLink: StateFlow<String?> = _selfLink.asStateFlow()

    private val _selfStatus = MutableStateFlow(Message.SelfStatus(linked = false, waiting = false))
    val selfStatus: StateFlow<Message.SelfStatus> = _selfStatus.asStateFlow()

    private val _deviceLink = MutableStateFlow<String?>(null)
    val deviceLink: StateFlow<String?> = _deviceLink.asStateFlow()

    /** Trips shared with the active group, new or changed (see [SharedTripsController]). */
    private val _trips = MutableSharedFlow<Message.GroupTrip>(extraBufferCapacity = 64)
    val trips: SharedFlow<Message.GroupTrip> = _trips

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
            is Message.GroupSettings -> _settings.value = m
            is Message.GroupProfile -> _profile.value = m
            is Message.FriendList -> {
                _friends.value = m.friends
                _friendSharing.value = m.sharing
                val old = _friendRiders.value
                _friendRiders.value = m.friends.associate { f ->
                    val keep = old[f.id]?.takeIf { it.nickname == f.nickname && it.colour == f.colour && it.icon == f.icon && it.photoBase64 == f.photoJpegBase64 }
                    f.id to (keep ?: GroupRider(f.id, f.nickname, f.colour, f.icon, f.photoJpegBase64?.let(::decode), f.photoJpegBase64))
                }
                _friendPositions.value = _friendPositions.value.filterKeys { id -> m.friends.any { it.id == id && it.sharesWithMe } }
            }
            is Message.FriendInvite -> _friendInvite.value = m.uri
            is Message.GroupInviteOffer -> if (_offers.value.none { it.offerId == m.offerId }) _offers.value = _offers.value + m
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
                    val keep = old[i.id]?.takeIf { it.nickname == i.nickname && it.colour == i.colour && it.icon == i.icon && it.photoBase64 == i.photoJpegBase64 }
                    i.id to (keep ?: GroupRider(i.id, i.nickname, i.colour, i.icon, i.photoJpegBase64?.let(::decode), i.photoJpegBase64))
                }
                // Someone who left takes their dot with them.
                _positions.value = _positions.value.filterKeys { it in _members.value }
            }
            is Message.GroupPositions -> if (m.groupId == Message.GroupPositions.FRIENDS) {
                _friendPositions.value = if (m.positions.size == 1) _friendPositions.value + (m.positions[0].id to m.positions[0])
                else m.positions.associateBy { it.id } // the whole list: one that stopped sharing is gone from it
            } else if (m.groupId == _activeId.value) _positions.value = _positions.value + m.positions.associateBy { it.id }
            is Message.GroupSos -> if (m.groupId == _activeId.value || !m.active) {
                _sos.value = if (m.active) _sos.value + (m.from to m) else _sos.value - m.from
            }
            is Message.GroupRoute -> _routes.tryEmit(m)
            is Message.GroupTrip -> _trips.tryEmit(m)
            is Message.GroupInvite -> _invite.value = m.uri
            is Message.GroupDeviceLink -> _deviceLink.value = m.uri
            is Message.SelfLink -> _selfLink.value = m.uri
            is Message.SelfStatus -> _selfStatus.value = m
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
    fun setDataSaver(on: Boolean) {
        _settings.value = Message.GroupSettings(on)
        send(Message.GroupSettings(on))
    }
    fun create(name: String?) = send(Message.GroupCreate(name))
    fun join(invite: String) = send(Message.GroupJoin(invite))
    fun leave(groupId: String) = send(Message.GroupLeave(groupId))
    fun select(groupId: String?) = send(Message.GroupSelect(groupId))
    fun setSharing(on: Boolean) = send(Message.GroupSharing(on))
    /** Another of the rider's devices sends their position now; this makes this one do it instead. */
    fun useThisDevice() = send(Message.GroupUseThisDevice)
    fun setProfile(nickname: String, colour: Int, icon: String?, photoJpegBase64: String?) =
        send(Message.GroupProfile(nickname, colour, icon, photoJpegBase64))
    /** A new or changed shared trip, to the active group. [rev] only goes up; the highest wins. */
    fun shareTrip(tripId: String, rev: Long, name: String, doc: String) = send(Message.TripShare(tripId, rev, name, doc))

    fun requestSelfLink() = send(Message.RequestSelfLink)
    fun forgetSelfLink() { _selfLink.value = null }
    fun unlinkSelf() = send(Message.SelfUnlink)

    fun requestFriends() = send(Message.RequestFriends)
    fun requestFriendInvite() = send(Message.RequestFriendInvite)
    fun forgetFriendInvite() { _friendInvite.value = null }
    fun removeFriend(channelId: String) = send(Message.FriendRemove(channelId))

    /** The switch for all friends ([on]), and whether [friendId] always sees the rider while it is on ([sees]). */
    fun setFriendSharing(on: Boolean, friendId: String? = null, sees: Boolean = false) {
        val chosen = _friends.value.filter { it.seesMe }.map { it.id }.toSet().let { s -> if (friendId == null) s else if (sees) s + friendId else s - friendId }
        _friendSharing.value = on
        _friends.value = _friends.value.map { it.copy(seesMe = it.id in chosen) }
        send(Message.FriendSharing(on, chosen.toList()))
    }

    /** True while some friend sees where the rider is. */
    private val sharingWithFriends: Boolean get() = _friendSharing.value && _friends.value.any { it.seesMe }
    fun addFriendsToGroup(groupId: String, friendIds: List<String>) = send(Message.FriendAddToGroup(groupId, friendIds))

    /** The rider's answer to a friend's invite to a ride group. */
    fun answerOffer(offerId: String, accept: Boolean) {
        _offers.value = _offers.value.filterNot { it.offerId == offerId }
        send(Message.GroupInviteAnswer(offerId, accept))
    }

    fun requestInvite(groupId: String, validDays: Int? = null) = send(Message.RequestGroupInvite(groupId, validDays))
    fun requestDeviceLink(groupId: String) = send(Message.RequestDeviceLink(groupId))
    /** The link is secret: do not leave it around once it has been shown. */
    fun forgetDeviceLink() { _deviceLink.value = null }
    fun removeRider(groupId: String, riderId: String) = send(Message.GroupRemoveRider(groupId, riderId))
    fun shareRoute(name: String, gpxGz: ByteArray) = send(Message.ShareRoute(name, Base64.encodeToString(gpxGz, Base64.NO_WRAP)))

    /**
     * Sends the SOS where the rider is; with no fix yet, where Android last knew ([fallback]).
     * False: no position at all and nothing sent, so the rider must be told.
     */
    fun sosStart(fallback: Pair<Double, Double>? = null): Boolean {
        val (lat, lon) = lastFix?.let { it.latitude to it.longitude } ?: fallback ?: return false
        send(Message.SosStart(lat, lon))
        return true
    }

    fun sosCancel() = send(Message.SosCancel)
    fun sosAnswer(sosRiderId: String, coming: Boolean) = send(Message.SosAnswer(sosRiderId, coming))

    /** The tablet's GPS, passed on while a group is active and sharing, or a friend sees the rider. The phone decides what goes out and how often. */
    fun onFix(fix: GpsFix) {
        lastFix = fix
        if (!(active && _sharing.value) && !sharingWithFriends) return
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
            // Two friends outside the group: Ola shares where he is, Anne does not.
            onMessage(Message.FriendList(listOf(
                Message.FriendInfo("ola", "Ola", 0xFFE040FB.toInt(), "bike", null, sharesWithMe = true),
                Message.FriendInfo("anne", "Anne", 0xFFFF9100.toInt(), null, null),
            )))
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
                    onMessage(Message.GroupPositions(Message.GroupPositions.FRIENDS, listOf(Message.GroupPositionInfo(
                        "ola", c.latitude - 0.012, c.longitude + 0.02, 45f, 20f, System.currentTimeMillis() - 180_000, false, null, 55
                    ))))
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
        _friends.value = emptyList()
        _friendPositions.value = emptyMap()
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
