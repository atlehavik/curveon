package com.curveon.data.routing

import com.curveon.protocol.Message.CueSide
import com.curveon.protocol.Message.NavCue
import com.curveon.routing.LatLon
import com.curveon.routing.Maneuver
import com.curveon.routing.ManeuverType
import com.curveon.routing.NavigationProgress
import com.curveon.routing.Route
import org.junit.Assert.assertEquals
import org.junit.Test

class NavCuesTest {

    /** A 5 km route with a right turn at 2 km and a left at 2.1 km. */
    private fun route() = Route(
        emptyList(),
        listOf(
            Maneuver(ManeuverType.DEPART, 0, 0.0), Maneuver(ManeuverType.RIGHT, 0, 2_000.0),
            Maneuver(ManeuverType.LEFT, 0, 2_100.0), Maneuver(ManeuverType.ARRIVE, 0, 5_000.0)
        ),
        5_000.0, 300.0, 0.0, 0, 0.0, 0
    )

    private fun at(r: Route, along: Double, offRoute: Boolean = false): NavigationProgress {
        val next = r.maneuvers.firstOrNull { it.distanceFromStart > along + 15 && it.type != ManeuverType.DEPART } ?: r.maneuvers.last()
        return NavigationProgress(
            LatLon(60.0, 10.0), 0.0, along, if (offRoute) 80.0 else 2.0, next, (next.distanceFromStart - along).coerceAtLeast(0.0), null,
            r.distanceMeters - along, 0.0, null, offRoute, r.distanceMeters - along < 30
        )
    }

    /** Rides [r] from [from] to [to] at [speed] m/s, one fix a second, and collects the sounds. */
    private fun NavCues.ride(r: Route, from: Double, to: Double, speed: Double, t0: Long = 0L): List<Cue> {
        val out = mutableListOf<Cue>()
        var along = from
        var t = t0
        while (along <= to) {
            out += update(r, at(r, along), speed, asking = false, approaching = false, nowMs = t)
            along += speed; t += 1_000
        }
        return out
    }

    @Test
    fun `heads-up then tick, each once, in the ear of the turn`() {
        val r = route()
        val cues = NavCues().ride(r, 1_000.0, 2_010.0, 20.0)
        assertEquals(listOf(Cue(NavCue.PREPARE, CueSide.RIGHT), Cue(NavCue.TURN, CueSide.RIGHT)), cues.take(2))
    }

    @Test
    fun `a turn right after another gets its tick but no heads-up`() {
        val r = route()
        // 100 m after the right turn at 20 m/s: too close for a heads-up of its own.
        val cues = NavCues().ride(r, 1_000.0, 2_100.0, 20.0)
        assertEquals(listOf(NavCue.PREPARE, NavCue.TURN, NavCue.TURN), cues.map { it.sound })
        assertEquals(CueSide.LEFT, cues.last().side)
    }

    @Test
    fun `slow riding keeps the distances it has at 30 km per h`() {
        val r = route()
        val n = NavCues()
        // At 2 m/s, 200 m out: still quiet (the heads-up comes at 8 m/s x 12 s = 96 m).
        assertEquals(emptyList<Cue>(), n.update(r, at(r, 1_800.0), 2.0, false, false, 0))
        assertEquals(listOf(Cue(NavCue.PREPARE, CueSide.RIGHT)), n.update(r, at(r, 1_910.0), 2.0, false, false, 1_000))
    }

    @Test
    fun `off the route, then a new route`() {
        val r = route()
        val n = NavCues()
        assertEquals(listOf(NavCue.OFF_ROUTE), n.update(r, at(r, 500.0, offRoute = true), 15.0, false, false, 0).map { it.sound })
        assertEquals(emptyList<NavCue>(), n.update(r, at(r, 500.0, offRoute = true), 15.0, false, false, 1_000).map { it.sound })
        assertEquals(listOf(NavCue.CHOOSE), n.update(r, at(r, 500.0, offRoute = true), 15.0, true, false, 2_000).map { it.sound })
        val again = route()
        assertEquals(listOf(Cue(NavCue.BACK_ON_ROUTE, newRoute = true)), n.update(again, at(again, 0.0), 15.0, false, false, 3_000))
    }

    @Test
    fun `off the route again soon after stays quiet, back on it sounds`() {
        val r = route()
        val n = NavCues()
        n.update(r, at(r, 500.0, offRoute = true), 15.0, false, false, 0)
        assertEquals(listOf(Cue(NavCue.BACK_ON_ROUTE)), n.update(r, at(r, 510.0), 15.0, false, false, 5_000))
        assertEquals(emptyList<NavCue>(), n.update(r, at(r, 520.0, offRoute = true), 15.0, false, false, 10_000).map { it.sound })
    }

    @Test
    fun `getting to the start of the route is not off it`() {
        val r = route()
        assertEquals(emptyList<NavCue>(), NavCues().update(r, at(r, 0.0, offRoute = true), 15.0, false, true, 0).map { it.sound })
    }

    @Test
    fun `arriving sounds once`() {
        val r = route()
        val n = NavCues()
        n.update(r, at(r, 4_000.0), 15.0, false, false, 0)
        assertEquals(listOf(NavCue.ARRIVE), n.update(r, at(r, 4_980.0), 15.0, false, false, 1_000).map { it.sound })
        assertEquals(emptyList<NavCue>(), n.update(r, at(r, 4_985.0), 15.0, false, false, 2_000).map { it.sound })
    }

    @Test
    fun `each kind can be switched off`() {
        val r = route()
        val n = NavCues().apply { alerts = NavAlerts.OFF; offRouteAlerts = false }
        assertEquals(emptyList<Cue>(), n.ride(r, 1_000.0, 2_010.0, 20.0))
        assertEquals(emptyList<NavCue>(), n.update(r, at(r, 2_050.0, offRoute = true), 15.0, true, false, 99_000).map { it.sound })
    }
}
