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
    val got = WebPlanInbox(factory).fetch(l, quietMs = 50) { trips ->
      trips.map { WebPlan.gotFrame(l, GroupKeys.newRiderId(), 1, it.tripId, it.rev, "Pixel 9a", 0) }
    }
    assertNotNull(got)
    assertEquals(1, got!!.size)
    assertEquals(3L, got[0].rev)
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
}
