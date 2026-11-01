package com.curveon.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

/** Routes over real Norway data must never turn back on themselves on a road, and use the slip lanes. */
class LegalTurnsTest {
    private val engine by lazy {
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue(File(segments, "E5_N55.rd5").exists())
        BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("p").toFile()))
    }

    private fun bearing(a: RoutePoint, b: RoutePoint) = Geo.bearing(a.lat, a.lon, b.lat, b.lon)
    private fun diff(a: Double, b: Double) = abs(((a - b + 540) % 360) - 180)

    /**
     * Places where the route turns back on itself: over at most [withinM], sharper than [deg]. A loop
     * ramp also comes round that far, the proper way, but takes much longer about it.
     */
    private fun reversals(r: Route, withinM: Double = 30.0, deg: Double = 150.0): List<String> {
        val pts = r.points.filter { !it.ferry }.fold(ArrayList<RoutePoint>()) { out, p ->
            if (out.isEmpty() || Geo.distance(out.last().lat, out.last().lon, p.lat, p.lon) > 1.0) out += p
            out
        }
        val found = ArrayList<String>()
        // Going round a roundabout is the proper way to turn back.
        val roundabouts = r.maneuvers.filter { it.type == ManeuverType.ROUNDABOUT }.map { it.distanceFromStart }
        for (i in 1 until pts.size - 1) {
            if (roundabouts.any { abs(it - pts[i].distanceFromStart) < 120 }) continue
            val before = bearing(pts[i - 1], pts[i])
            var j = i + 1
            while (j < pts.size && pts[j].distanceFromStart - pts[i].distanceFromStart <= withinM) {
                val after = bearing(pts[j - 1], pts[j])
                // Not a bend: the line back along the road the route came on is close by.
                if (diff(before, after) > deg && Geo.distance(pts[i].lat, pts[i].lon, pts[j].lat, pts[j].lon) < withinM) {
                    found += "%.5f,%.5f (%s -> %s, %.0f°)".format(pts[i].lat, pts[i].lon, pts[i].highway, pts[j].highway, diff(before, after))
                    break
                }
                j++
            }
        }
        return found
    }

    private val valevag = LatLon(59.700235, 5.471736)
    private val svortland = LatLon(59.792836, 5.172305)
    private val aboveHaukas = LatLon(59.5698, 5.41807)
    private val towardsHaugesund = LatLon(59.54112, 5.35814)

    @Test
    fun `Valevag to Bomlo leaves E39 by the exit and the bridge, with no U-turn north of the tunnel`() {
        for (style in RoadStyle.entries) {
            val r = engine.route(listOf(valevag, svortland), RouteOptions(style))
            assertTrue("$style: ${reversals(r)}", reversals(r).isEmpty())
            assertTrue("$style has a U-turn", r.maneuvers.none { it.type == ManeuverType.U_TURN })
            // Beyond the exit, E39 goes on towards Stord; the route has no business there.
            assertTrue("$style rides on past the exit", r.points.none { it.lat > 59.7425 && it.lon in 5.40..5.41 })
            // And it does take the exit: the ramp on the right, then the bridge over E39.
            assertTrue("$style misses the ramp", r.points.any { it.highway == "trunk_link" && it.lat in 59.7400..59.7420 && it.lon in 5.4050..5.4075 })
        }
    }

    @Test
    fun `Bomlo to Valevag has no U-turn either`() {
        for (style in RoadStyle.entries) {
            val r = engine.route(listOf(svortland, valevag), RouteOptions(style))
            assertTrue("$style: ${reversals(r)}", reversals(r).isEmpty())
        }
    }

    @Test
    fun `Haukas E39 south to Fv47 takes the slip lane, not the cut across`() {
        for (style in RoadStyle.entries) {
            val r = engine.route(listOf(aboveHaukas, towardsHaugesund), RouteOptions(style))
            val slip = r.points.filter { it.highway == "trunk_link" }
            assertTrue("$style misses the slip lane", slip.any { it.lat in 59.5611..59.5626 && it.lon < 5.4153 })
            assertTrue("$style still rides the through lane", r.points.none { it.lat in 59.5612..59.5620 && it.lon > 5.4154 })
        }
    }

    @Test
    fun `reversal check finds the U-turn at Foyno in a route forced through it`() {
        // The route as it was: on past the exit to the merge at the north end of the junction, and back.
        fun p(lat: Double, lon: Double, along: Double) = RoutePoint(lat, lon, 0.0, along, 0.0, "trunk", null, false, false)
        val pts = listOf(p(59.74225, 5.40248, 0.0), p(59.74305, 5.40162, 103.0), p(59.74223, 5.40239, 206.0), p(59.74178, 5.40317, 272.0))
        assertEquals(1, reversals(Route(pts, emptyList(), 272.0, 0.0, 0.0, 0, 0.0, 0), withinM = 110.0).size)
    }

    @Test
    fun `no route in the area turns back on itself`() {
        val places = listOf(
            valevag, svortland, aboveHaukas, towardsHaugesund,
            LatLon(59.76332, 5.45156), // Stord, McDonald's
            LatLon(59.738446, 5.416421), // Føyno
            LatLon(59.7790, 5.4900), // Stord, Leirvik side
            LatLon(59.4138, 5.2680), // Haugesund
            LatLon(59.5600, 5.4150), // Haukås
            LatLon(59.6100, 5.4760), // Sveio, E39
        )
        val bad = ArrayList<String>()
        for (a in places) for (b in places) if (a != b) {
            val r = engine.route(listOf(a, b), RouteOptions(RoadStyle.FASTEST))
            // Near either end it can be the way into or out of the place itself.
            val middle = r.copy(points = r.points.filter { it.distanceFromStart in 150.0..(r.distanceMeters - 150.0) })
            reversals(middle).forEach { bad += "$a -> $b: $it" }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }
}
