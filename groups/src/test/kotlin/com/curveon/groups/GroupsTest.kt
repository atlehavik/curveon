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
    val moved = Rekey(groupId, 2, r.keys, r.signature)
    assertNull(moved.open(admin.publicKey, ola, olaKeys.privateKey))
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
  }
}
