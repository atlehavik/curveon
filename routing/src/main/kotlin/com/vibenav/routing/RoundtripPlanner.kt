package com.vibenav.routing

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor

/**
 * GoRide-style roundtrips: "about 150 km, heading north-east".
 *
 * We lay a circle that touches the start and extends in the chosen direction, put a few via
 * points on it and let the router connect them. The work is bounded so it stays quick on a tablet:
 *  1. three slightly rotated circles are routed in parallel,
 *  2. all three are re-routed once with the radius corrected for how much longer roads are,
 *  3. only if every candidate still has gravel on a tarmac-only request, the best one is
 *     retried with its via points moved (forest vias tend to snap onto gravel roads).
 * The winner has the right length, little riding the same road twice and – for curvy
 * styles – the most curves.
 */
class RoundtripPlanner(private val engine: BRouterEngine) {

    private data class Loop(val radius: Double, val directionDeg: Double, val route: Route)

    fun plan(
        start: LatLon,
        distanceKm: Double,
        directionDeg: Double,
        options: RouteOptions,
        rotationsDeg: List<Double> = listOf(0.0, -30.0, 30.0)
    ): Route {
        val targetM = distanceKm * 1000
        val pool = Executors.newFixedThreadPool(rotationsDeg.size)
        try {
            val initialRadius = targetM / (2 * PI * ROAD_DETOUR_FACTOR)
            val first = parallel(pool, rotationsDeg.map { rot -> { loop(start, initialRadius, directionDeg + rot, options) } })
            if (first.successes.isEmpty()) throw noRoundtrip(first.firstError)

            // Correct each circle for how much longer its roads turned out than planned.
            val corrected = parallel(pool, first.successes.map { l ->
                {
                    val ratio = l.route.distanceMeters / targetM
                    if (abs(ratio - 1.0) <= LENGTH_TOLERANCE) l else loop(start, l.radius / ratio, l.directionDeg, options)
                }
            }).successes.ifEmpty { first.successes }

            var best = corrected.maxBy { score(it.route, targetM, options) }
            if (options.surface == SurfacePreference.PAVED && best.route.unpavedShare() > ACCEPTABLE_UNPAVED_SHARE) {
                val retries = parallel(pool, GRAVEL_NUDGES.map { (scale, turn) ->
                    { loop(start, best.radius * scale, best.directionDeg + turn, options) }
                }).successes
                best = (retries + best).maxBy { score(it.route, targetM, options) }
            }
            return best.route
        } finally {
            pool.shutdownNow()
        }
    }

    private class Results<T>(val successes: List<T>, val firstError: Throwable?)

    private fun <T : Any> parallel(pool: ExecutorService, jobs: List<() -> T>): Results<T> {
        val outcomes = jobs.map { job -> pool.submit(Callable { runCatching(job) }) }.map { it.get() }
        return Results(outcomes.mapNotNull { it.getOrNull() }, outcomes.firstNotNullOfOrNull { it.exceptionOrNull() })
    }

    private fun noRoundtrip(cause: Throwable?) =
        RoutingException("Could not find a roundtrip in that direction (${cause?.message}). Try another direction or distance.")

    /** Routes one circle; if a via point lands on an unreachable bit of road, shrinks the circle once and retries. */
    private fun loop(start: LatLon, radius: Double, directionDeg: Double, options: RouteOptions): Loop =
        try {
            Loop(radius, directionDeg, routeCircle(start, radius, directionDeg, options))
        } catch (e: RoutingException) {
            Loop(radius * 0.85, directionDeg + 8, routeCircle(start, radius * 0.85, directionDeg + 8, options))
        }

    private fun routeCircle(start: LatLon, radius: Double, directionDeg: Double, options: RouteOptions): Route {
        val center = Geo.destination(start, directionDeg, radius)
        val startAngle = directionDeg + 180.0
        val vias = (1..VIA_POINTS).map { k -> Geo.destination(center, startAngle + k * 360.0 / (VIA_POINTS + 1), radius) }
        return engine.route(listOf(start) + vias + start, options, viaCatchingRangeMeters = VIA_CATCHING_RANGE_M)
    }

    internal fun score(route: Route, targetM: Double, options: RouteOptions): Double {
        val lengthPenalty = abs(route.distanceMeters / targetM - 1.0) * 100     // 10% off = -10
        val overlapPenalty = overlapFraction(route) * 150                          // 20% same road twice = -30
        val curveBonus = if (options.roadStyle == RoadStyle.FASTEST) 0.0 else route.curviness.toDouble()
        val gravelPenalty = if (options.surface == SurfacePreference.PAVED) route.unpavedShare() * 600 else 0.0
        return curveBonus - lengthPenalty - overlapPenalty - gravelPenalty
    }

    private fun Route.unpavedShare() = if (distanceMeters > 0) unpavedMeters / distanceMeters else 0.0

    companion object {
        private const val ROAD_DETOUR_FACTOR = 1.3
        private const val VIA_POINTS = 3
        private const val VIA_CATCHING_RANGE_M = 5000.0
        private const val LENGTH_TOLERANCE = 0.12
        private const val ACCEPTABLE_UNPAVED_SHARE = 0.03
        private val GRAVEL_NUDGES = listOf(0.8 to 12.0, 0.9 to -15.0)

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
