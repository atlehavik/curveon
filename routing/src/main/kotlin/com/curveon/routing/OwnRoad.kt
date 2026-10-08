package com.curveon.routing

/**
 * Starting (or ending) on your own road when the map doesn't join it up.
 *
 * In OpenStreetMap a driveway or farm road often ends on the cycle path along the main road, a few
 * metres from the road itself, without being connected to it. Paths can't be ridden, so to the
 * router such a road is cut off: it starts the route on the nearest road it can reach instead, maybe
 * 100 m away. Here we find the rider's own road out to where it meets the path, and if a real road
 * is only a few metres further (crossing the path, as you do in real life) the route begins on the
 * own road, crosses, and carries on. A path is never ridden along.
 */
internal class OwnRoad(private val route: (List<LatLon>, Boolean) -> Route) {

    /** [r] routed through [waypoints]; its start and end moved onto the rider's own roads where possible. */
    fun join(waypoints: List<LatLon>, r: Route): Route {
        var out = r
        runCatching { fromOwnRoad(waypoints.first(), out, waypoints.getOrNull(1)?.takeIf { waypoints.size > 2 }) }.getOrNull()?.let { out = it }
        runCatching { toOwnRoad(waypoints.last(), out, waypoints.getOrNull(waypoints.size - 2)?.takeIf { waypoints.size > 2 }) }.getOrNull()?.let { out = it }
        return out
    }

    private fun fromOwnRoad(p: LatLon, r: Route, firstVia: LatLon?): Route? {
        val start = r.points.first().at()
        val gap = Geo.distance(p, start)
        if (gap < MIN_GAP_M || gap > MAX_GAP_M) return null
        // Out along the own road, looking past paths: keep only the road part.
        val look = route(listOf(p, start), true)
        val k = (1..look.points.lastIndex).firstOrNull { look.points[it].highway in PATHS } ?: return null
        if (k < 2) return null
        val own = head(look, k - 1)
        val end = own.points.last().at()
        // From the end of the own road onto the road network, joining the route a little way on.
        val joinAt = listOfNotNull(JOIN_M, r.distanceMeters, firstVia?.let { RouteCorridor(r).alongDistance(it.lat, it.lon, 2_000.0) }).min()
        val onto = route(listOf(end, Routes.pointAt(r, joinAt).first), false)
        val crossing = Geo.distance(end, onto.points.first().at())
        if (crossing > MAX_CROSSING_M) return null
        val legs = listOf(own, straight(end, onto.points.first()), onto)
        return Routes.joinLegs(if (joinAt < r.distanceMeters - 1) legs + Routes.slice(r, joinAt) else legs)
    }

    private fun toOwnRoad(p: LatLon, r: Route, lastVia: LatLon?): Route? {
        val finish = r.points.last().at()
        val gap = Geo.distance(p, finish)
        if (gap < MIN_GAP_M || gap > MAX_GAP_M) return null
        val look = route(listOf(finish, p), true)
        val k = (1..look.points.lastIndex).lastOrNull { look.points[it].highway in PATHS } ?: return null
        if (k > look.points.lastIndex - 2) return null
        // Its first point was reached over the path in the search; to the rider it's where they cross.
        val own = Routes.slice(look, look.points[k].distanceFromStart).let { o ->
            o.copy(points = listOf(o.points.first().copy(highway = null)) + o.points.drop(1))
        }
        val begin = own.points.first().at()
        val viaAlong = lastVia?.let { RouteCorridor(r).alongDistance(it.lat, it.lon, 2_000.0) } ?: 0.0
        val leaveAt = maxOf(r.distanceMeters - JOIN_M, viaAlong, 0.0)
        val off = route(listOf(if (leaveAt > 1) Routes.pointAt(r, leaveAt).first else r.points.first().at(), begin), false)
        val crossing = Geo.distance(off.points.last().at(), begin)
        if (crossing > MAX_CROSSING_M) return null
        val legs = listOf(off, straight(off.points.last().at(), own.points.first()), own)
        return Routes.joinLegs(if (leaveAt > 1) listOf(head(r, r.points.indexOfLast { it.distanceFromStart <= leaveAt })) + legs else legs)
    }

    /** The first [last] + 1 points of [r] as a route of its own. */
    private fun head(r: Route, last: Int): Route {
        val points = r.points.subList(0, last + 1)
        val end = points.last()
        return Route(
            points = points,
            maneuvers = r.maneuvers.filter { it.pointIndex < last && it.type != ManeuverType.ARRIVE } + Maneuver(ManeuverType.ARRIVE, last, end.distanceFromStart),
            distanceMeters = end.distanceFromStart,
            durationSeconds = end.secondsFromStart,
            ascentMeters = 0.0,
            curviness = Curviness.score(points.map { LatLon(it.lat, it.lon) }),
            unpavedMeters = points.zipWithNext().filter { it.second.unpaved }.sumOf { it.second.distanceFromStart - it.first.distanceFromStart },
            ferryCount = 0
        )
    }

    /** Crossing the few metres the map leaves out (over the cycle path, onto the road). */
    private fun straight(from: LatLon, to: RoutePoint): Route {
        val d = Geo.distance(from, to.at())
        val t = d / CROSSING_SPEED_MS
        return Route(
            points = listOf(
                to.copy(lat = from.lat, lon = from.lon, distanceFromStart = 0.0, secondsFromStart = 0.0),
                // Not a road in the map, and certainly not the path: just the crossing.
                to.copy(highway = null, distanceFromStart = d, secondsFromStart = t)
            ),
            maneuvers = listOf(Maneuver(ManeuverType.DEPART, 0, 0.0), Maneuver(ManeuverType.ARRIVE, 1, d)),
            distanceMeters = d, durationSeconds = t, ascentMeters = 0.0, curviness = 0, unpavedMeters = 0.0, ferryCount = 0
        )
    }

    private fun RoutePoint.at() = LatLon(lat, lon)

    companion object {
        /** Ways that can't be ridden, only crossed. */
        val PATHS = setOf("cycleway", "footway", "path", "pedestrian", "bridleway", "steps")
        /** The route starts this far from the asked place before we look for an own road. */
        private const val MIN_GAP_M = 25.0
        private const val MAX_GAP_M = 1_500.0
        /** Further than this from the own road to a real one isn't "the map left a gap". */
        const val MAX_CROSSING_M = 30.0
        private const val JOIN_M = 2_000.0
        private const val CROSSING_SPEED_MS = 10 / 3.6
    }
}
