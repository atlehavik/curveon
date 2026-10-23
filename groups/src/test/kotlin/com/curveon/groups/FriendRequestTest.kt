package com.curveon.groups

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FriendRequestTest {
  private val groupId = GroupKeys.newGroupId()
  private val sender = GroupKeys.newRiderId()
  private val target = GroupKeys.newRiderId()
  private val targetKeys = Keys.newAgreementKeyPair()
  private val link = "curveon://join#v=1&g=ab&k=cd&a=ef&r=relay.curveon.no&kind=friend"

  private fun sealed() = SealedBox.seal(link.toByteArray(), targetKeys.publicKey, groupId, sender + target)

  @Test fun onlyTheTargetCanOpenIt() {
    val box = sealed()
    assertArrayEquals(link.toByteArray(), box.open(targetKeys.privateKey, groupId, sender + target))
    // Another rider in the group: same group key and id, but not the target's key.
    val other = Keys.newAgreementKeyPair()
    assertNull(box.open(other.privateKey, groupId, sender + target))
  }

  @Test fun itCannotBeMovedToAnotherSenderTargetOrGroup() {
    val box = sealed()
    assertNull(box.open(targetKeys.privateKey, groupId, GroupKeys.newRiderId() + target))
    assertNull(box.open(targetKeys.privateKey, groupId, sender + GroupKeys.newRiderId()))
    assertNull(box.open(targetKeys.privateKey, GroupKeys.newGroupId(), sender + target))
  }

  @Test fun anAlteredBoxIsRefused() {
    val box = sealed()
    val c = box.ciphertext.copyOf().also { it[0] = (it[0] + 1).toByte() }
    assertNull(SealedBox(box.ephemeralPublic, box.nonce, c).open(targetKeys.privateKey, groupId, sender + target))
  }

  @Test fun theRequestSurvivesTheWire() {
    val box = sealed()
    val back = PayloadCodec.decode(FrameType.FRIEND_REQ, PayloadCodec.encode(5, 99, Payload.FriendRequest("id1", "toRider", box)))
    assertNotNull(back)
    val p = back!!.payload as Payload.FriendRequest
    assertEquals("id1", p.id)
    assertEquals("toRider", p.to)
    assertArrayEquals(link.toByteArray(), p.box.open(targetKeys.privateKey, groupId, sender + target))
  }

  @Test fun theLinkIsNotInThePlainFrame() {
    val bytes = PayloadCodec.encode(5, 99, Payload.FriendRequest("id1", "toRider", sealed()))
    assertEquals(false, String(bytes).contains("curveon://") || String(bytes).contains("relay.curveon.no"))
  }
}
