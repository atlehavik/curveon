package com.curveon.routing

/** Where a line (a GPX track) runs along a route, for showing it on the route line. */
object RouteOverlap {
    /** A track point this close to the route is on it (both are drawn road centrelines, near enough). */
    const val ON_ROUTE_M = 15.0

    /**
     * The stretches of [line] that lie along the route behind [corridor], in order, each of at least two points; a line
     * that only crosses the route makes none.
     */
    fun along(corridor: RouteCorridor, line: List<LatLon>, withinM: Double = ON_ROUTE_M): List<List<LatLon>> {
        val out = ArrayList<List<LatLon>>()
        var run = ArrayList<LatLon>()
        for (p in line) {
            if (corridor.alongDistance(p.lat, p.lon, withinM) != null) run.add(p)
            else {
                if (run.size >= 2) out.add(run)
                run = ArrayList()
            }
        }
        if (run.size >= 2) out.add(run)
        return out
    }
}
