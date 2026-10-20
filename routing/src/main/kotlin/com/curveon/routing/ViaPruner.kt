package com.curveon.routing

/**
 * Takes the via points a route doesn't need out of it: a converted GPX track is made with a great
 * many, to get the route right the first time, and most of them change nothing once it is made.
 * Removes blocks of points (halving the block size down to one point) and keeps a removal only if
 * the route through the rest is still the same road.
 */
object ViaPruner {

    /**
     * The points of [vias] that the route needs. [route] makes the route through the points it is
     * given; [full] is the route through all of them. At most [maxRoutes] routes are made, and it
     * stops early (returning what is found so far) when [cancelled] says so.
     */
    fun prune(
        vias: List<LatLon>, full: Route, maxRoutes: Int = MAX_ROUTES, cancelled: () -> Boolean = { false },
        route: (List<LatLon>) -> Route
    ): List<LatLon> {
        var keep = vias
        var made = 0
        var block = (vias.size + 1) / 2
        while (block >= 1 && keep.size > 0) {
            var start = 0
            while (start < keep.size) {
                if (cancelled() || made >= maxRoutes) return keep
                val end = minOf(start + block, keep.size)
                val without = keep.subList(0, start) + keep.subList(end, keep.size)
                made++
                val same = try {
                    sameRoad(full, route(without), vias.size - without.size)
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                if (same) keep = without else start = end
            }
            block /= 2
        }
        return keep
    }

    /**
     * [b] follows the same road as [a], which had [removed] more via points: each point the router
     * snapped to the road is a node of its own, so up to that many of [a]'s nodes may be missing.
     */
    fun sameRoad(a: Route, b: Route, removed: Int): Boolean {
        if (Math.abs(a.distanceMeters - b.distanceMeters) > DISTANCE_SLACK_M) return false
        val inB = HashSet<Long>(b.points.size * 2).also { s -> b.points.forEach { s += key(it) } }
        var missing = 0
        for (p in a.points) if (key(p) !in inB && ++missing > removed + NODE_SLACK) return false
        val inA = HashSet<Long>(a.points.size * 2).also { s -> a.points.forEach { s += key(it) } }
        missing = 0
        for (p in b.points) if (key(p) !in inA && ++missing > NODE_SLACK) return false
        return true
    }

    private fun key(p: RoutePoint): Long = Math.round(p.lat * 1e6) * 400_000_000L + Math.round(p.lon * 1e6)

    const val MAX_ROUTES = 80
    private const val DISTANCE_SLACK_M = 10.0
    private const val NODE_SLACK = 2
}
