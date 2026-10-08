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
}
