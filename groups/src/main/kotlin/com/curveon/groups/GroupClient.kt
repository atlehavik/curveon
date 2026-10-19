package com.curveon.groups

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

sealed interface GroupEvent {
  data object Connected : GroupEvent
  data class Disconnected(val code: Int) : GroupEvent
  data class MemberUpdated(val member: Member) : GroupEvent
  data class MemberLeft(val riderId: String) : GroupEvent
  data class Position(val riderId: String, val pos: Payload.Pos, val tsMs: Long) : GroupEvent
  data class SosReceived(val riderId: String, val sos: Payload.Sos, val tsMs: Long) : GroupEvent
  data class SosAnswered(val riderId: String, val sosRiderId: String, val coming: Boolean) : GroupEvent
  data class SosCancelled(val riderId: String) : GroupEvent
  data class RouteReceived(val riderId: String, val route: Payload.Route) : GroupEvent

  /** The group key changed (someone was removed, or the invite leaked). The device reconnects by itself; store the state. */
  data object KeyChanged : GroupEvent

  /** An admin made a new key and this rider is not in it. The client has stopped. */
  data object Removed : GroupEvent

  /**
   * Which of the rider's own devices sends their position changed: [thisDevice] true when it is this one. The other
   * device was told by the group (see [Payload.Claim]) or the rider took over here.
   */
  data class DeviceActiveChanged(val thisDevice: Boolean) : GroupEvent
}

private const val CLAIM_HEARTBEAT_MS = 60_000L
private const val CLAIM_STALE_MS = 180_000L

/**
 * One group's connection to the relay: encrypts what the rider sends, opens and checks what arrives,
 * reconnects with back-off, follows rekeys, and holds back what could not be sent (only the latest
 * position, and an SOS until it has gone out). Platform-free: the socket comes from [factory].
 */
class GroupClient(
  val state: GroupState,
  private val factory: TransportFactory,
  private val onEvent: (GroupEvent) -> Unit,
  private val onStateChanged: (GroupState) -> Unit = {},
  /** "wss" in production; tests talk to a relay on localhost. */
  private val scheme: String = "wss",
  private val clock: () -> Long = System::currentTimeMillis,
  private val retryDelaysMs: List<Long> = listOf(1_000, 2_000, 5_000, 10_000, 30_000),
) {
  private val lock = Any()
  private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "curveon-group").apply { isDaemon = true } }
  private val guard = ReplayGuard()
  private val members = LinkedHashMap<String, Member>()

  private var transport: Transport? = null
  private var generation = 0
  private var attempt = 0
  private var stopped = true
  private var removed = false
  private var retry: ScheduledFuture<*>? = null
  private var lastSeq = 0L

  var connected = false
    private set
  private var profile: Payload.Hello? = null
  private var pendingPos: Payload.Pos? = null

  /** The newest [Payload.Claim] seen, ours or another device's of the same rider: its time and device. */
  private var claim: Pair<Long, String>? = null
  private var lastClaimSent = 0L
  private var announcedActive = true
  private var pendingSos: Payload.Sos? = null

  fun start() {
    synchronized(lock) {
      if (removed) return
      stopped = false
      connect()
    }
  }

  fun stop() = synchronized(lock) {
    stopped = true
    retry?.cancel(false)
    generation++
    connected = false
    transport?.close()
    transport = null
  }

  /** Stops for good; the client cannot be started again. */
  fun shutdown() {
    stop()
    scheduler.shutdownNow()
  }

  /** The network came back (or changed): do not wait for the back-off. */
  fun reconnectNow() {
    synchronized(lock) {
      if (stopped || removed || connected) return
      retry?.cancel(false)
      attempt = 0
      connect()
    }
  }

  fun members(): List<Member> = synchronized(lock) { members.values.toList() }

  // ---- sending ----

  /** What others see of this rider. Sent now and again after every reconnect. */
  fun setProfile(hello: Payload.Hello) = synchronized(lock) {
    profile = hello
    if (connected) send(hello)
  }

  /**
   * False while another of the rider's devices is the one sending their position: positions given here are then
   * dropped. Nobody having claimed it, or the claimant having been silent for [CLAIM_STALE_MS], leaves it to this device.
   */
  val sendingHere: Boolean get() = synchronized(lock) { activeHere() }

  /** The rider chose this device: it sends from now on, and the rider's other devices are told to stop. */
  fun useThisDevice() = synchronized(lock) { claimNow() }

  /**
   * Sends now, or keeps it as the one to send when the connection is back (older ones are not worth sending).
   * Does nothing while another of the rider's devices is the one sending; otherwise this device claims the role
   * (and says so again every minute, so the other device can tell the claimant has gone quiet).
   */
  fun sendPosition(pos: Payload.Pos) = synchronized(lock) {
    if (!activeHere()) { pendingPos = null; return }
    val c = claim
    if (c == null || c.second != state.deviceId || clock() - lastClaimSent > CLAIM_HEARTBEAT_MS) claimNow()
    if (!(connected && send(pos))) pendingPos = pos
  }

  fun sendRoute(route: Payload.Route): Boolean = synchronized(lock) { connected && send(route) }

  /** Held until it has gone out, so an SOS made without coverage is sent when coverage returns. */
  fun sendSos(sos: Payload.Sos): Boolean = synchronized(lock) {
    pendingSos = sos
    (connected && send(sos)).also { if (it) pendingSos = null }
  }

  fun answerSos(sosRiderId: String, coming: Boolean) = synchronized(lock) { if (connected) send(Payload.SosAck(sosRiderId, coming)) }

  fun cancelSos() = synchronized(lock) {
    pendingSos = null
    if (connected) send(Payload.SosCancel)
  }

  /** Tells the others and stops. The group stays on the device until the app forgets it. */
  fun leave() = synchronized(lock) {
    if (connected) send(Payload.Leave)
    stop()
  }

  /**
   * Admin only: a new group key for everyone but [riderId], which that rider never gets. Riders whose
   * `hello` has not arrived cannot be given the key, so they are left out as well.
   */
  fun removeRider(riderId: String): Boolean = synchronized(lock) {
    val signing = state.adminSigning ?: return false
    if (!connected || riderId == B64.encode(state.riderId)) return false
    val newKey = GroupKeys.newGroupKey()
    val newEpoch = state.epoch + 1
    val recipients = members.values.filter { it.id != riderId }.map { it.riderId to it.agreementPublicKey }
    val rekey = Rekey.create(state.groupId, newEpoch, newKey, signing.privateKey, recipients)
    if (transport?.send(Wire.encode(rekey, state.riderId)) != true) return false
    state.switchKey(newKey, newEpoch)
    members.remove(riderId)
    onStateChanged(state)
    onEvent(GroupEvent.KeyChanged)
    true
  }

  private fun activeHere(): Boolean {
    val c = claim ?: return true
    return c.second == state.deviceId || clock() - c.first > CLAIM_STALE_MS
  }

  private fun claimNow() {
    val now = clock()
    claim = now to state.deviceId
    lastClaimSent = now
    if (connected) send(Payload.Claim(state.deviceId))
    announceActive()
  }

  private fun announceActive() {
    val now = activeHere()
    if (now != announcedActive) {
      announcedActive = now
      onEvent(GroupEvent.DeviceActiveChanged(now))
    }
  }

  /** Another device of this rider claimed the role; the newest claim wins, and a tie goes to the larger device id. */
  private fun otherClaimed(device: String, tsMs: Long) {
    if (device == state.deviceId) return
    val cur = claim
    if (cur == null || tsMs > cur.first || (tsMs == cur.first && device > cur.second)) claim = tsMs to device
    announceActive()
  }

  // ---- connection ----

  private fun connect() {
    if (stopped || transport != null) return
    val url = "$scheme://${state.relayHost}/g/${state.groupIdHex}?p=${state.keys.accessProof()}&a=${B64.encode(state.adminPublicKey)}"
    val gen = ++generation
    transport = factory.open(url, object : TransportListener {
      override fun onOpen() {
        synchronized(lock) {
          if (gen != generation) return
          connected = true
          attempt = 0
          onEvent(GroupEvent.Connected)
          profile?.let { send(it) }
          if (claim?.second == state.deviceId) send(Payload.Claim(state.deviceId)) // a claim made while offline
          pendingSos?.let { if (send(it)) pendingSos = null }
          pendingPos?.let { if (send(it)) pendingPos = null }
        }
      }

      override fun onMessage(text: String) {
        synchronized(lock) { if (gen == generation) incoming(text) }
      }

      override fun onClosed(code: Int) {
        synchronized(lock) {
          if (gen != generation) return
          connected = false
          transport = null
          onEvent(GroupEvent.Disconnected(code))
          if (stopped || removed) return
          val delay = if (code == RelayClose.REKEYED || code == RelayClose.CATCHUP_DONE) 300L
          else retryDelaysMs[minOf(attempt++, retryDelaysMs.size - 1)]
          retry = scheduler.schedule({ synchronized(lock) { connect() } }, delay, TimeUnit.MILLISECONDS)
        }
      }
    })
  }

  private fun send(p: Payload): Boolean {
    val seq = maxOf(lastSeq + 1, clock())
    lastSeq = seq
    val frame = FrameCrypto.seal(state.keys, p.type, state.riderId, PayloadCodec.encode(seq, clock(), p, state.deviceId))
    return transport?.send(Wire.encode(frame)) == true
  }

  // ---- receiving ----

  private fun incoming(text: String) {
    if (Wire.typeOf(text) == "rekey") return rekeyed(text)
    val frame = Wire.decode(text) ?: return
    if (frame.riderId.contentEquals(state.riderId)) {
      // One's own frames come from the rider's other devices. Only who sends the position matters.
      if (frame.type == FrameType.CLAIM) {
        val signed = FrameCrypto.open(state.keys, frame)?.let { PayloadCodec.decode(FrameType.CLAIM, it) } ?: return
        (signed.payload as? Payload.Claim)?.let { otherClaimed(it.deviceId, signed.tsMs) }
      }
      return
    }
    val plain = FrameCrypto.open(state.keys, frame) ?: return
    val signed = PayloadCodec.decode(frame.type, plain) ?: return
    if (!guard.accept(frame.riderId, frame.epoch, signed.seq, signed.device)) return
    val from = B64.encode(frame.riderId)
    when (val p = signed.payload) {
      is Payload.Hello -> {
        val m = Member(frame.riderId, p.nickname, p.colour, p.agreementPublicKey, p.avatar)
        members[from] = m
        onEvent(GroupEvent.MemberUpdated(m))
      }
      is Payload.Pos -> onEvent(GroupEvent.Position(from, p, signed.tsMs))
      is Payload.Route -> onEvent(GroupEvent.RouteReceived(from, p))
      is Payload.Sos -> {
        send(Payload.SosAck(from, coming = false)) // every device says it got it, so the sender sees the delivery
        onEvent(GroupEvent.SosReceived(from, p, signed.tsMs))
      }
      is Payload.SosAck -> onEvent(GroupEvent.SosAnswered(from, p.sosRiderId, p.coming))
      Payload.SosCancel -> onEvent(GroupEvent.SosCancelled(from))
      Payload.Leave -> {
        members.remove(from)
        onEvent(GroupEvent.MemberLeft(from))
      }
      is Payload.Claim -> Unit // only meaningful between one rider's own devices
    }
  }

  private fun rekeyed(text: String) {
    val rekey = Wire.decodeRekey(text, state.groupId) ?: return
    if (rekey.newEpoch != state.epoch + 1) return // one we already have, or one we missed in between
    val key = rekey.open(state.adminPublicKey, state.riderId, state.agreement.privateKey)
    if (key != null) {
      state.switchKey(key, rekey.newEpoch)
      members.clear() // the relay dropped the old profiles; everyone sends hello again
      onStateChanged(state)
      onEvent(GroupEvent.KeyChanged)
    } else if (rekey.verify(state.adminPublicKey)) {
      removed = true
      stop()
      onEvent(GroupEvent.Removed)
    }
  }
}
