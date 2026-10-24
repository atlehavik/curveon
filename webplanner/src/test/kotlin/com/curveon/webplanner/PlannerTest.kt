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

  private val leg = { a: LatLon, b: LatLon, _: RouteOptions, _: Double? -> straight(listOf(a, b)) }

  private fun refused(block: () -> Unit): String {
    try { block() } catch (e: PlanRefused) { return e.code }
    fail("not refused"); return ""
  }

  @Test fun `options are read the way the app saves them`() {
    var got: RouteOptions? = null
    Planner({ a, b, o, _ -> got = o; straight(listOf(a, b)) }).plan(body)
    assertEquals(RouteOptions(RoadStyle.VERY_CURVY, SurfacePreference.MIXED, avoidFerries = true, avoidToll = true), got)
  }

  @Test fun `the gravel detour is read as the app plans it, and only the app's choices`() {
    assertEquals(5, Planner.routeOptions(JSONObject("""{"surface":"GRAVEL","gravelReachKm":5}""")).gravelReachKm)
    assertEquals(null, Planner.routeOptions(JSONObject("""{"surface":"GRAVEL","gravelReachKm":null}""")).gravelReachKm)
    assertEquals(null, Planner.routeOptions(JSONObject("""{"surface":"GRAVEL","gravelReachKm":7}""")).gravelReachKm)
    assertEquals(null, Planner.routeOptions(JSONObject("""{"surface":"GRAVEL"}""")).gravelReachKm)
  }

  @Test fun `gravel roads for the map only in a box the reader can take`() {
    val p = Planner(leg)
    assertEquals(0, p.surfaceWays("5.2,59.7,5.4,59.8").getJSONArray("ways").length())
    assertEquals("too_long", refused { p.surfaceWays("4.0,59.0,6.0,60.0") })
    assertEquals("bad_request", refused { p.surfaceWays("5.4,59.7,5.2,59.8") })
  }

  @Test fun `real gravel roads on Bomlo, as the app's map shows them`() {
    val segments = File(System.getProperty("curveon.segments") ?: "")
    assumeTrue(File(segments, "E5_N55.rd5").exists())
    val profiles = ProfileInstaller.install(Files.createTempDirectory("p").toFile())
    val p = Planner(BRouterEngine(segments, profiles), com.curveon.routing.SurfaceReader(segments, File(profiles, "lookups.dat")))
    val ways = p.surfaceWays("5.10,59.70,5.40,59.85").getJSONArray("ways")
    val kinds = (0 until ways.length()).map { ways.getJSONArray(it).getString(0) }.toSet()
    assertTrue("$kinds, ${ways.length()} ways", "gravel" in kinds && ways.length() > 20)
  }

  @Test fun `the answer has the line and what the app shows`() {
    val r = Planner(leg).plan(body)
    assertEquals(2, r.getJSONArray("line").length())
    assertEquals(59.79, r.getJSONArray("line").getJSONArray(0).getDouble(0), 1e-9)
    assertEquals(1000, r.getInt("m"))
    // How curvy from the line itself, as the app scores a trip of several legs: a straight one is not.
    assertEquals(0, r.getInt("curvy"))
  }

  @Test fun `bad, too long and unroutable requests are turned down`() {
    val p = Planner({ _, _, _, _ -> throw RoutingException("none") })
    assertEquals("bad_request", refused { p.plan(JSONObject("""{"points":[[59,5]]}""")) })
    assertEquals("bad_request", refused { p.plan(JSONObject("""{"points":[[99,5],[60,6]]}""")) })
    assertEquals("too_long", refused { p.plan(JSONObject("""{"points":[[58,5],[71,25]]}""")) })
    assertEquals("no_route", refused { p.plan(body) })
  }

  @Test fun `when the queue is full the planner says it is busy`() {
    val release = CountDownLatch(1)
    val p = Planner({ a, b, _, _ -> release.await(); straight(listOf(a, b)) }, parallel = 1, queue = 1, waitSeconds = 5)
    val pool = Executors.newFixedThreadPool(2)
    repeat(2) { pool.submit { p.plan(body) } }
    Thread.sleep(200)
    assertEquals("busy", refused { p.plan(body) })
    release.countDown()
    pool.shutdown()
    assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
    p.plan(body) // free again
  }

  private val adventureBody = JSONObject("""{"points":[[59.79,5.14],[60.069,6.5459]],"area":[[59.6,5.1],[59.6,6.6],[60.2,6.6],[60.2,5.1]],"minutes":240,"variant":2,"options":{"style":"CURVY"}}""")

  /** A straight line 100 km long, a point every km. */
  private fun long() = Route(
    (0..100).map { RoutePoint(59.0 + it * 0.009, 5.0, 0.0, it * 1000.0, it * 40.0, null, null, false, it in 10..20) }, emptyList(), 100_000.0, 4000.0, 5.0, 40, 0.0, 1,
  )

  @Test fun `an adventure is asked as the page asks it, and comes with points that keep it on its roads`() {
    var got: AdventureAsk? = null
    val r = Planner(leg, { a -> got = a; long() }).adventure(adventureBody)
    assertEquals(240, got!!.minutes); assertEquals(2, got!!.variant); assertEquals(4, got!!.area.size)
    assertEquals(LatLon(60.069, 6.5459), got!!.destination)
    val vias = r.getJSONArray("vias")
    assertEquals(6, vias.length())
    // The legs between them, as a route asked for leg by leg comes, adding up to the whole.
    val legs = r.getJSONArray("legs")
    assertEquals(7, legs.length())
    assertEquals(100_000, (0 until legs.length()).sumOf { legs.getJSONObject(it).getInt("m") })
    assertEquals(vias.getJSONArray(0).getDouble(0), legs.getJSONObject(0).getJSONArray("line").let { it.getJSONArray(it.length() - 1).getDouble(0) }, 1e-9)
    // None on the ferry (km 10 to 20).
    for (i in 0 until vias.length()) assertFalse(vias.getJSONArray(i).getDouble(0) in 59.0 + 10 * 0.009..59.0 + 20 * 0.009)
    assertEquals(100_000, r.getInt("m"))
  }

  @Test fun `an adventure without a time, or with too little`() {
    var got: AdventureAsk? = null
    Planner(leg, { a -> got = a; long() }).adventure(JSONObject(adventureBody.toString()).put("minutes", JSONObject.NULL))
    assertEquals(null, got!!.minutes)
    val short = try {
      Planner(leg, { throw com.curveon.routing.NoAdventureException("quick", directMinutes = 95) }).adventure(adventureBody); null
    } catch (e: PlanRefused) { e }
    assertEquals("adventure_too_short", short?.code); assertEquals(95, short?.minutes)
    assertEquals("no_adventure", refused { Planner(leg, { throw com.curveon.routing.NoAdventureException("none") }).adventure(adventureBody) })
    assertEquals("bad_request", refused { Planner(leg).adventure(JSONObject(adventureBody.toString()).put("area", org.json.JSONArray("[[59,5],[60,6]]"))) })
  }

  @Test fun `an adventure takes every place, a route waits for it`() {
    val release = CountDownLatch(1)
    val p = Planner(leg, { release.await(); long() }, parallel = 2, queue = 4, waitSeconds = 1)
    val pool = Executors.newFixedThreadPool(1)
    pool.submit { p.adventure(adventureBody) }
    Thread.sleep(200)
    assertEquals("busy", refused { p.plan(body) })
    release.countDown()
    pool.shutdown()
    assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
    p.plan(body)
  }

  @Test fun `real adventure, Bomlo to Odda through Sunnhordland`() {
    val segments = File(System.getProperty("curveon.segments") ?: "")
    assumeTrue(File(segments, "E5_N55.rd5").exists())
    val profiles = ProfileInstaller.install(Files.createTempDirectory("p").toFile())
    val engine = BRouterEngine(segments, profiles, timeoutMillis = 45_000, memoryClassMb = 128)
    val t0 = System.nanoTime()
    val r = Planner(engine, com.curveon.routing.SurfaceReader(segments, File(profiles, "lookups.dat"))).adventure(adventureBody)
    val km = r.getInt("m") / 1000.0
    println("adventure: %.0f km, %d min, %.1f s".format(km, r.getInt("s") / 60, (System.nanoTime() - t0) / 1e9))
    assertTrue("$km km", km in 120.0..450.0)
    assertTrue("${r.getInt("s") / 60} min", r.getInt("s") <= 240 * 60 * 1.15)
    assertTrue(r.getJSONArray("vias").length() >= 4)
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

  @Test fun `a route comes leg by leg, each leaving its point the way the leg before arrived`() {
    val asked = mutableListOf<Double?>()
    // Northwards: each leg arrives heading 0°.
    val p = Planner({ a, b, _, h -> asked += h; straight(listOf(a, b, LatLon(b.lat + 0.001, b.lon), LatLon(b.lat + 0.002, b.lon))) })
    val r = p.plan(JSONObject("""{"points":[[59.0,5.0],[59.1,5.0],[59.2,5.0]],"heading":90,"legs":true}"""))
    assertEquals(listOf(90.0, 0.0), asked)
    val legs = r.getJSONArray("legs")
    assertEquals(2, legs.length())
    assertEquals(0, legs.getJSONObject(1).getInt("arrive"))
    assertTrue(legs.getJSONObject(0).has("ele"))
    // Asked for legs, the page gets no second copy of the whole line.
    assertFalse(r.has("line"))
    assertEquals(2000, r.getInt("m"))
  }

  @Test fun `a page that asks again gives up its search before`() {
    val started = CountDownLatch(1)
    val p = Planner({ a, b, _, _ ->
      // The second search is quick; the first runs until it is given up.
      if (started.count == 0L) return@Planner straight(listOf(a, b))
      started.countDown()
      // As BRouter runs: until done, or until its search is given up.
      val t0 = System.nanoTime()
      while (System.nanoTime() - t0 < 5_000_000_000) { Thread.sleep(20); if (BRouterEngine.cancelled()) throw RoutingException(BRouterEngine.CANCELLED) }
      straight(listOf(a, b))
    }, parallel = 1)
    val pool = Executors.newFixedThreadPool(1)
    val first = pool.submit<String> { try { p.plan(JSONObject(body.toString()).put("tab", "page-1234")); "done" } catch (e: PlanRefused) { e.code } }
    assertTrue(started.await(2, TimeUnit.SECONDS))
    val t0 = System.nanoTime()
    p.plan(JSONObject(body.toString()).put("tab", "page-1234").put("points", org.json.JSONArray("[[59.79,5.14],[59.8,5.2]]")))
    assertEquals("superseded", first.get(5, TimeUnit.SECONDS))
    assertTrue("the newer one waited for the old one to finish", (System.nanoTime() - t0) / 1e9 < 4)
    assertEquals(1L, p.stats.superseded.get())
    pool.shutdown()
  }

  @Test fun `places in a box, and the fuel along each leg`() {
    val pois = Pois.of(listOf(
      Pois.Poi("fuel", 59.05, 5.0, "Circle K"), Pois.Poi("fuel", 59.05, 5.3, "Far off"), Pois.Poi("viewpoint", 59.1, 5.001, "Utsikten"),
      Pois.Poi("fuel", 59.05, 5.0, "Circle K"), // the same one from a neighbouring country's file
    ))
    val p = Planner({ a, b, _, _ -> straight((0..10).map { LatLon(a.lat + (b.lat - a.lat) * it / 10, a.lon) }).let { r ->
      r.copy(points = r.points.mapIndexed { i, pt -> pt.copy(distanceFromStart = i * 1112.0) }, distanceMeters = 11_120.0)
    } }, pois = pois)
    val r = p.plan(JSONObject("""{"points":[[59.0,5.0],[59.1,5.0]],"legs":true}"""))
    val fuel = r.getJSONArray("legs").getJSONObject(0).getJSONArray("fuel")
    assertEquals(1, fuel.length())
    assertEquals("Circle K", fuel.getJSONArray(0).getString(3))
    assertEquals(5560.0, fuel.getJSONArray(0).getDouble(0), 100.0)
    val box = p.pois("4.9,58.9,5.5,59.2", "fuel,viewpoint,nonsense").getJSONArray("pois")
    assertEquals(3, box.length())
    assertEquals("bad_request", refused { p.pois("4.9,58.9,5.5,59.2", "nonsense") })
    assertEquals("too_long", refused { p.pois("-10,35,30,70", "fuel") })
  }

  @Test fun `gravel and tracks along a leg, as the app colours them`() {
    // Points every km: km 2–4 gravel, km 4–5 a track, the rest tarmac.
    val r = Route((0..8).map { i ->
      RoutePoint(59.0 + i * 0.009, 5.0, 0.0, i * 1000.0, i * 40.0, if (i == 5) "track" else "secondary", null, i in 3..4, false)
    }, emptyList(), 8000.0, 320.0, 0.0, 10, 2000.0, 0)
    assertEquals(listOf(Triple(2000L, 4000L, 1), Triple(4000L, 5000L, 2)), Planner.surfaces(r))
    val leg = Planner({ _, _, _, _ -> r }).plan(JSONObject("""{"points":[[59.0,5.0],[59.07,5.0]],"legs":true}""")).getJSONArray("legs").getJSONObject(0)
    assertEquals(2, leg.getJSONArray("surf").length())
  }

  @Test fun `a roundtrip is asked as the page asks it`() {
    var got: LoopAsk? = null
    val r = Planner(leg, roundtrip = { a -> got = a; long() }).roundtrip(JSONObject("""{"start":[59.79,5.14],"area":[[59.6,5.1],[59.6,6.6],[60.2,6.6]],"km":200,"variant":1,"options":{"style":"VERY_CURVY"}}"""))
    assertEquals(200, got!!.maxKm); assertEquals(1, got!!.variant); assertEquals(RoadStyle.VERY_CURVY, got!!.options.roadStyle)
    assertEquals(LatLon(59.79, 5.14), got!!.start)
    assertTrue(r.getJSONArray("vias").length() >= 4)
    assertEquals("bad_request", refused { Planner(leg).roundtrip(JSONObject("""{"start":[59.79,5.14],"area":[[59.6,5.1],[59.6,6.6],[60.2,6.6]],"km":5}""")) })
  }

  @Test fun `real roads leg by leg are the roads of the whole trip`() {
    val segments = File(System.getProperty("curveon.segments") ?: "")
    assumeTrue(File(segments, "E5_N55.rd5").exists())
    val engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("p").toFile()))
    val p = Planner(engine)
    val pts = """[[59.794218,5.144344],[59.98,6.01],[60.069,6.5459]]"""
    val whole = p.plan(JSONObject("""{"points":$pts,"options":{"style":"CURVY"},"legs":true}"""))
    val first = whole.getJSONArray("legs").getJSONObject(0)
    // The second leg asked on its own, leaving the stop the way the first one arrived: the same road.
    val second = p.plan(JSONObject("""{"points":[[59.98,6.01],[60.069,6.5459]],"options":{"style":"CURVY"},"legs":true,"heading":${first.getInt("arrive")}}"""))
    assertEquals(whole.getJSONArray("legs").getJSONObject(1).getInt("m").toDouble(), second.getInt("m").toDouble(), 1.0)
    val ele = first.getJSONArray("ele")
    assertEquals(first.getJSONArray("line").length(), ele.length())
    // Along the coast, over hills: heights that go up and down, not a flat zero.
    val heights = (0 until ele.length()).map { ele.getInt(it) }
    assertTrue("heights ${heights.min()}..${heights.max()}", heights.max() - heights.min() > 30)
  }
}
