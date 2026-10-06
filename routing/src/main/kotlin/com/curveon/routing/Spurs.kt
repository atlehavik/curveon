package com.curveon.routing

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Finds out-and-back "spurs": places where a route rides down a road and comes straight back the
 * same way (typically because a via point snapped onto a dead end). Seen from the route, a spur is
 * a run of sample pairs (before the turnaround, after it) that lie on top of each other, mirrored
 * around the turnaround.
 */
object Spurs {

    data class Spur(
        /** Where the route turns around. */
        val turnaround: LatLon,
        val turnaroundAlong: Double,
        /** Length of the spur one way (the detour is twice this). */
        val halfLength: Double,
        /** Where the spur leaves the rest of the route (the junction to ride past instead). */
        val base: LatLon
    )

    private const val STEP_M = 10.0
    private const val SAME_PLACE_M = 20.0
    private const val MIN_HALF_M = 100.0
    private const val ENDS_M = 300.0

    /**
     * A spur has a turnaround: the route reverses (U-turn) somewhere that isn't its start or end.
     * From there we walk backwards and forwards in step while the two sides stay on top of each
     * other; how far that goes is the spur's length.
     */
    fun find(route: Route): List<Spur> {
        val s = resample(route)
        if (s.size < 10) return emptyList()
        val kx = 111_320.0 * cos(Math.toRadians(s[0].lat))
        fun dist(a: Sample, b: Sample): Double {
            val dx = (a.lon - b.lon) * kx
            val dy = (a.lat - b.lat) * 110_574.0
            return sqrt(dx * dx + dy * dy)
        }
        val found = mutableListOf<Spur>()
        var k = 3
        while (k < s.size - 3) {
            val t = s[k]
            if (t.along < ENDS_M || t.along > route.distanceMeters - ENDS_M) { k++; continue }
            // Reversal: the points 30 m before and 30 m after the candidate are (nearly) the same place.
            if (dist(s[k - 3], s[k + 3]) > SAME_PLACE_M) { k++; continue }
            var d = 3
            while (k - d - 1 >= 0 && k + d + 1 < s.size && dist(s[k - d - 1], s[k + d + 1]) <= SAME_PLACE_M) d++
            val half = d * STEP_M
            if (half >= MIN_HALF_M) found += Spur(LatLon(t.lat, t.lon), t.along, half, LatLon(s[k - d].lat, s[k - d].lon))
            k += d + 1 // skip past this spur
        }
        return found
    }

    /** Total metres ridden twice because of spurs. */
    fun wastedMeters(route: Route) = find(route).sumOf { it.halfLength * 2 }

    private class Sample(val lat: Double, val lon: Double, val along: Double)

    private fun resample(route: Route): List<Sample> {
        val pts = route.points
        val out = ArrayList<Sample>()
        var next = 0.0
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val len = b.distanceFromStart - a.distanceFromStart
            while (len > 0 && next <= b.distanceFromStart) {
                val f = (next - a.distanceFromStart) / len
                out += Sample(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f, next)
                next += STEP_M
            }
        }
        return out
    }
}
