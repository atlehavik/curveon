package com.curveon.routing

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

    @Test
    fun `corridor finds points next to the route and how far along they are`() {
        val route = lRoute()
        val c = RouteCorridor(route)
        val corner = Geo.destination(LatLon(60.0, 10.0), 0.0, 1000.0)
        val nearSecondLeg = Geo.destination(Geo.destination(corner, 90.0, 400.0), 0.0, 40.0)
        assertEquals(1400.0, c.alongDistance(nearSecondLeg.lat, nearSecondLeg.lon, 100.0)!!, 30.0)
        val farAway = Geo.destination(corner, 270.0, 800.0)
        assertEquals(null, c.alongDistance(farAway.lat, farAway.lon, 100.0))
    }

    @Test
    fun `point at distance interpolates along the route`() {
        val route = lRoute()
        val (p, bearing) = Routes.pointAt(route, 1500.0)
        val expected = Geo.destination(Geo.destination(LatLon(60.0, 10.0), 0.0, 1000.0), 90.0, 500.0)
        assertTrue(Geo.distance(p, expected) < 3.0)
        assertEquals(90.0, bearing, 1.0)
    }

    @Test
    fun `track index finds the nearest point and the track direction`() {
        val start = LatLon(60.0, 10.0)
        val track = (0..20).map { Geo.destination(start, 90.0, it * 100.0) } // 2 km heading east
        val idx = TrackIndex(track)
        val p = Geo.destination(Geo.destination(start, 90.0, 750.0), 0.0, 60.0) // 60 m north of the 750 m mark
        val hit = idx.nearest(p, 500.0)!!
        assertEquals(60.0, hit.distanceMeters, 1.0)
        assertEquals(90.0, hit.trackBearing, 1.0)
        assertTrue(Geo.distance(hit.point, Geo.destination(start, 90.0, 750.0)) < 2)
        assertEquals(null, idx.nearest(Geo.destination(start, 0.0, 3000.0), 500.0))
    }

    @Test
    fun `snaps a drifting fix onto the road and points along it`() {
        val tracker = NavigationTracker(lRoute())
        // 12 m east of the northbound leg.
        val p = tracker.update(Geo.destination(Geo.destination(LatLon(60.0, 10.0), 0.0, 400.0), 90.0, 12.0))
        assertEquals(0.0, Geo.distance(p.snapped, Geo.destination(LatLon(60.0, 10.0), 0.0, 400.0)), 1.0)
        assertEquals(0.0, p.routeBearingDeg, 1.0)
        val corner = Geo.destination(LatLon(60.0, 10.0), 0.0, 1000.0)
        assertEquals(90.0, tracker.update(Geo.destination(corner, 90.0, 300.0)).routeBearingDeg, 1.0)
    }
}
