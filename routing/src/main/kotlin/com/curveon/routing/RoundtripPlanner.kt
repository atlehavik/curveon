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
class RoundtripPlanner(
    private val engine: BRouterEngine,
    /** Gives up the search (the web planner's page asked for something newer): also the routes worked out on the pools' threads. */
    private val cancel: BRouterEngine.Cancel? = null,
    private val log: (String) -> Unit = {},
) {

    /** [block] on a pool's thread, given up with [cancel] like the search that started it. */
    private fun <T> underCancel(block: () -> T): T = cancel?.let { BRouterEngine.cancellable(it, block) } ?: block()

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
            (!options.avoidFerries || route.ferryCount == 0) && Spurs.wastedMeters(route) < 1000 && uTurns(route) == 0

    fun plan(
        start: LatLon,
        distanceKm: Double,
        directionDeg: Double,
        options: RouteOptions,
        rotationsDeg: List<Double> = listOf(0.0, -30.0, 30.0)
    ): Route {
        val targetM = distanceKm * 1000
        // Per plan: legs, flags and pools belong to one request.
        legs.clear(); ferryLoops.clear(); loopVias.clear(); goodFound = false
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
            return polish(start, fewerVias(start, best.route, options, pool) { score(it, targetM, options) }, options)
        } finally {
            pool.shutdownNow()
            legPool.shutdownNow()
        }
    }

    /**
     * A loop inside an area the rider drew on the map. Via points go round its outline, drawn in a
     * little so the roads they snap to lie inside, and are ridden in the order drawn from the one
     * nearest the start. A few spreads and insets are tried in parallel; the winner stays inside,
     * rides little road twice and – for curvy styles – has the most curves. Each [variant] spreads
     * the points differently and goes round the other way.
     *
     * With [maxKm] shorter than the way round the area, the vias go round the area shrunk towards
     * the start (or, from outside it, towards its nearest edge), so the loop stays about that long.
     */
    fun planArea(start: LatLon, stroke: List<LatLon>, options: RouteOptions, variant: Int = 0, maxKm: Double? = null): Route {
        val drawn = SketchArea(stroke)
        if (drawn.outline.size < 3 || drawn.perimeterM < MIN_AREA_PERIMETER_M) throw noAreaRoundtrip(null)
        val maxM = maxKm?.times(1000)
        val area = if (maxM == null || maxM >= drawn.perimeterM) drawn else
            drawn.shrunkTowards(if (drawn.contains(start)) start else drawn.nearestOnEdge(start), (maxM / drawn.perimeterM).coerceIn(0.15, 1.0))
        legs.clear(); ferryLoops.clear(); loopVias.clear(); goodFound = false
        legPool = Executors.newFixedThreadPool(legThreads)
        val tries = AREA_INSETS.flatMap { inset -> AREA_SHIFTS.map { shift -> inset to shift } }
        val pool = Executors.newFixedThreadPool(tries.size)
        try {
            val count = (area.perimeterM * AREA_INSETS.first() / AREA_VIA_SPACING_M).toInt().coerceIn(4, 8)
            // Small areas need the vias on roads close by, not snapped out of them.
            val catching = (area.perimeterM / count / 4).coerceIn(1500.0, VIA_CATCHING_RANGE_M)
            // Every loop on the way competes: in an archipelago, moving vias out of dead ends can
            // shrink a loop round the area to a few kilometres near the start.
            val loops = ConcurrentLinkedQueue<Pair<Double, Route>>()
            val tried = parallel(pool, tries.map { (inset, shift) ->
                {
                    val vias = reachableVias(start, area.vias(count, inset, shift + variant * 0.37, start, reverse = variant % 2 == 1), options, catching)
                    // About the way round what was drawn (or the max): roads wind (longer), the vias sit inside (shorter).
                    val expected = area.perimeterM + 2 * maxOf(0.0, Geo.distance(start, vias.first()) - area.perimeterM / 8)
                    routeLoop(start, vias, options, catching, withFerry = {}, onEach = { loops += expected to it })
                }
            })
            if (loops.isEmpty()) throw noAreaRoundtrip(tried.firstError)
            val onLand = loops.filter { it.second.ferryCount == 0 }
            val usable = if (options.avoidFerries && onLand.isNotEmpty()) onLand else loops
            // Longer than the max: as bad as being half the length again for every 10% over.
            fun score(expected: Double, r: Route) = areaScore(r, drawn, expected, options) -
                if (maxM != null && r.distanceMeters > maxM * 1.05) ln(r.distanceMeters / maxM) * 700 else 0.0
            loops.forEach { (expected, r) ->
                log("  area -> %.0f km (expected %.0f) ferries=%d outside=%.0f%% twice=%.0f%% spurs=%.1f km score=%.0f".format(r.distanceMeters / 1000, expected / 1000,
                    r.ferryCount, drawn.outsideShare(r) * 100, overlapFraction(r) * 100, Spurs.wastedMeters(r) / 1000, score(expected, r)))
            }
            val (expected, best) = usable.maxBy { (expected, r) -> score(expected, r) }
            return polish(start, fewerVias(start, best, options, pool) { score(expected, it) }, options)
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
        val outcomes = jobs.map { job -> pool.submit(Callable { runCatching { underCancel(job) } }) }.map { it.get() }
        return Results(outcomes.mapNotNull { it.getOrNull() }, outcomes.firstNotNullOfOrNull { it.exceptionOrNull() })
    }

    private fun noAreaRoundtrip(cause: Throwable?) =
        NoRoundtripException("Could not find a roundtrip in that area (${cause?.message}).", inArea = true)

    private fun noRoundtrip(cause: Throwable?) =
        NoRoundtripException("Could not find a roundtrip in that direction (${cause?.message}). Try another direction or distance.")

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
        return routeLoop(start, vias, options, VIA_CATCHING_RANGE_M, withFerry = { ferryLoops += Loop(radius, directionDeg, it) })
    }

    /**
     * Routes start → [vias] → start, then mends it: vias on islands move to the quay, vias in dead
     * ends to where the dead end starts. [withFerry] gets the first try when it needed a ferry that
     * the options rule out; [onEach] every loop on the way (a mend can also spoil a loop).
     */
    private fun routeLoop(
        start: LatLon, firstVias: List<LatLon>, options: RouteOptions, catchingRange: Double,
        withFerry: (Route) -> Unit, onEach: (Route) -> Unit = {}
    ): Route {
        val record: (Route) -> Unit = { onEach(it) }
        return mendedLoop(start, firstVias, options, catchingRange, withFerry, record).third
    }

    /** The vias and catching range each loop was routed with, so the winner can be polished ([lessTwice]). */
    private val loopVias = java.util.Collections.synchronizedMap(java.util.IdentityHashMap<Route, Pair<List<LatLon>, Double>>())

    /** The chosen loop, riding less road twice where it can. */
    private fun polish(start: LatLon, route: Route, options: RouteOptions): Route {
        var r = route
        repeat(TWICE_ROUNDS) {
            val (vias, catching) = loopVias[r] ?: return r
            val next = lessTwice(start, vias, catching, options, r)
            if (next === r) return r
            loopVias[next] = vias to catching
            r = next
        }
        return r
    }

    /**
     * The chosen loop without one of its vias, then another, when that makes it better by [score]: a via out on a
     * peninsula or an island reached by one road is ridden out and back, and one on the road may turn the loop round
     * there; the loop is often as good without it.
     * Each try routes one new leg (the others are kept), all tries at once.
     */
    private fun fewerVias(start: LatLon, route: Route, options: RouteOptions, pool: ExecutorService, score: (Route) -> Double): Route {
        var best = route
        var bestScore = score(route)
        repeat(MAX_VIA_DROPS) {
            val (vias, catching) = loopVias[best] ?: return best
            if (vias.size <= 2 || (overlapFraction(best) < 0.03 && uTurns(best) == 0 && Spurs.wastedMeters(best) < 500)) return best
            // Mended as every loop is (off islands, out of dead ends): without a via the next one may lie down a dead end.
            val tries = parallel(pool, vias.indices.map { i -> { mendedLoop(start, vias.filterIndexed { k, _ -> k != i }, options, catching, withFerry = {}, onEach = {}) } }).successes
            // Only for riding less road twice, a dead end fewer or a turn on the road fewer, never by more of another: the best of those.
            fun better(r: Route) = overlapFraction(r) <= overlapFraction(best) - 0.01 || uTurns(r) < uTurns(best) || Spurs.wastedMeters(r) < Spurs.wastedMeters(best) - 500
            fun noWorse(r: Route) = overlapFraction(r) <= overlapFraction(best) + 0.01 && uTurns(r) <= uTurns(best) && Spurs.wastedMeters(r) <= Spurs.wastedMeters(best) + 500
            val (v, c, r) = tries.filter { better(it.third) && noWorse(it.third) }
                .maxByOrNull { score(it.third) } ?: return best
            val sc = score(r)
            log("    without a via: twice %.0f%% -> %.0f%%, spurs %.1f -> %.1f km, %.0f -> %.0f km, score %.0f -> %.0f".format(overlapFraction(best) * 100, overlapFraction(r) * 100,
                Spurs.wastedMeters(best) / 1000, Spurs.wastedMeters(r) / 1000, best.distanceMeters / 1000, r.distanceMeters / 1000, bestScore, sc))
            if (sc <= bestScore) return best
            loopVias[r] = v to c
            best = r; bestScore = sc
        }
        return best
    }

    /** The loop through [firstVias], with vias moved off islands and out of dead ends; also the vias and catching range it ended with. */
    private fun mendedLoop(
        start: LatLon, firstVias: List<LatLon>, options: RouteOptions, catchingRange: Double,
        withFerry: (Route) -> Unit, onEach: (Route) -> Unit
    ): Triple<List<LatLon>, Double, Route> {
        var vias = firstVias
        var catching = catchingRange
        fun stage(r: Route) { loopVias[r] = vias to catching; onEach(r) }
        var route = routeVias(start, vias, options, catchingRange).also(::stage)
        // Along the coast a via can snap onto an island; the router then has to take a ferry out and
        // back whatever the options say. Replace such a via by the quay on the near side: the loop then
        // stays on land, and the dead-end fix below moves it off the quay road onto the junction.
        if (options.avoidFerries && route.ferryCount > 0) withFerry(route)
        // With ferries allowed the same goes for a via the loop reaches by a ferry and leaves by the same ferry back: out
        // to an island and straight back is the same road twice, sea and all (one over and back by another ferry stays).
        repeat(2) {
            val sameFerryBack = !options.avoidFerries && ferryTwice(route)
            if (!sameFerryBack && (!options.avoidFerries || route.ferryCount == 0)) return@repeat
            val at = viaIndices(route, vias)
            val moved = vias.mapIndexed { i, via -> if (beyondFerry(route, at[i])) quayBefore(route, if (i == 0) 0 else at[i - 1], at[i]) else via }
                .distinct().filter { Geo.distance(it, start) > 1000 }
            if (moved.isEmpty()) { if (sameFerryBack) return@repeat else throw RoutingException("Every point of this loop needs a ferry") }
            if (moved == vias) return@repeat
            vias = moved
            route = routeVias(start, vias, options, catchingRange).also(::stage)
        }
        // A via that snapped onto a dead end makes the route ride in and turn back. Move such vias to
        // the junction where the dead end starts, so the loop rides past it instead.
        repeat(SPUR_FIX_PASSES) {
            val spurs = Spurs.find(route)
            if (spurs.isEmpty()) return Triple(vias, catching, route)
            var changed = false
            val moved = vias.map { via ->
                val spur = spurs.minByOrNull { Geo.distance(it.turnaround, via) }
                if (spur != null && Geo.distance(spur.turnaround, via) < catching + 500) {
                    changed = true
                    spur.base
                } else via
            }.filter { Geo.distance(it, start) > 500 }
            if (!changed || moved.isEmpty()) return Triple(vias, catching, route)
            val fixed = try {
                routeVias(start, moved, options, FIXED_VIA_CATCHING_RANGE_M)
            } catch (e: RoutingException) {
                return Triple(vias, catching, route)
            }
            // Keep the fix only if it rides past the dead end rather than off somewhere else.
            val withoutSpurs = route.distanceMeters - spurs.sumOf { it.halfLength * 2 }
            if (fixed.distanceMeters > withoutSpurs * 1.4 + 3_000) return Triple(vias, catching, route)
            vias = moved
            catching = FIXED_VIA_CATCHING_RANGE_M
            route = fixed.also(::stage)
        }
        return Triple(vias, catching, route)
    }

    /**
     * Riding the same road twice is what a loop should avoid most. Where this one does (beyond the
     * dead ends already dealt with), the legs that ride it again are routed once more with that
     * road made expensive – not forbidden: sometimes it is the only way, from an island or a valley.
     * A leg is kept when the loop then rides less road twice and doesn't get much longer.
     */
    private fun lessTwice(start: LatLon, vias: List<LatLon>, catching: Double, options: RouteOptions, route: Route): Route {
        val twice = repeats(route)
        if (twice.size * TWICE_SAMPLE_M < MIN_TWICE_M) return route
        val ends = listOf(start) + vias + start
        val legs = runCatching { ends.zipWithNext { a, b -> leg(a, b, options, catching).get() }.toMutableList() }.getOrNull() ?: return route
        val legStart = legs.runningFold(0.0) { acc, l -> acc + l.distanceMeters }
        fun legAt(along: Double) = (legStart.indexOfLast { it <= along }).coerceIn(0, legs.lastIndex)
        // The leg riding it the second time first, then the one riding it the first time.
        val tryLegs = (twice.map { legAt(it.second) } + twice.map { legAt(it.third) }).distinct()
        var best = route
        var bestTwice = overlapFraction(route)
        val t0 = System.nanoTime()
        for (i in tryLegs) {
            // Long legs take the router long with circles to weigh; they are the rarer case.
            if (legs[i].distanceMeters > TWICE_MAX_LEG_M) continue
            val noGo = twice.map { it.first }
                .filter { Geo.distance(it, ends[i]) > TWICE_NOGO_CLEAR_M && Geo.distance(it, ends[i + 1]) > TWICE_NOGO_CLEAR_M }
                .map { NoGo(it, TWICE_NOGO_RADIUS_M, TWICE_WEIGHT) }
            if (noGo.isEmpty()) continue
            val alt = runCatching {
                engine.route(listOf(ends[i], ends[i + 1]), options, catching, PLANNING_FERRY_COST, noGo = noGo)
            }.getOrNull() ?: continue
            val tried = legs.toMutableList().also { it[i] = alt }
            val joined = Routes.joinLegs(tried)
            val o = overlapFraction(joined)
            // Never by riding into a dead end and back instead (that is road twice too, and the worst kind).
            if (o < bestTwice - 0.01 && joined.distanceMeters < best.distanceMeters * 1.3 + 3_000 &&
                (!options.avoidFerries || joined.ferryCount <= best.ferryCount) && Spurs.wastedMeters(joined) <= Spurs.wastedMeters(best) + 300 &&
                uTurns(joined) <= uTurns(best)) {
                legs[i] = alt; best = joined; bestTwice = o
            }
        }
        log("    twice %.0f%% -> %.0f%%, spurs %.1f -> %.1f km (%d legs tried, %.1f s)".format(overlapFraction(route) * 100, bestTwice * 100,
            Spurs.wastedMeters(route) / 1000, Spurs.wastedMeters(best) / 1000, tryLegs.size, (System.nanoTime() - t0) / 1e9))
        return best
    }

    private fun routeVias(start: LatLon, vias: List<LatLon>, options: RouteOptions, catchingRange: Double): Route {
        val t0 = System.nanoTime()
        val futures = (listOf(start) + vias + start).zipWithNext { a, b -> leg(a, b, options, catchingRange) }
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

    private fun leg(a: LatLon, b: LatLon, options: RouteOptions, catchingRange: Double): Future<Route> =
        legs.computeIfAbsent(Leg(a, b, catchingRange, options)) { leg ->
            // The loop's shape is found with gravel's full pull; how far it may stray for gravel is
            // kept to once the loop is ridden as a trip through its points.
            legPool.submit(Callable { underCancel { engine.route(listOf(leg.from, leg.to), leg.options.copy(gravelReachKm = null), leg.catchingRange, PLANNING_FERRY_COST) } })
        }

    /**
     * Drops vias that can't be reached sensibly: both legs touching one fail, or go far round
     * (a road on an island, or one cut off from the rest, only reached by a long way and ferries).
     * Keeps at least two.
     */
    private fun reachableVias(start: LatLon, vias: List<LatLon>, options: RouteOptions, catchingRange: Double): List<LatLon> {
        val pts = listOf(start) + vias + start
        val bad = pts.zipWithNext { a, b ->
            val r = runCatching { leg(a, b, options, catchingRange).get() }.getOrNull()
            r == null || (r.distanceMeters > 30_000 && r.distanceMeters > Geo.distance(a, b) * 4) ||
                (options.avoidFerries && r.ferryCount > 0)
        }
        val keep = vias.filterIndexed { i, _ -> !(bad[i] && bad[i + 1]) }
        if (keep.size < vias.size) log("    dropped ${vias.size - keep.size} unreachable vias")
        return if (keep.size >= 2) keep else vias
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

    /** [route] crosses the same stretch of sea twice: a ferry out and the same ferry back. */
    private fun ferryTwice(route: Route): Boolean {
        if (route.ferryCount < 2) return false
        val cell = 0.002 // ~200 m: the two crossings of one ferry line lie about on top of each other
        val seen = HashMap<Long, Double>()
        val pts = route.points
        for (i in 1 until pts.size) {
            if (!pts[i].ferry) continue
            val a = pts[i - 1]; val b = pts[i]
            val len = b.distanceFromStart - a.distanceFromStart
            var d = 0.0
            while (d <= len) {
                val f = if (len > 0) d / len else 0.0
                val lat = a.lat + (b.lat - a.lat) * f; val lon = a.lon + (b.lon - a.lon) * f
                val key = (floor(lat / cell).toLong() shl 32) xor floor(lon / (cell * 2)).toLong()
                val first = seen[key]
                if (first == null) seen[key] = a.distanceFromStart + d
                else if (a.distanceFromStart + d - first > 2000) return true
                d += 200
            }
        }
        return false
    }

    /** With ferries this expensive, a via that needs one both to get there and to get back is on an island. */
    private fun beyondFerry(route: Route, index: Int): Boolean {
        val pts = route.points
        return (0..index).any { pts[it].ferry } && (index until pts.size).any { pts[it].ferry }
    }

    internal fun score(route: Route, targetM: Double, options: RouteOptions): Double {
        // Length first: half or double the asked distance are equally wrong (-170), 10% off is -24.
        val lengthPenalty = abs(ln(route.distanceMeters / targetM)) * 250
        return quality(route, options) - lengthPenalty
    }

    /**
     * A drawn area sets the length only roughly (the way round it), so it weighs less than an asked
     * distance; staying inside counts instead (a third of the loop outside: -67, as half the length -104).
     */
    private fun areaScore(route: Route, area: SketchArea, expectedM: Double, options: RouteOptions): Double =
        quality(route, options) - abs(ln(route.distanceMeters / expectedM)) * 150 - area.outsideShare(route) * 200

    /** What makes a loop nice to ride, whatever its length. */
    private fun quality(route: Route, options: RouteOptions): Double {
        // Some riding the same road twice is unavoidable on islands and peninsulas (one road out).
        // Riding the same road twice is what a loop should avoid most, after the length.
        // The rider's first wish for a loop (2026-11-08: "avoid, as far as at all possible, using the same road"): 20%
        // ridden twice weighs as much as a loop half again too long or short (asked distance), more than any curves.
        val overlapPenalty = overlapFraction(route) * OVERLAP_WEIGHT
        val curveBonus = if (options.roadStyle == RoadStyle.FASTEST) 0.0 else route.curviness.toDouble()
        val gravelPenalty = if (options.surface == SurfacePreference.PAVED) route.unpavedShare() * 600 else 0.0
        // Riding into a dead end and back weighs more than other road ridden twice (it is counted in that too).
        val spurPenalty = Spurs.wastedMeters(route) / route.distanceMeters * SPUR_WEIGHT
        // Ferries switched off: loops with one only compete when nothing on land fits (see plan),
        // and even then each ferry weighs as much as a loop 27% off the asked length.
        val ferryPenalty = if (options.avoidFerries) route.ferryCount * 60.0 else 0.0
        // A leg starts in any direction, so a via on the road can turn the loop round right there. The
        // search can't forbid that, as it can on a leg: such a loop loses to any other, even one twice too long.
        val uTurnPenalty = uTurns(route) * 200.0
        return curveBonus - overlapPenalty - gravelPenalty - spurPenalty - ferryPenalty - uTurnPenalty
    }

    private fun uTurns(route: Route) = route.maneuvers.count { it.type == ManeuverType.U_TURN }

    private fun Route.unpavedShare() = if (distanceMeters > 0) unpavedMeters / distanceMeters else 0.0

    companion object {
        private const val ROAD_DETOUR_FACTOR = 1.3
        /** Score per share of a loop ridden twice (see [quality]). */
        private const val OVERLAP_WEIGHT = 900.0
        /** Score per share of a loop wasted in dead ends ridden in and out (see [quality]). */
        private const val SPUR_WEIGHT = 3000.0
        /** At most this many vias are dropped from the chosen loop to ride less road twice (see [fewerVias]). */
        private const val MAX_VIA_DROPS = 2
        /** Rounds of routing legs again round road ridden twice (see [lessTwice]). */
        private const val TWICE_ROUNDS = 2
        private const val VIA_POINTS = 3
        /** A drawn area: about one via this far apart round it, shrunk towards the middle by each inset. */
        private const val AREA_VIA_SPACING_M = 15_000.0
        private val AREA_INSETS = listOf(0.85, 0.65)
        private val AREA_SHIFTS = listOf(0.0, 0.5)
        /** Smaller than this round (a tap or a scribble) is no area to ride in. */
        private const val MIN_AREA_PERIMETER_M = 8_000.0
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

        private const val TWICE_SAMPLE_M = 100.0
        /** Less than this ridden twice isn't worth routing legs again for. */
        private const val MIN_TWICE_M = 1_000.0
        private const val TWICE_NOGO_RADIUS_M = 80.0
        /** Cost per metre ridden inside one of the circles on a road already ridden (they overlap: about 15× a normal road). */
        private const val TWICE_WEIGHT = 8.0
        /** The leg's own ends stay reachable. */
        private const val TWICE_NOGO_CLEAR_M = 400.0
        private const val TWICE_MAX_LEG_M = 60_000.0

        /**
         * Where [route] rides road it rode before: (point, how far along the second time, how far
         * along the first time), about every [TWICE_SAMPLE_M].
         */
        internal fun repeats(route: Route): List<Triple<LatLon, Double, Double>> {
            val cell = 0.0004 // ~40 m in latitude
            val seen = HashMap<Long, Double>()
            val out = ArrayList<Triple<LatLon, Double, Double>>()
            var nextOutAt = 0.0
            along(route, 40.0) { lat, lon, at ->
                val key = (floor(lat / cell).toLong() shl 32) xor floor(lon / (cell * 2)).toLong()
                val first = seen[key]
                if (first == null) seen[key] = at
                else if (at - first > 500.0 && at >= nextOutAt) {
                    out += Triple(LatLon(lat, lon), at, first)
                    nextOutAt = at + TWICE_SAMPLE_M
                }
            }
            return out
        }

        /** Share of the route (by sample) that runs within ~40 m of an earlier, non-adjacent part of itself. */
        fun overlapFraction(route: Route): Double {
            if (route.points.size < 10) return 0.0
            val cell = 0.0004 // ~40 m in latitude
            val seen = HashMap<Long, Double>() // grid cell -> distance along route when first visited
            var overlapping = 0
            var samples = 0
            along(route, 50.0) { lat, lon, at ->
                samples++
                val key = (floor(lat / cell).toLong() shl 32) xor floor(lon / (cell * 2)).toLong()
                val first = seen[key]
                if (first == null) seen[key] = at
                else if (at - first > 500.0) overlapping++
            }
            return if (samples == 0) 0.0 else overlapping.toDouble() / samples
        }

        /**
         * Points every [step] metres along [route], between its points too: a ferry is one straight line of two points,
         * and a ferry ridden there and back must count as the kilometres it is (2026-11-08 a 13 km ferry out and back to
         * Huftarøy counted as almost nothing).
         */
        private inline fun along(route: Route, step: Double, each: (Double, Double, Double) -> Unit) {
            val pts = route.points
            var next = 0.0
            for (i in 1 until pts.size) {
                val a = pts[i - 1]; val b = pts[i]
                val len = b.distanceFromStart - a.distanceFromStart
                while (len > 0 && next <= b.distanceFromStart) {
                    val f = (next - a.distanceFromStart) / len
                    each(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f, next)
                    next += step
                }
            }
        }
    }
}
