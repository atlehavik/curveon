package com.curveon.routing

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Adventurous routing: from a start to a destination, but not the shortest way. The rider draws an
 * area to ride in and says how long (estimated riding time) the trip should take; we look for the
 * most fun roads inside the area that bring them to the destination in about that time.
 *
 * Routing is the expensive part, so it is done as little as possible and the thinking is done on
 * what it gave:
 *  1. A handful of anchor points are spread over the area. Each is joined to its few nearest
 *     neighbours (and the start and destination are anchors too) by an ordinary short route. That
 *     is the only routing done, every route once, in parallel: a graph of real roads with real
 *     times, lengths, curviness and surface.
 *  2. Thousands of random walks over that graph are tried in a blink: each uses a road between two
 *     anchors at most once and heads for the destination so that it arrives at the asked time.
 *     Time simply adds up along the walk, so it fits without trial and error.
 *  3. Walks are scored on the time, how fun the roads are (curves, the asked surface), little road
 *     ridden twice and staying inside the area. The best few are routed once more in the direction
 *     ridden, and the best of those wins.
 * Each [plan] with another `variant` spreads the anchors differently, for a different suggestion.
 *
 * Kept apart from [RoundtripPlanner] on purpose: loops there work as they are.
 *
 * @param progress called with (roads worked out, roads to work out) while the graph is built
 * @param cancelled polled while waiting; true stops the work (the rider left the preview)
 */
class AdventurePlanner(
    private val engine: BRouterEngine,
    private val log: (String) -> Unit = {},
    private val progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    private val cancelled: () -> Boolean = { false }
) {

    /** A road between two anchors, worked out once (from [a] to [b]). */
    private class Edge(val a: Int, val b: Int, val route: Route) {
        val seconds get() = route.durationSeconds
        val metres get() = route.distanceMeters
        /** About every 100 m of it, as grid cells from [a]: what two roads that run along each other share. */
        val cellList: List<Long> = cellsOf(route)
        val cells: Set<Long> = cellList.toSet()
        /** The cells as seen from [node]'s end. */
        fun cellsFrom(node: Int) = if (node == a) cellList else cellList.asReversed()
        var outside = 0.0
        fun other(n: Int) = if (n == a) b else a
    }

    /** Routes [start] → [destination] riding about [targetMinutes] of estimated time, inside [stroke]. */
    fun plan(
        start: LatLon,
        destination: LatLon,
        stroke: List<LatLon>,
        targetMinutes: Int,
        options: RouteOptions,
        variant: Int = 0
    ): Route {
        val area = SketchArea(stroke)
        if (area.outline.size < 3 || area.perimeterM < MIN_AREA_PERIMETER_M) throw NoAdventureException("The area is too small")
        // The area is what keeps the trip in check here, not how far it strays from the ordinary road.
        val opts = options.copy(gravelReachKm = null)
        val targetS = targetMinutes * 60.0

        val direct = engine.route(listOf(start, destination), opts, CATCHING_RANGE_M, PLANNING_FERRY_COST)
        log("  direct %.0f km, %.0f min".format(direct.distanceMeters / 1000, direct.durationSeconds / 60))
        if (targetS < direct.durationSeconds * MIN_DETOUR) {
            throw NoAdventureException("The quickest way takes ${(direct.durationSeconds / 60).toInt()} min", directMinutes = (direct.durationSeconds / 60).toInt())
        }

        val threads = minOf(Runtime.getRuntime().availableProcessors(), (Runtime.getRuntime().maxMemory() / ROUTE_MEMORY_BYTES).toInt()).coerceAtLeast(2)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val t0 = System.nanoTime()
            val nodes = (listOf(start, destination) + anchors(area, start, destination, direct, targetS, Random(variant * 7919L + 1))).toMutableList()
            val edges = buildGraph(pool, nodes, opts, direct, area)
            log("  graph: %d anchors, %d roads (%.1f s)".format(nodes.size, edges.size, (System.nanoTime() - t0) / 1e9))

            val walks = walks(nodes.size, edges, targetS, opts, Random(variant * 104_729L + 7))
            if (walks.isEmpty()) throw NoAdventureException("No route found in that area")
            log("  %d walks, best score %.0f".format(walks.size, walks.first().score))

            // The best few again in the direction ridden: the answer is the best of what that really gave.
            val assembled = walks.take(FINALISTS).map { w -> pool.submit(Callable { runCatching { assemble(nodes, w, opts) }.getOrNull() }) }
                .mapNotNull { await(it) }
            if (assembled.isEmpty()) throw NoAdventureException("No route found in that area")
            assembled.forEach { r -> log("  -> %.0f km %.0f min curvy=%d unpaved=%.0f%% outside=%.0f%% twice=%.0f%% spur=%.0f m score=%.0f".format(
                r.distanceMeters / 1000, r.durationSeconds / 60, r.curviness, r.unpavedMeters / r.distanceMeters * 100,
                area.outsideShare(r) * 100, RoundtripPlanner.overlapFraction(r) * 100, Spurs.wastedMeters(r), score(r, area, targetS, opts))) }
            // The asked time comes first: anything within reach of it beats anything further off.
            val onTime = assembled.filter { abs(ln(it.durationSeconds / targetS)) <= ln(1 + CLOSE_ENOUGH) }
            return (onTime.ifEmpty { assembled }).maxBy { score(it, area, targetS, opts) }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Anchor points: some along the quickest way (on through roads for certain, so a long trip has a
     * backbone) and the rest spread over the part of [area] that the time reaches at all (a trip that
     * must be at the destination in [targetS] can't get farther from the way there than that).
     */
    private fun anchors(area: SketchArea, start: LatLon, destination: LatLon, direct: Route, targetS: Double, random: Random): List<LatLon> {
        val straight = Geo.distance(start, destination)
        // Metres as the crow flies that the time buys (roads wind), start → anchor → destination at most.
        val reach = maxOf(straight * 1.05, targetS * direct.distanceMeters / direct.durationSeconds / ROAD_WINDING)
        fun reachable(p: LatLon) = Geo.distance(start, p) + Geo.distance(p, destination) <= reach
        val minLat = area.outline.minOf { it.lat }; val maxLat = area.outline.maxOf { it.lat }
        val minLon = area.outline.minOf { it.lon }; val maxLon = area.outline.maxOf { it.lon }
        fun sample() = LatLon(minLat + random.nextDouble() * (maxLat - minLat), minLon + random.nextDouble() * (maxLon - minLon))
        // The size of what can be used from how much of the bounding box it fills.
        val boxM2 = Geo.distance(minLat, minLon, maxLat, minLon) * Geo.distance(minLat, minLon, minLat, maxLon)
        val fill = (0 until 600).count { val p = sample(); area.contains(p) && reachable(p) } / 600.0
        val usableM2 = (boxM2 * fill).coerceAtLeast(1.0)
        val count = (usableM2 / (ANCHOR_SPACING_M * ANCHOR_SPACING_M)).toInt().coerceIn(MIN_ANCHORS, MAX_ANCHORS)
        val apart = sqrt(usableM2 / count) * 0.7
        val out = ArrayList<LatLon>()
        // Along the quickest way, about every [apart] of it (not too many), where it is inside the area.
        val spine = (direct.distanceMeters / (apart * 1.4)).toInt().coerceIn(0, MAX_SPINE)
        for (k in 1..spine) {
            val p = Routes.pointAt(direct, direct.distanceMeters * k / (spine + 1)).first
            if (area.contains(p) || area.distanceToEdge(p) <= OUTSIDE_SLACK_M) out += p
        }
        val spineCount = out.size
        repeat(4000) {
            if (out.size >= count + spineCount) return out
            val p = sample()
            // Drawn in from the edge a little, so the road the router snaps the point to lies inside too.
            if (area.contains(p) && area.distanceToEdge(p) > EDGE_MARGIN_M && reachable(p) && (out + start + destination).all { Geo.distance(it, p) > apart }) out += p
        }
        return out
    }

    /** Every anchor joined to its nearest few by a route; the quickest way start → destination is one too. */
    private fun buildGraph(pool: java.util.concurrent.ExecutorService, nodes: MutableList<LatLon>, options: RouteOptions, direct: Route, area: SketchArea): List<Edge> {
        val pairs = LinkedHashSet<Pair<Int, Int>>()
        // Roads can be as long as the anchors are far apart (a big area has few of them for its size).
        val nearest = nodes.indices.map { i -> nodes.indices.filter { it != i }.minOf { Geo.distance(nodes[i], nodes[it]) } }.sorted()
        val maxEdge = maxOf(MAX_EDGE_M, nearest[nearest.size / 2] * 2.6).coerceAtMost(MAX_EDGE_LIMIT_M)
        for (i in nodes.indices) {
            nodes.indices.filter { it != i }.sortedBy { Geo.distance(nodes[i], nodes[it]) }.take(NEIGHBOURS)
                .filter { Geo.distance(nodes[i], nodes[it]) <= maxEdge }
                .forEach { j -> pairs += minOf(i, j) to maxOf(i, j) }
        }
        pairs -= (0 to 1)
        // The start and the destination may lie well outside the area drawn: they are joined to the nearest anchors
        // however far, and first, or the trip could never get to the area (nor out of it).
        val ends = LinkedHashSet<Pair<Int, Int>>()
        for (i in 0..1) nodes.indices.filter { it > 1 }.sortedBy { Geo.distance(nodes[i], nodes[it]) }.take(NEIGHBOURS)
            .filter { Geo.distance(nodes[i], nodes[it]) <= END_EDGE_LIMIT_M }.forEach { j -> ends += i to j }
        val wanted = (ends + pairs.sortedBy { (a, b) -> Geo.distance(nodes[a], nodes[b]) }).take(MAX_EDGES)
        val done = AtomicInteger()
        val total = wanted.size
        progress(0, total)
        // Roads are worked out shortest first; when the time is up the rest are left out (a slow phone gets a sparser graph, not a long wait).
        val deadline = System.nanoTime() + GRAPH_BUDGET_MS * 1_000_000
        mendDeadline = deadline + MEND_EXTRA_MS * 1_000_000
        val futures = wanted.map { (a, b) ->
            pool.submit(Callable {
                if (cancelled()) throw CancellationException()
                if (System.nanoTime() > deadline) { progress(done.incrementAndGet(), total); return@Callable null }
                val t = System.nanoTime()
                val r = runCatching { engine.route(listOf(nodes[a], nodes[b]), options, CATCHING_RANGE_M, PLANNING_FERRY_COST, maxMillis = edgeTimeout(Geo.distance(nodes[a], nodes[b]))) }
                    .onFailure { log("    road %d-%d failed: %s".format(a, b, it.message)) }.getOrNull()
                log("    road %d-%d %.1f km crow %.1f km: %.1f s".format(a, b, (r?.distanceMeters ?: 0.0) / 1000, Geo.distance(nodes[a], nodes[b]) / 1000, (System.nanoTime() - t) / 1e9))
                progress(done.incrementAndGet(), total)
                // A road that needs a ferry is no road when ferries are off; a huge winding one is no neighbour.
                r?.takeIf { (!options.avoidFerries || it.ferryCount == 0) && !endsOnFerry(it) && it.distanceMeters < Geo.distance(nodes[a], nodes[b]) * MAX_WINDING }
                    ?.let { Edge(a, b, it) }
            })
        }
        val edges = futures.mapNotNull { await(it) }.toMutableList()
        edges += Edge(0, 1, direct)
        edges.forEach { e -> e.outside = outsideShare(e.route, area) }
        return mendDeadEnds(pool, nodes, edges, options, area)
    }

    /**
     * An anchor that landed on a dead end (a farm road, a quay) has every road from it riding the
     * same stretch first: a trip through it rides in, turns and rides back. Such an anchor moves to
     * where the stretch meets the real road (as the loop planner does with its via points) and the
     * roads to it are worked out again from there. Anchors left with a single road are left out;
     * the start and destination stay whatever they are.
     */
    private fun mendDeadEnds(
        pool: java.util.concurrent.ExecutorService, nodes: MutableList<LatLon>, edges: List<Edge>, options: RouteOptions, area: SketchArea
    ): List<Edge> {
        var kept = edges.toList()
        fun stubCells(n: Int, roads: List<Edge>) =
            if (roads.size < 2) 0 else roads.minOf { e -> roads.minOf { f -> if (f === e) Int.MAX_VALUE else sharedStart(e.cellsFrom(n), f.cells) } }
        for (n in 2 until nodes.size) {
            if (System.nanoTime() > mendDeadline) break
            val roads = kept.filter { it.a == n || it.b == n }
            val stub = stubCells(n, roads)
            if (stub < STUB_CELLS) continue
            // The junction: as far along any of them as the stretch they share.
            val e = roads.first()
            val along = stub * CELL_M
            val at = Routes.pointAt(e.route, if (e.a == n) along else e.route.distanceMeters - along).first
            log("    anchor %d on a dead end of about %.1f km: moved to its junction".format(n, along / 1000))
            nodes[n] = at
            val again = roads.map { r ->
                pool.submit(Callable {
                    if (cancelled()) throw CancellationException()
                    if (System.nanoTime() > mendDeadline) return@Callable null
                    runCatching { engine.route(listOf(nodes[r.a], nodes[r.b]), options, MENDED_CATCHING_RANGE_M, PLANNING_FERRY_COST, maxMillis = edgeTimeout(Geo.distance(nodes[r.a], nodes[r.b]))) }.getOrNull()
                        ?.takeIf { (!options.avoidFerries || it.ferryCount == 0) && !endsOnFerry(it) }
                        ?.let { Edge(r.a, r.b, it).also { edge -> edge.outside = outsideShare(edge.route, area) } }
                })
            }.mapNotNull { await(it) }
            kept = kept - roads.toSet() + again
        }
        for (n in 2 until nodes.size) {
            val roads = kept.filter { it.a == n || it.b == n }
            if (roads.size == 1) kept = kept - roads.toSet()
        }
        log("  %d of %d roads kept after mending dead ends".format(kept.size, edges.size))
        return kept
    }

    /** How many cells at the start of [cells] the other road also has (allowing a miss now and then, as the two are sampled apart). */
    private fun sharedStart(cells: List<Long>, other: Set<Long>): Int {
        var shared = 0
        var misses = 0
        for (c in cells) {
            // Two roads over the same stretch fall in cells a step apart as often as in the same one.
            if (NEIGHBOURS_OF.any { c + it in other }) { shared++; misses = 0 } else if (++misses >= 2) break
        }
        return shared
    }

    /**
     * An anchor out in a fjord snaps onto a ferry line, and a ferry to an island is a dead end: the trip
     * would ride out to the middle of the sea and back. A road that starts or ends on a ferry is no road;
     * ones that cross by ferry between two places on land stay.
     */
    private fun endsOnFerry(route: Route) = route.points.first().ferry || route.points.last().ferry

    /** A longer road gets longer to be found, up to a limit. */
    private fun edgeTimeout(crowM: Double) = (EDGE_TIMEOUT_MS + crowM / 1000 * 150).toLong().coerceAtMost(EDGE_TIMEOUT_MAX_MS)

    private fun <T> await(future: Future<T>): T? {
        while (true) {
            if (cancelled()) throw CancellationException()
            try {
                return future.get(200, TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                continue
            } catch (e: ExecutionException) {
                if (e.cause is CancellationException) throw CancellationException()
                return null
            }
        }
    }

    /** After this the dead-end anchors are no longer moved and routed again (the graph is as good as it gets in the time). */
    @Volatile private var mendDeadline = Long.MAX_VALUE

    private class Walk(val steps: List<Pair<Int, Edge>>, val seconds: Double, val score: Double)

    /** Random walks over [edges] from node 0 (start) to node 1 (destination), best first. */
    private fun walks(nodeCount: Int, allEdges: List<Edge>, targetS: Double, options: RouteOptions, random: Random): List<Walk> {
        // Not the quickest way itself (that is what an adventure isn't), unless nothing else gets there.
        val others = allEdges.filter { !(it.a == 0 && it.b == 1) }
        // Strictest first: no anchor where the road in and the road out ride the same stretch; looser only when that
        // finds nothing near the asked time. When nothing does, the one coming closest to it.
        var best: List<Walk> = emptyList()
        var bestOff = Double.MAX_VALUE
        for (maxShared in listOf(3, 10, Int.MAX_VALUE)) for (edges in listOf(others, allEdges)) {
            val found = walksOver(nodeCount, edges, targetS, options, random, maxShared)
            val off = found.minOfOrNull { abs(ln(it.seconds / targetS)) } ?: continue
            // The quickest way itself is no adventure: it only does when it is what the time asks for.
            if (edges === allEdges && off > ln(1 + NEAR_ENOUGH_WALK)) continue
            if (off <= ln(1 + NEAR_ENOUGH_WALK)) { log("  walks found allowing %s shared".format(if (maxShared == Int.MAX_VALUE) "any" else "< $maxShared cells")); return found }
            if (off < bestOff) { best = found; bestOff = off }
        }
        return best
    }

    private fun walksOver(nodeCount: Int, edges: List<Edge>, targetS: Double, options: RouteOptions, random: Random, maxShared: Int): List<Walk> {
        // How far the road in and the road out of an anchor ride the same stretch (remembered: the same pairs come up again and again).
        val shared = HashMap<Triple<Int, Edge, Edge>, Int>()
        fun turnsBack(node: Int, from: Edge, to: Edge) = maxShared != Int.MAX_VALUE &&
            shared.getOrPut(Triple(node, from, to)) { sharedStart(from.cellsFrom(node), to.cells) } >= maxShared
        val adjacent = List(nodeCount) { ArrayList<Edge>() }
        edges.forEach { adjacent[it.a] += it; adjacent[it.b] += it }
        // Quickest time from every node to the destination, and the first road to take, so that a walk
        // can always finish and never starts something it has no time to ride home from.
        val toDest = DoubleArray(nodeCount) { Double.MAX_VALUE }
        val next = arrayOfNulls<Edge>(nodeCount)
        toDest[1] = 0.0
        val open = java.util.PriorityQueue<Pair<Double, Int>>(compareBy { it.first }).apply { add(0.0 to 1) }
        while (open.isNotEmpty()) {
            val (t, n) = open.poll()
            if (t > toDest[n]) continue
            for (e in adjacent[n]) {
                val m = e.other(n)
                if (t + e.seconds < toDest[m]) { toDest[m] = t + e.seconds; next[m] = e; open.add(toDest[m] to m) }
            }
        }
        val found = HashMap<List<Int>, Walk>()
        fun close(w: Walk) = abs(ln(w.seconds / targetS)) <= ln(1 + CLOSE_WALK)
        for (tolerance in listOf(CLOSE_WALK, 0.12, 0.2, 0.35)) {
            val lo = targetS * (1 - tolerance); val hi = targetS * (1 + tolerance)
            repeat(WALK_ATTEMPTS) attempt@{
                var node = 0
                var arrived: Edge? = null
                var seconds = 0.0
                val used = HashSet<Edge>()
                val visited = hashSetOf(0)
                val steps = ArrayList<Pair<Int, Edge>>()
                while (steps.size < MAX_STEPS) {
                    val home = toDest[node]
                    if (node == 1 || (home < Double.MAX_VALUE && seconds + home in lo..hi && random.nextDouble() < FINISH_CHANCE)) {
                        // Finish: the quickest way on from here (it may share a road with what was ridden; that is scored).
                        var n = node
                        var t = seconds
                        var last = arrived
                        val tail = ArrayList<Pair<Int, Edge>>()
                        while (n != 1) {
                            val e = next[n] ?: return@attempt
                            if (last != null && turnsBack(n, last, e)) return@attempt
                            last = e
                            // Back through an anchor already ridden is how a trip ends up riding in and out of the same road.
                            if (e.other(n) != 1 && e.other(n) in visited) return@attempt
                            tail += n to e; t += e.seconds; n = e.other(n)
                        }
                        if (t in lo..hi) {
                            val all = steps + tail
                            found.putIfAbsent(all.map { System.identityHashCode(it.second) }, Walk(all, t, walkScore(all, t, targetS, options)))
                        }
                        return@attempt
                    }
                    // Each anchor once: coming back to one means riding some road in and out again.
                    val choices = adjacent[node].filter {
                        it !in used && it.other(node) !in visited && (arrived == null || !turnsBack(node, arrived!!, it)) && toDest[it.other(node)] < Double.MAX_VALUE && seconds + it.seconds + toDest[it.other(node)] <= hi
                    }
                    if (choices.isEmpty()) return@attempt
                    val e = choices.random(random)
                    used += e; seconds += e.seconds; steps += node to e; arrived = e
                    node = e.other(node); visited += node
                }
            }
            // Wider only when too few walks fit closely: a walk close to the time beats a fun one far from it.
            if (found.values.count(::close) >= FINALISTS) break
        }
        // The closer to the asked time the better, in steps; within a step the more fun first.
        fun step(w: Walk) = listOf(CLOSE_WALK, 0.12, 0.2, 0.35).indexOfFirst { abs(ln(w.seconds / targetS)) <= ln(1 + it) }.let { if (it < 0) 4 else it }
        return found.values.sortedWith(compareBy<Walk> { step(it) }.thenByDescending { it.score })
    }

    /** A walk's score from its roads alone, to pick which few are worth routing once more. */
    private fun walkScore(steps: List<Pair<Int, Edge>>, seconds: Double, targetS: Double, options: RouteOptions): Double {
        val metres = steps.sumOf { it.second.metres }.coerceAtLeast(1.0)
        fun weighted(of: (Edge) -> Double) = steps.sumOf { of(it.second) * it.second.metres } / metres
        val cellCount = HashMap<Long, Int>()
        steps.forEach { (_, e) -> e.cells.forEach { cellCount.merge(it, 1, Int::plus) } }
        val twice = cellCount.values.count { it > 1 }.toDouble() / cellCount.size.coerceAtLeast(1)
        val curves = if (options.roadStyle == RoadStyle.FASTEST) 0.0 else weighted { it.route.curviness.toDouble() }
        val unpaved = weighted { it.route.unpavedMeters / it.metres.coerceAtLeast(1.0) }
        // An anchor on a dead end: the way in and the way out ride the same stretch, there and back.
        var wasted = 0.0
        for (i in 1 until steps.size) {
            val (node, out) = steps[i]
            wasted += 2 * CELL_M * sharedStart(steps[i - 1].second.cellsFrom(node), out.cells)
        }
        return curves + surfaceScore(unpaved, options) - abs(ln(seconds / targetS)) * 400 - twice * 300 - weighted { it.outside } * 200 -
            wasted / metres * 1500
    }

    /** The walk as a trip, each road ridden the way the walk goes. */
    private fun assemble(nodes: List<LatLon>, walk: Walk, options: RouteOptions): Route {
        val legs = walk.steps.map { (from, e) ->
            if (e.a == from) e.route else engine.route(listOf(nodes[from], nodes[e.a]), options, CATCHING_RANGE_M, PLANNING_FERRY_COST)
        }
        return Routes.joinLegs(legs)
    }

    private fun outsideShare(route: Route, area: SketchArea): Double {
        val samples = route.points.filterIndexed { i, _ -> i % 8 == 0 }
        if (samples.isEmpty()) return 0.0
        return samples.count { val p = LatLon(it.lat, it.lon); !area.contains(p) && area.distanceToEdge(p) > OUTSIDE_SLACK_M }.toDouble() / samples.size
    }

    private fun surfaceScore(unpavedShare: Double, options: RouteOptions) = when (options.surface) {
        SurfacePreference.PAVED -> -unpavedShare * 600
        SurfacePreference.MIXED -> 0.0
        SurfacePreference.GRAVEL -> unpavedShare * 50
        SurfacePreference.OFFROAD -> unpavedShare * 80
    }

    /** Higher is better, for the finished trips. The time comes first, then what makes a trip fun to ride. */
    private fun score(route: Route, area: SketchArea, targetS: Double, options: RouteOptions): Double {
        val timePenalty = abs(ln(route.durationSeconds / targetS)) * 400                  // 10% off: -38
        val outsidePenalty = area.outsideShare(route) * 200
        val twicePenalty = RoundtripPlanner.overlapFraction(route) * 300                  // 20% twice: -60
        val spurPenalty = Spurs.wastedMeters(route) / route.distanceMeters * 1500
        val curves = if (options.roadStyle == RoadStyle.FASTEST) 0.0 else route.curviness.toDouble()
        val ferries = if (options.avoidFerries) route.ferryCount * 60.0 else 0.0
        return curves + surfaceScore(route.unpavedMeters / route.distanceMeters, options) - timePenalty - outsidePenalty - twicePenalty - spurPenalty - ferries
    }

    private companion object {
        /** Smaller than this round (a tap or a scribble) is no area to ride in. */
        const val MIN_AREA_PERIMETER_M = 8_000.0
        /** Less time than this times the quickest way's isn't a detour to speak of. */
        const val MIN_DETOUR = 1.1
        /** A route this close to the asked time competes on how fun it is; the others only when none is. */
        const val CLOSE_ENOUGH = 0.15

        /** About one anchor for every this much distance squared of area, between the limits. */
        const val ANCHOR_SPACING_M = 12_000.0
        const val MIN_ANCHORS = 18
        const val MAX_ANCHORS = 30
        const val EDGE_MARGIN_M = 800.0
        /** Each anchor is joined to this many of its nearest, no farther away than [MAX_EDGE_M]. */
        const val NEIGHBOURS = 6
        const val MAX_EDGE_M = 16_000.0
        const val MAX_EDGE_LIMIT_M = 60_000.0
        /** The start and destination are joined to the nearest anchors up to this far away. */
        const val END_EDGE_LIMIT_M = 150_000.0
        /** The most anchors put along the quickest way. */
        const val MAX_SPINE = 10
        /** Crow-flight metres the roads wind to (road length over crow flight), as a guess before there are roads. */
        const val ROAD_WINDING = 1.3
        /** The most roads worked out; the longest are left out first. */
        const val MAX_EDGES = 100
        /** A road that takes the router longer than this to find winds round a fjord or the like: not worth it. */
        const val EDGE_TIMEOUT_MS = 4_000L
        const val EDGE_TIMEOUT_MAX_MS = 8_000L
        /** The most time spent working out roads. */
        const val GRAPH_BUDGET_MS = 20_000L
        /** Mending dead ends may go on this much longer. */
        const val MEND_EXTRA_MS = 8_000L
        /** A road more than this times longer than the crow flies isn't a neighbour (a fjord in between). */
        const val MAX_WINDING = 4.5
        const val OUTSIDE_SLACK_M = 1_500.0

        const val WALK_ATTEMPTS = 4_000
        const val MAX_STEPS = 40
        const val FINISH_CHANCE = 0.4
        /** A walk this close to the asked time competes on how fun it is. */
        const val CLOSE_WALK = 0.06
        /** Walks this near the asked time are good enough to stop looking for ones that also avoid more riding in and out. */
        const val NEAR_ENOUGH_WALK = 0.2
        private val NEIGHBOURS_OF = listOf(0L, 1L, -1L, ROW, -ROW, ROW + 1, ROW - 1, -ROW + 1, -ROW - 1)
        /** What a grid cell along a road stands for. */
        const val CELL_M = 100.0
        /** Cells are numbered row by row, this many to a row (so a neighbour is +-1 or +-ROW). */
        const val ROW = 1_000_000L
        /** Cells (~100 m each) that every road to an anchor shares before the anchor counts as being on a dead end. */
        const val STUB_CELLS = 8
        /** Roads to a moved anchor: it lies on the road already, so no looking far for another. */
        const val MENDED_CATCHING_RANGE_M = 200.0
        /** Walks routed once more in the direction ridden. */
        const val FINALISTS = 6

        const val CATCHING_RANGE_M = 3_000.0
        const val PLANNING_FERRY_COST = 50_000
        const val ROUTE_MEMORY_BYTES = 96L * 1024 * 1024

        /** Grid cells (~100 m) along [route]. */
        fun cellsOf(route: Route): List<Long> {
            val cell = 0.001
            val out = ArrayList<Long>()
            var next = 0.0
            for (p in route.points) if (p.distanceFromStart >= next) {
                out += floor(p.lat / cell).toLong() * ROW + floor(p.lon / (cell * 2)).toLong() + ROW / 2
                next = p.distanceFromStart + 100.0
            }
            return out
        }
    }
}

/** No adventurous route could be made; [directMinutes] is set when the asked time is less than the quickest way takes. */
class NoAdventureException(message: String, val directMinutes: Int? = null) : RoutingException(message)
