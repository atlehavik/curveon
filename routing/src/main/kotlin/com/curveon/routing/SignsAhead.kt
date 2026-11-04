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
    private class Seen<T>(val sign: T, var closest: Double, var passed: Boolean = false)

    private val seen = ArrayList<Seen<T>>()
    /** Where the rider last was, far enough back to say which way they are going: [going]. */
    private var last: LatLon? = null
    private var going: Double? = null

    /** What a look at the road ahead found; a sign already known stays where it first stood. */
    fun add(found: List<T>, here: LatLon) {
        for (sign in found) if (seen.none { same(it.sign, sign) }) seen += Seen(sign, distance(sign, here))
    }

    /**
     * The signs still ahead of a rider at [here]. [onPassed] hears of each as they ride by it the way its road goes,
     * not of one on a road they didn't take, however close that passes; the sign itself goes a little later, so it
     * doesn't vanish before they get there.
     */
    fun ahead(here: LatLon, onPassed: (T) -> Unit = {}): List<T> {
        val from = last
        if (from == null) last = here
        else if (Geo.distance(from, here) >= MOVED_M) { going = Geo.bearing(from.lat, from.lon, here.lat, here.lon); last = here }
        seen.removeAll { s ->
            val d = distance(s.sign, here)
            s.closest = minOf(s.closest, d)
            val way = abs(Geo.angleDiff(Geo.bearing(here.lat, here.lon, latOf(s.sign), lonOf(s.sign)), bearingOf(s.sign)))
            // Also on the way from it: in a roundabout, the way the road goes at the sign says little.
            val behind = d > PAST_M && d > s.closest + PAST_M && way > BEHIND_DEG
            val along = going?.let { abs(Geo.angleDiff(it, bearingOf(s.sign))) <= ALONG_DEG } == true
            if (!s.passed && s.closest <= REACHED_M && way > ABREAST_DEG && along) { s.passed = true; onPassed(s.sign) }
            behind || d > s.closest + AWAY_M
        }
        return seen.map { it.sign }
    }

    fun clear() { seen.clear(); last = null; going = null }

    private fun distance(sign: T, here: LatLon) = Geo.distance(here.lat, here.lon, latOf(sign), lonOf(sign))

    private companion object {
        /** A sign this far behind the rider has been passed. */
        const val PAST_M = 10.0
        /** Further from the way the road comes to the sign than this, it is behind the rider. */
        const val BEHIND_DEG = 100.0
        /** This much further away than the rider has been: they have left that road. */
        const val AWAY_M = 100.0
        /** The rider has been this close to a sign: they rode by it (GPS error plus a wide road), not past the road it stands on. */
        const val REACHED_M = 30.0
        /** Further from the way the road comes to the sign than this, the rider is level with it or past it. */
        const val ABREAST_DEG = 90.0
        /** The rider going this close to the way the sign's road goes is on that road. */
        const val ALONG_DEG = 60.0
        /** This far between two places says which way the rider is going. */
        const val MOVED_M = 5.0
    }
}

/**
 * Not navigating: the speed signs on the road ahead, and the limit for the speed card, which say the same: the limit
 * changes as the rider rides past a sign. A look at the road says what the limit is where the rider is, which puts
 * right what the signs missed (a road just turned onto).
 */
class FreeRideSigns {
    private fun signs() = SignsAhead<SpeedChange>({ it.lat }, { it.lon }, { it.bearingDeg }) { a, b ->
        a.limit == b.limit && Geo.distance(a.lat, a.lon, b.lat, b.lon) < SAME_SIGN_M
    }
    private val signs = signs()
    /** Only to be seen: they say nothing of the road the rider is on, also when it passes close by. */
    private val sideRoadSigns = signs()

    /** The limit where the rider is; null when the road isn't known, so it must be read some other way. */
    var limit: Int? = null
        private set

    /** What a look at the road ahead from [here] found; null: it didn't find the road. */
    fun look(road: RouteLimits?, here: LatLon) {
        if (road != null) { signs.add(road.changes, here); sideRoadSigns.add(listOfNotNull(road.sideRoad), here) }
        ride(here)
        val start = road?.startHere
        // A look reads a limit that begins just ahead as the one it starts with: wait for the sign.
        if (start == null || !signSoon(start, here)) limit = start
    }

    /** The signs still ahead of a rider at [here]. */
    fun ride(here: LatLon): List<SpeedChange> = signs.ahead(here) { limit = it.limit } + sideRoadSigns.ahead(here)

    /** Whether a sign just ahead of [here] will say [limit], so a reading of it can wait until the rider gets there. */
    fun signSoon(limit: Int, here: LatLon): Boolean =
        signs.ahead(here).any { it.limit == limit && Geo.distance(here.lat, here.lon, it.lat, it.lon) < SIGN_SOON_M }

    /** The rider has come too far from the last look for it to say what the limit is here. */
    fun forgetLimit() { limit = null }

    fun clear() { signs.clear(); sideRoadSigns.clear(); limit = null }

    private companion object {
        /** Signs this close with the same limit, seen on two looks at the road, are one sign. */
        const val SAME_SIGN_M = 60.0
        /** A sign this close stands just ahead: a limit's first sample may lie past it, or it stands a way into a road turned onto. */
        const val SIGN_SOON_M = 100.0
    }
}
