package com.curveon.routing

import kotlin.math.cos

/** Where the rider is relative to the active route. */
data class NavigationProgress(
    /** Rider position snapped onto the route. */
    val snapped: LatLon,
    val distanceAlongRoute: Double,
    val distanceFromRoute: Double,
    val nextManeuver: Maneuver,
    val distanceToNextManeuver: Double,
    /** The maneuver after [nextManeuver], for a "then …" hint when they are close together. */
    val followingManeuver: Maneuver?,
    val remainingDistance: Double,
    val remainingSeconds: Double,
    /** Signed speed limit on the current road segment, null when unknown. */
    val speedLimitKmh: Int?,
    val offRoute: Boolean,
    val arrived: Boolean
)

/**
 * Follows a rider along a [Route]: snaps GPS fixes onto the polyline, tracks the next maneuver
 * and detects when the rider has left the route. Only searches a window ahead of the last
 * known position so loops and out-and-back sections don't make it jump.
 */
class NavigationTracker(val route: Route) {

    private var lastIndex = 0
    private var offRouteStreak = 0

    fun update(position: LatLon, accuracyMeters: Double = 10.0): NavigationProgress {
        val pts = route.points
        val searchEnd = findSearchEnd(lastIndex)
        var bestIdx = lastIndex
        var bestT = 0.0
        var bestDist = Double.MAX_VALUE
        for (i in lastIndex until searchEnd) {
            val (t, d) = projectOnSegment(position, pts[i], pts[i + 1])
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
                bestT = t
            }
        }
        // Lost the route window (e.g. after a long tunnel)? Search everything once.
        if (bestDist > REJOIN_SEARCH_M) {
            for (i in 0 until pts.lastIndex) {
                val (t, d) = projectOnSegment(position, pts[i], pts[i + 1])
                if (d < bestDist) { bestDist = d; bestIdx = i; bestT = t }
            }
        }

        val a = pts[bestIdx]
        val b = pts[minOf(bestIdx + 1, pts.lastIndex)]
        val along = a.distanceFromStart + (b.distanceFromStart - a.distanceFromStart) * bestT
        val snapped = LatLon(a.lat + (b.lat - a.lat) * bestT, a.lon + (b.lon - a.lon) * bestT)

        val threshold = OFF_ROUTE_M + accuracyMeters.coerceAtMost(50.0)
        offRouteStreak = if (bestDist > threshold) offRouteStreak + 1 else 0
        val offRoute = offRouteStreak >= OFF_ROUTE_FIXES
        if (!offRoute) lastIndex = bestIdx

        val next = route.maneuvers.firstOrNull { it.distanceFromStart > along + PASSED_MANEUVER_M && it.type != ManeuverType.DEPART }
            ?: route.maneuvers.last()
        val following = route.maneuvers.firstOrNull { it.distanceFromStart > next.distanceFromStart }
        val secondsAtPos = a.secondsFromStart + (b.secondsFromStart - a.secondsFromStart) * bestT
        val remaining = (route.distanceMeters - along).coerceAtLeast(0.0)

        return NavigationProgress(
            snapped = snapped,
            distanceAlongRoute = along,
            distanceFromRoute = bestDist,
            nextManeuver = next,
            distanceToNextManeuver = (next.distanceFromStart - along).coerceAtLeast(0.0),
            followingManeuver = following,
            remainingDistance = remaining,
            remainingSeconds = (route.durationSeconds - secondsAtPos).coerceAtLeast(0.0),
            speedLimitKmh = b.maxSpeedKmh,
            offRoute = offRoute,
            arrived = remaining < ARRIVED_M
        )
    }

    private fun findSearchEnd(from: Int): Int {
        val pts = route.points
        val limit = pts[from].distanceFromStart + SEARCH_AHEAD_M
        var i = from
        while (i < pts.lastIndex && pts[i].distanceFromStart < limit) i++
        return i
    }

    /** Returns (fraction along segment 0..1, distance in metres) using a local flat projection. */
    private fun projectOnSegment(p: LatLon, a: RoutePoint, b: RoutePoint): Pair<Double, Double> {
        val kx = 111_320.0 * cos(Math.toRadians(p.lat))
        val ky = 110_574.0
        val ax = a.lon * kx; val ay = a.lat * ky
        val bx = b.lon * kx; val by = b.lat * ky
        val px = p.lon * kx; val py = p.lat * ky
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx - px
        val cy = ay + t * dy - py
        return t to Math.sqrt(cx * cx + cy * cy)
    }

    companion object {
        const val OFF_ROUTE_M = 40.0
        const val OFF_ROUTE_FIXES = 3
        const val SEARCH_AHEAD_M = 2_000.0
        const val REJOIN_SEARCH_M = 500.0
        const val PASSED_MANEUVER_M = 15.0
        const val ARRIVED_M = 30.0
    }
}
