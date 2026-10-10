package com.curveon.routing

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/**
 * An area the rider drew on the map with a finger: the stroke, closed from its end back to its
 * start. Worked on in metres on a flat projection around the area (fine for anything a day's ride
 * across).
 */
class SketchArea(stroke: List<LatLon>) {
    private val lat0 = stroke.map { it.lat }.average()
    private val lon0 = stroke.map { it.lon }.average()
    private val mPerLat = PI * Geo.EARTH_RADIUS_M / 180
    private val mPerLon = mPerLat * cos(Math.toRadians(lat0))

    private fun x(p: LatLon) = (p.lon - lon0) * mPerLon
    private fun y(p: LatLon) = (p.lat - lat0) * mPerLat
    private fun at(x: Double, y: Double) = LatLon(lat0 + y / mPerLat, lon0 + x / mPerLon)

    /** The outline, without the closing point repeated. */
    val outline: List<LatLon> = stroke.dropLastWhile { stroke.size > 1 && it == stroke.first() }.ifEmpty { stroke }

    val perimeterM: Double = (outline + outline.first()).zipWithNext { a, b -> hypot(x(b) - x(a), y(b) - y(a)) }.sum()

    /** Middle of the outline (by length, so a wiggly bit of finger doesn't pull it). */
    private val centre: Pair<Double, Double> = run {
        var sx = 0.0; var sy = 0.0; var total = 0.0
        (outline + outline.first()).zipWithNext { a, b ->
            val len = hypot(x(b) - x(a), y(b) - y(a))
            sx += (x(a) + x(b)) / 2 * len; sy += (y(a) + y(b)) / 2 * len; total += len
        }
        if (total > 0) sx / total to sy / total else x(outline.first()) to y(outline.first())
    }

    /** Inside the outline (even-odd: a stroke that crossed itself still counts what it went round). */
    fun contains(p: LatLon): Boolean {
        val px = x(p); val py = y(p)
        var inside = false
        var j = outline.lastIndex
        for (i in outline.indices) {
            val xi = x(outline[i]); val yi = y(outline[i]); val xj = x(outline[j]); val yj = y(outline[j])
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Metres from [p] to the outline. */
    fun distanceToEdge(p: LatLon): Double {
        val px = x(p); val py = y(p)
        return (outline + outline.first()).zipWithNext { a, b ->
            val ax = x(a); val ay = y(a); val dx = x(b) - ax; val dy = y(b) - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
            hypot(px - (ax + t * dx), py - (ay + t * dy))
        }.min()
    }

    /** The point [fraction] (0..1) of the way round the outline from where it was started. */
    fun pointAround(fraction: Double): LatLon {
        var left = ((fraction % 1.0 + 1.0) % 1.0) * perimeterM
        val ring = outline + outline.first()
        for ((a, b) in ring.zipWithNext()) {
            val len = hypot(x(b) - x(a), y(b) - y(a))
            if (left <= len && len > 0) {
                val t = left / len
                return at(x(a) + (x(b) - x(a)) * t, y(a) + (y(b) - y(a)) * t)
            }
            left -= len
        }
        return outline.first()
    }

    /**
     * [p] drawn towards the middle of the area to [inset] of its distance from it, so the road the
     * router snaps it to lies inside. Where the area bends round (the middle is outside), the point
     * comes in less, as far as it stays inside.
     */
    fun inset(p: LatLon, inset: Double): LatLon {
        val (cx, cy) = centre
        for (f in listOf(inset, (1 + inset) / 2, 0.95)) {
            val q = at(cx + (x(p) - cx) * f, cy + (y(p) - cy) * f)
            if (contains(q)) return q
        }
        return p
    }

    /**
     * [count] points spread evenly round the area at [inset], starting [shift] (0..1 of the gap
     * between two) along. Ridden from the one nearest [start], in the direction drawn or, with
     * [reverse], the other way round.
     */
    fun vias(count: Int, inset: Double, shift: Double, start: LatLon, reverse: Boolean = false): List<LatLon> {
        val around = (0 until count).map { k -> inset(pointAround((k + shift) / count), inset) }
        val ordered = if (reverse) around.reversed() else around
        val first = ordered.indices.minBy { Geo.distance(ordered[it], start) }
        return ordered.drop(first) + ordered.take(first)
    }

    /**
     * Share of [route] that lies well outside the area (more than [slackM] beyond the outline). The
     * ride there and back from a start outside it doesn't count: only what lies between first
     * reaching the area and last leaving it.
     */
    fun outsideShare(route: Route, slackM: Double = 1500.0): Double {
        val samples = route.points.filterIndexed { i, _ -> i % SAMPLE_EVERY == 0 } + route.points.last()
        val near = samples.map { contains(LatLon(it.lat, it.lon)) || distanceToEdge(LatLon(it.lat, it.lon)) <= slackM }
        val from = near.indexOfFirst { it }
        val until = near.indexOfLast { it }
        if (from < 0) return 1.0
        var outside = 0.0
        for (i in from until until) if (!near[i] || !near[i + 1]) outside += samples[i + 1].distanceFromStart - samples[i].distanceFromStart
        val inArea = samples[until].distanceFromStart - samples[from].distanceFromStart
        return if (inArea > 0) outside / inArea else 0.0
    }

    private companion object {
        const val SAMPLE_EVERY = 8
    }
}
