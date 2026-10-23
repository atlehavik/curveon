package com.curveon.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Riders cannot send in each other's name (code review 2026-10-23), and older apps still get through. */
class RiderAuthTest {
  private var now = 1_800_000_000_000L
  private val sent = ArrayList<String>()
  private lateinit var listener: TransportListener

  private val factory = TransportFactory { _, l ->
    listener = l
    object : Transport {
      override fun send(text: String): Boolean { sent += text; return true }
      override fun close(code: Int) = Unit
    }
  }

  private val admin = GroupState.create("relay.example.org", "Tur")
  private val events = ArrayList<GroupEvent>()

  private fun client(state: GroupState = admin, onState: (GroupState) -> Unit = {}) =
    GroupClient(state, factory, { events += it }, onState, clock = { now }).also {
      it.setProfile(Payload.Hello("Me", 1, state.agreement.publicKey))
      it.start()
      listener.onOpen()
    }

  /** Someone in the group (who holds the group key) sends [p] as [riderId], signed with [signing] or, like an older app, not at all. */
  private var seq = 1L
  private fun receive(riderId: ByteArray, p: Payload, signing: KeyPair?, ts: Long = now, state: GroupState = admin) {
    val body = PayloadCodec.encode(seq++, ts, p, "dev-" + B64.encode(riderId).take(4))
    val plain = signing?.let { FrameSig.sign(body, it, state.groupId, riderId, state.epoch, p.type) } ?: body
    listener.onMessage(Wire.encode(FrameCrypto.seal(state.keys, p.type, riderId, plain)))
  }

  private class Someone(groupId: ByteArray, legacy: Boolean) {
    val agreement = Keys.newAgreementKeyPair()
    val signing = Keys.riderSigningKeyPair(agreement.privateKey, groupId)
    val id: ByteArray = if (legacy) GroupKeys.newRiderId() else Keys.riderIdOf(signing.publicKey)
    fun hello(name: String) = Payload.Hello(name, 2, agreement.publicKey)
  }

  private fun members(c: GroupClient) = c.members().associate { it.nickname to B64.encode(it.agreementPublicKey) }

  /** The review's attack: Mallory says hello as Bob with her own key; when the admin removes her, the new key must reach Bob and not her. */
  private fun forgedHelloDoesNotStealTheRekey(bobIsLegacy: Boolean, bobSigns: Boolean) {
    val c = client()
    val bob = Someone(admin.groupId, bobIsLegacy)
    val mal = Someone(admin.groupId, legacy = false)
    receive(bob.id, bob.hello("Bob"), bob.signing.takeIf { bobSigns })
    receive(mal.id, mal.hello("Mal"), mal.signing)
    receive(bob.id, Payload.Hello("Bob", 2, mal.agreement.publicKey), null) // forged, unsigned
    receive(bob.id, Payload.Hello("Bob", 2, mal.agreement.publicKey), mal.signing) // forged, signed with her own key
    assertEquals(B64.encode(bob.agreement.publicKey), members(c)["Bob"])

    assertTrue(c.removeRider(B64.encode(mal.id)))
    val rekey = Wire.decodeRekey(sent.last { Wire.typeOf(it) == "rekey" }, admin.groupId)!!
    assertNotNull("Bob gets the new key", rekey.open(admin.adminPublicKey, bob.id, bob.agreement.privateKey))
    assertNull("Mallory does not", rekey.open(admin.adminPublicKey, mal.id, mal.agreement.privateKey))
    assertNull(rekey.open(admin.adminPublicKey, bob.id, mal.agreement.privateKey))
  }

  @Test fun aForgedHelloCannotStealTheKeyOfARiderWhoJoinedSinceSigning() = forgedHelloDoesNotStealTheRekey(bobIsLegacy = false, bobSigns = true)

  @Test fun aForgedHelloCannotStealTheKeyOfARiderOnAnOlderApp() = forgedHelloDoesNotStealTheRekey(bobIsLegacy = true, bobSigns = false)

  @Test fun aForgedHelloCannotStealTheKeyOfAnOlderRiderOnANewApp() = forgedHelloDoesNotStealTheRekey(bobIsLegacy = true, bobSigns = true)

  @Test fun whatIsLearntOfTheOthersSurvivesARestartButIsNotSyncedToOtherDevices() {
    var saved: String? = null
    val c = client { saved = it.toJson() }
    val bob = Someone(admin.groupId, legacy = true)
    receive(bob.id, bob.hello("Bob"), null)
    c.shutdown()

    // after a restart the relay hands over Mallory's hello in Bob's name first
    val again = GroupState.fromJson(saved!!)!!
    val c2 = client(again)
    receive(bob.id, Payload.Hello("Bob", 2, Keys.newAgreementKeyPair().publicKey), null, state = again)
    assertTrue(c2.members().isEmpty())
    receive(bob.id, bob.hello("Bob"), null, state = again)
    assertEquals(B64.encode(bob.agreement.publicKey), members(c2)["Bob"])

    assertFalse(SyncDoc.payload(again).contains("trust"))
    val synced = SyncDoc.restore(SyncDoc.payload(again), "other", existing = again)!!
    assertNotNull(synced.trustOf(B64.encode(bob.id)))
  }

  @Test fun aRiderWhoSignsCannotBeImpersonatedAtAll() {
    client()
    val bob = Someone(admin.groupId, legacy = false)
    val mal = Someone(admin.groupId, legacy = false)
    receive(bob.id, bob.hello("Bob"), bob.signing)
    receive(bob.id, Payload.Sos(1.0, 1.0), null)
    receive(bob.id, Payload.Sos(2.0, 2.0), mal.signing)
    receive(bob.id, Payload.Pos(3.0, 3.0, 0f, 0f, 0f), null)
    assertTrue(events.none { it is GroupEvent.SosReceived || it is GroupEvent.Position })
    receive(bob.id, Payload.Pos(4.0, 4.0, 0f, 0f, 0f), bob.signing)
    assertEquals(4.0, events.filterIsInstance<GroupEvent.Position>().single().pos.lat, 0.0)
  }

  @Test fun anOlderAppStillGetsThrough() {
    client()
    val ola = Someone(admin.groupId, legacy = true)
    receive(ola.id, ola.hello("Ola"), null)
    receive(ola.id, Payload.Pos(1.0, 1.0, 0f, 0f, 0f), null)
    receive(ola.id, Payload.Sos(1.0, 1.0), null)
    assertEquals(1, events.filterIsInstance<GroupEvent.MemberUpdated>().size)
    assertEquals(1, events.filterIsInstance<GroupEvent.Position>().size)
    assertEquals(1, events.filterIsInstance<GroupEvent.SosReceived>().size)
  }

  @Test fun anOlderAppReadsASignedPayload() {
    val c = client()
    c.sendPosition(Payload.Pos(59.9, 10.7, 12f, 90f, 5f))
    val frame = Wire.decode(sent.last { Wire.typeOf(it) == "pos" })!!
    val plain = FrameCrypto.open(admin.keys, frame)!!
    val pos = PayloadCodec.decode(FrameType.POS, plain)!!.payload as Payload.Pos
    assertEquals(59.9, pos.lat, 0.0)
    val sig = FrameSig.read(plain, admin.groupId, admin.riderId, admin.epoch, FrameType.POS)!!
    assertTrue(sig.valid)
    assertTrue(admin.bound)
    assertFalse("moved to another type", FrameSig.read(plain, admin.groupId, admin.riderId, admin.epoch, FrameType.SOS)!!.valid)
  }

  @Test fun anOldOrCancelledSosIsNotShownAgainEvenAfterARestart() {
    var saved: String? = null
    client { saved = it.toJson() }
    val bob = Someone(admin.groupId, legacy = true)
    receive(bob.id, Payload.Sos(1.0, 1.0), null, ts = now - 3 * 24 * 3600_000L)
    assertTrue("three days old", events.none { it is GroupEvent.SosReceived })

    val sosTs = now - 60_000
    receive(bob.id, Payload.Sos(1.0, 1.0), null, ts = sosTs)
    receive(bob.id, Payload.SosCancel, null)
    assertEquals(1, events.filterIsInstance<GroupEvent.SosReceived>().size)

    events.clear()
    val again = GroupState.fromJson(saved!!)!!
    client(again)
    receive(bob.id, Payload.Sos(1.0, 1.0), null, ts = sosTs, state = again)
    assertTrue("the cancelled one, sent again", events.none { it is GroupEvent.SosReceived })
    now += 5_000
    receive(bob.id, Payload.Sos(1.0, 1.0), null, ts = now, state = again)
    assertEquals("a new one", 1, events.filterIsInstance<GroupEvent.SosReceived>().size)
  }

  @Test fun anOldPositionIsNotShown() {
    client()
    val bob = Someone(admin.groupId, legacy = true)
    receive(bob.id, Payload.Pos(1.0, 1.0, 0f, 0f, 0f), null, ts = now - 10 * 60_000)
    assertTrue(events.none { it is GroupEvent.Position })
  }

  @Test fun aClaimFromTheFutureDoesNotSilenceTheRiderForEver() {
    val c = client()
    receive(admin.riderId, Payload.Claim("other-device"), admin.signing, ts = now + 365L * 24 * 3600_000)
    assertFalse(c.sendingHere)
    now += 4 * 60_000
    assertTrue(c.sendingHere)
  }

  @Test fun aClaimInTheRidersNameFromSomeoneElseIsIgnored() {
    val c = client()
    receive(admin.riderId, Payload.Claim("other-device"), null) // the admin joined since signing, so all their devices sign
    receive(admin.riderId, Payload.Claim("other-device"), Someone(admin.groupId, legacy = false).signing)
    assertTrue(c.sendingHere)
  }

  @Test fun aHelloInTheRidersOwnNameIsReplacedWithTheirOwn() {
    client()
    sent.clear()
    receive(admin.riderId, Payload.Hello("Fake", 3, Keys.newAgreementKeyPair().publicKey), null)
    assertEquals(1, sent.count { Wire.typeOf(it) == "hello" })
  }
}
