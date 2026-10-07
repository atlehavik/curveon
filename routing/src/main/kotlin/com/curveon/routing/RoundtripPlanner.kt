package com.curveon.routing

import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln

/**
 * GoRide-style roundtrips: "about 150 km, heading north-east".
 *
 * We lay a circle that touches the start and extends in the chosen direction, put a few via
 * points on it and let the router connect them. Kept quick on a tablet by:
 *  - three slightly rotated circles worked on in parallel, each correcting its own radius towards
 *    the asked length (at most twice, and not at all once any direction has a good loop),
 *  - routing loops leg by leg, in parallel, remembering legs so that moving one via re-routes
 *    only the two legs touching it.
 * Vias that land on an island (with ferries switched off) move to the quay on the near side; vias
 * in dead ends move to where the dead end starts. The winner has the right length, little riding
 * the same road twice and – for curvy styles – the most curves. When nothing on land comes near
 * the asked length, a loop with ferries may win; the preview then warns about it.
 */
class RoundtripPlanner(private val engine: BRouterEngine, private val log: (String) -> Unit = {}) {

    private data class Loop(val radius: Double, val directionDeg: Double, val route: Route)

    private data class Leg(val from: LatLon, val to: LatLon, val catchingRange: Double, val options: RouteOptions)

    /**
     * Loops are routed leg by leg: the legs run in parallel, and moving one via (off an island, out
     * of a dead end) only re-routes the two legs that touch it. Bounded by memory as well as cores:
     * every running leg holds its own decoded map data.
     */
    private val legThreads = minOf(
        Runtime.getRuntime().availableProcessors(),
        (Runtime.getRuntime().maxMemory() / LEG_MEMORY_BYTES).toInt()
    ).coerceAtLeast(2)
    @Volatile private lateinit var legPool: ExecutorService
    private val legs = ConcurrentHashMap<Leg, Future<Route>>()

    /** Loops as first routed, before island vias were moved off: offered when nothing on land fits. */
    private val ferryLoops = ConcurrentLinkedQueue<Loop>()

    /** Set once any direction has a loop of about the right length that keeps the options. */
    @Volatile private var goodFound = false

    private fun good(route: Route, targetM: Double, options: RouteOptions) =
        abs(route.distanceMeters / targetM - 1.0) <= LENGTH_TOLERANCE &&
            (!options.avoidFerries || route.ferryCount == 0) && Spurs.wastedMeters(route) < 1000

    fun plan(
        start: LatLon,
        distanceKm: Double,
        directionDeg: Double,
        options: RouteOptions,
        rotationsDeg: List<Double> = listOf(0.0, -30.0, 30.0)
    ): Route {
        val targetM = distanceKm * 1000
        // Per plan: legs, flags and pools belong to one request.
        legs.clear(); ferryLoops.clear(); goodFound = false
        legPool = Executors.newFixedThreadPool(legThreads)
        val pool = Executors.newFixedThreadPool(rotationsDeg.size)
        try {
            val initialRadius = targetM / (2 * PI * ROAD_DETOUR_FACTOR)
            // Each direction homes in on the right length by itself, so a slow one holds up no other.
            val tried = parallel(pool, rotationsDeg.map { rot -> { direction(start, initialRadius, directionDeg + rot, targetM, options) } })
            val loops = tried.successes.flatten() + ferryLoops
            if (loops.isEmpty()) throw noRoundtrip(tried.firstError)
            loops.forEach { log("  r=%.1f dir=%.0f -> %.0f km ferries=%d score=%.0f".format(it.radius / 1000, it.directionDeg, it.route.distanceMeters / 1000, it.route.ferryCount, score(it.route, targetM, options))) }

            // Every try competes: a correction can make a loop worse (it may now reach an island).
            // With ferries switched off, a loop with one only competes when nothing on land comes
            // within 25% of the length (the preview then warns about it).
            val onLand = loops.filter { it.route.ferryCount == 0 }
            val usable = if (options.avoidFerries && onLand.any { abs(ln(it.route.distanceMeters / targetM)) < ln(1.25) }) onLand else loops
            var best = usable.maxBy { score(it.route, targetM, options) }
            if (options.surface == SurfacePreference.PAVED && best.route.unpavedShare() > ACCEPTABLE_UNPAVED_SHARE) {
                val retries = parallel(pool, GRAVEL_NUDGES.map { (scale, turn) ->
                    { loop(start, best.radius * scale, best.directionDeg + turn, options) }
                }).successes
                best = (retries + best).maxBy { score(it.route, targetM, options) }
            }
            return best.route
        } finally {
            pool.shutdownNow()
            legPool.shutdownNow()
        }
    }

    /**
     * Loops in one direction: the first guess, then up to [MAX_CORRECTIONS] more with the radius
     * adjusted towards the asked length. Returns every loop that routed.
     */
    private fun direction(start: LatLon, initialRadius: Double, directionDeg: Double, targetM: Double, options: RouteOptions): List<Loop> {
        val tried = mutableListOf(loop(start, initialRadius, directionDeg, options))
        repeat(MAX_CORRECTIONS) {
            val last = tried.last()
            if (good(last.route, targetM, options)) { goodFound = true; return tried }
            // Another direction already has a loop to offer: don't keep the rider waiting for a better one.
            if (goodFound) return tried
            // The coast caps how far this direction goes: a different radius gives the same loop.
            val prev = tried.getOrNull(tried.size - 2)
            if (prev != null && abs(last.route.distanceMeters / prev.route.distanceMeters - 1.0) < 0.05) return tried
            val radius = nextRadius(tried, targetM).coerceAtMost(targetM / (2 * PI) * 1.2)
            val next = runCatching { loop(start, radius, directionDeg, options) }.getOrNull() ?: return tried
            tried += next
        }
        if (good(tried.last().route, targetM, options)) goodFound = true
        return tried
    }

    /**
     * Road length grows roughly in step with the radius, but coastlines make that rough. With two
     * tries, aim between them (secant); with one, scale it. Never more than halve or double per step:
     * a loop three times too long (an island) should not shrink to nothing.
     */
    private fun nextRadius(tried: List<Loop>, targetM: Double): Double {
        val b = tried.last()
        val a = tried.getOrNull(tried.size - 2)
        val guess = if (a != null && abs(b.route.distanceMeters - a.route.distanceMeters) > 1000 &&
            (b.route.distanceMeters - a.route.distanceMeters) / (b.radius - a.radius) > 0) {
            b.radius + (targetM - b.route.distanceMeters) * (b.radius - a.radius) / (b.route.distanceMeters - a.route.distanceMeters)
        } else b.radius * targetM / b.route.distanceMeters
        return guess.coerceIn(b.radius * 0.5, b.radius * 2.0)
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
        var vias = (1..VIA_POINTS).map { k -> Geo.destination(center, startAngle + k * 360.0 / (VIA_POINTS + 1), radius) }
        var route = routeVias(start, vias, options, VIA_CATCHING_RANGE_M)
        // Along the coast a via can snap onto an island; the router then has to take a ferry out and
        // back whatever the options say. Replace such a via by the quay on the near side: the loop then
        // stays on land, and the dead-end fix below moves it off the quay road onto the junction.
        if (options.avoidFerries && route.ferryCount > 0) ferryLoops += Loop(radius, directionDeg, route)
        repeat(2) {
            if (!options.avoidFerries || route.ferryCount == 0) return@repeat
            val at = viaIndices(route, vias)
            val moved = vias.mapIndexed { i, via -> if (beyondFerry(route, at[i])) quayBefore(route, if (i == 0) 0 else at[i - 1], at[i]) else via }
                .distinct().filter { Geo.distance(it, start) > 1000 }
            if (moved.isEmpty()) throw RoutingException("Every point of this loop needs a ferry")
            if (moved == vias) return@repeat
            vias = moved
            route = routeVias(start, vias, options, VIA_CATCHING_RANGE_M)
        }
        // A via that snapped onto a dead end makes the route ride in and turn back. Move such vias to
        // the junction where the dead end starts, so the loop rides past it instead.
        repeat(SPUR_FIX_PASSES) {
            val spurs = Spurs.find(route)
            if (spurs.isEmpty()) return route
            var changed = false
            vias = vias.map { via ->
                val spur = spurs.minByOrNull { Geo.distance(it.turnaround, via) }
                if (spur != null && Geo.distance(spur.turnaround, via) < VIA_CATCHING_RANGE_M + 500) {
                    changed = true
                    spur.base
                } else via
            }.filter { Geo.distance(it, start) > 500 }
            if (!changed || vias.isEmpty()) return route
            val fixed = try {
                routeVias(start, vias, options, FIXED_VIA_CATCHING_RANGE_M)
            } catch (e: RoutingException) {
                return route
            }
            // Keep the fix only if it rides past the dead end rather than off somewhere else.
            val withoutSpurs = route.distanceMeters - spurs.sumOf { it.halfLength * 2 }
            if (fixed.distanceMeters > withoutSpurs * 1.4 + 3_000) return route
            route = fixed
        }
        return route
    }

    private fun routeVias(start: LatLon, vias: List<LatLon>, options: RouteOptions, catchingRange: Double): Route {
        val t0 = System.nanoTime()
        val futures = (listOf(start) + vias + start).zipWithNext { a, b ->
            legs.computeIfAbsent(Leg(a, b, catchingRange, options)) { leg ->
                legPool.submit(Callable { engine.route(listOf(leg.from, leg.to), leg.options, leg.catchingRange, PLANNING_FERRY_COST) })
            }
        }
        val route = Routes.joinLegs(futures.map {
            try {
                it.get()
            } catch (e: ExecutionException) {
                throw e.cause as? RoutingException ?: RoutingException(e.cause?.message ?: "Routing failed")
            }
        })
        log("    loop %d vias -> %.0f km ferries=%d (%.1f s)".format(vias.size, route.distanceMeters / 1000, route.ferryCount, (System.nanoTime() - t0) / 1e9))
        return route
    }

    /** The last point on land before the route's first ferry on the way to [viaIndex]. */
    private fun quayBefore(route: Route, legStart: Int, viaIndex: Int): LatLon {
        val pts = route.points
        val ferry = (legStart..viaIndex).firstOrNull { pts[it].ferry } ?: (0..viaIndex).first { pts[it].ferry }
        return pts[(ferry - 1).coerceAtLeast(0)].let { LatLon(it.lat, it.lon) }
    }

    /** Index of the route point closest to each via, in order along the route. */
    private fun viaIndices(route: Route, vias: List<LatLon>): List<Int> {
        var from = 0
        return vias.map { v ->
            var best = from
            var bestD = Double.MAX_VALUE
            for (i in from until route.points.size) {
                val p = route.points[i]
                val d = Geo.distance(p.lat, p.lon, v.lat, v.lon)
                if (d < bestD) { bestD = d; best = i }
            }
            from = best
            best
        }
    }

    /** With ferries this expensive, a via that needs one both to get there and to get back is on an island. */
    private fun beyondFerry(route: Route, index: Int): Boolean {
        val pts = route.points
        return (0..index).any { pts[it].ferry } && (index until pts.size).any { pts[it].ferry }
    }

    internal fun score(route: Route, targetM: Double, options: RouteOptions): Double {
        // Length first: half or double the asked distance are equally wrong (-170), 10% off is -24.
        val lengthPenalty = abs(ln(route.distanceMeters / targetM)) * 250
        // Some riding the same road twice is unavoidable on islands and peninsulas (one road out).
        val overlapPenalty = overlapFraction(route) * 100                          // 20% same road twice = -20
        val curveBonus = if (options.roadStyle == RoadStyle.FASTEST) 0.0 else route.curviness.toDouble()
        val gravelPenalty = if (options.surface == SurfacePreference.PAVED) route.unpavedShare() * 600 else 0.0
        // Riding into a dead end and back is the most annoying thing a loop can do.
        val spurPenalty = Spurs.wastedMeters(route) / route.distanceMeters * 400
        // Ferries switched off: loops with one only compete when nothing on land fits (see plan),
        // and even then each ferry weighs as much as a loop 27% off the asked length.
        val ferryPenalty = if (options.avoidFerries) route.ferryCount * 60.0 else 0.0
        return curveBonus - lengthPenalty - overlapPenalty - gravelPenalty - spurPenalty - ferryPenalty
    }

    private fun Route.unpavedShare() = if (distanceMeters > 0) unpavedMeters / distanceMeters else 0.0

    companion object {
        private const val ROAD_DETOUR_FACTOR = 1.3
        private const val VIA_POINTS = 3
        private const val VIA_CATCHING_RANGE_M = 5000.0
        /** Vias moved onto a junction are already on the road. */
        private const val FIXED_VIA_CATCHING_RANGE_M = 100.0
        private const val SPUR_FIX_PASSES = 3
        private const val LENGTH_TOLERANCE = 0.12
        private const val MAX_CORRECTIONS = 2
        private const val LEG_MEMORY_BYTES = 96L * 1024 * 1024
        private const val ACCEPTABLE_UNPAVED_SHARE = 0.03
        private val GRAVEL_NUDGES = listOf(0.8 to 12.0, 0.9 to -15.0)
        /**
         * Ferry cost while planning: high enough that the router doesn't take one to save a little
         * time, low enough that a via on an island doesn't make it search half of Norway for a way
         * around (BRouter looks at everything cheaper than the first route it finds). Islands are
         * then handled by moving the via (see [quayBefore]) and by the score.
         */
        private const val PLANNING_FERRY_COST = 50_000

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
