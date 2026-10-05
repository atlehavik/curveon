package com.vibenav.routing

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor

/**
 * GoRide-style roundtrips: "about 150 km, heading north-east".
 *
 * We lay a circle that touches the start and extends in the chosen direction, put a few via
 * points on it and let the router connect them. Roads are longer than the circle, so the radius
 * is corrected once after the first attempt. Several rotated candidates are tried in parallel
 * and the best one wins: right length, little riding the same road twice, and – for curvy
 * styles – the most curves.
 */
class RoundtripPlanner(private val engine: BRouterEngine) {

    data class Candidate(val route: Route, val score: Double, val rotationDeg: Double)

    fun plan(
        start: LatLon,
        distanceKm: Double,
        directionDeg: Double,
        options: RouteOptions,
        rotationsDeg: List<Double> = listOf(0.0, -30.0, 30.0)
    ): Route {
        val pool = Executors.newFixedThreadPool(rotationsDeg.size)
        try {
            val futures = rotationsDeg.map { rot ->
                pool.submit(Callable { runCatching { candidate(start, distanceKm * 1000, directionDeg + rot, rot, options) } })
            }
            val results = futures.map { it.get() }
            return results.mapNotNull { it.getOrNull() }.maxByOrNull { it.score }?.route
                ?: throw RoutingException(
                    "Could not find a roundtrip in that direction (" +
                        results.firstNotNullOfOrNull { it.exceptionOrNull()?.message } + ")"
                )
        } finally {
            pool.shutdownNow()
        }
    }

    private fun candidate(start: LatLon, targetM: Double, directionDeg: Double, rotation: Double, options: RouteOptions): Candidate {
        var radius = targetM / (2 * PI * ROAD_DETOUR_FACTOR)
        var route = loop(start, radius, directionDeg, options)
        val error = route.distanceMeters / targetM
        if (abs(error - 1.0) > 0.15) {
            radius /= error
            route = loop(start, radius, directionDeg, options)
        }
        return Candidate(route, score(route, targetM, options), rotation)
    }

    /** Routes one loop; if a via point lands on an unreachable bit of road, nudges the circle and retries. */
    private fun loop(start: LatLon, radius: Double, directionDeg: Double, options: RouteOptions): Route {
        var lastError: RoutingException? = null
        for ((scale, turn) in NUDGES) {
            val r = radius * scale
            val center = Geo.destination(start, directionDeg + turn, r)
            val startAngle = directionDeg + turn + 180.0
            val vias = (1..VIA_POINTS).map { k -> Geo.destination(center, startAngle + k * 360.0 / (VIA_POINTS + 1), r) }
            try {
                return engine.route(listOf(start) + vias + start, options, viaCatchingRangeMeters = VIA_CATCHING_RANGE_M)
            } catch (e: RoutingException) {
                lastError = e
            }
        }
        throw lastError!!
    }

    internal fun score(route: Route, targetM: Double, options: RouteOptions): Double {
        val lengthPenalty = abs(route.distanceMeters / targetM - 1.0) * 100     // 10% off = -10
        val overlapPenalty = overlapFraction(route) * 150                          // 20% same road twice = -30
        val curveBonus = if (options.roadStyle == RoadStyle.FASTEST) 0.0 else route.curviness.toDouble()
        return curveBonus - lengthPenalty - overlapPenalty
    }

    companion object {
        private const val ROAD_DETOUR_FACTOR = 1.3
        private const val VIA_POINTS = 3
        private const val VIA_CATCHING_RANGE_M = 5000.0
        private val NUDGES = listOf(1.0 to 0.0, 0.85 to 8.0, 1.15 to -8.0)

        /** Share of the route (by sample) that runs within ~40 m of an earlier, non-adjacent part of itself. */
        fun overlapFraction(route: Route): Double {
            val pts = route.points
            if (pts.size < 10) return 0.0
            val cell = 0.0004 // ~40 m in latitude
            val seen = HashMap<Long, Double>() // grid cell -> distance along route when first visited
            var overlapping = 0
            var samples = 0
            var nextSampleAt = 0.0
            for (p in pts) {
                if (p.distanceFromStart < nextSampleAt) continue
                nextSampleAt = p.distanceFromStart + 50.0
                samples++
                val key = (floor(p.lat / cell).toLong() shl 32) xor floor(p.lon / (cell * 2)).toLong()
                val first = seen[key]
                if (first == null) seen[key] = p.distanceFromStart
                else if (p.distanceFromStart - first > 500.0) overlapping++
            }
            return if (samples == 0) 0.0 else overlapping.toDouble() / samples
        }
    }
}
