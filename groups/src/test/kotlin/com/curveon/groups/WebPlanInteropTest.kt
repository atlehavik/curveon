package com.curveon.groups

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The planner page (webplanner/web/webplan.js, run in Node) and this library read each other's frames. */
class WebPlanInteropTest {
  private val script = File(System.getProperty("curveon.root") ?: "..", "webplanner/web/test/interop.mjs")
  private val node = listOf("/usr/bin/node", "/usr/local/bin/node").firstOrNull { File(it).exists() }

  private fun run(vararg args: String): JSONObject {
    val p = ProcessBuilder(listOf(node!!, script.path) + args).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    assertEquals(out, 0, p.waitFor())
    return JSONObject(out.trim().lines().last())
  }

  @Test fun `a trip sealed on the page opens here, and the app's answer opens there`() {
    assumeTrue(node != null && script.exists())
    val page = run("seal")
    val link = WebPlanLink.parse(page.getString("link"))!!
    assertEquals(link.relayUrl(), page.getString("url"))
    val trip = WebPlan.openPlan(link, page.getString("frame"))!!
    assertEquals("tid-1", trip.tripId)
    assertEquals(4L, trip.rev)
    assertEquals("Hardanger på langs", trip.name)
    assertEquals("Bømlo", trip.start!!.name)
    assertEquals(true, trip.stops.single().via)
    assertEquals(70, trip.curviness)
    assertEquals("tid-1", JSONObject(page.getString("frame")).getString("k"))

    val got = WebPlan.gotFrame(link, GroupKeys.newRiderId(), 1, "tid-1", 4, "Pixel 9a", 123)
    val opened = run("open", link.toUri(), got).getJSONObject("opened")
    assertEquals("plan_got", opened.getString("type"))
    assertEquals("Pixel 9a", opened.getJSONObject("body").getString("dev"))
  }
}
