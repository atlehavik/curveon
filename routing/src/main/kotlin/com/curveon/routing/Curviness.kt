package com.curveon.routing

import kotlin.math.abs

/**
 * Scores how twisty a route is from its geometry alone.
 *
 * The polyline is resampled every [STEP_M] metres and we sum the absolute heading change
 * per kilometre, ignoring tiny wiggles (GPS-ish noise in OSM geometry) and turns at junctions
 * (those are not "curves"). A junction turn is told from a bend by its shape, not its size: the
 * road turns at one point and runs straight on either side, where a bend, however tight, is
 * drawn as an arc of several points. So a hairpin counts its full 180°, and a right-angle turn
 * in a town counts nothing. Typical values: motorway ~20 deg/km, Norwegian county road
 * ~150 deg/km, Trollstigen/Geiranger hairpins > 400 deg/km.
 */
object Curviness {
    private const val STEP_M = 25.0
    private const val MIN_TURN_DEG = 3.0
    /** A turn at least this sharp, made at a single point of the line, is a junction rather than a bend. */
    private const val CORNER_MIN_DEG = 45.0
    /** ...when that point holds this share of all the turning within [CORNER_WINDOW_M] of it. */
    private const val CORNER_SHARE = 0.7
    private const val CORNER_WINDOW_M = 12.0

    /** Total relevant heading change per kilometre. */
    fun degreesPerKm(points: List<LatLon>): Double {
        val line = points.filterIndexed { i, p -> i == 0 || Geo.distance(points[i - 1], p) > 0.0 }
        val along = DoubleArray(line.size)
        for (i in 1 until line.size) along[i] = along[i - 1] + Geo.distance(line[i - 1], line[i])
        val corners = corners(line, along)
        val resampled = resample(line, STEP_M)
        if (resampled.size < 3) return 0.0
        var total = 0.0
        var next = 0 // first corner not yet behind the current step
        var prevBearing = Geo.bearing(resampled[0].lat, resampled[0].lon, resampled[1].lat, resampled[1].lon)
        for (i in 1 until resampled.size - 1) {
            val b = Geo.bearing(resampled[i].lat, resampled[i].lon, resampled[i + 1].lat, resampled[i + 1].lon)
            val d = abs(Geo.angleDiff(prevBearing, b))
            prevBearing = b
            // Sample i sits i steps along the line; this step's two chords span the samples either side of it.
            val from = (i - 1) * STEP_M
            val to = (i + 1) * STEP_M
            while (next < corners.size && corners[next] <= from) next++
            val atJunction = next < corners.size && corners[next] < to
            if (d >= MIN_TURN_DEG && !atJunction) total += d
        }
        val km = (resampled.size - 1) * STEP_M / 1000.0
        return if (km > 0) total / km else 0.0
    }

    /** Maps degrees/km onto a 0..100 score (saturates at ~300 deg/km). */
    fun score(points: List<LatLon>): Int = (degreesPerKm(points) / 3.0).coerceIn(0.0, 100.0).toInt()

    /** How far along [line] each junction turn is (see the class comment), in order. */
    private fun corners(line: List<LatLon>, along: DoubleArray): List<Double> {
        if (line.size < 3) return emptyList()
        val turn = DoubleArray(line.size)
        for (v in 1 until line.size - 1) {
            val a = Geo.bearing(line[v - 1].lat, line[v - 1].lon, line[v].lat, line[v].lon)
            val b = Geo.bearing(line[v].lat, line[v].lon, line[v + 1].lat, line[v + 1].lon)
            turn[v] = abs(Geo.angleDiff(a, b))
        }
        val out = ArrayList<Double>()
        for (v in 1 until line.size - 1) {
            if (turn[v] < CORNER_MIN_DEG) continue
            var near = 0.0
            var u = v
            while (u > 0 && along[v] - along[u] <= CORNER_WINDOW_M) near += turn[u--]
            u = v + 1
            while (u < line.size - 1 && along[u] - along[v] <= CORNER_WINDOW_M) near += turn[u++]
            if (turn[v] >= CORNER_SHARE * near) out += along[v]
        }
        return out
    }

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
