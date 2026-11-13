package com.curveon.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

/** Wrong turns on real Norwegian roads; skipped when the segment files are not available. */
class OffRoutePlannerTest {

    private lateinit var engine: BRouterEngine
    private lateinit var planner: OffRoutePlanner

    @Before
    fun setUp() {
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue("No BRouter segments at $segments", File(segments, "E5_N60.rd5").exists())
        engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("profiles").toFile()))
        planner = OffRoutePlanner(engine)
    }

    private val WRONG_M = 6_000.0
    private val fast = RouteOptions(RoadStyle.FASTEST)

    /** Planned Voss → Bergen, but the rider took the road towards Granvin and is [alongWrong] m along it. */
    private fun wrongTurn(alongWrong: Double): Triple<Route, LatLon, Double> {
        val voss = LatLon(60.6280, 6.4180)
        val bergen = LatLon(60.3913, 5.3221)
        val granvin = LatLon(60.5560, 6.7180)
        val planned = engine.route(listOf(voss, bergen), fast)
        val wrong = engine.route(listOf(voss, granvin), fast)
        val (pos, heading) = Routes.pointAt(wrong, alongWrong)
        println("wrong-turn position is %.0f m from the planned route".format(planned.points.minOf { Geo.distance(pos.lat, pos.lon, it.lat, it.lon) }))
        return Triple(planned, pos, heading)
    }

    @Test
    fun `turning back from a wrong road goes back, ahead never turns round`() {
        val (planned, pos, heading) = wrongTurn(WRONG_M)
        val back = planner.back(pos, planned, 0.0, fast)
        println("back: %.1f km %.0f s".format(back.route.distanceMeters / 1000, back.totalSeconds))
        val ahead = planner.ahead(pos, heading, planned, 0.0, fast)
        println("ahead: " + (ahead?.let { "%.1f km %.0f s, joins at %.0f".format(it.route.distanceMeters / 1000, it.totalSeconds, it.joinAlong) } ?: "none"))
        assertNotNull("there are roads on from the wrong road", ahead)
        if (ahead != null) {
            assertFalse(OffRoutePlanner.turnsBack(ahead.route, pos, heading))
            assertTrue(ahead.totalSeconds >= back.totalSeconds - 60)
        }
        // From the wrong road, the way back starts by turning round.
        assertTrue(OffRoutePlanner.turnsBack(back.route, pos, heading))
    }

    @Test
    fun `new route from here keeps going the way the rider rides`() {
        val (_, pos, heading) = wrongTurn(WRONG_M)
        val r = planner.fromHere(pos, heading, listOf(LatLon(60.3913, 5.3221)), fast)
        println("from here: %.1f km %.1f h".format(r.distanceMeters / 1000, r.durationSeconds / 3600))
        assertFalse(OffRoutePlanner.turnsBack(r, pos, heading))
    }

    @Test
    fun `a rejoin is quick only within both limits`() {
        val r = Route(emptyList(), emptyList(), 0.0, 0.0, 0.0, 0, 0.0, 0)
        val back = WayBack(r, 0.0, 3_600.0)
        assertTrue(OffRoutePlanner.isQuick(WayBack(r, 0.0, 3_700.0), back, 180.0, 0.10))
        assertFalse(OffRoutePlanner.isQuick(WayBack(r, 0.0, 3_900.0), back, 180.0, 0.10))
        // Short trip left: the percentage is the tighter limit.
        val shortBack = WayBack(r, 0.0, 600.0)
        assertFalse(OffRoutePlanner.isQuick(WayBack(r, 0.0, 700.0), shortBack, 180.0, 0.10))
    }

    @Test
    fun `slice keeps the rest of the route`() {
        val (planned, _, _) = wrongTurn(WRONG_M)
        val tail = Routes.slice(planned, 10_000.0)
        assertEquals(planned.distanceMeters - 10_000.0, tail.distanceMeters, 200.0)
        assertEquals(planned.durationSeconds - Routes.secondsAt(planned, 10_000.0), tail.durationSeconds, 30.0)
        assertEquals(ManeuverType.ARRIVE, tail.maneuvers.last().type)
        assertTrue(abs(tail.points.first().distanceFromStart) < 1e-6)
        assertNotNull(tail.maneuvers.firstOrNull { it.type == ManeuverType.DEPART })
    }

    /**
     * Stord → Bømlo on curvy roads: the plan turns off Bremnesvegen for the loop over Folderøy, the rider goes
     * straight on (2026-11-13). The way "back" to the route was to ride on to where the loop comes out again,
     * the whole loop backwards to where it was left, and then the loop once more: a quarter of an hour for nothing.
     */
    @Test
    fun `passing a loop the route would have ridden does not send the rider round it backwards`() {
        assumeTrue(File(System.getProperty("curveon.segments") ?: "", "E5_N55.rd5").exists())
        val curvy = RouteOptions(RoadStyle.CURVY)
        val home = LatLon(59.794100, 5.144630)
        val planned = engine.route(listOf(LatLon(59.759636, 5.443022), home), curvy)
        val leftAlong = NavigationTracker(planned).update(LatLon(59.76846, 5.30879), 5.0).distanceAlongRoute
        val pos = LatLon(59.769418, 5.308527) // 110 m on along Bremnesvegen
        val fresh = engine.route(listOf(pos, home), curvy, startHeadingDeg = 350.0)

        fun check(d: OffRouteDecision, from: LatLon, label: String): Route {
            assertTrue("$label: expected a quiet new route, got $d".take(300), d is OffRouteDecision.Rejoin)
            val r = (d as OffRouteDecision.Rejoin).way.route
            println("$label: %.1f km %.0f s (a new route from the first junction is %.0f s)".format(r.distanceMeters / 1000, r.durationSeconds, fresh.durationSeconds))
            assertFalse("$label: rides the same road both ways", ridesBackOnItself(r))
            return r
        }
        val first = check(planner.decide(pos, 350.0, planned, leftAlong, curvy, 180.0, 0.10, listOf(home)), pos, "passed the first junction")
        assertTrue("no slower than a new route from here", first.durationSeconds <= fresh.durationSeconds + 60)

        // Rides straight past the next way in too (where the loop comes out), if the new route takes it.
        val next = LatLon(59.788644, 5.305060)
        val p = NavigationTracker(first).update(next, 5.0)
        if (p.offRoute) check(planner.decide(next, 290.0, first, p.distanceAlongRoute, curvy, 180.0, 0.10, listOf(home)), next, "passed the second junction")
    }

    /** True when [r] comes back along a road it has already ridden (not merely crossing it). */
    private fun ridesBackOnItself(r: Route): Boolean {
        val samples = generateSequence(0.0) { it + 50 }.takeWhile { it < r.distanceMeters }.map { it to Routes.pointAt(r, it).first }.toList()
        val again = samples.count { (d, p) -> samples.any { (e, q) -> e < d - 500 && Geo.distance(p, q) < 20 } }
        return again * 50 > 300
    }
}
