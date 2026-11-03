package com.curveon.routing

import kotlin.math.abs

/**
 * Not navigating: the signs seen so far on the road ahead. Each look at the road is a guess at where it goes and
 * can miss or take another branch, so a sign doesn't go when a look doesn't have it: only once the rider has
 * ridden past it or away from it, so it stands still from when it comes until it is passed.
 */
class SignsAhead<T>(
    private val latOf: (T) -> Double,
    private val lonOf: (T) -> Double,
    /** Which way the road comes to the sign. */
    private val bearingOf: (T) -> Double,
    /** Whether two are the same sign, seen on two looks. */
    private val same: (T, T) -> Boolean
) {
    private class Seen<T>(val sign: T, var closest: Double)

    private val seen = ArrayList<Seen<T>>()

    /** What a look at the road ahead found; a sign already known stays where it first stood. */
    fun add(found: List<T>, here: LatLon) {
        for (sign in found) if (seen.none { same(it.sign, sign) }) seen += Seen(sign, distance(sign, here))
    }

    /** The signs still ahead of a rider at [here]. */
    fun ahead(here: LatLon): List<T> {
        seen.removeAll { s ->
            val d = distance(s.sign, here)
            s.closest = minOf(s.closest, d)
            val way = Geo.bearing(here.lat, here.lon, latOf(s.sign), lonOf(s.sign))
            // Also on the way from it: in a roundabout, the way the road goes at the sign says little.
            val behind = d > PAST_M && d > s.closest + PAST_M && abs(Geo.angleDiff(way, bearingOf(s.sign))) > BEHIND_DEG
            behind || d > s.closest + AWAY_M
        }
        return seen.map { it.sign }
    }

    fun clear() = seen.clear()

    private fun distance(sign: T, here: LatLon) = Geo.distance(here.lat, here.lon, latOf(sign), lonOf(sign))

    private companion object {
        /** A sign this far behind the rider has been passed. */
        const val PAST_M = 10.0
        /** Further from the way the road comes to the sign than this, it is behind the rider. */
        const val BEHIND_DEG = 100.0
        /** This much further away than the rider has been: they have left that road. */
        const val AWAY_M = 100.0
    }
}
