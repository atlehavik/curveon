package com.curveon.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * The rides E39 and FV47 through Sveio, with NVDB's real signs and priority roads as of 2026-11-01
 * (src/test/resources/giveway, cut from the speed limits file; NLOD 2.0, Statens vegvesen).
 */
class GiveWayRealRoadsTest {
    private fun runs(name: String) = javaClass.getResourceAsStream("/giveway/$name")!!.bufferedReader().readLines().map { l ->
        val (value, pts) = l.split(",", limit = 2)
        value.toInt() to pts.split(";").map { p -> p.split(" ").let { LatLon(it[0].toDouble(), it[1].toDouble()) } }
    }

    private val signs by lazy { runs("signs.csv").filter { it.second.size >= 2 }.map { (v, p) -> GiveWaySign(p[0].lat, p[0].lon, p[1].lat, p[1].lon, v) } }
    private val pieces by lazy { runs("priority.csv").flatMap { (_, p) -> p.zipWithNext().map { (a, b) -> RoadPiece(a.lat, a.lon, b.lat, b.lon) } } }

    /** As SpeedLimits.priorityRoadNear. */
    private val priority: (Double, Double) -> RoadPiece? = { lat, lon ->
        val kx = 111_320.0 * cos(Math.toRadians(lat))
        var best: RoadPiece? = null
        var bestD = 20.0
        for (r in pieces) {
            if (abs(r.lat1 - lat) > 0.02 || abs(r.lon1 - lon) > 0.04) continue
            val ax = (r.lon1 - lon) * kx; val ay = (r.lat1 - lat) * 110_574.0
            val sx = (r.lon2 - r.lon1) * kx; val sy = (r.lat2 - r.lat1) * 110_574.0
            val len2 = sx * sx + sy * sy
            val t = if (len2 == 0.0) 0.0 else (-(ax * sx + ay * sy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(ax + t * sx, ay + t * sy)
            if (d < bestD) { bestD = d; best = r }
        }
        best
    }

    private val engine by lazy {
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue(File(segments, "E5_N55.rd5").exists())
        BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("p").toFile()))
    }

    private fun giveWays(from: LatLon, to: LatLon) =
        GiveWay.alongRoute(engine.route(listOf(from, to), RouteOptions(RoadStyle.FASTEST)), signs, priority)

    private val fv47 = LatLon(59.54112, 5.35814)
    private val e39North = LatLon(59.5698, 5.41807)
    private val e39AtForde = LatLon(59.6520, 5.4780)

    @Test
    fun `FV47 at Haukas is the stop onto E39, and not the warning 300 m before it`() {
        val found = giveWays(fv47, e39North)
        assertEquals(found.toString(), 1, found.size)
        assertTrue(found[0].stop)
        assertEquals(0.0, Geo.distance(found[0].lat, found[0].lon, 59.5614, 5.4157), 30.0)
    }

    @Test
    fun `FV47 to E39 south by its own slip has the stop at the end of the slip`() {
        val found = giveWays(fv47, LatLon(59.5487, 5.4207))
        assertTrue(found.toString(), found.any { it.stop && Geo.distance(it.lat, it.lon, 59.5612, 5.4157) < 40 })
    }

    @Test
    fun `E39 by the slip lane onto FV47 gives way to nobody`() {
        assertEquals(emptyList<GiveWayOnRoute>(), giveWays(e39North, fv47))
    }

    @Test
    fun `E39 is a priority road where the register has no such object, by its signs`() {
        assertTrue(priority(59.5650, 5.4162) != null) // EV39 S89D1, the stretch of the rider's report
        assertTrue(priority(59.6270, 5.4932) != null) // EV39 S88D1
    }

    @Test
    fun `riding E39 through Sveio nothing gives way`() {
        // The plates close by that used to count: a ramp's at Valevåg, side roads'. Only one is left: NVDB
        // has a give way sign on E39 itself, EV39 S88D1 m1652, facing traffic going south-east. It looks
        // wrong, but nothing tells it from a real one: hiding such signs hid 4538 of them, as real as the rest.
        val south = giveWays(e39North, e39AtForde)
        assertTrue(south.toString(), south.isEmpty() || south.size == 1 && Geo.distance(south[0].lat, south[0].lon, 59.62710, 5.49302) < 40)
        val north = giveWays(e39AtForde, e39North)
        assertTrue(north.toString(), north.isEmpty() || north.size == 1 && Geo.distance(north[0].lat, north[0].lon, 59.62710, 5.49302) < 40)
    }
}
