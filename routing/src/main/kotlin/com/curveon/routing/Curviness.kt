package com.curveon.routing

import kotlin.math.abs

/**
 * Scores how twisty a route is from its geometry alone.
 *
 * The polyline is resampled every [STEP_M] metres and we sum the absolute heading change
 * per kilometre, ignoring tiny wiggles (GPS-ish noise in OSM geometry) and sharp junction
 * turns in towns (those are not "curves"). Typical values: motorway ~20 deg/km, Norwegian
 * county road ~150 deg/km, Trollstigen/Geiranger hairpins > 400 deg/km.
 */
object Curviness {
    private const val STEP_M = 25.0
    private const val MIN_TURN_DEG = 3.0
    private const val MAX_TURN_DEG = 100.0

    /** Total relevant heading change per kilometre. */
    fun degreesPerKm(points: List<LatLon>): Double {
        val resampled = resample(points, STEP_M)
        if (resampled.size < 3) return 0.0
        var total = 0.0
        var prevBearing = Geo.bearing(resampled[0].lat, resampled[0].lon, resampled[1].lat, resampled[1].lon)
        for (i in 1 until resampled.size - 1) {
            val b = Geo.bearing(resampled[i].lat, resampled[i].lon, resampled[i + 1].lat, resampled[i + 1].lon)
            val d = abs(Geo.angleDiff(prevBearing, b))
            if (d in MIN_TURN_DEG..MAX_TURN_DEG) total += d
            prevBearing = b
        }
        val km = (resampled.size - 1) * STEP_M / 1000.0
        return if (km > 0) total / km else 0.0
    }

    /** Maps degrees/km onto a 0..100 score (saturates at ~300 deg/km). */
    fun score(points: List<LatLon>): Int = (degreesPerKm(points) / 3.0).coerceIn(0.0, 100.0).toInt()

    private fun resample(points: List<LatLon>, step: Double): List<LatLon> {
        if (points.size < 2) return points
        val out = mutableListOf(points[0])
        var carry = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val seg = Geo.distance(a, b)
            if (seg <= 0.0) continue
            var pos = step - carry
            while (pos <= seg) {
                val t = pos / seg
                out += LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
                pos += step
            }
            carry = seg - (pos - step)
        }
        return out
    }
}
