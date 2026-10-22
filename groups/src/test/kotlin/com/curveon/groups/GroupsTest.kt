package com.curveon.groups

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupsTest {
  private val admin = Keys.newSigningKeyPair()

  private fun invite(name: String? = "Lofoten trip", expires: Long? = 1_800_000_000L) = Invite(
    GroupKeys.newGroupId(), GroupKeys.newGroupKey(), admin.publicKey, "relay.example.org", name, expires,
  )

  @Test fun inviteRoundTripsAsHttpsAndAsScheme() {
    val i = invite(name = "Sommertur æøå & co")
    for (scheme in listOf("https", "curveon")) {
      val back = Invite.parse(i.toUri(scheme))!!
      assertArrayEquals(i.groupId, back.groupId)
      assertArrayEquals(i.groupKey, back.groupKey)
      assertArrayEquals(i.adminPublicKey, back.adminPublicKey)
      assertEquals("relay.example.org", back.relayHost)
      assertEquals("Sommertur æøå & co", back.name)
      assertEquals(1_800_000_000L, back.expiresAt)
    }
  }

  @Test fun inviteKeepsSecretsInTheFragment() {
    val uri = invite().toUri()
    val beforeFragment = uri.substringBefore('#')
    assertEquals("https://relay.example.org/join", beforeFragment)
  }

  @Test fun inviteWithoutOptionalFields() {
    val back = Invite.parse(invite(name = null, expires = null).toUri())!!
    assertNull(back.name)
    assertNull(back.expiresAt)
    assertFalse(back.isExpired(Long.MAX_VALUE))
  }

  @Test fun inviteRejectsJunkAndOtherVersions() {
    assertNull(Invite.parse(""))
    assertNull(Invite.parse("https://x/join#g=abc"))
    assertNull(Invite.parse(invite().toUri().replace("v=1", "v=2")))
    assertNull(Invite.parse(invite().toUri().replace("&k=", "&k=!!")))
  }

  @Test fun inviteExpiry() {
    val i = invite(expires = 100)
    assertFalse(i.isExpired(100))
    assertTrue(i.isExpired(101))
  }

  @Test fun frameRoundTrip() {
    val keys = GroupKeys(GroupKeys.newGroupId(), GroupKeys.newGroupKey())
    val rider = GroupKeys.newRiderId()
    val frame = FrameCrypto.seal(keys, FrameType.POS, rider, "hello".toByteArray())
    assertEquals("hello", String(FrameCrypto.open(keys, frame)!!))
  }

  @Test fun frameNoncesAreFresh() {
    val keys = GroupKeys(GroupKeys.newGroupId(), GroupKeys.newGroupKey())
    val rider = GroupKeys.newRiderId()
    val a = FrameCrypto.seal(keys, FrameType.POS, rider, "x".toByteArray())
    val b = FrameCrypto.seal(keys, FrameType.POS, rider, "x".toByteArray())
    assertFalse(a.nonce.contentEquals(b.nonce))
  }

  @Test fun frameFromAnotherKeyIsRejected() {
    val id = GroupKeys.newGroupId()
    val rider = GroupKeys.newRiderId()
    val frame = FrameCrypto.seal(GroupKeys(id, GroupKeys.newGroupKey()), FrameType.POS, rider, "x".toByteArray())
    assertNull(FrameCrypto.open(GroupKeys(id, GroupKeys.newGroupKey()), frame))
  }

  @Test fun alteredOrMovedFramesAreRejected() {
    val keys = GroupKeys(GroupKeys.newGroupId(), GroupKeys.newGroupKey())
    val rider = GroupKeys.newRiderId()
    val f = FrameCrypto.seal(keys, FrameType.POS, rider, "x".toByteArray())
    val flipped = f.ciphertext.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }
    assertNull(FrameCrypto.open(keys, Frame(f.type, f.riderId, f.epoch, f.nonce, flipped)))
    // Same bytes claiming to be another type, another rider, another epoch, or in another group.
    assertNull(FrameCrypto.open(keys, Frame(FrameType.SOS, f.riderId, f.epoch, f.nonce, f.ciphertext)))
    assertNull(FrameCrypto.open(keys, Frame(f.type, GroupKeys.newRiderId(), f.epoch, f.nonce, f.ciphertext)))
    assertNull(FrameCrypto.open(keys, Frame(f.type, f.riderId, f.epoch + 1, f.nonce, f.ciphertext)))
    assertNull(FrameCrypto.open(GroupKeys(GroupKeys.newGroupId(), keys.groupKey), f))
  }

  @Test fun slotsAreTheSameForTheGroupAndTellTheRelayNothing() {
    val id = GroupKeys.newGroupId()
    val key = GroupKeys.newGroupKey()
    val s = GroupKeys(id, key).slot("trip/t1/dev")
    assertEquals(s, GroupKeys(id, key).slot("trip/t1/dev"))
    assertTrue("short base64url, as the relay wants", Regex("[A-Za-z0-9_-]{22}").matches(s))
    assertFalse("the name does not show", s.contains("trip"))
    assertNotEquals(s, GroupKeys(id, key).slot("trip/t2/dev"))
    assertNotEquals(s, GroupKeys(id, GroupKeys.newGroupKey()).slot("trip/t1/dev"))
  }

  @Test fun theAskToKeepGoesOnTheEnvelopeOnly() {
    val keys = GroupKeys(GroupKeys.newGroupId(), GroupKeys.newGroupKey())
    val frame = FrameCrypto.seal(keys, FrameType.TRIP, GroupKeys.newRiderId(), "x".toByteArray())
    val o = org.json.JSONObject(Wire.encode(frame, Keep("slot1", 60, memory = true)))
    assertEquals("slot1", o.getString("k"))
    assertEquals(60, o.getInt("x"))
    assertTrue(o.getBoolean("m"))
    val plain = org.json.JSONObject(Wire.encode(frame))
    assertFalse(plain.has("k") || plain.has("x") || plain.has("m"))
    // A frame that still carries it (a relay that forwards it) reads the same.
    assertArrayEquals(frame.ciphertext, Wire.decode(o.toString())!!.ciphertext)
  }

  @Test fun whatANewerAppOrTheRelaySaysIsIgnored() {
    // The relay's own frame, sent first on every connection.
    assertNull(Wire.decode("""{"v":1,"t":"relay","f":["any-type","keep"],"lim":{"frame":524288}}"""))
    // A type from a newer app.
    assertNull(Wire.decode("""{"v":1,"t":"newthing","r":"AAAAAAAAAAAAAAAAAAAAAA","e":0,"n":"AAAAAAAAAAAAAAAA","c":"AAAA"}"""))
    // A newer envelope version.
    assertNull(Wire.decode("""{"v":2,"t":"pos","r":"AAAAAAAAAAAAAAAAAAAAAA","e":0,"n":"AAAAAAAAAAAAAAAA","c":"AAAA"}"""))
  }

  @Test fun accessProofDependsOnGroupAndEpoch() {
    val id = GroupKeys.newGroupId()
    val key = GroupKeys.newGroupKey()
    val p0 = GroupKeys(id, key, 0).accessProof()
    assertEquals(p0, GroupKeys(id, key, 0).accessProof())
    assertNotEquals(p0, GroupKeys(id, key, 1).accessProof())
    assertNotEquals(p0, GroupKeys(id, GroupKeys.newGroupKey(), 0).accessProof())
    assertNotEquals(p0, GroupKeys(GroupKeys.newGroupId(), key, 0).accessProof())
  }

  @Test fun replayGuardAcceptsOnlyRisingNumbers() {
    val g = ReplayGuard()
    val a = GroupKeys.newRiderId()
    val b = GroupKeys.newRiderId()
    assertTrue(g.accept(a, 0, 5))
    assertFalse(g.accept(a, 0, 5))
    assertFalse(g.accept(a, 0, 4))
    assertTrue(g.accept(a, 0, 6))
    assertTrue(g.accept(b, 0, 1))
    assertTrue(g.accept(a, 1, 1))
  }

  @Test fun twoDevicesOfOneRiderAreCountedApart() {
    val g = ReplayGuard()
    val rider = GroupKeys.newRiderId()
    assertTrue(g.accept(rider, 0, 1_000, "tablet"))
    // the phone's clock is a little behind: its numbers are lower, and still fine
    assertTrue(g.accept(rider, 0, 900, "phone"))
    assertFalse(g.accept(rider, 0, 900, "phone"))
    assertTrue(g.accept(rider, 0, 901, "phone"))
    assertFalse(g.accept(rider, 0, 1_000, "tablet"))
  }

  @Test fun rekeyReachesRemainingRidersOnly() {
    val groupId = GroupKeys.newGroupId()
    val ola = GroupKeys.newRiderId(); val olaKeys = Keys.newAgreementKeyPair()
    val kari = GroupKeys.newRiderId(); val kariKeys = Keys.newAgreementKeyPair()
    val removed = GroupKeys.newRiderId(); val removedKeys = Keys.newAgreementKeyPair()
    val newKey = GroupKeys.newGroupKey()

    val rekey = Rekey.create(groupId, 1, newKey, admin.privateKey, listOf(ola to olaKeys.publicKey, kari to kariKeys.publicKey))

    assertArrayEquals(newKey, rekey.open(admin.publicKey, ola, olaKeys.privateKey))
    assertArrayEquals(newKey, rekey.open(admin.publicKey, kari, kariKeys.privateKey))
    assertNull(rekey.open(admin.publicKey, removed, removedKeys.privateKey))
    // Kari's copy cannot be read with Ola's private key.
    assertNull(rekey.open(admin.publicKey, kari, olaKeys.privateKey))
  }

  @Test fun rekeyFromANonAdminIsRejected() {
    val groupId = GroupKeys.newGroupId()
    val ola = GroupKeys.newRiderId(); val olaKeys = Keys.newAgreementKeyPair()
    val impostor = Keys.newSigningKeyPair()
    val rekey = Rekey.create(groupId, 1, GroupKeys.newGroupKey(), impostor.privateKey, listOf(ola to olaKeys.publicKey))
    assertFalse(rekey.verify(admin.publicKey))
    assertNull(rekey.open(admin.publicKey, ola, olaKeys.privateKey))
  }

  @Test fun tamperedRekeyIsRejected() {
    val groupId = GroupKeys.newGroupId()
    val ola = GroupKeys.newRiderId(); val olaKeys = Keys.newAgreementKeyPair()
    val r = Rekey.create(groupId, 1, GroupKeys.newGroupKey(), admin.privateKey, listOf(ola to olaKeys.publicKey))
    val moved = Rekey(groupId, 2, r.newProof, r.keys, r.signature)
    assertNull(moved.open(admin.publicKey, ola, olaKeys.privateKey))
    // A member swapping the proof (to lock everyone out) breaks the signature too.
    val swapped = Rekey(groupId, 1, GroupKeys(groupId, GroupKeys.newGroupKey(), 1).accessProof(), r.keys, r.signature)
    assertNull(swapped.open(admin.publicKey, ola, olaKeys.privateKey))
  }

  @Test fun newKeyWorksForFramesAfterRekey() {
    val groupId = GroupKeys.newGroupId()
    val ola = GroupKeys.newRiderId(); val olaKeys = Keys.newAgreementKeyPair()
    val oldKeys = GroupKeys(groupId, GroupKeys.newGroupKey(), 0)
    val rekey = Rekey.create(groupId, 1, GroupKeys.newGroupKey(), admin.privateKey, listOf(ola to olaKeys.publicKey))
    val fresh = GroupKeys(groupId, rekey.open(admin.publicKey, ola, olaKeys.privateKey)!!, 1)
    val frame = FrameCrypto.seal(fresh, FrameType.POS, ola, "after".toByteArray())
    assertEquals("after", String(FrameCrypto.open(fresh, frame)!!))
    assertNull(FrameCrypto.open(oldKeys, frame))
    assertEquals(fresh.accessProof(), rekey.newProof)
  }
}
