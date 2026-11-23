package com.curveon.routing

/**
 * Getting away from (and to) the asked place when the map is wrong around it.
 *
 * Cycle paths and footpaths are never ridden. But the map isn't always right: a driveway may be
 * joined to the road network only through a cycle path in OpenStreetMap (as at Stavlandsvegen 197),
 * or the place itself may be on a path. To the router such a place is cut off, so it starts the
 * route on the nearest road it can reach instead, maybe hundreds of metres away.
 *
 * Then the first leg (and likewise the last) is worked out again with paths allowed as a last
 * resort: as little path as possible, and only when there is no road way at all. The middle of the
 * route is never touched, so it never takes a path as a short cut.
 */
internal class OwnRoad(private val route: (List<LatLon>, Boolean) -> Route) {

    /** [r] routed through [waypoints]; its start and end reaching the asked places where the map cut them off. */
    fun join(waypoints: List<LatLon>, r: Route): Route {
        var out = r
        runCatching { fromStart(waypoints.first(), out, waypoints.getOrNull(1)?.takeIf { waypoints.size > 2 }) }.getOrNull()?.let { out = it }
        runCatching { toEnd(waypoints.last(), out, waypoints.getOrNull(waypoints.size - 2)?.takeIf { waypoints.size > 2 }) }.getOrNull()?.let { out = it }
        return out
    }

    private fun fromStart(p: LatLon, r: Route, firstVia: LatLon?): Route? {
        val start = r.points.first().at()
        val gap = Geo.distance(p, start)
        if (gap < MIN_GAP_M || gap > MAX_GAP_M) return null
        // Join the route a little way on (before the first stop), so the leg can leave the place
        // whichever way it really can.
        val joinAt = listOfNotNull(JOIN_M, r.distanceMeters, firstVia?.let { RouteCorridor(r).alongDistance(it.lat, it.lon, 2_000.0) }).min()
        val leg = route(listOf(p, Routes.pointAt(r, joinAt).first), true)
        // Only worth it if it really starts nearer the place and rejoins cleanly.
        if (Geo.distance(p, leg.points.first().at()) >= gap - 1) return null
        if (joinAt >= r.distanceMeters - 1) return leg
        val tail = Routes.slice(r, joinAt)
        if (Geo.distance(leg.points.last().at(), tail.points.first().at()) > JOIN_TOLERANCE_M) return null
        return Routes.joinLegs(listOf(leg, tail))
    }

    private fun toEnd(p: LatLon, r: Route, lastVia: LatLon?): Route? {
        val finish = r.points.last().at()
        val gap = Geo.distance(p, finish)
        if (gap < MIN_GAP_M || gap > MAX_GAP_M) return null
        val viaAlong = lastVia?.let { RouteCorridor(r).alongDistance(it.lat, it.lon, 2_000.0) } ?: 0.0
        val leaveAt = maxOf(r.distanceMeters - JOIN_M, viaAlong, 0.0)
        val leg = route(listOf(if (leaveAt > 1) Routes.pointAt(r, leaveAt).first else r.points.first().at(), p), true)
        if (Geo.distance(p, leg.points.last().at()) >= gap - 1) return null
        if (leaveAt <= 1) return leg
        val prefix = head(r, r.points.indexOfLast { it.distanceFromStart <= leaveAt })
        if (Geo.distance(prefix.points.last().at(), leg.points.first().at()) > JOIN_TOLERANCE_M) return null
        return Routes.joinLegs(listOf(prefix, leg))
    }

    /** The first [last] + 1 points of [r] as a route of its own. */
    private fun head(r: Route, last: Int): Route {
        val points = r.points.subList(0, last + 1)
        val end = points.last()
        val maneuvers = r.maneuvers.filter { it.pointIndex < last && it.type != ManeuverType.ARRIVE } + Maneuver(ManeuverType.ARRIVE, last, end.distanceFromStart)
        var ascent = 0.0
        var unpaved = 0.0
        var ferries = 0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            if (b.elevation > a.elevation) ascent += b.elevation - a.elevation
            if (b.unpaved) unpaved += b.distanceFromStart - a.distanceFromStart
            if (b.ferry && !a.ferry) ferries++
        }
        return Route(
            points = points,
            maneuvers = maneuvers,
            distanceMeters = end.distanceFromStart,
            durationSeconds = end.secondsFromStart,
            ascentMeters = ascent,
            curviness = Curviness.score(points, maneuvers),
            unpavedMeters = unpaved,
            ferryCount = ferries
        )
    }

    private fun RoutePoint.at() = LatLon(lat, lon)

    companion object {
        /** Ways that are never ridden, except to get away from or to a place the map cuts off. */
        val PATHS = setOf("cycleway", "footway", "path", "pedestrian", "bridleway", "steps")
        /** The route starts this far from the asked place before we look again; further than [MAX_GAP_M] is sea or wilderness, not a cut-off road. */
        private const val MIN_GAP_M = 20.0
        private const val MAX_GAP_M = 2_000.0
        private const val JOIN_M = 2_000.0
        private const val JOIN_TOLERANCE_M = 100.0
    }
}
