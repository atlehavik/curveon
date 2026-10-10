package com.curveon.routing

object Routes {

    /** The farthest [route] gets from [other] (metres), sampled every ~200 m. */
    fun farthestFrom(route: Route, other: Route): Double {
        fun samples(r: Route, step: Double): List<LatLon> {
            val out = ArrayList<LatLon>()
            var next = 0.0
            for (p in r.points) if (p.distanceFromStart >= next) { out += LatLon(p.lat, p.lon); next = p.distanceFromStart + step }
            return out
        }
        val ref = samples(other, 150.0)
        if (ref.isEmpty()) return 0.0
        val kx = Math.cos(Math.toRadians(ref[0].lat)) * 111_320.0
        return samples(route, 200.0).maxOfOrNull { p ->
            ref.minOf { q -> val dx = (p.lon - q.lon) * kx; val dy = (p.lat - q.lat) * 110_574.0; dx * dx + dy * dy }
        }?.let { Math.sqrt(it) } ?: 0.0
    }
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

    /** Seconds from the start of [route] to [distance] metres along it. */
    fun secondsAt(route: Route, distance: Double): Double {
        val pts = route.points
        val i = pts.indexOfFirst { it.distanceFromStart >= distance }
        if (i <= 0) return if (i == 0) 0.0 else route.durationSeconds
        val a = pts[i - 1]
        val b = pts[i]
        val span = b.distanceFromStart - a.distanceFromStart
        val f = if (span > 0) (distance - a.distanceFromStart) / span else 0.0
        return a.secondsFromStart + (b.secondsFromStart - a.secondsFromStart) * f
    }

    /** The part of [route] from [fromDistance] metres on, as a route of its own. */
    fun slice(route: Route, fromDistance: Double): Route {
        val pts = route.points
        val start = pts.indexOfLast { it.distanceFromStart <= fromDistance }.coerceIn(0, maxOf(pts.size - 2, 0))
        val d0 = pts[start].distanceFromStart
        val t0 = pts[start].secondsFromStart
        val points = pts.subList(start, pts.size).map { it.copy(distanceFromStart = it.distanceFromStart - d0, secondsFromStart = it.secondsFromStart - t0) }
        val maneuvers = listOf(Maneuver(ManeuverType.DEPART, 0, 0.0)) + route.maneuvers
            .filter { it.pointIndex > start && it.type != ManeuverType.DEPART }
            .map { it.copy(pointIndex = it.pointIndex - start, distanceFromStart = it.distanceFromStart - d0) }
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
            distanceMeters = points.last().distanceFromStart,
            durationSeconds = points.last().secondsFromStart,
            ascentMeters = ascent,
            curviness = Curviness.score(points.map { LatLon(it.lat, it.lon) }),
            unpavedMeters = unpaved,
            ferryCount = ferries
        )
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

    /**
     * Joins legs routed one by one into one route. A leg's end and the next leg's start are the same
     * via point; a turn there is added from the geometry (the router only gives turns inside a leg).
     */
    fun joinLegs(legs: List<Route>): Route {
        var out = legs.first()
        for (leg in legs.drop(1)) {
            val joinIndex = out.points.lastIndex
            val turn = turnAt(out, leg)
            out = concat(out, leg)
            if (turn != null) {
                out = out.copy(maneuvers = (out.maneuvers + Maneuver(turn, joinIndex, out.points[joinIndex].distanceFromStart))
                    .sortedBy { it.pointIndex })
            }
        }
        return out.copy(curviness = Curviness.score(out.points.map { LatLon(it.lat, it.lon) }))
    }

    /** The turn from the end of [a] into the start of [b], or null when riding on (roughly) straight. */
    private fun turnAt(a: Route, b: Route): ManeuverType? {
        val (inPoint, inBearing) = pointAt(a, a.distanceMeters - 30.0)
        val (_, outBearing) = pointAt(b, minOf(30.0, b.distanceMeters))
        if (a.distanceMeters < 1 || b.distanceMeters < 1) return null
        val end = a.points.last()
        val arrive = Geo.bearing(inPoint.lat, inPoint.lon, end.lat, end.lon).takeIf { Geo.distance(inPoint.lat, inPoint.lon, end.lat, end.lon) > 1 } ?: inBearing
        val d = Geo.angleDiff(arrive, outBearing)
        val abs = kotlin.math.abs(d)
        return when {
            abs < 30 -> null
            abs > 165 -> ManeuverType.U_TURN
            abs < 60 -> if (d > 0) ManeuverType.SLIGHT_RIGHT else ManeuverType.SLIGHT_LEFT
            abs < 130 -> if (d > 0) ManeuverType.RIGHT else ManeuverType.LEFT
            else -> if (d > 0) ManeuverType.SHARP_RIGHT else ManeuverType.SHARP_LEFT
        }
    }
}
