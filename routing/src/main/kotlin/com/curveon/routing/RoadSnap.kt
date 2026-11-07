package com.curveon.routing

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sign

/** Puts the rider on a road line: used to draw them on the road they ride when there is no route. */
object RoadSnap {

    /** A line the map draws among its roads, and what kind of way it is. */
    class Line(val points: List<LatLon>, val kind: Kind = Kind.ROAD)

    enum class Kind {
        /** A road a motorcycle rides: gravel roads and tracks too. */
        ROAD,
        /** A way to a house, a yard or a car park: ridden, but one turning off is no junction worth stopping at. */
        SERVICE,
        /** A footway, cycleway, path or steps: the rider is put on one only when no road is near. */
        PATH,
    }

    /**
     * The point nearest [p] within [maxM] metres on one of [lines] whose piece there runs within [maxAngleDeg]
     * of [headingDeg] (either way along it, so a road crossing at a junction doesn't win), and that piece's
     * bearing the way the rider goes. A path counts as [PATH_PENALTY_M] further off than it is, so a road
     * with a footway or cycleway beside it wins. Null when there is none.
     */
    fun near(p: LatLon, lines: List<Line>, headingDeg: Double, maxM: Double, maxAngleDeg: Double): Pair<LatLon, Double>? {
        val kx = cos(Math.toRadians(p.lat)) * 111_320.0
        val ky = 110_574.0
        var best: Pair<LatLon, Double>? = null
        var bestScore = Double.MAX_VALUE
        for (road in lines) {
            val line = road.points
            val extra = if (road.kind == Kind.PATH) PATH_PENALTY_M else 0.0
            for (i in 0 until line.size - 1) {
                val a = line[i]; val b = line[i + 1]
                val ax = (a.lon - p.lon) * kx; val ay = (a.lat - p.lat) * ky
                val sx = (b.lon - a.lon) * kx; val sy = (b.lat - a.lat) * ky
                val len2 = sx * sx + sy * sy
                if (len2 < 1.0) continue
                val t = (-(ax * sx + ay * sy) / len2).coerceIn(0.0, 1.0)
                val d = hypot(ax + t * sx, ay + t * sy)
                if (d >= maxM || d + extra >= bestScore) continue
                val bearing = (Math.toDegrees(atan2(sx, sy)) + 360) % 360
                val off = abs(Geo.angleDiff(headingDeg, bearing)) // 0 = the same way, 180 = the other way
                if (minOf(off, 180 - off) > maxAngleDeg) continue
                bestScore = d + extra
                best = LatLon(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon)) to (if (off <= 90) bearing else (bearing + 180) % 360)
            }
        }
        return best
    }

    /**
     * The road through [from] (a point on one of [lines], as [near] gives it) as a line to carry the rider along
     * between fixes, so the arrow follows its bends instead of running straight off them: [behindM] metres back
     * and [aheadM] metres on the way [bearingDeg] points, from line to line where one carries on from another
     * (a bridge, the edge of a map tile), taking the one that goes on most nearly straight. Ahead it stops at the
     * first junction with another road, as which way the rider takes there isn't known; from there, as where the
     * lines read run out, it goes on straight. Returns the line and how far along it [from] lies.
     */
    fun path(from: LatLon, lines: List<Line>, bearingDeg: Double, behindM: Double, aheadM: Double): Pair<Route, Double> {
        val kx = cos(Math.toRadians(from.lat)) * 111_320.0
        val ky = 110_574.0
        val xy = lines.map { line -> line.points.map { (it.lon - from.lon) * kx to (it.lat - from.lat) * ky } }
        val kinds = lines.map { it.kind }
        val back = walk(xy, kinds, (bearingDeg + 180) % 360, behindM, stopAtJunction = false)
        val ahead = walk(xy, kinds, bearingDeg, aheadM, stopAtJunction = true)
        val pts = back.asReversed() + ahead.drop(1)
        var d = 0.0
        val points = pts.mapIndexed { i, (x, y) ->
            if (i > 0) d += hypot(x - pts[i - 1].first, y - pts[i - 1].second)
            RoutePoint(from.lat + y / ky, from.lon + x / kx, 0.0, d, 0.0, null, null, false, false)
        }
        var behind = 0.0
        for (i in 1 until back.size) behind += hypot(back[i].first - back[i - 1].first, back[i].second - back[i - 1].second)
        return Route(points, emptyList(), d, 0.0, 0.0, 0, 0.0, 0) to behind
    }

    /**
     * The roundabout [from] lies on (a point on one of [lines], as [near] gives it, ridden [bearingDeg]), as its
     * middle and radius: the road there, followed both ways, bends round the same way without corners until it
     * has turned (nearly) a full circle, round and within a small space. Null for any other road, hairpins and
     * loops of streets included.
     */
    fun roundabout(from: LatLon, lines: List<Line>, bearingDeg: Double): Pair<LatLon, Double>? {
        val kx = cos(Math.toRadians(from.lat)) * 111_320.0
        val ky = 110_574.0
        val xy = lines.map { line -> line.points.map { (it.lon - from.lon) * kx to (it.lat - from.lat) * ky } }
        val kinds = lines.map { it.kind }
        val (aheadTurn, ahead) = turning(xy, kinds, bearingDeg)
        val (backTurn, back) = turning(xy, kinds, (bearingDeg + 180) % 360)
        // Back along the road its bends turn the other way round.
        if (abs(aheadTurn - backTurn) < RING_TURN_DEG) return null
        val pts = back.asReversed() + ahead.drop(1)
        val cx = pts.sumOf { it.first } / pts.size
        val cy = pts.sumOf { it.second } / pts.size
        val r = pts.sumOf { hypot(it.first - cx, it.second - cy) } / pts.size
        // Round, and not wide: a loop of streets round a block or a hill bends all the way round too.
        if (r > RING_MAX_RADIUS_M || pts.any { abs(hypot(it.first - cx, it.second - cy) - r) > RING_ROUNDNESS * r }) return null
        return LatLon(from.lat + cy / ky, from.lon + cx / kx) to r
    }

    /**
     * How far the road from the start (0, 0) turns the way [bearingDeg] points (degrees, + to the right), from line
     * to line on the one bending on the same way, and the points it passes: until a corner, a bend the other way,
     * [RING_MAX_SPAN_M] from the start, or a full turn.
     */
    private fun turning(xy: List<List<Pair<Double, Double>>>, kinds: List<Kind>, bearingDeg: Double): Pair<Double, List<Pair<Double, Double>>> {
        val out = mutableListOf(0.0 to 0.0)
        var heading = bearingDeg
        var total = 0.0
        val used = HashSet<Int>()
        while (abs(total) < 360) {
            val (x, y) = out.last()
            var best: Triple<Int, Int, Double>? = null
            var bestForward = true
            var bestScore = Double.MAX_VALUE
            for ((k, line) in xy.withIndex()) {
                if (k in used || kinds[k] == Kind.PATH) continue
                for (s in 0 until line.size - 1) {
                    val (ax, ay) = line[s]; val (bx, by) = line[s + 1]
                    val sx = bx - ax; val sy = by - ay
                    val len2 = sx * sx + sy * sy
                    if (len2 < 0.01) continue
                    val t = (((x - ax) * sx + (y - ay) * sy) / len2).coerceIn(0.0, 1.0)
                    val dist = hypot(ax + t * sx - x, ay + t * sy - y)
                    if (dist > JOIN_M) continue
                    val bearing = (Math.toDegrees(atan2(sx, sy)) + 360) % 360
                    val forward = abs(Geo.angleDiff(heading, bearing)) <= 90
                    val turn = Geo.angleDiff(heading, if (forward) bearing else (bearing + 180) % 360)
                    if (abs(turn) > RING_MAX_STEP_DEG) continue
                    // An exit bends off the other way.
                    if (abs(total) > 20 && abs(turn) > 10 && sign(turn) != sign(total)) continue
                    if (if (forward) s == line.size - 2 && t > 0.999 else s == 0 && t < 0.001) continue
                    val score = abs(turn) + dist * 10
                    if (score < bestScore) { bestScore = score; best = Triple(k, s, t); bestForward = forward }
                }
            }
            val (k, s, t) = best ?: break
            used += k
            val line = xy[k]
            val (ax, ay) = line[s]; val (bx, by) = line[s + 1]
            var px = ax + t * (bx - ax); var py = ay + t * (by - ay)
            var i = if (bestForward) s + 1 else s
            // A roundabout drawn as one closed line is followed on round past where it starts and ends.
            val closed = line.size > 3 && hypot(line[0].first - line.last().first, line[0].second - line.last().second) < 1.0
            var steps = 0
            while (i in line.indices && steps++ < 4 * line.size) {
                val (nx, ny) = line[i]
                if (hypot(nx - px, ny - py) > 0.5) {
                    val h = (Math.toDegrees(atan2(nx - px, ny - py)) + 360) % 360
                    val turn = Geo.angleDiff(heading, h)
                    if (abs(turn) > RING_MAX_STEP_DEG) return total to out
                    if (abs(total) > 20 && abs(turn) > 10 && sign(turn) != sign(total)) return total to out
                    total += turn
                    heading = h
                    px = nx; py = ny
                    if (hypot(px, py) > RING_MAX_SPAN_M) return total to out
                    out += px to py
                    // Round once: on from here would be the same ring again, or out along an exit.
                    if (abs(total) >= 360) return total to out
                }
                i += if (bestForward) 1 else -1
                if (closed && i !in line.indices) i = if (bestForward) 1 else line.size - 2
            }
        }
        return total to out
    }

    /** Points (metres east/north of the start) from the start along the roads [maxM] metres the way [bearingDeg] points. */
    private fun walk(xy: List<List<Pair<Double, Double>>>, kinds: List<Kind>, bearingDeg: Double, maxM: Double, stopAtJunction: Boolean): List<Pair<Double, Double>> {
        val out = mutableListOf(0.0 to 0.0)
        var heading = bearingDeg
        var left = maxM
        val used = HashSet<Int>()
        var onKind: Kind? = null
        var atJunction = false
        while (left > 0.5 && !atJunction) {
            val (x, y) = out.last()
            // The line here that runs on nearest the way the rider goes: the one already on at the start, the one
            // carrying on at a line's end. Not a road turning off, nor a line that ends here, nor a path beside a road.
            var best: Triple<Int, Int, Double>? = null // line, segment, t
            var bestForward = true
            var bestScore = Double.MAX_VALUE
            for ((k, line) in xy.withIndex()) {
                if (k in used) continue
                val extra = if (kinds[k] == Kind.PATH && onKind != Kind.PATH) PATH_PENALTY_M * 10 else 0.0
                for (s in 0 until line.size - 1) {
                    val (ax, ay) = line[s]; val (bx, by) = line[s + 1]
                    val sx = bx - ax; val sy = by - ay
                    val len2 = sx * sx + sy * sy
                    if (len2 < 0.01) continue
                    val t = (((x - ax) * sx + (y - ay) * sy) / len2).coerceIn(0.0, 1.0)
                    val dist = hypot(ax + t * sx - x, ay + t * sy - y)
                    if (dist > JOIN_M) continue
                    val bearing = (Math.toDegrees(atan2(sx, sy)) + 360) % 360
                    val off = abs(Geo.angleDiff(heading, bearing))
                    val forward = off <= 90
                    val turn = if (forward) off else 180 - off
                    if (turn > MAX_TURN_DEG) continue
                    // Nothing left of this line that way: it ends here.
                    if (if (forward) s == line.size - 2 && t > 0.999 else s == 0 && t < 0.001) continue
                    val score = turn + dist * 10 + extra
                    if (score < bestScore) { bestScore = score; best = Triple(k, s, t); bestForward = forward }
                }
            }
            val (k, s, t) = best ?: break
            used += k
            onKind = kinds[k]
            val line = xy[k]
            val (ax, ay) = line[s]; val (bx, by) = line[s + 1]
            var px = ax + t * (bx - ax); var py = ay + t * (by - ay)
            var i = if (bestForward) s + 1 else s
            while (left > 0.5 && i in line.indices) {
                val (nx, ny) = line[i]
                val seg = hypot(nx - px, ny - py)
                if (seg > 0.01) heading = (Math.toDegrees(atan2(nx - px, ny - py)) + 360) % 360
                val j = if (stopAtJunction && seg > 0.01) junction(xy, kinds, used, px, py, nx, ny) else null
                if (j != null && j * seg <= left) {
                    px += (nx - px) * j; py += (ny - py) * j
                    left -= j * seg
                    out += px to py
                    atJunction = true
                    break
                }
                if (seg >= left) {
                    val f = left / seg
                    px += (nx - px) * f; py += (ny - py) * f
                    left = 0.0
                } else {
                    px = nx; py = ny
                    left -= seg
                    i += if (bestForward) 1 else -1
                }
                out += px to py
            }
        }
        if (left > 0.5) {
            val (x, y) = out.last()
            val h = Math.toRadians(heading)
            out += (x + left * kotlin.math.sin(h)) to (y + left * cos(h))
        }
        return out
    }

    /**
     * Where along the piece from (ax, ay) to (bx, by) (0..1) another road first meets or crosses it, or null: a
     * road starting, ending or bending there, or one crossing it. A line running along it (the same road from the
     * next map tile, or on after it) is no junction, nor is a path or a way to a yard.
     */
    private fun junction(xy: List<List<Pair<Double, Double>>>, kinds: List<Kind>, used: Set<Int>,
                         ax: Double, ay: Double, bx: Double, by: Double): Double? {
        val sx = bx - ax; val sy = by - ay
        val len2 = sx * sx + sy * sy
        val len = kotlin.math.sqrt(len2)
        val bearing = Math.toDegrees(atan2(sx, sy))
        fun alongside(cx: Double, cy: Double, dx: Double, dy: Double): Boolean {
            if (hypot(dx - cx, dy - cy) < 0.01) return true
            val off = abs(Geo.angleDiff(bearing, Math.toDegrees(atan2(dx - cx, dy - cy))))
            return minOf(off, 180 - off) <= PARALLEL_DEG
        }
        var first: Double? = null
        for ((k, line) in xy.withIndex()) {
            if (k in used || kinds[k] != Kind.ROAD) continue
            for ((v, p) in line.withIndex()) {
                val (vx, vy) = p
                val t = ((vx - ax) * sx + (vy - ay) * sy) / len2
                if (t < 0 || t > 1 || (first != null && t >= first)) continue
                if (abs((vx - ax) * sy - (vy - ay) * sx) / len > JOIN_M) continue
                val before = if (v > 0) alongside(line[v - 1].first, line[v - 1].second, vx, vy) else true
                val after = if (v < line.size - 1) alongside(vx, vy, line[v + 1].first, line[v + 1].second) else true
                if (!(before && after)) first = t
            }
            for (s in 0 until line.size - 1) {
                val (cx, cy) = line[s]; val (dx, dy) = line[s + 1]
                val ex = dx - cx; val ey = dy - cy
                val den = sx * ey - sy * ex
                if (abs(den) < 1e-9 || alongside(cx, cy, dx, dy)) continue
                val t = ((cx - ax) * ey - (cy - ay) * ex) / den
                val u = ((cx - ax) * sy - (cy - ay) * sx) / den
                if (t in 0.0..1.0 && u in 0.0..1.0 && (first == null || t < first)) first = t
            }
        }
        return first
    }

    /** A line carries on from another when it passes within this of where that one ends (tile edges, rounding). */
    private const val JOIN_M = 4.0
    /** A line turning off more than this from the way the rider goes is another road, not the one ridden. */
    private const val MAX_TURN_DEG = 50.0
    /** A path counts as this much further off than a road: a footway or cycleway beside the road is some 3–10 m off it. */
    const val PATH_PENALTY_M = 12.0
    /** A line meeting the road at less than this is the same road (the next tile's piece, a split in the line), not a junction. */
    private const val PARALLEL_DEG = 25.0
    /** How far a roundabout's road turns, both ways from the rider together: a full circle, give or take the rounding. */
    private const val RING_TURN_DEG = 280.0
    /** A bend sharper than this at one point is a corner (a block of streets), not a roundabout. */
    private const val RING_MAX_STEP_DEG = 65.0
    /** Roundabouts are smaller than this (radius); a loop wider than it is a road, not one to float in. */
    private const val RING_MAX_RADIUS_M = 45.0
    /** No point of a roundabout lies further than this share of its radius in or out of the circle. */
    private const val RING_ROUNDNESS = 0.3
    private const val RING_MAX_SPAN_M = 150.0
}
