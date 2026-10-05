package com.vibenav.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTrackerTest {

    /** An L-shaped route: 1 km north, then a right turn and 1 km east. */
    private fun lRoute(): Route {
        val start = LatLon(60.0, 10.0)
        val corner = Geo.destination(start, 0.0, 1000.0)
        val end = Geo.destination(corner, 90.0, 1000.0)
        val pts = mutableListOf<RoutePoint>()
        var d = 0.0
        var prev: LatLon? = null
        val geometry = (0..10).map { Geo.destination(start, 0.0, it * 100.0) } +
            (1..10).map { Geo.destination(corner, 90.0, it * 100.0) }
        for (p in geometry) {
            if (prev != null) d += Geo.distance(prev, p)
            pts += RoutePoint(p.lat, p.lon, 0.0, d, d / 20.0, "secondary", 80, unpaved = false, ferry = false)
            prev = p
        }
        val maneuvers = listOf(
            Maneuver(ManeuverType.DEPART, 0, 0.0),
            Maneuver(ManeuverType.RIGHT, 10, pts[10].distanceFromStart),
            Maneuver(ManeuverType.ARRIVE, pts.lastIndex, d)
        )
        check(Geo.distance(end, LatLon(pts.last().lat, pts.last().lon)) < 1.0)
        return Route(pts, maneuvers, d, d / 20.0, 0.0, 0, 0.0, 0)
    }

    @Test
    fun `tracks distance to the next turn`() {
        val route = lRoute()
        val tracker = NavigationTracker(route)
        val p = tracker.update(Geo.destination(LatLon(60.0, 10.0), 0.0, 300.0))
        assertEquals(ManeuverType.RIGHT, p.nextManeuver.type)
        assertEquals(700.0, p.distanceToNextManeuver, 2.0)
        assertEquals(1700.0, p.remainingDistance, 3.0)
        assertEquals(80, p.speedLimitKmh)
        assertFalse(p.offRoute)
    }

    @Test
    fun `after the turn the next maneuver is arrival`() {
        val route = lRoute()
        val tracker = NavigationTracker(route)
        tracker.update(Geo.destination(LatLon(60.0, 10.0), 0.0, 900.0))
        val corner = Geo.destination(LatLon(60.0, 10.0), 0.0, 1000.0)
        val p = tracker.update(Geo.destination(corner, 90.0, 400.0))
        assertEquals(ManeuverType.ARRIVE, p.nextManeuver.type)
        assertEquals(600.0, p.distanceToNextManeuver, 3.0)
    }

    @Test
    fun `needs several fixes away from the route before declaring off-route`() {
        val tracker = NavigationTracker(lRoute())
        val away = Geo.destination(Geo.destination(LatLon(60.0, 10.0), 0.0, 500.0), 270.0, 200.0)
        assertFalse(tracker.update(away).offRoute)
        assertFalse(tracker.update(away).offRoute)
        assertTrue(tracker.update(away).offRoute)
    }

    @Test
    fun `detects arrival`() {
        val route = lRoute()
        val tracker = NavigationTracker(route)
        tracker.update(Geo.destination(LatLon(60.0, 10.0), 0.0, 900.0))
        val corner = Geo.destination(LatLon(60.0, 10.0), 0.0, 1000.0)
        tracker.update(Geo.destination(corner, 90.0, 500.0))
        assertTrue(tracker.update(Geo.destination(corner, 90.0, 990.0)).arrived)
    }
}
