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
 * area to ride in and says how long (estimated riding time) the trip may take at most; we look for the
 * most fun roads inside the area that bring them to the destination within that time.
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
    private val cancelled: () -> Boolean = { false },
    /** Where the roads are: anchors are put on them (none in the sea) instead of anywhere. Without it they are spread at random. */
    private val roads: SurfaceReader? = null
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
        /** Metres it rides into a dead end and back on its own (between its anchors), worked out when first asked. */
        val spur by lazy { Spurs.wastedMeters(route) }
        /** Its place in the walks' list of roads. */
        var id = -1
        /** How many cells it shares with another road (worked out once: the roads stay the same from round to round). */
        val shares = HashMap<Edge, Int>()
        val minRow = cells.minOfOrNull { it / ROW } ?: 0L
        val maxRow = cells.maxOfOrNull { it / ROW } ?: 0L
        val minCol = cells.minOfOrNull { it % ROW } ?: 0L
        val maxCol = cells.maxOfOrNull { it % ROW } ?: 0L
    }

    /**
     * Routes [start] → [destination] riding about [targetMinutes] of estimated time, inside [stroke]. With no
     * time (null) the area is the limit: the trip aims for a generous time of its own ([noLimitSeconds]).
     */
    fun plan(
        start: LatLon,
        destination: LatLon,
        stroke: List<LatLon>,
        targetMinutes: Int?,
        options: RouteOptions,
        variant: Int = 0,
        /**
         * More than 1: a first trip comes quickly from most of the anchors, then each further round adds anchors
         * (and the roads between them) to the same graph and looks again. A round that finds a better trip
         * hands it to [onRoute]; the first trip is handed over too. The best one is also what [plan] returns.
         */
        rounds: Int = 1,
        onRoute: (Route) -> Unit = {}
    ): Route {
        val area = SketchArea(stroke)
        if (area.outline.size < 3 || area.perimeterM < MIN_AREA_PERIMETER_M) throw NoAdventureException("The area is too small")
        // The area is what keeps the trip in check here, not how far it strays from the ordinary road.
        val opts = options.copy(gravelReachKm = null)

        val direct = engine.route(listOf(start, destination), opts, CATCHING_RANGE_M, PLANNING_FERRY_COST)
        log("  direct %.0f km, %.0f min".format(direct.distanceMeters / 1000, direct.durationSeconds / 60))
        val targetS = targetMinutes?.let { it * 60.0 } ?: noLimitSeconds(direct.durationSeconds)
        if (targetMinutes == null) log("  no time asked: aiming for up to %.0f min".format(targetS / 60))
        if (targetMinutes != null && targetS < direct.durationSeconds * MIN_DETOUR) {
            throw NoAdventureException("The quickest way takes ${(direct.durationSeconds / 60).toInt()} min", directMinutes = (direct.durationSeconds / 60).toInt())
        }

        val threads = minOf(Runtime.getRuntime().availableProcessors(), (Runtime.getRuntime().maxMemory() / ROUTE_MEMORY_BYTES).toInt()).coerceAtLeast(2)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val began = System.nanoTime()
            val nodes = mutableListOf(start, destination)
            var edges: List<Edge> = emptyList()
            var best: Route? = null
            var bestWithin = false
            var bestScore = 0.0
            fun round(index: Int, share: Double): Pair<Route, Boolean>? {
                val t0 = System.nanoTime()
                val from = nodes.size
                nodes += anchors(area, start, destination, direct, targetS, Random(variant * 7919L + 1 + index * 131L), share, nodes.toList(), withSpine = index == 0)
                edges = buildGraph(pool, nodes, opts, direct, area, from, edges)
                if (index == 0) directCells = edges.firstOrNull { it.a == 0 && it.b == 1 }?.cells.orEmpty()
                log("  round %d graph: %d anchors, %d roads (%.1f s)".format(index + 1, nodes.size, edges.size, (System.nanoTime() - t0) / 1e9))
                return search(pool, nodes, edges, opts, area, targetS, Random(variant * 104_729L + 7 + index * 977L))
            }
            val shares = if (rounds <= 1) listOf(1.0) else List(rounds) { if (it == 0) FIRST_ROUND_SHARE else ROUND_SHARE }
            for ((index, share) in shares.withIndex()) {
                if (index > 0 && (cancelled() || System.nanoTime() - began > ROUNDS_BUDGET_MS * 1_000_000)) break
                // The first round answers for itself; a later one that fails or is cancelled leaves what there is.
                val found = if (index == 0) round(index, share) else runCatching { round(index, share) }.getOrNull()
                if (found == null) continue
                val (route, within) = found
                val points = score(route, area, targetS, opts)
                val better = best == null || (within && !bestWithin) ||
                    (within == bestWithin && if (within) points > bestScore + MIN_GAIN else route.durationSeconds < best!!.durationSeconds)
                log("  round %d: %.0f km %.0f min score %.0f%s".format(index + 1, route.distanceMeters / 1000, route.durationSeconds / 60, points, if (better) " (better)" else ""))
                if (better) { best = route; bestWithin = within; bestScore = points; onRoute(route) }
            }
            return best ?: throw NoAdventureException("No route found in that area")
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * What a trip with no time asked for aims at: well over the quickest way (there is something to wander for) but
     * not endless, the drawn area keeping it in check as well.
     */
    private fun noLimitSeconds(directSeconds: Double) = maxOf(directSeconds * NO_LIMIT_FACTOR, directSeconds + NO_LIMIT_EXTRA_S)

    /** The best trip the graph gives, and whether it fits under the asked time (or null when there is none at all). */
    private fun search(
        pool: java.util.concurrent.ExecutorService, nodes: List<LatLon>, edges: List<Edge>, opts: RouteOptions, area: SketchArea,
        targetS: Double, random: Random
    ): Pair<Route, Boolean>? {
        // The asked time is a ceiling. A walk adds up the roads as worked out; the trip as ridden can come out
        // longer, so when nothing fits under the ceiling the walks are made again for a shorter goal (same
        // graph, no new routing but for the roads ridden the other way round).
        val ceiling = targetS * (1 + CLOSE_ENOUGH)
        var goal = targetS
        var nearest: Route? = null
        repeat(GOAL_TRIES) {
            val walks = walks(nodes.size, edges, goal, opts, random)
            if (walks.isEmpty()) return@repeat
            log("  %d walks for %.0f min, best score %.0f".format(walks.size, goal / 60, walks.first().score))
            // The best few again in the direction ridden: the answer is the best of what that really gave.
            val assembled = walks.take(FINALISTS).map { w -> pool.submit(Callable { runCatching { assemble(nodes, w, opts) }.getOrNull() }) }
                .mapNotNull { await(it) }
            assembled.forEach { r -> log("  -> %.0f km %.0f min curvy=%d unpaved=%.0f%% outside=%.0f%% twice=%.0f%% spur=%.0f m score=%.0f".format(
                r.distanceMeters / 1000, r.durationSeconds / 60, r.curviness, r.unpavedMeters / r.distanceMeters * 100,
                area.outsideShare(r) * 100, RoundtripPlanner.overlapFraction(r) * 100, Spurs.wastedMeters(r), score(r, area, targetS, opts))) }
            if (assembled.isEmpty()) return@repeat
            val within = assembled.filter { it.durationSeconds <= ceiling }
            if (within.isNotEmpty()) return within.maxBy { score(it, area, targetS, opts) } to true
            val shortest = assembled.minBy { it.durationSeconds }
            if (nearest == null || shortest.durationSeconds < nearest!!.durationSeconds) nearest = shortest
            goal *= targetS / shortest.durationSeconds * 0.95
        }
        return nearest?.let { it to false }
    }

    /**
     * Anchor points: some along the quickest way (on through roads for certain, so a long trip has a
     * backbone) and the rest spread over the part of [area] that the time reaches at all (a trip that
     * must be at the destination in [targetS] can't get farther from the way there than that).
     */
    private fun anchors(
        area: SketchArea, start: LatLon, destination: LatLon, direct: Route, targetS: Double, random: Random,
        /** How much of the full number of anchors this round puts out. */
        share: Double, /** Everything already out (start, destination and earlier anchors): new ones keep away from them. */ existing: List<LatLon>,
        withSpine: Boolean
    ): List<LatLon> {
        val straight = Geo.distance(start, destination)
        // Metres as the crow flies that the time buys (roads wind), start → anchor → destination at most.
        val reach = maxOf(straight * 1.05, targetS * direct.distanceMeters / direct.durationSeconds / ROAD_WINDING)
        fun reachable(p: LatLon) = Geo.distance(start, p) + Geo.distance(p, destination) <= reach
        val minLat = area.outline.minOf { it.lat }; val maxLat = area.outline.maxOf { it.lat }
        val minLon = area.outline.minOf { it.lon }; val maxLon = area.outline.maxOf { it.lon }
        fun sample() = LatLon(minLat + random.nextDouble() * (maxLat - minLat), minLon + random.nextDouble() * (maxLon - minLon))
        // Moved onto the nearest road, or thrown away when there is none near (sea, fjord, bare mountain).
        fun onRoad(p: LatLon): LatLon? = if (roads == null) p else roads.nearestRoad(p.lat, p.lon, SNAP_M)
        // The size of what can be used from how much of the bounding box it fills.
        val boxM2 = Geo.distance(minLat, minLon, maxLat, minLon) * Geo.distance(minLat, minLon, minLat, maxLon)
        val fill = (0 until 600).count { val p = sample(); area.contains(p) && reachable(p) } / 600.0
        val usableM2 = (boxM2 * fill).coerceAtLeast(1.0)
        val count = (usableM2 / (ANCHOR_SPACING_M * ANCHOR_SPACING_M)).toInt().coerceIn(MIN_ANCHORS, MAX_ANCHORS)
        val apart = sqrt(usableM2 / count) * 0.7
        val want = (count * share).toInt().coerceAtLeast(MIN_ROUND_ANCHORS)
        val out = ArrayList<LatLon>()
        // Along the quickest way, about every [apart] of it (not too many), where it is inside the area.
        val spine = if (withSpine) (direct.distanceMeters / (apart * 1.4)).toInt().coerceIn(0, MAX_SPINE) else 0
        for (k in 1..spine) {
            val p = Routes.pointAt(direct, direct.distanceMeters * k / (spine + 1)).first
            if (area.contains(p) || area.distanceToEdge(p) <= OUTSIDE_SLACK_M) out += p
        }
        val spineCount = out.size
        repeat(20_000) {
            if (out.size >= want + spineCount) return out
            val p = onRoad(sample()) ?: return@repeat
            // Drawn in from the edge a little, so the road the router snaps the point to lies inside too.
            if (area.contains(p) && area.distanceToEdge(p) > EDGE_MARGIN_M && reachable(p) && (out + existing).all { Geo.distance(it, p) > apart }) out += p
        }
        return out
    }

    /** Every anchor joined to its nearest few by a route; the quickest way start → destination is one too. */
    private fun buildGraph(
        pool: java.util.concurrent.ExecutorService, nodes: MutableList<LatLon>, options: RouteOptions, direct: Route, area: SketchArea,
        /** Anchors from this one on are new: only roads to them are worked out; [existing] roads are kept. */
        fromNode: Int, existing: List<Edge>
    ): List<Edge> {
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
        val have = existing.map { it.a to it.b }.toSet()
        pairs.removeAll { (a, b) -> maxOf(a, b) < fromNode || (a to b) in have }
        // The start and the destination may lie well outside the area drawn: they are joined to the nearest anchors
        // however far, and first, or the trip could never get to the area (nor out of it).
        val ends = LinkedHashSet<Pair<Int, Int>>()
        for (i in 0..1) nodes.indices.filter { it >= fromNode }.sortedBy { Geo.distance(nodes[i], nodes[it]) }.take(NEIGHBOURS)
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
        if (existing.isEmpty()) edges += Edge(0, 1, direct)
        edges.forEach { e -> e.outside = outsideShare(e.route, area) }
        return mendDeadEnds(pool, nodes, existing + edges, options, area, fromNode)
    }

    /**
     * An anchor that landed on a dead end (a farm road, a quay) has every road from it riding the
     * same stretch first: a trip through it rides in, turns and rides back. Such an anchor moves to
     * where the stretch meets the real road (as the loop planner does with its via points) and the
     * roads to it are worked out again from there. Anchors left with a single road are left out;
     * the start and destination stay whatever they are.
     */
    private fun mendDeadEnds(
        pool: java.util.concurrent.ExecutorService, nodes: MutableList<LatLon>, edges: List<Edge>, options: RouteOptions, area: SketchArea,
        fromNode: Int
    ): List<Edge> {
        var kept = edges.toList()
        fun stubCells(n: Int, roads: List<Edge>) =
            if (roads.size < 2) 0 else roads.minOf { e -> roads.minOf { f -> if (f === e) Int.MAX_VALUE else sharedStart(e.cellsFrom(n), f.cells) } }
        for (n in maxOf(2, fromNode) until nodes.size) {
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

    /** The cells (~100 m) of the quickest way start → destination; set when the roads are known. */
    @Volatile private var directCells: Set<Long> = emptySet()

    private class Walk(val steps: List<Pair<Int, Edge>>, val seconds: Double, val score: Double)

    /** Random walks over [edges] from node 0 (start) to node 1 (destination), best first. */
    private fun walks(nodeCount: Int, allEdges: List<Edge>, targetS: Double, options: RouteOptions, random: Random): List<Walk> {
        // Not the quickest way itself (that is what an adventure isn't), unless nothing else gets there.
        val others = allEdges.filter { !(it.a == 0 && it.b == 1) }
        // Strictest first: no anchor where the road in and the road out ride the same stretch; looser only when
        // that finds no walk at all. A walk that falls short of the asked time is better than one that fills it
        // with riding in and out of dead ends.
        // A level that finds only a handful of walks is not enough to choose from: the next, looser one adds to them
        // (the score still counts riding in and out of dead ends against a walk).
        val all = LinkedHashMap<List<Int>, Walk>()
        var loosest = STRICTEST
        allEdges.forEachIndexed { i, e -> e.id = i }
        val again = sharedRoads(allEdges)
        for (maxShared in listOf(STRICTEST, 10, Int.MAX_VALUE)) for (edges in listOf(others, allEdges)) {
            // Over the ceiling a little rather than nothing at all (the trip can be too close to the quickest way for more room).
            val found = listOf(CLOSE_WALK, LOOSE_CEILING).firstNotNullOfOrNull { over ->
                walksOver(nodeCount, edges, again, targetS, options, random, maxShared, over).takeIf { it.isNotEmpty() }
            } ?: continue
            // The quickest way itself is no adventure: it only does when it is what the time asks for.
            if (edges === allEdges && found.none { it.seconds >= targetS * NEAR_FULL }) continue
            found.forEach { w -> all.putIfAbsent(w.steps.map { System.identityHashCode(it.second) }, w) }
            loosest = maxShared
            if (all.size >= ENOUGH_WALKS) {
                log("  walks found allowing %s shared".format(if (maxShared == Int.MAX_VALUE) "any" else "< $maxShared cells"))
                return all.values.sortedByDescending { it.score }
            }
        }
        if (all.isNotEmpty()) log("  only %d walks, allowing %s shared".format(all.size, if (loosest == Int.MAX_VALUE) "any" else "< $loosest cells"))
        return all.values.sortedByDescending { it.score }
    }

    /**
     * For each road (by [Edge.id]): the other roads that share more than a crossing with it, and how many cells.
     * A walk looks these up instead of comparing cells, so keeping it off roads already ridden costs next to nothing.
     */
    private class SharedRoads(val ids: Array<IntArray>, val cells: Array<IntArray>)

    private fun sharedRoads(edges: List<Edge>): SharedRoads {
        val ids = List(edges.size) { ArrayList<Int>() }
        val cells = List(edges.size) { ArrayList<Int>() }
        for (i in edges.indices) for (j in i + 1 until edges.size) {
            val a = edges[i]; val b = edges[j]
            if (a.maxRow + 1 < b.minRow || b.maxRow + 1 < a.minRow || a.maxCol + 1 < b.minCol || b.maxCol + 1 < a.minCol) continue
            val n = a.shares.getOrPut(b) { a.cellList.count { c -> NEIGHBOURS_OF.any { c + it in b.cells } } }
            if (n <= CROSSING_CELLS) continue
            ids[i] += j; cells[i] += n; ids[j] += i; cells[j] += n
        }
        return SharedRoads(Array(edges.size) { ids[it].toIntArray() }, Array(edges.size) { cells[it].toIntArray() })
    }

    private fun walksOver(nodeCount: Int, edges: List<Edge>, again: SharedRoads, targetS: Double, options: RouteOptions, random: Random, maxShared: Int, over: Double): List<Walk> {
        // How far the road in and the road out of an anchor ride the same stretch (remembered: the same pairs come up again and again).
        val shared = HashMap<Triple<Int, Edge, Edge>, Int>()
        fun sharedAt(node: Int, from: Edge, to: Edge) = shared.getOrPut(Triple(node, from, to)) { sharedStart(from.cellsFrom(node), to.cells) }
        fun turnsBack(node: Int, from: Edge, to: Edge) = maxShared != Int.MAX_VALUE && sharedAt(node, from, to) >= maxShared
        // How much of a road an earlier one (not the one just ridden: that is [turnsBack]'s) already rode: riding up
        // a valley and back down the same road is no better than a dead end, even with anchors in between.
        val maxAgain = if (maxShared == Int.MAX_VALUE) Int.MAX_VALUE else maxShared * AGAIN_PER_SHARED
        val ridden = IntArray(again.ids.size)
        fun ridesAgain(e: Edge) = ridden[e.id] >= maxAgain
        fun rode(e: Edge) {
            val ids = again.ids[e.id]; val cells = again.cells[e.id]
            for (k in ids.indices) if (cells[k] > ridden[ids[k]]) ridden[ids[k]] = cells[k]
        }
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
        // The asked time is a ceiling; a walk may fall well short of it when the roads don't fill it.
        val lo = targetS * MIN_FILL; val hi = targetS * (1 + over)
        run {
            repeat(WALK_ATTEMPTS) attempt@{
                var node = 0
                var arrived: Edge? = null
                var seconds = 0.0
                val used = HashSet<Edge>()
                val visited = hashSetOf(0)
                val steps = ArrayList<Pair<Int, Edge>>()
                ridden.fill(0)
                while (steps.size < MAX_STEPS) {
                    val home = toDest[node]
                    if (node == 1 || (home < Double.MAX_VALUE && seconds + home in lo..hi &&
                            random.nextDouble() < (if (seconds + home >= targetS * NEAR_FULL) FINISH_CHANCE else EARLY_FINISH_CHANCE))) {
                        // Finish: the quickest way on from here (it may share a road with what was ridden; that is scored).
                        var n = node
                        var t = seconds
                        var last = arrived
                        val tail = ArrayList<Pair<Int, Edge>>()
                        while (n != 1) {
                            val e = next[n] ?: return@attempt
                            if (last != null && turnsBack(n, last, e)) return@attempt
                            if (ridesAgain(e)) return@attempt
                            last?.let { rode(it) }
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
                        it !in used && it.other(node) !in visited && (arrived == null || !turnsBack(node, arrived!!, it)) && !ridesAgain(it) && toDest[it.other(node)] < Double.MAX_VALUE && seconds + it.seconds + toDest[it.other(node)] <= hi
                    }
                    if (choices.isEmpty()) return@attempt
                    // Allowed is not wanted: mostly a road on that neither turns back nor rides one already ridden, when there is one.
                    val clean = if (maxShared <= STRICTEST || random.nextDouble() >= PREFER_CLEAN) choices else choices.filter {
                        (arrived == null || sharedAt(node, arrived!!, it) < STRICTEST) && ridden[it.id] < STRICTEST * AGAIN_PER_SHARED
                    }
                    val e = clean.ifEmpty { choices }.random(random)
                    arrived?.let { rode(it) }
                    used += e; seconds += e.seconds; steps += node to e; arrived = e
                    node = e.other(node); visited += node
                }
            }
        }
        return found.values.sortedByDescending { it.score }
    }

    /** A walk's score from its roads alone, to pick which few are worth routing once more. */
    private fun walkScore(steps: List<Pair<Int, Edge>>, seconds: Double, targetS: Double, options: RouteOptions): Double {
        val quickest = directCells
        val metres = steps.sumOf { it.second.metres }.coerceAtLeast(1.0)
        fun weighted(of: (Edge) -> Double) = steps.sumOf { of(it.second) * it.second.metres } / metres
        val cellCount = HashMap<Long, Int>()
        steps.forEach { (_, e) -> e.cells.forEach { cellCount.merge(it, 1, Int::plus) } }
        val twice = cellCount.values.count { it > 1 }.toDouble() / cellCount.size.coerceAtLeast(1)
        val curves = if (options.roadStyle == RoadStyle.FASTEST) 0.0 else weighted { it.route.curviness.toDouble() }
        val unpaved = weighted { it.route.unpavedMeters / it.metres.coerceAtLeast(1.0) }
        // An anchor on a dead end: the way in and the way out ride the same stretch, there and back.
        var wasted = steps.sumOf { it.second.spur }
        for (i in 1 until steps.size) {
            val (node, out) = steps[i]
            wasted += 2 * CELL_M * sharedStart(steps[i - 1].second.cellsFrom(node), out.cells)
        }
        // Riding the quickest way is no adventure: that part earns no bonus (it costs nothing either, for a long trip needs it).
        val onQuickest = steps.sumOf { (_, e) -> e.metres * (e.cellList.count { c -> NEIGHBOURS_OF.any { c + it in quickest } }.toDouble() / e.cellList.size.coerceAtLeast(1)) }
        val freshKm = (metres * (1 - twice) - wasted - onQuickest).coerceAtLeast(0.0) / 1000
        return curves + funBonus(freshKm, curves, options) + surfaceScore(unpaved, options) - overTimePenalty(seconds, targetS) -
            twice * TWICE_WEIGHT - weighted { it.outside } * 200 - wasted / metres * WASTE_WEIGHT
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

    /** Over the asked time costs, under it doesn't: the time is a ceiling (10% over: -76). */
    private fun overTimePenalty(seconds: Double, targetS: Double) = maxOf(0.0, ln(seconds / targetS)) * 800

    /**
     * What more time is for: more road to ride that is new (not ridden twice, not a spur) and winding.
     * Without it the shortest walk would win now that falling short of the time costs nothing.
     */
    private fun funBonus(freshKm: Double, curves: Double, options: RouteOptions): Double {
        val share = if (options.roadStyle == RoadStyle.FASTEST) FASTEST_FUN_SHARE else maxOf(curves / 100, MIN_FUN_SHARE)
        return freshKm * share * FUN_PER_KM
    }

    private fun surfaceScore(unpavedShare: Double, options: RouteOptions) = when (options.surface) {
        SurfacePreference.PAVED -> -unpavedShare * 600
        SurfacePreference.MIXED -> 0.0
        SurfacePreference.GRAVEL -> unpavedShare * 50
        SurfacePreference.OFFROAD -> unpavedShare * 80
    }

    /** Higher is better, for the finished trips. The time comes first, then what makes a trip fun to ride. */
    private fun score(route: Route, area: SketchArea, targetS: Double, options: RouteOptions): Double {
        val timePenalty = overTimePenalty(route.durationSeconds, targetS)
        val outsidePenalty = area.outsideShare(route) * 200
        val twicePenalty = RoundtripPlanner.overlapFraction(route) * TWICE_WEIGHT         // 5% twice: -75
        val spur = Spurs.wastedMeters(route)
        val spurPenalty = spur / route.distanceMeters * WASTE_WEIGHT
        val curves = if (options.roadStyle == RoadStyle.FASTEST) 0.0 else route.curviness.toDouble()
        val freshKm = (route.distanceMeters * (1 - RoundtripPlanner.overlapFraction(route)) - spur).coerceAtLeast(0.0) / 1000
        val ferries = if (options.avoidFerries) route.ferryCount * 60.0 else 0.0
        return curves + funBonus(freshKm, curves, options) + surfaceScore(route.unpavedMeters / route.distanceMeters, options) - timePenalty - outsidePenalty - twicePenalty - spurPenalty - ferries
    }

    private companion object {
        /** Smaller than this round (a tap or a scribble) is no area to ride in. */
        const val MIN_AREA_PERIMETER_M = 8_000.0
        /** Less time than this times the quickest way's isn't a detour to speak of. */
        const val MIN_DETOUR = 1.1
        /** With no time asked for a trip aims at this many times the quickest way, or that plus [NO_LIMIT_EXTRA_S] when that is more. */
        const val NO_LIMIT_FACTOR = 2.0
        const val NO_LIMIT_EXTRA_S = 90 * 60.0
        /** A route this close to the asked time competes on how fun it is; the others only when none is. */
        const val CLOSE_ENOUGH = 0.15

        /** About one anchor for every this much distance squared of area, between the limits. */
        const val ANCHOR_SPACING_M = 12_000.0
        const val MIN_ANCHORS = 18
        const val MAX_ANCHORS = 30
        /** A round of a progressive plan puts out this share of the full number of anchors (at least [MIN_ROUND_ANCHORS]). */
        const val ROUND_SHARE = 0.5
        /** The first round puts out more: it is the trip shown at once, and from too few roads it can come out well short of the asked time. */
        const val FIRST_ROUND_SHARE = 0.75
        const val MIN_ROUND_ANCHORS = 6
        /** A later round's trip replaces the earlier only when it scores this much better. */
        const val MIN_GAIN = 25.0
        /** Further rounds are not begun after this long. */
        const val ROUNDS_BUDGET_MS = 60_000L
        const val EDGE_MARGIN_M = 800.0
        /** An anchor is moved to the nearest road when there is one this close. */
        const val SNAP_M = 6_000.0
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

        const val WALK_ATTEMPTS = 20_000
        /** Fewer walks than this at a strict level: the looser levels are tried too and their walks added. */
        const val ENOUGH_WALKS = 150
        /** A walk shorter than this part of the asked time isn't worth taking. */
        const val MIN_FILL = 0.4
        /** From this part of the asked time a walk is likely to finish (and the quickest way itself passes for a trip); before it only rarely. */
        const val NEAR_FULL = 0.85
        const val EARLY_FINISH_CHANCE = 0.04
        /** How much over the asked time a walk may be when none fits under it. */
        const val LOOSE_CEILING = 0.25
        /** The walks are made again for a shorter goal this many times when the trips come out over the time. */
        const val GOAL_TRIES = 3
        /** Score per kilometre of new, winding road (at full curviness), and the least share of that any road gives. */
        const val FUN_PER_KM = 4.0
        const val MIN_FUN_SHARE = 0.15
        const val FASTEST_FUN_SHARE = 0.3
        /** Score lost per share of the trip that is dead ends ridden in and out. */
        const val WASTE_WEIGHT = 3000.0
        /** Road ridden a second time (going back the way you came, not a dead end) costs about as much. */
        const val TWICE_WEIGHT = 1500.0
        /** Two roads sharing no more cells than this only cross (or meet at an anchor). */
        const val CROSSING_CELLS = 3
        /** At each strictness, a road may share this many times as many cells with any earlier one as with the road just ridden. */
        const val AGAIN_PER_SHARED = 3
        /** Cells the road in and the road out of an anchor may share at the strictest. */
        const val STRICTEST = 3
        /** How often a looser walk still takes a clean road on when there is one. */
        const val PREFER_CLEAN = 0.85
        const val MAX_STEPS = 40
        const val FINISH_CHANCE = 0.4
        /** A walk this close to the asked time competes on how fun it is. */
        const val CLOSE_WALK = 0.06
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
