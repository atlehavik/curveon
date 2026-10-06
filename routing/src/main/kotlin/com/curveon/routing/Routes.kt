package com.curveon.routing

object Routes {
    /** Position and travel bearing at [distance] metres along [route] (clamped to its ends). */
    fun pointAt(route: Route, distance: Double): Pair<LatLon, Double> {
        val pts = route.points
        val d = distance.coerceIn(0.0, route.distanceMeters)
        var lo = 0
        var hi = pts.lastIndex
        while (hi - lo > 1) { // binary search for the segment containing d
            val mid = (lo + hi) / 2
            if (pts[mid].distanceFromStart <= d) lo = mid else hi = mid
        }
        val a = pts[lo]
        val b = pts[hi]
        val span = b.distanceFromStart - a.distanceFromStart
        val f = if (span > 0) (d - a.distanceFromStart) / span else 0.0
        return LatLon(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f) to Geo.bearing(a.lat, a.lon, b.lat, b.lon)
    }

    /** Joins [b] onto the end of [a] (b should start where a ends). */
    fun concat(a: Route, b: Route): Route {
        val offsetD = a.distanceMeters
        val offsetT = a.durationSeconds
        val shift = a.points.size - 1 // b's first point replaces a's last point
        val points = a.points.dropLast(1) + b.points.map {
            it.copy(distanceFromStart = it.distanceFromStart + offsetD, secondsFromStart = it.secondsFromStart + offsetT)
        }
        val maneuvers = a.maneuvers.filter { it.type != ManeuverType.ARRIVE } +
            b.maneuvers.filter { it.type != ManeuverType.DEPART }.map {
                it.copy(pointIndex = it.pointIndex + shift, distanceFromStart = it.distanceFromStart + offsetD)
            }
        val total = offsetD + b.distanceMeters
        return Route(
            points = points,
            maneuvers = maneuvers,
            distanceMeters = total,
            durationSeconds = offsetT + b.durationSeconds,
            ascentMeters = a.ascentMeters + b.ascentMeters,
            curviness = if (total > 0) ((a.curviness * a.distanceMeters + b.curviness * b.distanceMeters) / total).toInt() else 0,
            unpavedMeters = a.unpavedMeters + b.unpavedMeters,
            ferryCount = a.ferryCount + b.ferryCount
        )
    }
}
