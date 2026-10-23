package com.curveon.phone

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import android.util.Log
import com.curveon.groups.Avatar
import com.curveon.groups.FRIEND_REQUEST_KEEP_S
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
import com.curveon.groups.SyncDoc
import com.curveon.groups.SyncEntry
import org.json.JSONObject
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
    private const val SYNC_TRIP = "me"
    /** In a friend channel: "1" when the sender lets the friend see where they are, "0" when no more. */
    private const val SHARE_TRIP = "share"
    /** In the rider's sync document: who of the friends see the rider. */
    private const val SHARE_KEY = "share"
    /** How long the channel to a friend who sees the rider stays open after a position. */
    private const val FRIEND_SHARE_KEEP_MS = 3 * 60_000L
    /** After a nudge (sent or come in) the friend channel stays open this long, so the answer comes at once. */
    private const val NUDGE_KEEP_MS = 3 * 60_000L
    /** A nudge to a friend not on its way by then is reported as not sent (it may still go when the channel opens). */
    private const val NUDGE_SEND_WAIT_MS = 12_000L
    /** A position that came before the friend's "I share" is shown with it only if no older than this. */
    private const val FRIEND_EARLY_MAX_MS = 15 * 60_000L
    /** The same friend or rider is nudged at most this often. */
    private const val NUDGE_MIN_GAP_MS = 20_000L
    /** How often Curveon looks for where the friends who share with the rider are. */
    private const val FRIEND_WATCH_MINUTES = 2L
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "curveon-group-hub").apply { isDaemon = true } }
    /** The relay straight over the internet. */
    val directTransports: com.curveon.groups.TransportFactory = OkHttpTransportFactory()

    /** How the relay is reached: straight over the internet, or (set by Curveon on a tablet without any) through the phone. */
    @Volatile var transports: com.curveon.groups.TransportFactory = directTransports

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
            // A friend code that was shown but never scanned is of no use after a restart. A link sent through a chat is kept
            // for as long as it works: the friend may open it days later, in another country.
            val now = System.currentTimeMillis() / 1000
            val links = s.pendingLinks.filter { (id, until) -> until > now && id in groups }
            s.pendingLinks = links
            groups.values.filter { it.kind == GroupKind.FRIEND && it.peer == null && it.isAdmin && it.groupIdHex !in links }.map { it.groupIdHex }.forEach { id ->
                groups.remove(id)
                s.delete(id)
            }
            syncDoc = s.syncDoc?.let(SyncDoc::fromJson) ?: SyncDoc()
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
        friendPositions.clear()
        earlyFriendPositions.clear()
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

    /** True for the messages the groups act on (commands from a screen), so a caller can tell them from the phone's own. */
    fun accepts(m: Message): Boolean = when (m) {
        is Message.RequestGroups, is Message.GroupCreate, is Message.GroupJoin, is Message.GroupLeave, is Message.GroupSelect,
        is Message.GroupSharing, is Message.GroupProfile, is Message.MyPosition, is Message.ShareRoute, is Message.SosStart,
        is Message.SosCancel, is Message.SosAnswer, is Message.GroupRemoveRider, is Message.RequestDeviceLink, is Message.GroupSettings,
        is Message.GroupUseThisDevice, is Message.RequestFriends, is Message.RequestFriendInvite, is Message.FriendRemove,
        is Message.FriendAddToGroup, is Message.GroupInviteAnswer, is Message.TripShare, is Message.TripToFriends, is Message.RequestGroupInvite,
        is Message.RequestSelfLink, is Message.SelfUnlink, is Message.FriendSharing, is Message.RequestFriendLink,
        is Message.NudgeSend, is Message.NudgeAnswer, is Message.FriendRequestSend -> true
        else -> false
    }

    /** Opens the relay again at once (the way to it changed: another network, or the phone came or went). */
    fun reconnectNow() = exec.execute {
        client?.reconnectNow()
        friendClients.values.forEach { it.reconnectNow() }
    }

    fun handle(message: Message) = exec.execute {
        runCatching { handleNow(message) }.onFailure { Log.w(TAG, "Group message failed", it) }
    }

    // ---- tablet -> phone ----

    private fun handleNow(m: Message) {
        val store = store ?: return
        when (m) {
            is Message.RequestGroups -> snapshot().forEach(::send)
            is Message.GroupCreate -> {
                add(GroupState.create(RELAY_HOST, m.name?.takeIf { it.isNotBlank() }))
            }
            is Message.GroupJoin -> {
                val invite = Invite.parse(m.invite) ?: return error(Message.GroupErrorCode.INVALID_INVITE)
                if (invite.isExpired(System.currentTimeMillis() / 1000)) return error(Message.GroupErrorCode.EXPIRED_INVITE)
                if (invite.kind == GroupKind.SELF) return joinSelf(invite)
                val id = invite.groupId.joinToString("") { "%02x".format(it) }
                if (id in groups) return error(Message.GroupErrorCode.ALREADY_MEMBER).also { if (groups[id]?.kind == GroupKind.RIDE) select(id) }
                onCurveonRelay(GroupState.join(invite)).let { if (it.kind == GroupKind.FRIEND) addFriend(it) else add(it) }
            }
            is Message.GroupLeave -> {
                val state = groups[m.groupId] ?: return error(Message.GroupErrorCode.NO_SUCH_GROUP)
                if (state.kind == GroupKind.FRIEND) return removeFriend(m.groupId)
                if (m.groupId == activeId) { client?.leave(); detach() }
                groups.remove(state.groupIdHex)
                store.delete(state.groupIdHex)
                trackGone(state.groupIdHex)
                if (activeId == state.groupIdHex) { activeId = null; store.activeId = null }
                send(groupList())
            }
            is Message.GroupSelect -> select(m.groupId?.takeIf { groups[it]?.kind == GroupKind.RIDE })
            is Message.GroupSharing -> {
                val was = store.sharing
                store.sharing = m.on
                sharer.reset()
                // Paused: the group is told, so the rider goes off their maps at once (not just fades as if out of
                // coverage); then the link to the group closes until the rider is back.
                if (was && !m.on) {
                    lastFix?.let { f -> client?.sendPosition(Payload.Pos(f.lat, f.lon, 0f, 0f, 0f, battery(), paused = true)) }
                    client?.let { c -> exec.schedule({ if (store.sharing == false && client === c) { detach(); sendConnection(); membersMessage()?.let(::send) } }, 1_500, TimeUnit.MILLISECONDS) }
                }
                send(groupList())
                if (m.on) updateConnection()
                sendConnection()
            }
            is Message.GroupProfile -> {
                store.profile = m
                track(SyncDoc.PROFILE, profilePayload())
                profileChanged()
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
                store.dataSaver = m.dataSaver
                sharer.dataSaver = m.dataSaver
                send(settingsMessage())
            }
            is Message.GroupUseThisDevice -> {
                client?.useThisDevice()
                sharer.reset()
                sendConnection()
            }
            is Message.RequestSelfLink -> makeSelfLink()
            is Message.SelfUnlink -> unlinkSelf()
            is Message.RequestFriends -> { send(friendList()); sweepFriends() }
            is Message.RequestFriendInvite -> makeFriendInvite()?.let { (id, uri) -> send(Message.FriendInvite(id, uri)) }
            is Message.RequestFriendLink -> makeFriendLink()
            is Message.FriendRemove -> removeFriend(m.channelId)
            is Message.FriendSharing -> {
                setFriendSharing(m.on, m.friendIds.toSet())
                track(SHARE_KEY, sharePayload())
            }
            is Message.FriendAddToGroup -> {
                val ride = groups[m.groupId]?.takeIf { it.kind == GroupKind.RIDE } ?: return error(Message.GroupErrorCode.NO_SUCH_GROUP)
                val link = ride.invite(System.currentTimeMillis() / 1000 + DEFAULT_INVITE_DAYS * 86_400L).toUri()
                m.friendIds.forEach { id -> touchFriend(id, 8_000) { c -> c.sendInvite(link, ride.name) } }
            }
            is Message.GroupInviteAnswer -> {
                val offer = offers.remove(m.offerId) ?: return
                store.markOfferHandled(m.offerId)
                if (m.accept) handleNow(Message.GroupJoin(offer.link))
                // Said no to a friend request: no more from that rider in that group (nothing tells them).
                else offer.request?.let { store.markOfferHandled(it.blockKey) }
            }
            is Message.FriendRequestSend -> friendRequest(m.riderId)
            is Message.TripShare -> client?.sendTrip(Payload.Trip(m.tripId, m.rev, m.name, m.doc))
            is Message.TripToFriends -> m.friendIds.forEach { id ->
                touchFriend(id, 8_000) { c ->
                    c.sendTrip(Payload.Trip(m.tripId, 1, m.name, m.doc))
                    // The relay keeps only the newest trips in a channel: say again that this friend sees the rider, so a
                    // run of shared trips never pushes that out.
                    if (id in seeingMe()) c.sendTrip(Payload.Trip(SHARE_TRIP, System.currentTimeMillis(), "", "1"))
                }
            }
            is Message.NudgeSend -> nudge(m)
            is Message.NudgeAnswer -> {
                store?.markOfferHandled(m.nudgeId)
                val state = groups[m.channelId] ?: return
                if (state.kind == GroupKind.RIDE) { if (m.channelId == activeId) client?.sendNudgeSeen(m.nudgeId, m.toRiderId) }
                else touchFriend(m.channelId, NUDGE_KEEP_MS) { c -> c.sendNudgeSeen(m.nudgeId, m.toRiderId) }
            }
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
        trackGroup(state)
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
        // Paused: away from the group altogether (they don't see the rider, the rider doesn't see them, no SOS).
        val wanted = state != null && !removed && PhoneHub.tabletCount > 0 && store?.sharing != false
        if (!wanted) {
            if (client != null) detach()
            return
        }
        if (client != null) return
        state!!
        sos = SosTracker(myIdOf(state))
        val c = GroupClient(
            state, transports, { e -> exec.execute { onEvent(state, e) } }, { s -> exec.execute { stateSaved(s) } },
        )
        c.setProfile(helloFor(state))
        client = c
        c.start()
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

    private class Offer(val friendId: String, val link: String, val groupName: String?, val request: FriendRequest? = null)

    /** Who asks to be the rider's friend, from the group they met in; [blockKey] is remembered when the rider says no. */
    private class FriendRequest(val nickname: String, val colour: Int, val photo: String?, val blockKey: String)

    private val offers = LinkedHashMap<String, Offer>()
    private val friendClients = HashMap<String, GroupClient>()
    private val friendDeadlines = HashMap<String, Long>()
    private val friendActions = HashMap<String, MutableList<(GroupClient) -> Unit>>()
    private var sweepJob: ScheduledFuture<*>? = null

    private fun friendStates() = groups.values.filter { it.kind == GroupKind.FRIEND }

    private fun friendList(): Message.FriendList {
        val store = store
        val seeing = store?.friendsSeeingMe.orEmpty()
        val sharing = store?.friendsSharingWithMe.orEmpty()
        return Message.FriendList(friendStates().mapNotNull { st ->
            st.peer?.let {
                val id = st.groupIdHex
                Message.FriendInfo(id, it.nickname, it.colour, it.icon, it.photoBase64, id in seeing, id in sharing) to (it.riderId ?: id)
            }
        }.distinctBy { it.second }.map { it.first }, store?.friendSharing ?: false) // a friend met on both of the rider's devices is one friend
    }

    /** Keeps the channel to a friend open for at least [keepMs] more, and runs [action] as soon as it is. */
    private fun touchFriend(id: String, keepMs: Long, action: ((GroupClient) -> Unit)? = null) {
        val state = groups[id]?.takeIf { it.kind != GroupKind.RIDE } ?: return
        var c = friendClients[id]
        if (c == null) {
            c = GroupClient(
                state, transports, { e -> exec.execute { onFriendEvent(state, e) } }, { s -> exec.execute { stateSaved(s) } },
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

    /** When each friend or rider was last nudged: a burst of taps (from any of the rider's screens) sends one. */
    private val lastNudge = HashMap<String, Long>()

    /** The rider asks a friend (through their channel) or a rider in the active group for attention. */
    private fun nudge(m: Message.NudgeSend) {
        val riderId = m.riderId
        val key = "${m.channelId}/$riderId"
        val now = System.currentTimeMillis()
        if (now - (lastNudge[key] ?: 0L) < NUDGE_MIN_GAP_MS) return
        lastNudge[key] = now
        if (riderId != null) {
            val ok = m.channelId == activeId && client?.sendNudge(riderId) != null
            send(Message.NudgeStatus(m.channelId, riderId, if (ok) Message.NudgeState.SENT else Message.NudgeState.FAILED))
            return
        }
        val to = groups[m.channelId]?.takeIf { it.kind == GroupKind.FRIEND }?.peer?.riderId
            ?: return send(Message.NudgeStatus(m.channelId, null, Message.NudgeState.FAILED))
        var sent = false
        touchFriend(m.channelId, NUDGE_KEEP_MS) { c ->
            sent = c.sendNudge(to) != null
            send(Message.NudgeStatus(m.channelId, null, if (sent) Message.NudgeState.SENT else Message.NudgeState.FAILED))
        }
        exec.schedule({ if (!sent) send(Message.NudgeStatus(m.channelId, null, Message.NudgeState.FAILED)) }, NUDGE_SEND_WAIT_MS, TimeUnit.MILLISECONDS)
    }

    /** Someone tries to reach the rider: shown once (an answered one the relay hands over again is not). */
    private fun nudgeIn(channelId: String, from: String, nudge: Payload.Nudge, tsMs: Long, nickname: String?, colour: Int, photo: String?) {
        if (store?.isOfferHandled(nudge.id) == true) return
        send(Message.NudgeIn(channelId, nudge.id, from, nickname.orEmpty(), colour, photo, tsMs))
    }

    /** An answer to a nudge: the rider's own one was seen, or the rider answered one on their other device. */
    private fun nudgeSeen(channelId: String, e: GroupEvent.NudgeSeen, friend: Boolean) {
        if (e.mine) {
            store?.markOfferHandled(e.seen.id)
            send(Message.NudgeGone(e.seen.id))
        } else send(Message.NudgeStatus(channelId, if (friend) null else e.riderId, Message.NudgeState.SEEN))
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
        watchJob?.cancel(false)
        watchJob = null
        friendClients.keys.toList().forEach(::stopFriend)
    }

    /**
     * While a tablet listens, look in on every friend now and then: an invite from one may be waiting. Friends who share where
     * they are are looked in on more often, all at once (one wake of the radio), and the relay hands over their latest position.
     */
    private fun updateFriendSweep() {
        val listening = PhoneHub.tabletCount > 0
        if (listening && sweepJob == null) sweepJob = exec.scheduleWithFixedDelay({ sweepFriends() }, 0, 10, TimeUnit.MINUTES)
        else if (!listening && sweepJob != null) stopAllFriends()
        val watching = listening && store?.friendsSharingWithMe.orEmpty().isNotEmpty()
        if (watching && watchJob == null) watchJob = exec.scheduleWithFixedDelay({ watchFriends() }, 0, FRIEND_WATCH_MINUTES, TimeUnit.MINUTES)
        else if (!watching && watchJob != null) { watchJob?.cancel(false); watchJob = null }
    }

    private fun watchFriends() = store?.friendsSharingWithMe.orEmpty().forEach { touchFriend(it, 6_000) }

    private fun sweepFriends() {
        friendStates().forEach { touchFriend(it.groupIdHex, 6_000) }
        selfState()?.let { touchFriend(it.groupIdHex, 8_000) { c -> exec.schedule({ sendSync(c) }, 1_500, TimeUnit.MILLISECONDS) } }
    }

    private fun onFriendEvent(state: GroupState, e: GroupEvent) {
        val id = state.groupIdHex
        when (e) {
            GroupEvent.Connected -> friendActions.remove(id)?.forEach { act -> friendClients[id]?.let { runCatching { act(it) } } }
            is GroupEvent.MemberUpdated -> {
                val m = e.member
                if (state.kind == GroupKind.SELF) { // the rider's other device said hello: linked
                    if (state.peer == null) {
                        state.peer = PeerProfile("device", 0, null, null, m.id)
                        store?.save(state)
                        send(selfStatus())
                        scheduleSync(500)
                    }
                    return
                }
                // A friend channel is for two: whoever said hello first is the friend.
                if (state.peer?.riderId != null && state.peer?.riderId != m.id) return
                val isNew = state.peer == null
                val photo = (m.avatar as? Avatar.Photo)?.let { Base64.getEncoder().encodeToString(it.jpeg) }
                state.peer = PeerProfile(m.nickname, m.colour, (m.avatar as? Avatar.Icon)?.id, photo, m.id)
                store?.save(state)
                send(friendList())
                if (isNew) {
                    // The code or link has done its job: a new friend, on both sides at once.
                    store?.let { s -> if (id in s.pendingLinks) s.pendingLinks = s.pendingLinks - id }
                    send(Message.FriendAdded(id, m.nickname))
                    scheduleSync()
                }
            }
            is GroupEvent.InviteReceived -> offer(state, e.invite)
            // A friend tries to reach the rider: the channel stays open a while, so the answer goes straight back.
            is GroupEvent.NudgeReceived -> if (state.kind == GroupKind.FRIEND) {
                val peer = state.peer
                touchFriend(id, NUDGE_KEEP_MS)
                nudgeIn(id, e.riderId, e.nudge, e.tsMs, peer?.nickname, peer?.colour ?: 0xFF00E5FF.toInt(), peer?.photoBase64)
            }
            is GroupEvent.NudgeSeen -> if (state.kind == GroupKind.FRIEND) nudgeSeen(id, e, friend = true)
            is GroupEvent.TripReceived -> when {
                state.kind == GroupKind.SELF && e.trip.tripId == SYNC_TRIP -> onSyncReceived(e.trip.doc)
                state.kind == GroupKind.FRIEND && e.trip.tripId == SHARE_TRIP && !e.mine -> friendSharesWithMe(id, e.trip.doc == "1")
                // A trip the friend sent: offered like a group's, from them (the channel's id stands for the group).
                state.kind == GroupKind.FRIEND && e.trip.tripId != SHARE_TRIP && !e.mine -> send(Message.GroupTrip(
                    id, e.trip.tripId, e.trip.rev, e.riderId, state.peer?.nickname, e.trip.name, e.trip.doc, false
                ))
            }
            // Only from a friend who has said they share: a position the relay still holds after they stopped is not shown.
            // One that comes before their "I share" (the relay may hand the two over either way round) waits for it.
            is GroupEvent.Position -> if (state.kind == GroupKind.FRIEND) {
                val p = e.pos
                val info = Message.GroupPositionInfo(id, p.lat, p.lon, p.heading, p.speed, e.tsMs, p.stopped, p.stoppedSince, p.battery)
                if (id in store?.friendsSharingWithMe.orEmpty()) {
                    friendPositions[id] = info
                    send(Message.GroupPositions(Message.GroupPositions.FRIENDS, listOf(info)))
                } else earlyFriendPositions[id] = info
            }
            else -> Unit
        }
    }

    private fun addFriend(state: GroupState) {
        groups[state.groupIdHex] = state
        store?.save(state)
        trackGroup(state)
        touchFriend(state.groupIdHex, 20_000) // long enough for the two to say hello
        send(friendList())
    }

    private fun removeFriend(id: String) {
        val state = groups[id]?.takeIf { it.kind == GroupKind.FRIEND } ?: return
        friendClients[id]?.leave()
        stopFriend(id)
        groups.remove(id)
        store?.delete(id)
        trackGone(id)
        offers.entries.removeAll { it.value.friendId == id }
        forgetFriendSharing(id)
        track(SHARE_KEY, sharePayload())
        publish()
        send(friendList())
    }

    /** A new private channel for one friend to join (the code and the link). Kept open while the code is on show. */
    private fun makeFriendInvite(): Pair<String, String>? {
        val store = store ?: return null
        val links = store.pendingLinks
        val state = friendStates().firstOrNull { it.peer == null && it.isAdmin && it.groupIdHex !in links }
            ?: GroupState.create(RELAY_HOST, null, GroupKind.FRIEND).also { groups[it.groupIdHex] = it; store.save(it) }
        touchFriend(state.groupIdHex, 15 * 60_000L)
        return state.groupIdHex to state.invite(System.currentTimeMillis() / 1000 + DEFAULT_INVITE_DAYS * 86_400L).toUri()
    }

    /**
     * A link for one friend far away, a new channel each time (two friends must never share one). Opened now so the relay keeps
     * this rider's hello for the friend; the friend's hello is heard on the next look-in (every 10 minutes, or at once in Friends).
     */
    private fun makeFriendLink() {
        val store = store ?: return
        val until = System.currentTimeMillis() / 1000 + DEFAULT_INVITE_DAYS * 86_400L
        val state = GroupState.create(RELAY_HOST, null, GroupKind.FRIEND).also { groups[it.groupIdHex] = it; store.save(it) }
        store.pendingLinks = store.pendingLinks + (state.groupIdHex to until)
        touchFriend(state.groupIdHex, 20_000)
        send(Message.FriendLink(state.invite(until).toUri()))
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
        o.request?.let { return Message.GroupInviteOffer(id, it.nickname, o.groupName, it.photo, it.colour, friendRequest = true) }
        val peer = groups[o.friendId]?.peer
        return Message.GroupInviteOffer(id, peer?.nickname ?: "?", o.groupName, peer?.photoBase64, peer?.colour ?: 0)
    }

    /**
     * The rider asks someone in the active group to be a friend: a new private channel for the two (like a link sent to one
     * friend), its address sealed to that rider's key and sent through the group. Nothing is said when they decline.
     */
    private fun friendRequest(riderId: String) {
        val store = store ?: return
        val c = client
        val ride = activeId?.let { groups[it] }?.takeIf { it.kind == GroupKind.RIDE }
        if (ride == null || c == null) return send(Message.FriendRequestStatus(riderId, Message.NudgeState.FAILED))
        val until = System.currentTimeMillis() / 1000 + FRIEND_REQUEST_KEEP_S
        val state = GroupState.create(RELAY_HOST, null, GroupKind.FRIEND).also { groups[it.groupIdHex] = it; store.save(it) }
        if (c.sendFriendRequest(riderId, state.invite(until).toUri()) == null) {
            groups.remove(state.groupIdHex)
            store.delete(state.groupIdHex)
            return send(Message.FriendRequestStatus(riderId, Message.NudgeState.FAILED))
        }
        // Kept for as long as the request lives, like a link sent through a chat; opened now so the relay holds this rider's hello.
        store.pendingLinks = store.pendingLinks + (state.groupIdHex to until)
        touchFriend(state.groupIdHex, 20_000)
        send(Message.FriendRequestStatus(riderId, Message.NudgeState.SENT))
    }

    /** Someone in the active group asks to be the rider's friend: an offer to add or ignore, shown once and not again from a rider said no to. */
    private fun friendRequestIn(state: GroupState, e: GroupEvent.FriendRequestReceived) {
        val store = store ?: return
        val blockKey = "fr:${state.groupIdHex}:${e.riderId}"
        if (store.isOfferHandled(e.id) || e.id in offers || store.isOfferHandled(blockKey)) return
        val parsed = Invite.parse(e.link)
        if (parsed == null || parsed.kind != GroupKind.FRIEND || parsed.isExpired(System.currentTimeMillis() / 1000)) { store.markOfferHandled(e.id); return }
        if (parsed.groupId.joinToString("") { "%02x".format(it) } in groups) { store.markOfferHandled(e.id); return } // already their friend
        val from = client?.members()?.firstOrNull { it.id == e.riderId }
        val o = Offer("", e.link, state.name, FriendRequest(from?.nickname ?: "?", from?.colour ?: 0xFF00E5FF.toInt(), photo(from?.avatar), blockKey))
        offers[e.id] = o
        send(offerMessage(e.id, o))
    }

    // ---- friends who always see where the rider is, and friends the rider sees ----

    /**
     * Friends see the rider through the private channel between them, outside any group: less often than a group (a friend
     * wants to know where you are, not follow every bend), and only while Curveon has a position. The channel to each is kept
     * open while positions flow and closes a while after they stop. The relay keeps the latest one for a friend who looks later.
     */
    private val friendSharer = PositionSharer(movingIntervalMs = 60_000, stoppedIntervalMs = 300_000, dataSaverIntervalMs = 60_000)
    private val friendPositions = LinkedHashMap<String, Message.GroupPositionInfo>()
    /** A friend's position that came before they said they share, by friend. */
    private val earlyFriendPositions = HashMap<String, Message.GroupPositionInfo>()
    private var watchJob: ScheduledFuture<*>? = null

    /** The friends who see the rider now: the switch is on and they are chosen. */
    private fun seeingMe(): Set<String> = store?.takeIf { it.friendSharing }?.friendsSeeingMe.orEmpty().filter { groups[it]?.kind == GroupKind.FRIEND }.toSet()

    private fun setFriendSharing(on: Boolean, ids: Set<String>) {
        val store = store ?: return
        val before = seeingMe()
        // A friend met on both of the rider's devices has two channels: both are chosen together.
        val riders = ids.mapNotNull { groups[it]?.peer?.riderId }.toSet()
        store.friendsSeeingMe = ids + friendStates().filter { it.peer?.riderId in riders }.map { it.groupIdHex }
        store.friendSharing = on
        val after = seeingMe()
        (before - after).forEach { tellFriend(it, false) }
        (after - before).forEach { tellFriend(it, true) }
        friendSharer.reset()
        send(friendList())
    }

    /** Says in the channel whether the friend sees the rider from now on; the relay keeps it for when the friend looks. */
    private fun tellFriend(id: String, sharing: Boolean) = touchFriend(id, 8_000) { c ->
        c.sendTrip(Payload.Trip(SHARE_TRIP, System.currentTimeMillis(), "", if (sharing) "1" else "0"))
    }

    private fun friendSharesWithMe(id: String, on: Boolean) {
        val store = store ?: return
        val now = store.friendsSharingWithMe
        if (on == (id in now)) return
        store.friendsSharingWithMe = if (on) now + id else now - id
        val early = earlyFriendPositions.remove(id)
        if (!on && friendPositions.remove(id) != null) send(friendPositionsMessage())
        // Their position came first: shown now, if it is from this time round (not one left from before).
        if (on && early != null && System.currentTimeMillis() - early.timeMs < FRIEND_EARLY_MAX_MS) {
            friendPositions[id] = early
            send(Message.GroupPositions(Message.GroupPositions.FRIENDS, listOf(early)))
        }
        send(friendList())
        updateFriendSweep()
    }

    private fun friendPositionsMessage() = Message.GroupPositions(Message.GroupPositions.FRIENDS, friendPositions.values.toList())

    private fun forgetFriendSharing(id: String) {
        val store = store ?: return
        if (id in store.friendsSeeingMe) store.friendsSeeingMe = store.friendsSeeingMe - id
        if (id in store.friendsSharingWithMe) store.friendsSharingWithMe = store.friendsSharingWithMe - id
        earlyFriendPositions.remove(id)
        if (friendPositions.remove(id) != null) send(friendPositionsMessage())
    }

    private fun sharePayload(): String? = store?.let { s -> JSONObject().put("on", s.friendSharing).put("ids", org.json.JSONArray(s.friendsSeeingMe.sorted())).toString() }

    private fun applyRemoteShare(payload: String) {
        val o = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val ids = o.optJSONArray("ids")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty().toSet()
        setFriendSharing(o.optBoolean("on"), ids)
    }

    // ---- the rider's other devices: one set of friends, groups and profile ----

    /**
     * A rider with Curveon on a phone and a tablet is one rider. The devices share a private [GroupKind.SELF] channel, and over it
     * keep every friend and ride group (with this rider's identity in each, so a friend sees one rider), and the profile, in step:
     * see [SyncDoc]. Each change is stamped, sent, and merged by the other; a group left on one is left on both.
     */
    private var syncDoc = SyncDoc()
    private var syncSeen = SyncDoc() // what the other device is known to have
    private var syncRev = 0L
    private var lastSent: String? = null
    private var pushJob: ScheduledFuture<*>? = null

    private fun selfState() = groups.values.firstOrNull { it.kind == GroupKind.SELF }

    private fun selfStatus(): Message.SelfStatus = selfState().let { Message.SelfStatus(linked = it?.peer != null, waiting = it != null && it.peer == null) }

    private fun saveSync() { store?.syncDoc = syncDoc.toJson() }

    /** Something new here (a group joined, the profile changed): stamped now, and sent on to the other device. */
    private fun track(key: String, payload: String?) {
        syncDoc = syncDoc.with(SyncEntry(key, System.currentTimeMillis(), payload == null, payload))
        saveSync()
        scheduleSync()
    }

    private fun trackGroup(state: GroupState) = track(SyncDoc.groupKey(state.groupIdHex), SyncDoc.payload(state))

    private fun trackGone(groupIdHex: String) = track(SyncDoc.groupKey(groupIdHex), null)

    private fun profilePayload(): String? = store?.profile?.let { p ->
        JSONObject().put("nick", p.nickname).put("colour", p.colour).put("icon", p.icon ?: JSONObject.NULL)
            .put("photo", p.photoJpegBase64 ?: JSONObject.NULL).toString()
    }

    /** A group's keys or a friend's name changed on its own (a rekey, a hello): the other device should have it. */
    private fun stateSaved(s: GroupState) {
        store?.save(s)
        if (s.kind != GroupKind.SELF) scheduleSync(3_000)
    }

    /** Notes what changed here since the document was last made; what was here before syncing existed counts as oldest. */
    private fun refreshSync() {
        val now = System.currentTimeMillis()
        val changes = ArrayList<SyncEntry>()
        for (g in groups.values) {
            if (g.kind == GroupKind.SELF) continue
            val key = SyncDoc.groupKey(g.groupIdHex)
            val payload = SyncDoc.payload(g)
            val e = syncDoc.entries[key]
            if (e == null) changes += SyncEntry(key, 0L, false, payload)
            else if (!e.deleted && e.payload != payload) changes += SyncEntry(key, now, false, payload)
        }
        for ((key, payload) in listOf(SyncDoc.PROFILE to profilePayload(), SHARE_KEY to sharePayload())) {
            val p = payload ?: continue
            val e = syncDoc.entries[key]
            if (e == null) changes += SyncEntry(key, 0L, false, p)
            else if (!e.deleted && e.payload != p) changes += SyncEntry(key, now, false, p)
        }
        if (changes.isNotEmpty()) { syncDoc = syncDoc.with(changes); saveSync() }
    }

    private fun scheduleSync(delayMs: Long = 2_000) {
        if (selfState() == null) return
        pushJob?.cancel(false)
        pushJob = exec.schedule({ pushSync() }, delayMs, TimeUnit.MILLISECONDS)
    }

    /** Opens the channel to the other device for a moment and, once what it has had time to arrive, sends ours if it lacks something. */
    private fun pushSync() {
        val self = selfState() ?: return
        refreshSync()
        touchFriend(self.groupIdHex, 10_000) { c -> exec.schedule({ sendSync(c) }, 1_500, TimeUnit.MILLISECONDS) }
    }

    private fun sendSync(c: GroupClient) = exec.execute {
        val self = selfState() ?: return@execute
        if (friendClients[self.groupIdHex] !== c) return@execute
        refreshSync()
        val json = syncDoc.toJson()
        if (json == lastSent && syncSeen.entries.isNotEmpty() && !syncDoc.hasNewerThan(syncSeen)) return@execute
        if (syncSeen.entries.isNotEmpty() && !syncDoc.hasNewerThan(syncSeen)) return@execute // the other device has all of it
        val rev = maxOf(System.currentTimeMillis(), syncRev + 1)
        syncRev = rev
        if (c.sendTrip(Payload.Trip(SYNC_TRIP, rev, "", json))) lastSent = json
    }

    /** What the other device has: take what is newer there, and send back what is newer here. */
    private fun onSyncReceived(doc: String) {
        val remote = SyncDoc.fromJson(doc) ?: return
        syncSeen = syncSeen.merge(remote)
        refreshSync()
        val changes = syncDoc.newerIn(remote)
        if (changes.isNotEmpty()) applySync(changes)
        if (syncDoc.hasNewerThan(syncSeen)) scheduleSync(500)
    }

    private fun applySync(changes: List<SyncEntry>) {
        for (e in changes) {
            if (e.key == SyncDoc.PROFILE) {
                if (!e.deleted) e.payload?.let(::applyRemoteProfile)
                syncDoc = syncDoc.with(e)
            } else if (e.key == SHARE_KEY) {
                if (!e.deleted) e.payload?.let(::applyRemoteShare)
                syncDoc = syncDoc.with(e)
            } else if (e.key.startsWith("g:")) {
                val id = e.key.removePrefix("g:")
                if (e.deleted) {
                    dropGroup(id)
                    syncDoc = syncDoc.with(e)
                } else {
                    val existing = groups[id]
                    val state = e.payload?.let { SyncDoc.restore(it, existing?.deviceId ?: newDeviceId()) } ?: continue
                    if (state.kind == GroupKind.SELF) continue
                    adopt(state, existing)
                    // Kept as this device would write it, so the two agree on the text and do not keep re-sending.
                    syncDoc = syncDoc.with(SyncEntry(e.key, e.ts, false, SyncDoc.payload(state)))
                }
            }
        }
        saveSync()
        send(groupList())
        send(friendList())
        membersMessage()?.let(::send)
        publish()
    }

    private fun adopt(state: GroupState, existing: GroupState?) {
        if (existing != null && SyncDoc.payload(existing) == SyncDoc.payload(state)) return
        val id = state.groupIdHex
        if (existing != null) {
            if (id == activeId) detach()
            stopFriend(id)
        }
        groups[id] = state
        store?.save(state)
        if (state.kind == GroupKind.FRIEND) touchFriend(id, 8_000) // hear the friend say hello, for the name and picture
        if (id == activeId) updateConnection()
    }

    private fun newDeviceId(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(8).also { java.security.SecureRandom().nextBytes(it) })

    private fun dropGroup(id: String) {
        val st = groups[id] ?: return
        if (st.kind == GroupKind.FRIEND) {
            stopFriend(id)
            offers.entries.removeAll { it.value.friendId == id }
            forgetFriendSharing(id)
        }
        if (id == activeId) { detach(); activeId = null; store?.activeId = null }
        groups.remove(id)
        store?.delete(id)
    }

    private fun applyRemoteProfile(payload: String) {
        val o = runCatching { JSONObject(payload) }.getOrNull() ?: return
        store?.profile = Message.GroupProfile(
            o.optString("nick"), o.optInt("colour"), if (o.isNull("icon")) null else o.optString("icon"), if (o.isNull("photo")) null else o.optString("photo")
        )
        profileChanged()
    }

    private fun profileChanged() {
        send(profileMessage())
        friendClients.forEach { (id, c) -> groups[id]?.let { c.setProfile(helloFor(it)) } }
        client?.setProfile(helloFor(activeId?.let { groups[it] } ?: return))
        membersMessage()?.let(::send) // the rider's own row shows the new name and picture at once
    }

    /** A link the rider's other device scans; the channel between the two is made the first time. */
    private fun makeSelfLink() {
        val store = store ?: return
        val state = selfState() ?: GroupState.create(RELAY_HOST, null, GroupKind.SELF).also { groups[it.groupIdHex] = it; store.save(it) }
        touchFriend(state.groupIdHex, 15 * 60_000L) // open while the code is on show, to hear the other device say hello
        send(Message.SelfLink(state.invite(System.currentTimeMillis() / 1000 + DEVICE_LINK_MINUTES * 60L).toUri()))
        send(selfStatus())
    }

    private fun joinSelf(invite: Invite) {
        selfState()?.let { old -> stopFriend(old.groupIdHex); groups.remove(old.groupIdHex); store?.delete(old.groupIdHex) }
        val state = onCurveonRelay(GroupState.join(invite))
        groups[state.groupIdHex] = state
        store?.save(state)
        syncSeen = SyncDoc()
        touchFriend(state.groupIdHex, 20_000)
        send(selfStatus())
        scheduleSync(1_000)
    }

    private fun unlinkSelf() {
        selfState()?.let { st -> stopFriend(st.groupIdHex); groups.remove(st.groupIdHex); store?.delete(st.groupIdHex) }
        syncSeen = SyncDoc()
        lastSent = null
        send(selfStatus())
    }

    // ---- position ----

    /** Where the rider was last, for the "paused" sent when sharing stops. */
    private var lastFix: Fix? = null

    private fun position(m: Message.MyPosition) {
        val fix = Fix(m.lat, m.lon, m.speedMs, m.heading, m.accuracy)
        lastFix = fix
        val c = client
        if (c != null && store?.sharing == true) sharer.offer(fix, battery())?.let(c::sendPosition)
        val friends = seeingMe()
        if (friends.isNotEmpty()) friendSharer.offer(fix, battery())?.let { pos ->
            friends.forEach { id -> touchFriend(id, FRIEND_SHARE_KEEP_MS); friendClients[id]?.sendPosition(pos) }
        }
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
                val info = Message.GroupPositionInfo(e.riderId, p.lat, p.lon, p.heading, p.speed, e.tsMs, p.stopped, p.stoppedSince, p.battery, p.paused)
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
            is GroupEvent.FriendRequestReceived -> friendRequestIn(state, e)
            is GroupEvent.NudgeReceived -> client?.members()?.firstOrNull { it.id == e.riderId }.let { m ->
                nudgeIn(state.groupIdHex, e.riderId, e.nudge, e.tsMs, m?.nickname, m?.colour ?: 0xFF00E5FF.toInt(), photo(m?.avatar))
            }
            is GroupEvent.NudgeSeen -> nudgeSeen(state.groupIdHex, e, friend = false)
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
        PhoneHub.broadcastGroup(m)
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
        connectionMessage()?.let { PhoneHub.broadcastGroup(it) }
    }

    private fun error(code: Message.GroupErrorCode) {
        _errors.tryEmit(code)
        send(Message.GroupError(code))
    }

    // ---- what the tablet is told ----

    /** The rider's own name, colour and picture (what is set also when there is no group). */
    private fun profileMessage() = store?.profile ?: Message.GroupProfile("Rider", 0xFF00E5FF.toInt(), null, null)

    private fun settingsMessage() = Message.GroupSettings(store?.dataSaver ?: false)

    private fun snapshot(): List<Message> = buildList {
        add(settingsMessage())
        add(profileMessage())
        add(friendList())
        if (friendPositions.isNotEmpty()) add(friendPositionsMessage())
        add(selfStatus())
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
            store?.sharing == false -> Message.GroupStatus.PAUSED
            !connected -> Message.GroupStatus.OFFLINE
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
