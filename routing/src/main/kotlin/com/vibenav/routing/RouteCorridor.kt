package com.vibenav.routing

import kotlin.math.cos
import kotlin.math.floor

/**
 * Answers "is this point next to the route, and how far along is it?" quickly, for things like
 * fuel stations and speed cameras. The route is sampled every [stepMeters] into a grid.
 */
class RouteCorridor(route: Route, private val stepMeters: Double = 50.0) {

    private class Sample(val lat: Double, val lon: Double, val along: Double)

    private val grid = HashMap<Long, MutableList<Sample>>()
    private val kx = 111_320.0 * cos(Math.toRadians(route.points.firstOrNull()?.lat ?: 60.0))

    val minLat: Double
    val maxLat: Double
    val minLon: Double
    val maxLon: Double

    init {
        var next = 0.0
        var aLat = Double.MAX_VALUE; var bLat = -Double.MAX_VALUE; var aLon = Double.MAX_VALUE; var bLon = -Double.MAX_VALUE
        val pts = route.points
        for (i in pts.indices) {
            val p = pts[i]
            aLat = minOf(aLat, p.lat); bLat = maxOf(bLat, p.lat); aLon = minOf(aLon, p.lon); bLon = maxOf(bLon, p.lon)
            if (i == 0) { add(Sample(p.lat, p.lon, 0.0)); continue }
            val prev = pts[i - 1]
            val segLen = p.distanceFromStart - prev.distanceFromStart
            while (next <= p.distanceFromStart && segLen > 0) {
                val f = (next - prev.distanceFromStart) / segLen
                add(Sample(prev.lat + (p.lat - prev.lat) * f, prev.lon + (p.lon - prev.lon) * f, next))
                next += stepMeters
            }
        }
        minLat = aLat; maxLat = bLat; minLon = aLon; maxLon = bLon
    }

    private fun key(lat: Double, lon: Double): Long =
        (floor(lat / CELL_DEG).toLong() shl 32) xor (floor(lon / (CELL_DEG * 2)).toLong() and 0xffffffffL)

    private fun add(s: Sample) {
        grid.getOrPut(key(s.lat, s.lon)) { mutableListOf() } += s
    }

    /**
     * Distance along the route of the route sample closest to (lat, lon), if that sample is within
     * [maxOffsetMeters]. When the route passes several times (loops), the first pass after
     * [afterAlong] wins.
     */
    fun alongDistance(lat: Double, lon: Double, maxOffsetMeters: Double, afterAlong: Double = 0.0): Double? {
        var best: Double? = null
        var bestOffset = Double.MAX_VALUE
        val cy = floor(lat / CELL_DEG).toLong()
        val cx = floor(lon / (CELL_DEG * 2)).toLong()
        for (dy in -1L..1L) for (dx in -1L..1L) {
            val k = ((cy + dy) shl 32) xor ((cx + dx) and 0xffffffffL)
            for (s in grid[k] ?: continue) {
                if (s.along < afterAlong) continue
                val ox = (s.lon - lon) * kx
                val oy = (s.lat - lat) * 110_574.0
                val off = Math.sqrt(ox * ox + oy * oy)
                if (off > maxOffsetMeters + stepMeters / 2) continue
                // Prefer the earliest pass; among samples of the same pass, the closest.
                if (best == null || s.along < best - 500 || (Math.abs(s.along - best) <= 500 && off < bestOffset)) {
                    best = s.along
                    bestOffset = off
                }
            }
        }
        return best
    }

    companion object {
        private const val CELL_DEG = 0.01 // ~1.1 km north-south
    }
}
