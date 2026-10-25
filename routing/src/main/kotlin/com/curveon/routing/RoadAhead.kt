package com.curveon.routing

import kotlin.math.abs

/**
 * Not navigating: the road a rider is on for the next few hundred metres, as a short route towards a point
 * straight ahead (the router keeps to roads), for what stands along it (speed limit signs). Where the road
 * bends, the point straight ahead is off it, so shorter probes are tried too. A probe that doesn't start
 * at the rider, goes another way than the rider or takes a detour is not the road the rider is on: none then,
 * never signs of some other road.
 */
object RoadAhead {
    private val LENGTHS_M = listOf(700.0, 500.0, 300.0)
    private const val START_M = 30.0
    private const val SNAP_M = 100.0
    /** The probe's first stretch may point this far from the rider's heading. */
    private const val WAY_DEG = 60.0
    /** A way that is longer than this times the straight line is a detour round something, not the road ahead. */
    private const val DETOUR = 1.4
    /** How far along the probe its direction is read. */
    private const val DIRECTION_AT_M = 100.0

    fun probe(engine: BRouterEngine, position: LatLon, headingDeg: Double): Route? {
        for (length in LENGTHS_M) {
            val route = runCatching {
                engine.route(
                    listOf(position, Geo.destination(position, headingDeg, length)), RouteOptions(RoadStyle.FASTEST),
                    viaCatchingRangeMeters = SNAP_M, startHeadingDeg = headingDeg
                )
            }.getOrNull() ?: continue
            if (isRoadAhead(route, position, headingDeg, length)) return route
        }
        return null
    }

    /** Whether [route], made towards a point [aheadM] metres from [position] in direction [headingDeg], is the road ahead. */
    fun isRoadAhead(route: Route, position: LatLon, headingDeg: Double, aheadM: Double): Boolean {
        val first = route.points.firstOrNull() ?: return false
        if (Geo.distance(first.lat, first.lon, position.lat, position.lon) > START_M) return false
        if (route.distanceMeters > aheadM * DETOUR) return false
        val far = route.points.firstOrNull { it.distanceFromStart >= DIRECTION_AT_M } ?: return false
        val way = Geo.bearing(first.lat, first.lon, far.lat, far.lon)
        return abs(Geo.angleDiff(headingDeg, way)) <= WAY_DEG
    }
}
