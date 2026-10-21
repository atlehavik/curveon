package com.curveon.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RiderSyncTest {
  private fun e(key: String, ts: Long, payload: String? = "p$ts", deleted: Boolean = false) = SyncEntry(key, ts, deleted, payload)
  private fun doc(vararg es: SyncEntry) = SyncDoc(es.associateBy { it.key })
  private fun SyncDoc.same(o: SyncDoc) = entries.keys == o.entries.keys && entries.all { (k, v) -> v.sameAs(o.entries.getValue(k)) }

  @Test fun theNewerEntryWinsAndNewKeysAreTaken() {
    val a = doc(e("g:1", 10), e("g:2", 5))
    val b = doc(e("g:1", 20), e("g:3", 1))
    val m = a.merge(b)
    assertEquals(20L, m.entries.getValue("g:1").ts)
    assertEquals(5L, m.entries.getValue("g:2").ts)
    assertEquals(1L, m.entries.getValue("g:3").ts)
  }

  @Test fun mergingGivesTheSameWhateverTheOrderAndHoweverOften() {
    val a = doc(e("g:1", 10), e("profile", 3), e("g:9", 7, null, deleted = true))
    val b = doc(e("g:1", 12), e("g:2", 4), e("g:9", 5))
    val c = doc(e("profile", 9), e("g:2", 4, "other"))
    val abc = a.merge(b).merge(c)
    assertTrue(abc.same(c.merge(a).merge(b)))
    assertTrue(abc.same(b.merge(c).merge(a).merge(b).merge(a)))
  }

  @Test fun aDeletionIsNotBroughtBackByAnOlderCopy() {
    val gone = doc(e("g:1", 20, null, deleted = true))
    val old = doc(e("g:1", 10))
    assertTrue(gone.merge(old).entries.getValue("g:1").deleted)
    assertTrue(old.merge(gone).entries.getValue("g:1").deleted)
  }

  @Test fun aNewerCopyBeatsAnOlderDeletion() { // the group was joined again later
    val gone = doc(e("g:1", 10, null, deleted = true))
    val again = doc(e("g:1", 30))
    assertFalse(gone.merge(again).entries.getValue("g:1").deleted)
  }

  @Test fun atTheSameTimeTheDeletionAndThenTheLargerPayloadWin() {
    val x = e("g:1", 5, "a"); val y = e("g:1", 5, "b"); val d = e("g:1", 5, null, deleted = true)
    assertTrue(doc(x).merge(doc(y)).same(doc(y).merge(doc(x))))
    assertEquals("b", doc(x).merge(doc(y)).entries.getValue("g:1").payload)
    assertTrue(doc(x).merge(doc(d)).entries.getValue("g:1").deleted)
    assertTrue(doc(d).merge(doc(x)).entries.getValue("g:1").deleted)
  }

  @Test fun hasNewerThanTellsWhoMustSend() {
    val a = doc(e("g:1", 10), e("g:2", 5))
    val b = doc(e("g:1", 10))
    assertTrue(a.hasNewerThan(b)) // a has g:2
    assertFalse(b.hasNewerThan(a))
    assertFalse(a.hasNewerThan(a))
  }

  @Test fun twoDevicesEndIdenticalAfterOneExchange() {
    var x = doc(e("g:1", 10), e("profile", 2))
    var y = doc(e("g:2", 11), e("profile", 8))
    val fromX = x; val fromY = y
    x = x.merge(fromY); y = y.merge(fromX)
    assertTrue(x.same(y))
    assertEquals(8L, x.entries.getValue("profile").ts)
  }

  @Test fun jsonRoundTripsAndRejectsJunk() {
    val d = doc(e("g:1", 10, """{"a":"å"}"""), e("g:2", 11, null, deleted = true))
    val back = SyncDoc.fromJson(d.toJson())
    assertNotNull(back)
    assertTrue(d.same(back!!))
    assertNull(SyncDoc.fromJson("not json"))
    assertNull(SyncDoc.fromJson("""{"v":2,"items":[]}"""))
  }

  @Test fun aGroupMovesToTheOtherDeviceWithTheSameIdentityAndItsOwnDeviceId() {
    val state = GroupState.create("relay.example", "Saturday")
    val payload = SyncDoc.payload(state)
    assertFalse(payload.contains("\"device\""))
    val there = SyncDoc.restore(payload, "other-device")!!
    assertEquals(state.groupIdHex, there.groupIdHex)
    assertEquals(B64.encode(state.riderId), B64.encode(there.riderId))
    assertEquals(B64.encode(state.agreement.privateKey), B64.encode(there.agreement.privateKey))
    assertEquals(state.isAdmin, there.isAdmin)
    assertEquals("other-device", there.deviceId)
    assertEquals("Saturday", there.name)
  }

  @Test fun aFriendsPictureStaysBehindButTheNameGoes() {
    val state = GroupState.create("relay.example", null, GroupKind.FRIEND)
    state.peer = PeerProfile("Kari", 0xFF00AA, null, "BIGPHOTO", "rider1")
    val payload = SyncDoc.payload(state)
    assertFalse(payload.contains("BIGPHOTO"))
    val there = SyncDoc.restore(payload, "d")!!
    assertEquals("Kari", there.peer?.nickname)
    assertNull(there.peer?.photoBase64)
    assertEquals(GroupKind.FRIEND, there.kind)
  }

  @Test fun anOwnDeviceInviteCarriesItsKind() {
    val s = GroupState.create("relay.example", "me", GroupKind.SELF)
    val parsed = Invite.parse(s.invite().toUri())
    assertEquals(GroupKind.SELF, parsed?.kind)
  }

  @Test fun aGroupMovedToTheOtherDeviceReadsTheSameThere() { // else the two would keep re-sending to each other
    for (kind in listOf(GroupKind.RIDE, GroupKind.FRIEND)) {
      val state = GroupState.create("relay.example", "Name å", kind)
      if (kind == GroupKind.FRIEND) state.peer = PeerProfile("Kari", 7, "bike", "PHOTO", "r1")
      val once = SyncDoc.payload(state)
      val there = SyncDoc.restore(once, "d2")!!
      assertEquals(once.replace("\"photo\":\"PHOTO\"", "\"photo\":null"), SyncDoc.payload(there))
      assertEquals(SyncDoc.payload(there), SyncDoc.payload(SyncDoc.restore(SyncDoc.payload(there), "d3")!!))
    }
  }
}
