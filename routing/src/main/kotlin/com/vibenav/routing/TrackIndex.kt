package com.vibenav.routing

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Nearest point on a polyline (a GPX track), fast enough to ask every GPS fix: segments are
 * bucketed into a grid so only nearby ones are checked.
 */
class TrackIndex(private val points: List<LatLon>) {

    data class Hit(
        /** Closest point on the track (projected onto the segment). */
        val point: LatLon,
        val distanceMeters: Double,
        /** Index of the segment start; the track runs from [segment] towards [segment] + 1. */
        val segment: Int,
        /** Bearing of the track at that point, in its own direction. */
        val trackBearing: Double
    )

    private val kx = 111_320.0 * cos(Math.toRadians(points.firstOrNull()?.lat ?: 60.0))
    private val grid = HashMap<Long, MutableList<Int>>()

    init {
        for (i in 0 until points.size - 1) {
            val a = points[i]; val b = points[i + 1]
            val y0 = cellY(minOf(a.lat, b.lat)); val y1 = cellY(maxOf(a.lat, b.lat))
            val x0 = cellX(minOf(a.lon, b.lon)); val x1 = cellX(maxOf(a.lon, b.lon))
            for (y in y0..y1) for (x in x0..x1) grid.getOrPut(key(y, x)) { mutableListOf() } += i
        }
    }

    private fun cellY(lat: Double) = floor(lat / CELL).toLong()
    private fun cellX(lon: Double) = floor(lon * kx / 110_574.0 / CELL).toLong()
    private fun key(y: Long, x: Long) = (y shl 32) xor (x and 0xffffffffL)

    /** Closest point within [maxMeters], or null. */
    fun nearest(p: LatLon, maxMeters: Double): Hit? {
        if (points.size < 2) return null
        val r = (maxMeters / (CELL * 110_574.0)).toLong() + 1
        val cy = cellY(p.lat); val cx = cellX(p.lon)
        var best: Hit? = null
        val seen = HashSet<Int>()
        for (y in cy - r..cy + r) for (x in cx - r..cx + r) {
            for (i in grid[key(y, x)] ?: continue) {
                if (!seen.add(i)) continue
                val a = points[i]; val b = points[i + 1]
                val ax = a.lon * kx; val ay = a.lat * 110_574.0
                val bx = b.lon * kx; val by = b.lat * 110_574.0
                val px = p.lon * kx; val py = p.lat * 110_574.0
                val dx = bx - ax; val dy = by - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
                val qx = ax + t * dx; val qy = ay + t * dy
                val d = sqrt((qx - px) * (qx - px) + (qy - py) * (qy - py))
                if (d <= maxMeters && (best == null || d < best.distanceMeters)) {
                    best = Hit(LatLon(qy / 110_574.0, qx / kx), d, i, Geo.bearing(a.lat, a.lon, b.lat, b.lon))
                }
            }
        }
        return best
    }

    companion object {
        private const val CELL = 0.005 // ~550 m
    }
}
