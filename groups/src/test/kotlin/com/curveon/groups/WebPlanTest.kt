package com.curveon.groups

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPlanTest {
  private fun link() = WebPlanLink(GroupKeys.newGroupId(), GroupKeys.newGroupKey(), GroupKeys.randomBytes(32), "relay.curveon.no")

  private fun trip(rev: Long = 1) = WebPlanTrip(
    "abc_DEF-1", rev, "Hardanger", TripStop("Bømlo", 59.79, 5.14), TripStop("Odda", 60.069, 6.5459),
    listOf(TripStop("Rosendal", 59.98, 6.01), TripStop("", 60.0, 6.2, via = true)),
    """{"style":"CURVY","surface":"PAVED","ferries":true}""", "59.79000,5.14000;59.98000,6.01000;60.06900,6.54590",
    133_000.0, 9_000.0, 1_200.0, 64, 1_700_000_000_000,
  )

  @Test fun `link survives the QR code`() {
    val l = link()
    val back = WebPlanLink.parse(l.toUri())!!
    assertEquals(l, back)
    assertEquals("relay.curveon.no", back.relayHost)
    assertTrue(back.relayUrl().startsWith("wss://relay.curveon.no/g/${l.groupIdHex}?p="))
  }

  @Test fun `group invites and junk are not planner links`() {
    assertNull(WebPlanLink.parse("curveon://join#v=1&g=AAAA"))
    assertNull(WebPlanLink.parse("curveon://webplan#v=2&g=x"))
    assertNull(WebPlanLink.parse("https://example.com"))
  }

  @Test fun `a trip goes through sealed and comes out the same`() {
    val l = link()
    val text = WebPlan.planFrame(l, GroupKeys.newRiderId(), 1, trip())
    val o = JSONObject(text)
    assertEquals("plan", o.getString("t"))
    assertEquals("abc_DEF-1", o.getString("k"))
    assertEquals(WebPlan.KEEP_SECONDS, o.getInt("x"))
    val t = WebPlan.openPlan(l, text)!!
    assertEquals("Hardanger", t.name)
    assertEquals("Bømlo", t.start!!.name)
    assertEquals(2, t.stops.size)
    assertTrue(t.stops[1].via)
    assertEquals(64, t.curviness)
    assertEquals("CURVY", JSONObject(t.options).getString("style"))
  }

  @Test fun `a track has no destination and no stops, and one without is not a trip`() {
    val l = link()
    val track = WebPlanTrip("gpx-1", 1, "Fjellrunden", null, null, emptyList(), "{}", "60.0,6.0;60.1,6.1;60.2,6.0", 30_000.0, 1_800.0, 0.0, 0, 0)
    val text = WebPlan.planFrame(l, GroupKeys.newRiderId(), 1, track)
    val t = WebPlan.openPlan(l, text)!!
    assertNull(t.destination)
    assertTrue(t.stops.isEmpty())
    assertEquals("Fjellrunden", t.name)
    assertNull(t.group)
    // One of a file's tracks carries the file's name, for the GPX group they go in.
    val inFile = WebPlanTrip("gpx-2", 1, "TET N-04", null, null, emptyList(), "{}", "60.0,6.0;60.1,6.1", 9_000.0, 600.0, 0.0, 0, 0, group = "TET Norway")
    assertEquals("TET Norway", WebPlan.openPlan(l, WebPlan.planFrame(l, GroupKeys.newRiderId(), 2, inFile))!!.group)
    // Without `track` and without a destination: dropped, as before.
    val o = track.toJson().apply { remove("track") }
    assertNull(runCatching { WebPlanTrip.fromJson("x", 1, 0, o) }.getOrNull())
  }

  @Test fun `another key, an altered frame or the wrong type is dropped`() {
    val l = link()
    val text = WebPlan.planFrame(l, GroupKeys.newRiderId(), 1, trip())
    assertNull(WebPlan.openPlan(link(), text))
    val o = JSONObject(text)
    val c = o.getString("c")
    o.put("c", (if (c[0] == 'A') "B" else "A") + c.substring(1))
    assertNull(WebPlan.openPlan(l, o.toString()))
    assertNull(WebPlan.openPlan(l, WebPlan.gotFrame(l, GroupKeys.newRiderId(), 1, "abc", 1, "Pixel", 0)))
  }

  @Test fun `the inbox keeps the newest revision and answers before it closes`() {
    val l = link()
    val rider = GroupKeys.newRiderId()
    val sent = mutableListOf<String>()
    var closed = false
    val factory = TransportFactory { _, listener ->
      listener.onOpen()
      listener.onMessage("""{"v":1,"t":"relay","f":[]}""")
      listener.onMessage(WebPlan.planFrame(l, rider, 1, trip(rev = 1)))
      listener.onMessage(WebPlan.planFrame(l, rider, 2, trip(rev = 3)))
      listener.onMessage(WebPlan.planFrame(l, rider, 3, trip(rev = 2)))
      object : Transport {
        override fun send(text: String): Boolean { sent.add(text); return true }
        override fun close(code: Int) { closed = true }
      }
    }
    val got = WebPlanInbox(factory).fetch(l, quietMs = 50) { inbound ->
      inbound.trips.map { WebPlan.gotFrame(l, GroupKeys.newRiderId(), 1, it.tripId, it.rev, "Pixel 9a", 0) }
    }
    assertNotNull(got)
    assertEquals(1, got!!.trips.size)
    assertEquals(3L, got.trips[0].rev)
    assertEquals(1, sent.size)
    assertTrue(closed)
  }

  @Test fun `no connection is null, not an empty list`() {
    val factory = TransportFactory { _, listener ->
      listener.onClosed(TransportListener.FAILED)
      object : Transport {
        override fun send(text: String) = false
        override fun close(code: Int) {}
      }
    }
    assertNull(WebPlanInbox(factory).fetch(link(), quietMs = 50, maxMs = 500))
  }

  @Test fun `polylines come back within a metre`() {
    val pts = listOf(59.79421 to 5.14434, 59.8 to 5.2, 60.06912 to 6.54591, -33.5 to -70.25)
    val back = Polyline.decode(Polyline.encode(pts))
    assertEquals(pts.size, back.size)
    pts.zip(back).forEach { (a, b) -> assertEquals(a.first, b.first, 1e-5); assertEquals(a.second, b.second, 1e-5) }
    // The example from Google's documentation.
    assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", Polyline.encode(listOf(38.5 to -120.2, 40.7 to -120.95, 43.252 to -126.453)))
  }

  @Test fun `a change of a device's trip names it, and a delete is read`() {
    val l = link()
    val ref = WebPlanRef("abc", 42)
    val t = WebPlanTrip("r42", 2, "Endret", null, TripStop("B", 60.0, 6.0), emptyList(), "{}", "59.00000,5.00000;60.00000,6.00000",
      1.0, 1.0, 0.0, 0, 0, ref)
    assertEquals(ref, WebPlan.openPlan(l, WebPlan.planFrame(l, GroupKeys.newRiderId(), 1, t))!!.ref)
    val keys = l.keys
    val del = Wire.encode(FrameCrypto.seal(keys, FrameType.PLAN_DEL, GroupKeys.newRiderId(),
      JSONObject().put("seq", 1).put("ts", 0).put("ref", ref.toJson()).toString().toByteArray()))
    assertEquals(ref, WebPlan.openDelete(l, del))
    assertNull(WebPlan.openPlan(l, del))
  }

  @Test fun `a device's trips go out kept under their own slots, and a dropped one is forgotten`() {
    val l = link()
    val have = WebPlanHave(7, "x1", "Helgetur", "Sommer", false, null, TripStop("Odda", 60.0, 6.5), emptyList(), "{}",
      Polyline.encode(listOf(59.0 to 5.0, 60.0 to 6.5)), 1000.0, 60.0, 5.0, 40, 0)
    val h = JSONObject(WebPlan.haveFrame(l, GroupKeys.newRiderId(), 1, have, 0))
    assertEquals("plan_have", h.getString("t")); assertEquals("h7", h.getString("k"))
    val i = JSONObject(WebPlan.indexFrame(l, GroupKeys.newRiderId(), 2, "Pixel 9a", listOf(7, 3), 0))
    assertEquals("plan_index", i.getString("t")); assertEquals("index", i.getString("k"))
    val d = JSONObject(WebPlan.dropHaveFrame(l, GroupKeys.newRiderId(), 3, 7, 0))
    assertEquals("h7", d.getString("k")); assertEquals(0, d.getInt("x"))
  }

  @Test fun `live answers an empty channel too, so a page just paired sees the device`() {
    val l = link()
    var listener: TransportListener? = null
    val sent = java.util.concurrent.CopyOnWriteArrayList<String>()
    val factory = TransportFactory { _, li ->
      listener = li
      object : Transport {
        override fun send(text: String): Boolean { sent.add(text); return true }
        override fun close(code: Int) {}
      }
    }
    val batches = java.util.concurrent.CopyOnWriteArrayList<WebPlanInbound>()
    val live = WebPlanInbox(factory).live(l, quietMs = 100, onInbound = { batches.add(it); listOf("index") })
    listener!!.onOpen()
    Thread.sleep(400)
    assertEquals(1, batches.size)
    assertTrue(batches[0].trips.isEmpty() && batches[0].deletes.isEmpty())
    assertEquals(listOf("index"), sent.toList())
    live.close()
  }

  @Test fun `live hands over the replay in one go, then each trip as it comes`() {
    val l = link()
    val rider = GroupKeys.newRiderId()
    var listener: TransportListener? = null
    val sent = java.util.concurrent.CopyOnWriteArrayList<String>()
    val factory = TransportFactory { _, li ->
      listener = li
      object : Transport {
        override fun send(text: String): Boolean { sent.add(text); return true }
        override fun close(code: Int) {}
      }
    }
    val batches = java.util.concurrent.CopyOnWriteArrayList<WebPlanInbound>()
    val live = WebPlanInbox(factory).live(l, quietMs = 100, onInbound = { batches.add(it); listOf("ack${batches.size}") })
    listener!!.onOpen()
    listener!!.onMessage(WebPlan.planFrame(l, rider, 1, trip(rev = 1)))
    listener!!.onMessage(WebPlan.planFrame(l, rider, 2, WebPlanTrip("other", 1, "B", null, TripStop("B", 60.0, 6.0), emptyList(), "{}",
      "59.00000,5.00000;60.00000,6.00000", 1.0, 1.0, 0.0, 0, 0)))
    Thread.sleep(400)
    assertEquals(1, batches.size)
    assertEquals(2, batches[0].trips.size)
    listener!!.onMessage(WebPlan.planFrame(l, rider, 3, trip(rev = 5)))
    assertEquals(2, batches.size)
    assertEquals(5L, batches[1].trips.single().rev)
    assertEquals(listOf("ack1", "ack2"), sent.toList())
    live.close()
  }
}
