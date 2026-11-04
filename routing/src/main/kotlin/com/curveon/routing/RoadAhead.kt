package com.curveon.routing

import kotlin.math.abs

/**
 * Not navigating: the road a rider is on for the next few hundred metres, as a short route towards a point
 * straight ahead (the router keeps to roads), for what stands along it (speed limit, give way and stop signs). Where
 * the road bends, the point straight ahead is off it or on a road beside it, and the route turns off at a junction the
 * rider rides straight through: shorter probes are tried too, and points to each side of straight ahead, and the one
 * going furthest before it turns wins (on a straight road the first will do: one route). A probe that doesn't start at the rider, goes another way than the rider or takes a detour is not the road the rider is on:
 * none then, never signs of some other road.
 */
object RoadAhead {
    /** Where to look, as (how far ahead, degrees off straight ahead), in the order they are tried. */
    private val TARGETS = listOf(700.0 to 0.0, 500.0 to 0.0, 300.0 to 0.0, 500.0 to 30.0, 500.0 to -30.0)
    private const val START_M = 30.0
    private const val SNAP_M = 100.0
    /** The probe's first stretch may point this far from the rider's heading. */
    private const val WAY_DEG = 60.0
    /** And right where it starts, this far: more, and it starts on a side road next to the rider, not on their road. */
    private const val START_WAY_DEG = 50.0
    private const val START_WAY_M = 25.0
    /** A way that is longer than this times the straight line is a detour round something, not the road ahead. */
    private const val DETOUR = 1.4
    /** How far along the probe its direction is read. */
    private const val DIRECTION_AT_M = 100.0

    fun probe(engine: BRouterEngine, position: LatLon, headingDeg: Double): Route? {
        var best: Route? = null
        var bestClear = 0.0
        for ((length, off) in TARGETS) {
            val route = runCatching {
                engine.route(
                    listOf(position, Geo.destination(position, headingDeg + off, length)), RouteOptions(RoadStyle.FASTEST),
                    viaCatchingRangeMeters = SNAP_M, startHeadingDeg = headingDeg
                )
            }.getOrNull() ?: continue
            if (!isRoadAhead(route, position, headingDeg, length)) continue
            val leaves = SpeedSigns.leavesRoadAt(route) ?: return route // straight on all the way: the usual case, one route
            if (best == null || leaves > bestClear) { best = route; bestClear = leaves }
        }
        return best
    }

    /** Whether [route], made towards a point [aheadM] metres from [position] in direction [headingDeg], is the road ahead. */
    fun isRoadAhead(route: Route, position: LatLon, headingDeg: Double, aheadM: Double): Boolean {
        val first = route.points.firstOrNull() ?: return false
        if (Geo.distance(first.lat, first.lon, position.lat, position.lon) > START_M) return false
        if (route.distanceMeters > aheadM * DETOUR) return false
        val start = route.points.firstOrNull { it.distanceFromStart >= START_WAY_M } ?: return false
        if (abs(Geo.angleDiff(headingDeg, Geo.bearing(first.lat, first.lon, start.lat, start.lon))) > START_WAY_DEG) return false
        val far = route.points.firstOrNull { it.distanceFromStart >= DIRECTION_AT_M } ?: return false
        val way = Geo.bearing(first.lat, first.lon, far.lat, far.lon)
        return abs(Geo.angleDiff(headingDeg, way)) <= WAY_DEG
    }
}
