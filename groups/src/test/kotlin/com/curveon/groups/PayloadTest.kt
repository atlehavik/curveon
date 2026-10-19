package com.curveon.groups

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadTest {
  private fun roundTrip(p: Payload): Payload {
    val s = PayloadCodec.decode(p.type, PayloadCodec.encode(7, 1234, p))!!
    assertEquals(7L, s.seq)
    assertEquals(1234L, s.tsMs)
    return s.payload
  }

  @Test fun position() {
    val p = Payload.Pos(59.9139, 10.7522, 22.5f, 187f, 4f, battery = 80, paused = false, stopped = true, stoppedSince = 99)
    assertEquals(p, roundTrip(p))
    val minimal = Payload.Pos(1.0, 2.0, 0f, 0f, 0f)
    assertEquals(minimal, roundTrip(minimal))
  }

  @Test fun helloWithEachAvatarKind() {
    val key = Keys.newAgreementKeyPair().publicKey
    val icon = roundTrip(Payload.Hello("Ola æøå", 0xFFAA0000.toInt(), key, Avatar.Icon("helmet"))) as Payload.Hello
    assertEquals("Ola æøå", icon.nickname)
    assertEquals(0xFFAA0000.toInt(), icon.colour)
    assertArrayEquals(key, icon.agreementPublicKey)
    assertEquals(Avatar.Icon("helmet"), icon.avatar)

    val jpeg = ByteArray(300) { it.toByte() }
    val photo = roundTrip(Payload.Hello("Kari", 1, key, Avatar.Photo(jpeg))) as Payload.Hello
    assertArrayEquals(jpeg, (photo.avatar as Avatar.Photo).jpeg)

    assertNull((roundTrip(Payload.Hello("Per", 2, key)) as Payload.Hello).avatar)
  }

  @Test fun routeSosAndTheRest() {
    val data = ByteArray(1000) { (it * 7).toByte() }
    val r = roundTrip(Payload.Route("r1", "Lofoten", "gpx", data)) as Payload.Route
    assertEquals("Lofoten", r.name)
    assertArrayEquals(data, r.data)
    assertEquals(Payload.Sos(68.1, 13.6), roundTrip(Payload.Sos(68.1, 13.6)))
    assertEquals(Payload.SosAck("abc", true), roundTrip(Payload.SosAck("abc", true)))
    assertEquals(Payload.SosCancel, roundTrip(Payload.SosCancel))
    assertEquals(Payload.Leave, roundTrip(Payload.Leave))
  }

  @Test fun junkAndWrongTypeAreRejected() {
    assertNull(PayloadCodec.decode(FrameType.POS, "not json".toByteArray()))
    assertNull(PayloadCodec.decode(FrameType.POS, "{}".toByteArray()))
    assertNull(PayloadCodec.decode(FrameType.SOS, PayloadCodec.encode(1, 1, Payload.Leave)))
    assertNull(PayloadCodec.decode(FrameType.REKEY, PayloadCodec.encode(1, 1, Payload.Leave)))
  }

  @Test fun frameJsonRoundTrips() {
    val keys = GroupKeys(GroupKeys.newGroupId(), GroupKeys.newGroupKey(), 3)
    val rider = GroupKeys.newRiderId()
    val frame = FrameCrypto.seal(keys, FrameType.SOS, rider, "x".toByteArray())
    val text = Wire.encode(frame)
    assertEquals("sos", Wire.typeOf(text))
    val back = Wire.decode(text)!!
    assertEquals(FrameType.SOS, back.type)
    assertEquals(3, back.epoch)
    assertEquals("x", String(FrameCrypto.open(keys, back)!!))
    assertNull(Wire.decode(text.replace("\"v\":1", "\"v\":2")))
    assertNull(Wire.decode("{}"))
  }

  @Test fun rekeyJsonRoundTrips() {
    val admin = Keys.newSigningKeyPair()
    val groupId = GroupKeys.newGroupId()
    val ola = GroupKeys.newRiderId()
    val olaKeys = Keys.newAgreementKeyPair()
    val newKey = GroupKeys.newGroupKey()
    val rk = Rekey.create(groupId, 1, newKey, admin.privateKey, listOf(ola to olaKeys.publicKey))
    val text = Wire.encode(rk, GroupKeys.newRiderId())
    assertEquals("rekey", Wire.typeOf(text))
    assertNull("a rekey is not an ordinary frame", Wire.decode(text))
    val back = Wire.decodeRekey(text, groupId)!!
    assertArrayEquals(newKey, back.open(admin.publicKey, ola, olaKeys.privateKey))
  }

  @Test fun stateRoundTripsForAdminAndMember() {
    val mine = GroupState.create("relay.example.org", "Lofoten")
    val back = GroupState.fromJson(mine.toJson())!!
    assertTrue(back.isAdmin)
    assertArrayEquals(mine.groupKey, back.groupKey)
    assertEquals("Lofoten", back.name)

    val invite = Invite.parse(mine.invite(1_800_000_000L).toUri())!!
    val joined = GroupState.join(invite)
    assertTrue(!joined.isAdmin)
    assertArrayEquals(mine.groupId, joined.groupId)
    assertArrayEquals(mine.adminPublicKey, joined.adminPublicKey)
    val joinedBack = GroupState.fromJson(joined.toJson())!!
    assertTrue(!joinedBack.isAdmin)
    assertArrayEquals(joined.riderId, joinedBack.riderId)

    joined.switchKey(GroupKeys.newGroupKey(), 4)
    assertEquals(4, GroupState.fromJson(joined.toJson())!!.epoch)
    assertNotNull(GroupState.fromJson(joined.toJson()))
    assertNull(GroupState.fromJson("junk"))
  }
}
