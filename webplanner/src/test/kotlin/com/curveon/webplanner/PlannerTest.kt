package com.curveon.webplanner

import com.curveon.routing.BRouterEngine
import com.curveon.routing.LatLon
import com.curveon.routing.ProfileInstaller
import com.curveon.routing.RoadStyle
import com.curveon.routing.Route
import com.curveon.routing.RoutePoint
import com.curveon.routing.RouteOptions
import com.curveon.routing.RoutingException
import com.curveon.routing.SurfacePreference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PlannerTest {
  private val body = JSONObject("""{"points":[[59.79,5.14],[60.069,6.5459]],"options":{"style":"VERY_CURVY","surface":"MIXED","ferries":true,"toll":true}}""")

  private fun straight(points: List<LatLon>) = Route(
    points.map { RoutePoint(it.lat, it.lon, 0.0, 0.0, 0.0, null, null, false, false) }, emptyList(), 1000.0, 60.0, 5.0, 40, 0.0, 0,
  )

  private fun refused(block: () -> Unit): String {
    try { block() } catch (e: PlanRefused) { return e.code }
    fail("not refused"); return ""
  }

  @Test fun `options are read the way the app saves them`() {
    var got: RouteOptions? = null
    Planner({ p, o -> got = o; straight(p) }).plan(body)
    assertEquals(RouteOptions(RoadStyle.VERY_CURVY, SurfacePreference.MIXED, avoidFerries = true, avoidToll = true), got)
  }

  @Test fun `the answer has the line and what the app shows`() {
    val r = Planner({ p, _ -> straight(p) }).plan(body)
    assertEquals(2, r.getJSONArray("line").length())
    assertEquals(59.79, r.getJSONArray("line").getJSONArray(0).getDouble(0), 1e-9)
    assertEquals(1000, r.getInt("m"))
    assertEquals(40, r.getInt("curvy"))
  }

  @Test fun `bad, too long and unroutable requests are turned down`() {
    val p = Planner({ _, _ -> throw RoutingException("none") })
    assertEquals("bad_request", refused { p.plan(JSONObject("""{"points":[[59,5]]}""")) })
    assertEquals("bad_request", refused { p.plan(JSONObject("""{"points":[[99,5],[60,6]]}""")) })
    assertEquals("too_long", refused { p.plan(JSONObject("""{"points":[[58,5],[71,25]]}""")) })
    assertEquals("no_route", refused { p.plan(body) })
  }

  @Test fun `when the queue is full the planner says it is busy`() {
    val release = CountDownLatch(1)
    val p = Planner({ pts, _ -> release.await(); straight(pts) }, parallel = 1, queue = 1, waitSeconds = 5)
    val pool = Executors.newFixedThreadPool(2)
    repeat(2) { pool.submit { p.plan(body) } }
    Thread.sleep(200)
    assertEquals("busy", refused { p.plan(body) })
    release.countDown()
    pool.shutdown()
    assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
    p.plan(body) // free again
  }

  @Test fun `an address gets so many routes per window`() {
    var now = 0L
    val l = RateLimiter(perWindow = 2, windowMillis = 1000) { now }
    assertTrue(l.allow("a")); assertTrue(l.allow("a")); assertFalse(l.allow("a"))
    assertTrue(l.allow("b"))
    now = 1500
    assertTrue(l.allow("a"))
  }

  @Test fun `real roads, Bomlo to Odda`() {
    val segments = File(System.getProperty("curveon.segments") ?: "")
    assumeTrue(File(segments, "E5_N55.rd5").exists())
    val engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("p").toFile()))
    val r = Planner(engine).plan(JSONObject("""{"points":[[59.794218,5.144344],[59.98,6.01],[60.069,6.5459]],"options":{"style":"CURVY"}}"""))
    val km = r.getInt("m") / 1000.0
    assertTrue("$km km", km in 100.0..250.0)
    // Thinned to 5 m: a few points per km, not one every few metres.
    assertTrue("${r.getJSONArray("line").length()} points", r.getJSONArray("line").length() < km * 40)
  }

  @Test fun `outside the routing data the planner says so`() {
    val segments = File(System.getProperty("curveon.segments") ?: "")
    assumeTrue(File(segments, "E5_N55.rd5").exists() && !File(segments, "E10_N50.rd5").exists())
    val engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("p").toFile()))
    // Berlin to Munich: no routing data there.
    val e = try { Planner(engine).plan(JSONObject("""{"points":[[52.52,13.40],[48.14,11.58]]}""")); null } catch (x: PlanRefused) { x }
    assertEquals("outside", e?.code)
  }
}
