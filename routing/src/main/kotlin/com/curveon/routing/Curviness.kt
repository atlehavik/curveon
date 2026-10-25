package com.curveon.routing

import kotlin.math.abs

/**
 * Scores how twisty a route is from its geometry.
 *
 * The polyline is resampled every [STEP_M] metres and we sum the absolute heading change
 * per kilometre, ignoring tiny wiggles (GPS-ish noise in OSM geometry) and turns at junctions
 * (those are not "curves"). Where the junctions are comes from the route's turn instructions,
 * the turns the rider is told about: from the shape alone a bend drawn with a point or two
 * (common on small and gravel roads) looks just like a street corner. Everything else counts in
 * full, so a hairpin counts its 180° however coarsely it is drawn. Typical values: motorway
 * ~20 deg/km, Norwegian county road ~150 deg/km, Trollstigen/Geiranger hairpins > 400 deg/km.
 */
object Curviness {
    private const val STEP_M = 25.0
    private const val MIN_TURN_DEG = 3.0

    /** Maneuvers that are no turn at a junction. */
    private val NOT_TURNS = setOf(ManeuverType.DEPART, ManeuverType.ARRIVE, ManeuverType.STRAIGHT)

    /** Total relevant heading change per kilometre; [junctions] are the indexes in [points] where the route turns at a junction. */
    fun degreesPerKm(points: List<LatLon>, junctions: Collection<Int> = emptyList()): Double {
        val resampled = resample(points, STEP_M)
        if (resampled.size < 3) return 0.0
        val along = DoubleArray(points.size)
        for (i in 1 until points.size) along[i] = along[i - 1] + Geo.distance(points[i - 1], points[i])
        val corners = junctions.filter { it in points.indices }.map { along[it] }.sorted()
        var total = 0.0
        var next = 0 // first junction not yet behind the current step
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
    fun score(points: List<LatLon>, junctions: Collection<Int> = emptyList()): Int =
        (degreesPerKm(points, junctions) / 3.0).coerceIn(0.0, 100.0).toInt()

    /** The score of a routed line, its [maneuvers] saying where it turns at junctions. */
    fun score(points: List<RoutePoint>, maneuvers: List<Maneuver>): Int =
        score(points.map { LatLon(it.lat, it.lon) }, junctions(maneuvers))

    /** The indexes where [maneuvers] turn at a junction. */
    fun junctions(maneuvers: List<Maneuver>): List<Int> = maneuvers.filter { it.type !in NOT_TURNS }.map { it.pointIndex }

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
