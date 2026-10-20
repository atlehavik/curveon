package com.curveon.phone

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import android.util.Log
import com.curveon.groups.Avatar
import com.curveon.groups.Fix
import com.curveon.groups.GroupClient
import com.curveon.groups.GroupEvent
import com.curveon.groups.GroupKind
import com.curveon.groups.PeerProfile
import com.curveon.groups.GroupState
import com.curveon.groups.Invite
import com.curveon.groups.Member
import com.curveon.groups.Payload
import com.curveon.groups.PositionSharer
import com.curveon.groups.SosTracker
import com.curveon.groups.SosView
import com.curveon.protocol.Message
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Group rides on the phone: holds the groups, keeps the active one connected to the relay while a
 * tablet (or Curveon itself, on a phone that is its own companion) is listening, and turns what
 * happens there into the [Message]s the tablet draws. The tablet holds no keys and no network code.
 *
 * Everything runs on one thread: the group client calls back from its own threads, and taking
 * locks in both directions could deadlock.
 */
object GroupHub {
    private const val TAG = "GroupHub"
    private const val DEFAULT_INVITE_DAYS = 7
    private const val DEVICE_LINK_MINUTES = 15
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "curveon-group-hub").apply { isDaemon = true } }
    private val transports = OkHttpTransportFactory()

    /** For the companion's and Curveon's own screens: the active group and how its link is doing. */
    data class Summary(val groupId: String, val name: String?, val status: Message.GroupStatus, val members: Int, val admin: Boolean)

    private val _summary = MutableStateFlow<Summary?>(null)
    val summary: StateFlow<Summary?> = _summary.asStateFlow()

    /** Every group on this phone (id, name), for settings screens. */
    private val _groupNames = MutableStateFlow<List<Pair<String, String?>>>(emptyList())
    val groupNames: StateFlow<List<Pair<String, String?>>> = _groupNames.asStateFlow()

    @Volatile private var published: Map<String, GroupState> = emptyMap()

    /** Things that went wrong (a bad invite, no relay set), for a short message on the phone's own screen. */
    private val _errors = MutableSharedFlow<Message.GroupErrorCode>(extraBufferCapacity = 4)
    val errors: SharedFlow<Message.GroupErrorCode> = _errors.asSharedFlow()

    /** A link that makes the rider's other device the same rider in [groupId] (secret, so short-lived); null for an unknown group. */
    fun deviceLink(groupId: String, minutes: Int = DEVICE_LINK_MINUTES): String? =
        published[groupId]?.deviceLink(System.currentTimeMillis() / 1000 + minutes * 60L)?.toUri("curveon")

    /** A link to join [groupId], valid for [days]; null for a group this phone does not know. Safe to call from any thread. */
    fun inviteLink(groupId: String, days: Int = DEFAULT_INVITE_DAYS): String? =
        published[groupId]?.invite(System.currentTimeMillis() / 1000 + days * 86_400L)?.toUri()

    // All of this is touched only on [exec].
    private var context: Context? = null
    private var store: GroupStore? = null
    private val groups = LinkedHashMap<String, GroupState>()
    private var activeId: String? = null
    private var client: GroupClient? = null
    private var connected = false
    private var removed = false
    private val sharer = PositionSharer()
    private var sos: SosTracker? = null
    private val positions = LinkedHashMap<String, Message.GroupPositionInfo>()
    private var network: ConnectivityManager.NetworkCallback? = null

    /** Called when the companion service starts, or when Curveon starts being its own companion. */
    fun start(context: Context) = exec.execute {
        if (this.context != null) return@execute
        val app = context.applicationContext
        this.context = app
        store = GroupStore(app).also { s ->
            s.loadGroups().forEach { groups[it.groupIdHex] = it }
            // A friend code that was shown but never scanned is of no use after a restart.
            groups.values.filter { it.kind == GroupKind.FRIEND && it.peer == null && it.isAdmin }.map { it.groupIdHex }.forEach { id ->
                groups.remove(id)
                s.delete(id)
            }
            activeId = s.activeId?.takeIf { groups[it]?.kind == GroupKind.RIDE }
            sharer.dataSaver = s.dataSaver
        }
        runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            network = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { exec.execute { client?.reconnectNow() } }
            }.also { cm.registerDefaultNetworkCallback(it) }
        }
        publish()
        updateConnection()
    }

    fun stop() = exec.execute {
        val app = context ?: return@execute
        client?.shutdown()
        client = null
        connected = false
        stopAllFriends()
        offers.clear()
        runCatching { network?.let { app.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        network = null
        context = null
        store = null
        groups.clear()
        positions.clear()
        activeId = null
        publish()
    }

    fun setDataSaver(on: Boolean) = exec.execute {
        store?.dataSaver = on
        sharer.dataSaver = on
    }

    /** A tablet came or went: connect only while someone is looking, to save the phone's battery. */
    fun tabletsChanged() = exec.execute { updateConnection() }

    /** What a tablet that just connected needs to catch up. */
    fun sendSnapshot(sink: (Message) -> Unit) = exec.execute { snapshot().forEach { runCatching { sink(it) } } }

    fun handle(message: Message) = exec.execute {
        runCatching { handleNow(message) }.onFailure { Log.w(TAG, "Group message failed", it) }
    }

    // ---- tablet -> phone ----

    private fun handleNow(m: Message) {
        val store = store ?: return
        when (m) {
            is Message.RequestGroups -> snapshot().forEach(::send)
            is Message.GroupCreate -> {
                val relay = store.relayHost
                if (relay.isBlank()) return error(Message.GroupErrorCode.NO_RELAY)
                add(GroupState.create(relay, m.name?.takeIf { it.isNotBlank() }))
            }
            is Message.GroupJoin -> {
                val invite = Invite.parse(m.invite) ?: return error(Message.GroupErrorCode.INVALID_INVITE)
                if (invite.isExpired(System.currentTimeMillis() / 1000)) return error(Message.GroupErrorCode.EXPIRED_INVITE)
                val id = invite.groupId.joinToString("") { "%02x".format(it) }
                if (id in groups) return error(Message.GroupErrorCode.ALREADY_MEMBER).also { if (groups[id]?.kind == GroupKind.RIDE) select(id) }
                GroupState.join(invite).let { if (it.kind == GroupKind.FRIEND) addFriend(it) else add(it) }
            }
            is Message.GroupLeave -> {
                val state = groups[m.groupId] ?: return error(Message.GroupErrorCode.NO_SUCH_GROUP)
                if (state.kind == GroupKind.FRIEND) return removeFriend(m.groupId)
                if (m.groupId == activeId) { client?.leave(); detach() }
                groups.remove(state.groupIdHex)
                store.delete(state.groupIdHex)
                if (activeId == state.groupIdHex) { activeId = null; store.activeId = null }
                send(groupList())
            }
            is Message.GroupSelect -> select(m.groupId?.takeIf { groups[it]?.kind == GroupKind.RIDE })
            is Message.GroupSharing -> {
                store.sharing = m.on
                sharer.reset()
                send(groupList())
                sendConnection()
            }
            is Message.GroupProfile -> {
                store.profile = m
                send(profileMessage())
                friendClients.forEach { (id, c) -> groups[id]?.let { c.setProfile(helloFor(it)) } }
                client?.setProfile(helloFor(activeId?.let { groups[it] } ?: return))
                membersMessage()?.let(::send) // the rider's own row shows the new name and picture at once
            }
            is Message.MyPosition -> position(m)
            is Message.ShareRoute -> {
                val data = runCatching { Base64.getDecoder().decode(m.gpxGzBase64) }.getOrNull() ?: return
                client?.sendRoute(Payload.Route(UUID.randomUUID().toString(), m.name, "gpx", data))
            }
            is Message.SosStart -> {
                val c = client ?: return
                val payload = Payload.Sos(m.lat, m.lon)
                c.sendSos(payload) // held until it has gone out
                sos?.started(payload, System.currentTimeMillis())?.let { send(sosMessage(it)) }
            }
            is Message.SosCancel -> {
                client?.cancelSos()
                sos?.cancelled(myId() ?: return)?.let { send(sosMessage(it)) }
            }
            is Message.SosAnswer -> {
                client?.answerSos(m.sosRiderId, m.coming)
                sos?.answered(myId() ?: return, m.sosRiderId, m.coming)?.let { send(sosMessage(it)) }
            }
            is Message.GroupRemoveRider -> {
                val state = groups[m.groupId] ?: return error(Message.GroupErrorCode.NO_SUCH_GROUP)
                if (!state.isAdmin || m.groupId != activeId || client?.removeRider(m.riderId) != true) return error(Message.GroupErrorCode.NOT_ADMIN)
                positions.remove(m.riderId)
                sos?.forget(m.riderId)?.let { send(sosMessage(it)) }
                membersMessage()?.let(::send)
            }
            is Message.RequestDeviceLink -> {
                if (groups[m.groupId] == null) return error(Message.GroupErrorCode.NO_SUCH_GROUP)
                send(Message.GroupDeviceLink(m.groupId, deviceLink(m.groupId) ?: return))
            }
            is Message.GroupSettings -> {
                store.customRelay = m.relayHost
                store.dataSaver = m.dataSaver
                sharer.dataSaver = m.dataSaver
                send(settingsMessage())
            }
            is Message.GroupUseThisDevice -> {
                client?.useThisDevice()
                sharer.reset()
                sendConnection()
            }
            is Message.RequestFriends -> { send(friendList()); sweepFriends() }
            is Message.RequestFriendInvite -> makeFriendInvite()?.let { (id, uri) -> send(Message.FriendInvite(id, uri)) }
            is Message.FriendRemove -> removeFriend(m.channelId)
            is Message.FriendAddToGroup -> {
                val ride = groups[m.groupId]?.takeIf { it.kind == GroupKind.RIDE } ?: return error(Message.GroupErrorCode.NO_SUCH_GROUP)
                val link = ride.invite(System.currentTimeMillis() / 1000 + DEFAULT_INVITE_DAYS * 86_400L).toUri()
                m.friendIds.forEach { id -> touchFriend(id, 8_000) { c -> c.sendInvite(link, ride.name) } }
            }
            is Message.GroupInviteAnswer -> {
                val offer = offers.remove(m.offerId) ?: return
                store.markOfferHandled(m.offerId)
                if (m.accept) handleNow(Message.GroupJoin(offer.link))
            }
            is Message.TripShare -> client?.sendTrip(Payload.Trip(m.tripId, m.rev, m.name, m.doc))
            is Message.RequestGroupInvite -> {
                val state = groups[m.groupId] ?: return error(Message.GroupErrorCode.NO_SUCH_GROUP)
                val days = m.validDays ?: DEFAULT_INVITE_DAYS
                send(Message.GroupInvite(m.groupId, state.invite(System.currentTimeMillis() / 1000 + days * 86_400L).toUri()))
            }
            else -> Unit
        }
    }

    private fun add(state: GroupState) {
        val store = store ?: return
        groups[state.groupIdHex] = state
        store.save(state)
        select(state.groupIdHex)
        send(Message.GroupInvite(state.groupIdHex, state.invite(System.currentTimeMillis() / 1000 + DEFAULT_INVITE_DAYS * 86_400L).toUri()))
    }

    private fun select(id: String?) {
        detach()
        activeId = id
        store?.activeId = id
        sharer.reset()
        updateConnection()
        send(groupList())
        membersMessage()?.let(::send) // the rider is in the list from the start, before anyone else is
    }

    // ---- connection ----

    private fun updateConnection() {
        if (context == null) return
        updateFriendSweep()
        val state = activeId?.let { groups[it] }
        val wanted = state != null && !removed && PhoneHub.tabletCount > 0
        if (!wanted) {
            if (client != null) detach()
            return
        }
        if (client != null) return
        state!!
        sos = SosTracker(myIdOf(state))
        val c = GroupClient(
            state, transports, { e -> exec.execute { onEvent(state, e) } }, { s -> exec.execute { store?.save(s) } },
            scheme = scheme(state.relayHost)
        )
        c.setProfile(helloFor(state))
        client = c
        c.start()
    }

    /** TLS always, except in a debuggable build talking to this PC (adb reverse, or the emulator's host address). */
    private fun scheme(host: String): String {
        val debuggable = (context?.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val local = host.startsWith("localhost") || host.startsWith("127.") || host.startsWith("10.0.2.2") || host.startsWith("192.168.")
        return if (debuggable && local) "ws" else "wss"
    }

    /** Stop talking to the relay and forget what was seen in this group (it is replayed on the next connect). */
    private fun detach() {
        client?.shutdown()
        client = null
        connected = false
        removed = false
        positions.clear()
        sos = null
    }

    private fun helloFor(state: GroupState): Payload.Hello {
        val p = store?.profile
        val avatar = when {
            p?.photoJpegBase64 != null -> runCatching { Avatar.Photo(Base64.getDecoder().decode(p.photoJpegBase64)) }.getOrNull()
            p?.icon != null -> Avatar.Icon(p.icon!!)
            else -> null
        }
        return Payload.Hello(p?.nickname?.takeIf { it.isNotBlank() } ?: "Rider", p?.colour ?: 0xFF00E5FF.toInt(), state.agreement.publicKey, avatar)
    }

    private fun myId() = activeId?.let { groups[it] }?.let(::myIdOf)

    private fun myIdOf(state: GroupState) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(state.riderId)

    // ---- friends: a private channel with each, opened briefly (a socket per friend all day would cost the battery) ----

    private class Offer(val friendId: String, val link: String, val groupName: String?)

    private val offers = LinkedHashMap<String, Offer>()
    private val friendClients = HashMap<String, GroupClient>()
    private val friendDeadlines = HashMap<String, Long>()
    private val friendActions = HashMap<String, MutableList<(GroupClient) -> Unit>>()
    private var sweepJob: ScheduledFuture<*>? = null

    private fun friendStates() = groups.values.filter { it.kind == GroupKind.FRIEND }

    private fun friendList() = Message.FriendList(friendStates().mapNotNull { st ->
        st.peer?.let { Message.FriendInfo(st.groupIdHex, it.nickname, it.colour, it.icon, it.photoBase64) }
    })

    /** Keeps the channel to a friend open for at least [keepMs] more, and runs [action] as soon as it is. */
    private fun touchFriend(id: String, keepMs: Long, action: ((GroupClient) -> Unit)? = null) {
        val state = groups[id]?.takeIf { it.kind == GroupKind.FRIEND } ?: return
        var c = friendClients[id]
        if (c == null) {
            c = GroupClient(
                state, transports, { e -> exec.execute { onFriendEvent(state, e) } }, { s -> exec.execute { store?.save(s) } },
                scheme = scheme(state.relayHost)
            )
            c.setProfile(helloFor(state))
            friendClients[id] = c
            c.start()
        }
        if (action != null) { if (c.connected) action(c) else friendActions.getOrPut(id) { mutableListOf() }.add(action) }
        val deadline = maxOf(friendDeadlines[id] ?: 0L, System.currentTimeMillis() + keepMs)
        friendDeadlines[id] = deadline
        exec.schedule({ closeFriendIfDone(id) }, deadline - System.currentTimeMillis() + 50, TimeUnit.MILLISECONDS)
    }

    private fun closeFriendIfDone(id: String) {
        if (System.currentTimeMillis() < (friendDeadlines[id] ?: 0L)) return // kept open longer since
        stopFriend(id)
    }

    private fun stopFriend(id: String) {
        friendDeadlines.remove(id)
        friendActions.remove(id)
        friendClients.remove(id)?.shutdown()
    }

    private fun stopAllFriends() {
        sweepJob?.cancel(false)
        sweepJob = null
        friendClients.keys.toList().forEach(::stopFriend)
    }

    /** While a tablet listens, look in on every friend now and then: an invite from one may be waiting. */
    private fun updateFriendSweep() {
        val listening = PhoneHub.tabletCount > 0
        if (listening && sweepJob == null) sweepJob = exec.scheduleWithFixedDelay({ sweepFriends() }, 0, 10, TimeUnit.MINUTES)
        else if (!listening && sweepJob != null) stopAllFriends()
    }

    private fun sweepFriends() = friendStates().forEach { touchFriend(it.groupIdHex, 6_000) }

    private fun onFriendEvent(state: GroupState, e: GroupEvent) {
        val id = state.groupIdHex
        when (e) {
            GroupEvent.Connected -> friendActions.remove(id)?.forEach { act -> friendClients[id]?.let { runCatching { act(it) } } }
            is GroupEvent.MemberUpdated -> {
                val m = e.member
                // A friend channel is for two: whoever said hello first is the friend.
                if (state.peer?.riderId != null && state.peer?.riderId != m.id) return
                val photo = (m.avatar as? Avatar.Photo)?.let { Base64.getEncoder().encodeToString(it.jpeg) }
                state.peer = PeerProfile(m.nickname, m.colour, (m.avatar as? Avatar.Icon)?.id, photo, m.id)
                store?.save(state)
                send(friendList())
            }
            is GroupEvent.InviteReceived -> offer(state, e.invite)
            else -> Unit
        }
    }

    private fun addFriend(state: GroupState) {
        groups[state.groupIdHex] = state
        store?.save(state)
        touchFriend(state.groupIdHex, 20_000) // long enough for the two to say hello
        send(friendList())
    }

    private fun removeFriend(id: String) {
        val state = groups[id]?.takeIf { it.kind == GroupKind.FRIEND } ?: return
        friendClients[id]?.leave()
        stopFriend(id)
        groups.remove(id)
        store?.delete(id)
        offers.entries.removeAll { it.value.friendId == id }
        publish()
        send(friendList())
    }

    /** A new private channel for one friend to join (the code and the link). Kept open while the code is on show. */
    private fun makeFriendInvite(): Pair<String, String>? {
        val store = store ?: return null
        if (store.relayHost.isBlank()) { error(Message.GroupErrorCode.NO_RELAY); return null }
        val state = friendStates().firstOrNull { it.peer == null && it.isAdmin }
            ?: GroupState.create(store.relayHost, null, GroupKind.FRIEND).also { groups[it.groupIdHex] = it; store.save(it) }
        touchFriend(state.groupIdHex, 15 * 60_000L)
        return state.groupIdHex to state.invite(System.currentTimeMillis() / 1000 + DEFAULT_INVITE_DAYS * 86_400L).toUri()
    }

    /** For the companion's own screen: a friend code, delivered on the main thread (null if there is no relay set). */
    fun createFriendInvite(onReady: (String?) -> Unit) = exec.execute {
        val uri = runCatching { makeFriendInvite()?.second }.getOrNull()
        android.os.Handler(android.os.Looper.getMainLooper()).post { onReady(uri) }
    }

    private fun offer(from: GroupState, inv: Payload.GroupInvite) {
        val store = store ?: return
        if (store.isOfferHandled(inv.offerId) || inv.offerId in offers) return
        val parsed = Invite.parse(inv.link)
        if (parsed == null || parsed.isExpired(System.currentTimeMillis() / 1000)) { store.markOfferHandled(inv.offerId); return }
        if (parsed.groupId.joinToString("") { "%02x".format(it) } in groups) { store.markOfferHandled(inv.offerId); return } // already in it
        val o = Offer(from.groupIdHex, inv.link, inv.groupName)
        offers[inv.offerId] = o
        send(offerMessage(inv.offerId, o))
    }

    private fun offerMessage(id: String, o: Offer): Message.GroupInviteOffer {
        val peer = groups[o.friendId]?.peer
        return Message.GroupInviteOffer(id, peer?.nickname ?: "?", o.groupName, peer?.photoBase64, peer?.colour ?: 0)
    }

    // ---- position ----

    private fun position(m: Message.MyPosition) {
        val c = client ?: return
        if (store?.sharing != true) return
        val pos = sharer.offer(Fix(m.lat, m.lon, m.speedMs, m.heading, m.accuracy), battery()) ?: return
        c.sendPosition(pos)
    }

    private fun battery(): Int? {
        val i = context?.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level < 0 || scale <= 0) null else level * 100 / scale
    }

    // ---- relay -> tablet ----

    private fun onEvent(state: GroupState, e: GroupEvent) {
        if (state.groupIdHex != activeId) return // an event from a group we have left or switched away from
        when (e) {
            GroupEvent.Connected -> { connected = true; sendConnection(); membersMessage()?.let(::send) }
            is GroupEvent.Disconnected -> { connected = false; sendConnection() }
            is GroupEvent.DeviceActiveChanged -> { sharer.reset(); sendConnection() }
            is GroupEvent.MemberUpdated -> { membersMessage()?.let(::send); sendConnection() }
            is GroupEvent.MemberLeft -> {
                positions.remove(e.riderId)
                sos?.forget(e.riderId)?.let { send(sosMessage(it)) }
                membersMessage()?.let(::send)
                sendConnection()
            }
            is GroupEvent.Position -> {
                val p = e.pos
                val info = Message.GroupPositionInfo(e.riderId, p.lat, p.lon, p.heading, p.speed, e.tsMs, p.stopped, p.stoppedSince, p.battery)
                positions[e.riderId] = info
                send(Message.GroupPositions(state.groupIdHex, listOf(info)))
            }
            is GroupEvent.SosReceived -> sos?.received(e.riderId, e.sos, e.tsMs)?.let { send(sosMessage(it)) }
            is GroupEvent.SosAnswered -> sos?.answered(e.riderId, e.sosRiderId, e.coming)?.let { send(sosMessage(it)) }
            is GroupEvent.SosCancelled -> sos?.cancelled(e.riderId)?.let { send(sosMessage(it)) }
            is GroupEvent.TripReceived -> send(Message.GroupTrip(
                state.groupIdHex, e.trip.tripId, e.trip.rev, e.riderId, client?.members()?.firstOrNull { it.id == e.riderId }?.nickname,
                e.trip.name, e.trip.doc, e.mine
            ))
            is GroupEvent.InviteReceived -> Unit // invites come through friend channels, not rides
            is GroupEvent.RouteReceived ->
                send(Message.GroupRoute(state.groupIdHex, e.riderId, e.route.name, Base64.getEncoder().encodeToString(e.route.data)))
            GroupEvent.KeyChanged -> {
                positions.clear()
                sos?.clear()
                store?.save(state)
            }
            GroupEvent.Removed -> {
                removed = true
                connected = false
                send(Message.GroupConnection(state.groupIdHex, Message.GroupStatus.REMOVED, 0))
                // The group is of no use any more; the rider sees "removed" and can leave it from the list.
            }
        }
    }

    private fun send(m: Message) {
        if (m is Message.GroupList) publish()
        PhoneHub.broadcast(m)
    }

    /** Keeps the screens' view of the groups and the active group's link in step. */
    private fun publish() {
        published = HashMap(groups)
        _groupNames.value = groups.values.filter { it.kind == GroupKind.RIDE }.map { it.groupIdHex to it.name }
        val c = connectionMessage()
        val g = c?.let { groups[it.groupId] }
        _summary.value = if (c == null || g == null) null else Summary(c.groupId, g.name, c.status, c.members, g.isAdmin)
    }

    private fun sendConnection() {
        publish()
        connectionMessage()?.let { PhoneHub.broadcast(it) }
    }

    private fun error(code: Message.GroupErrorCode) {
        _errors.tryEmit(code)
        send(Message.GroupError(code))
    }

    // ---- what the tablet is told ----

    /** The rider's own name, colour and picture (what is set also when there is no group). */
    private fun profileMessage() = store?.profile ?: Message.GroupProfile("Rider", 0xFF00E5FF.toInt(), null, null)

    private fun settingsMessage() = Message.GroupSettings(store?.customRelay.orEmpty(), store?.dataSaver ?: false)

    private fun snapshot(): List<Message> = buildList {
        add(settingsMessage())
        add(profileMessage())
        add(friendList())
        offers.forEach { (id, o) -> add(offerMessage(id, o)) }
        add(groupList())
        connectionMessage()?.let(::add)
        membersMessage()?.let(::add)
        val id = activeId
        if (id != null && positions.isNotEmpty()) add(Message.GroupPositions(id, positions.values.toList()))
        sos?.active()?.forEach { add(sosMessage(it)) }
    }

    private fun groupList() = Message.GroupList(
        groups.values.filter { it.kind == GroupKind.RIDE }.map { g ->
            Message.GroupInfo(g.groupIdHex, g.name, if (g.groupIdHex == activeId) (client?.members()?.size ?: 0) + 1 else 0, g.isAdmin)
        },
        activeId, store?.sharing ?: true
    )

    private fun connectionMessage(): Message.GroupConnection? {
        val id = activeId ?: return null
        val status = when {
            removed -> Message.GroupStatus.REMOVED
            !connected -> Message.GroupStatus.OFFLINE
            store?.sharing == false -> Message.GroupStatus.PAUSED
            else -> Message.GroupStatus.CONNECTED
        }
        return Message.GroupConnection(id, status, (client?.members()?.size ?: 0) + 1, otherDeviceActive = client?.sendingHere == false)
    }

    private fun membersMessage(): Message.GroupMembers? {
        val id = activeId ?: return null
        val state = groups[id] ?: return null
        val me = helloFor(state)
        val mine = Message.GroupMemberInfo(myIdOf(state), me.nickname, me.colour, (me.avatar as? Avatar.Icon)?.id, photo(me.avatar))
        return Message.GroupMembers(id, listOf(mine) + (client?.members().orEmpty()).map(::info))
    }

    private fun info(m: Member) = Message.GroupMemberInfo(m.id, m.nickname, m.colour, (m.avatar as? Avatar.Icon)?.id, photo(m.avatar))

    private fun photo(a: Avatar?): String? = (a as? Avatar.Photo)?.let { Base64.getEncoder().encodeToString(it.jpeg) }

    private fun sosMessage(v: SosView): Message.GroupSos {
        val id = activeId.orEmpty()
        val members = (client?.members()?.size ?: 0) + 1
        return Message.GroupSos(
            id, v.from, v.lat, v.lon, v.tsMs, v.active, v.mine,
            v.acks.map { (rider, coming) -> Message.SosAckInfo(rider, coming) }, members
        )
    }
}
