package com.vibenav.routing

object Routes {
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
