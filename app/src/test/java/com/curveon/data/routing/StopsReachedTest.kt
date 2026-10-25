package com.curveon.data.routing

import com.curveon.routing.Geo
import com.curveon.routing.LatLon
import com.curveon.routing.Maneuver
import com.curveon.routing.ManeuverType
import com.curveon.routing.NavigationTracker
import com.curveon.routing.Route
import com.curveon.routing.RouteCorridor
import com.curveon.routing.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-25, McDonald's on Stord: the road passes the stop at 60 m, the route goes on round a roundabout
 * and reaches the stop from the other side. "Reached" was said on the road, 750 m too early.
 */
class StopsReachedTest {
    private val start = LatLon(60.0, 10.0)
    private val stop = Stop("McDonald's", Geo.destination(Geo.destination(start, 90.0, 500.0), 0.0, 60.0))

    /** 1 km east, 60 m north, 1 km back west: passes the stop at 60 m going east, and at 0 m going west. */
    private fun route(): Route {
        val eastEnd = Geo.destination(start, 90.0, 1000.0)
        val back = Geo.destination(eastEnd, 0.0, 60.0)
        val geometry = (0..10).map { Geo.destination(start, 90.0, it * 100.0) } + back + (1..10).map { Geo.destination(back, 270.0, it * 100.0) }
        val pts = mutableListOf<RoutePoint>()
        var d = 0.0
        var prev: LatLon? = null
        for (p in geometry) {
            if (prev != null) d += Geo.distance(prev, p)
            pts += RoutePoint(p.lat, p.lon, 0.0, d, d / 20.0, "primary", 80, unpaved = false, ferry = false)
            prev = p
        }
        return Route(pts, listOf(Maneuver(ManeuverType.DEPART, 0, 0.0), Maneuver(ManeuverType.ARRIVE, pts.lastIndex, d)), d, d / 20.0, 0.0, 0, 0.0, 0)
    }

    /** Where along the route (every 10 m, a fix each) the stop is said to be reached. */
    private fun reachedAt(route: Route, stops: List<Stop>): List<Double> {
        val tracker = NavigationTracker(route)
        val stopsAlong = stops.zip(alongDistances(stops, route))
        val at = mutableListOf<Double>()
        var d = 0.0
        while (d < route.distanceMeters) {
            val i = route.points.indexOfLast { it.distanceFromStart <= d }.coerceAtMost(route.points.size - 2)
            val a = route.points[i]; val b = route.points[i + 1]
            val t = (d - a.distanceFromStart) / (b.distanceFromStart - a.distanceFromStart)
            val p = tracker.update(LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t))
            if (stopsReached(stopsAlong, p).isNotEmpty()) at += d
            d += 10.0
        }
        return at
    }

    @Test
    fun `a stop is reached where the route gets to it, not on the road that passes it`() {
        val route = route()
        val at = reachedAt(route, listOf(stop))
        assertTrue("never reached", at.isNotEmpty())
        // The stop is at 1560 m along; nothing is said on the first pass (500 m).
        assertTrue("reached too early, at ${at.first()}", at.first() > 1500.0)
        assertEquals(1560.0, at.first(), 40.0)
    }

    @Test
    fun `a via point is never reached as a stop`() {
        assertTrue(reachedAt(route(), listOf(stop.copy(via = true))).isEmpty())
    }

    @Test
    fun `nothing is reached while off the route`() {
        val route = route()
        val along = alongDistances(listOf(stop), route)
        val tracker = NavigationTracker(route)
        val far = Geo.destination(Geo.destination(start, 90.0, 1000.0), 0.0, 400.0)
        repeat(5) { tracker.update(far) }
        val p = tracker.update(far)
        assertTrue(p.offRoute)
        assertTrue(stopsReached(listOf(stop to along[0]), p).isEmpty())
        // The same corridor answer the app uses.
        assertEquals(1560.0, RouteCorridor(route).alongClosest(stop.point.lat, stop.point.lon, 2_000.0)!!, 40.0)
    }
}
