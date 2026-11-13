package com.curveon.routing

import kotlin.math.abs

/** A way from where the rider is back onto a route, and what riding to the end that way takes. */
data class WayBack(
    /** From the rider's position, onto the old route at [joinAlong], then along it to the end. */
    val route: Route,
    /** Where it joins the old route, metres along the old route. */
    val joinAlong: Double,
    /** Seconds from here to the end of the old route this way. */
    val totalSeconds: Double
)

/** What to do after leaving the route. */
sealed interface OffRouteDecision {
    /** A way back that keeps going ahead and costs little: take it without asking. */
    data class Rejoin(val way: WayBack, val extraSeconds: Double, val deadEnd: Boolean = false) : OffRouteDecision
    /** Only turning back is cheap: ask the rider. [ahead] is the best way without turning round, if any. */
    data class Ask(val back: WayBack, val ahead: WayBack?) : OffRouteDecision
    /** No way back to the route was found at all. */
    data object Replan : OffRouteDecision
}

/**
 * After a wrong turn: finds the best way back onto the route that keeps riding the way the rider
 * is going (no turning round on the road they are on), and the way back that turns round. Comparing
 * the two decides whether to quietly rejoin or to ask the rider.
 */
class OffRoutePlanner(private val engine: BRouterEngine) {

    /**
     * On a trip ([tripWaypoints]: the stops left and the destination) the answer is a new route from here
     * that keeps riding ahead; the old route is never ridden back to for its own sake. On a loop or track,
     * the way back onto its line that keeps riding ahead. Either is taken quietly when it costs at most
     * [maxExtraSeconds] and [maxExtraFraction] of the trip left more than turning round; otherwise ask.
     * At a dead end (no way ahead at all) turning round is the only choice: no question.
     * Without a heading (standing still): a trip is planned anew, a loop or track takes the plain way back.
     */
    fun decide(
        position: LatLon, headingDeg: Double?, route: Route, leftAlong: Double, options: RouteOptions,
        maxExtraSeconds: Double, maxExtraFraction: Double, tripWaypoints: List<LatLon>? = null
    ): OffRouteDecision {
        val back = runCatching { back(position, route, leftAlong, options) }.getOrNull()
        if (headingDeg == null) return back?.takeIf { tripWaypoints == null }?.let { OffRouteDecision.Rejoin(it, 0.0) } ?: OffRouteDecision.Replan
        val turnRound = turnRound(position, headingDeg, route, leftAlong, options, back)
        // A way on that rides out along a road and back along it is turning round, only later and further
        // away: where the rider can turn round here, it is not offered as carrying on.
        fun WayBack.reallyOn() = turnRound == null || Routes.riddenTwiceMeters(this.route) <= RIDDEN_TWICE_M
        // On a trip the old route is worth nothing in itself: the answer is the best route from here that keeps
        // riding ahead. Turning round is only brought up when it is the only way, or clearly quicker.
        if (tripWaypoints != null) {
            val onward = forwardOnly(position, headingDeg, tripWaypoints, options).second
                ?.let { WayBack(it, route.distanceMeters, it.durationSeconds) }?.takeIf { it.reallyOn() }
            return when {
                onward != null && (turnRound == null || isQuick(onward, turnRound, maxExtraSeconds, maxExtraFraction)) ->
                    OffRouteDecision.Rejoin(onward, onward.totalSeconds - (turnRound?.totalSeconds ?: onward.totalSeconds))
                onward != null -> OffRouteDecision.Ask(turnRound!!, onward)
                turnRound != null -> OffRouteDecision.Rejoin(turnRound, 0.0, deadEnd = true)
                // No way on and none found that turns round: the plain way back, whatever it does.
                back != null -> OffRouteDecision.Rejoin(back, 0.0)
                else -> OffRouteDecision.Replan
            }
        }
        // A loop or track is ridden for its line. A way back onto it that doesn't turn round (e.g. the wrong road
        // runs alongside) and costs next to nothing: that's it.
        val backRidesOn = back?.takeIf { !turnsBack(it.route, position, headingDeg) && !uTurnsAhead(it.route) }
        if (backRidesOn != null) {
            val onRoute = route.durationSeconds - Routes.secondsAt(route, leftAlong)
            if (backRidesOn.totalSeconds - onRoute <= ALWAYS_QUICK_S) return OffRouteDecision.Rejoin(backRidesOn, 0.0)
        }
        // Otherwise it is one way ahead among the others: riding on only to come all the way back to where the
        // route was left (round a loop the route would have ridden) must not win over joining it further on.
        val found = aheadWays(position, headingDeg, route, leftAlong, options)
        val best = (found.ways + listOfNotNull(backRidesOn)).filter { it.reallyOn() }.minByOrNull { it.totalSeconds }
        return when {
            best != null && (turnRound == null || isQuick(best, turnRound, maxExtraSeconds, maxExtraFraction)) ->
                OffRouteDecision.Rejoin(best, best.totalSeconds - (turnRound?.totalSeconds ?: best.totalSeconds))
            best != null -> OffRouteDecision.Ask(turnRound!!, best)
            // No way on: turning round is the only choice, so there is nothing to ask.
            turnRound != null -> OffRouteDecision.Rejoin(turnRound, 0.0, deadEnd = true)
            back != null -> OffRouteDecision.Rejoin(back, 0.0)
            else -> OffRouteDecision.Replan
        }
    }

    /**
     * Turning round where the rider is and riding back to the route just after where they left it, or null
     * when they can't turn round here (a one-way road, a slip road). The search itself avoids turning round
     * on a road, so the plain way [back] may ride on and round instead where turning is far shorter: the
     * way that starts the other way is asked for too, and the quicker of the two that do turn is it.
     */
    fun turnRound(position: LatLon, headingDeg: Double, route: Route, leftAlong: Double, options: RouteOptions, back: WayBack? = runCatching { back(position, route, leftAlong, options) }.getOrNull()): WayBack? {
        val plain = back?.takeIf { turnsBack(it.route, position, headingDeg) || uTurnsAhead(it.route) }
        val along = minOf(leftAlong + BACK_JOIN_M, route.distanceMeters)
        val turned = runCatching {
            val leg = engine.route(listOf(position, Routes.pointAt(route, along).first), options, startHeadingDeg = (headingDeg + 180) % 360, tracksAsLastResort = false)
            leg.takeIf { turnsBack(it, position, headingDeg) }?.let { join(it, route, along) }
        }.getOrNull()
        return listOfNotNull(plain, turned).minByOrNull { it.totalSeconds }
    }

    /** Ways back onto the route that keep riding ahead; [deadEnd] when no road leads on at all. */
    class AheadWays(val ways: List<WayBack>, val noGo: List<NoGo>, val deadEnd: Boolean)

    fun aheadWays(position: LatLon, headingDeg: Double, route: Route, leftAlong: Double, options: RouteOptions): AheadWays {
        val ends = JOIN_AHEAD_M.map { leftAlong + it }.filter { it < route.distanceMeters - 50 }
            .ifEmpty { listOf(route.distanceMeters) }
        // Find the ways back once (they are right behind the rider), then try each place to join.
        val (noGo, first) = forwardOnly(position, headingDeg, listOf(Routes.pointAt(route, ends.first()).first), options)
        val ways = ends.mapIndexedNotNull { i, along ->
            runCatching {
                val (target, routeBearing) = Routes.pointAt(route, along)
                val leg = if (i == 0) first else engine.route(listOf(position, target), options, startHeadingDeg = headingDeg, noGo = noGo, tracksAsLastResort = false)
                if (leg == null || turnsBack(leg, position, headingDeg) || uTurnsAhead(leg) || arrivesAgainst(leg, routeBearing)) null
                else join(leg, route, along)
            }.getOrNull()
        }
        return AheadWays(ways, noGo, deadEnd = first == null && ways.isEmpty())
    }

    /**
     * Best way back that does not turn round, joining the route somewhere after [leftAlong], or
     * null when every way back starts by turning round.
     */
    fun ahead(position: LatLon, headingDeg: Double, route: Route, leftAlong: Double, options: RouteOptions): WayBack? =
        aheadWays(position, headingDeg, route, leftAlong, options).ways.minByOrNull { it.totalSeconds }

    /** Back to the route just after where the rider left it, turning round if that is the way. */
    fun back(position: LatLon, route: Route, leftAlong: Double, options: RouteOptions): WayBack {
        val along = minOf(leftAlong + BACK_JOIN_M, route.distanceMeters)
        val leg = engine.route(listOf(position, Routes.pointAt(route, along).first), options, tracksAsLastResort = false)
        return join(leg, route, along)
    }

    /**
     * A new route through [waypoints] that starts the way the rider is going. Falls back to turning
     * round only when there is no other way (a dead end).
     */
    fun fromHere(position: LatLon, headingDeg: Double, waypoints: List<LatLon>, options: RouteOptions): Route =
        forwardOnly(position, headingDeg, waypoints, options).second
            ?: engine.route(listOf(position) + waypoints, options, startHeadingDeg = headingDeg, tracksAsLastResort = false, visitEveryVia = true)

    /**
     * Routes from [position] through [waypoints] without turning back the way the rider came.
     * BRouter can't be told "no U-turn at the start", and a no-go area on the rider's own road
     * segment would remove that segment altogether (the start then snaps to another road). So: route,
     * and while the route starts by going back, block that way just past the end of the rider's
     * segment and route again. Returns the no-go areas found and the route (null if there is none).
     */
    fun forwardOnly(
        position: LatLon, headingDeg: Double, waypoints: List<LatLon>, options: RouteOptions, known: List<NoGo> = emptyList()
    ): Pair<List<NoGo>, Route?> {
        val noGo = known.toMutableList()
        repeat(MAX_BLOCKS + 1) {
            val r = runCatching { engine.route(listOf(position) + waypoints, options, startHeadingDeg = headingDeg, noGo = noGo, tracksAsLastResort = false, visitEveryVia = true) }
                .getOrNull() ?: return noGo to null
            val block = when {
                turnsBack(r, position, headingDeg) -> blockBehind(r)
                uTurnsAhead(r) -> blockReturn(r, position)
                else -> return noGo to r
            }
            noGo += block ?: return noGo to null
        }
        return noGo to null
    }

    private fun join(leg: Route, route: Route, along: Double): WayBack {
        val rest = route.distanceMeters - along
        val combined = if (rest > 30) Routes.joinLegs(listOf(leg, Routes.slice(route, along))) else leg
        return WayBack(combined, along, leg.durationSeconds + (route.durationSeconds - Routes.secondsAt(route, along)))
    }

    companion object {
        /** Where on the route ahead to try joining it again, from where the rider left it. */
        val JOIN_AHEAD_M = listOf(400.0, 1_500.0, 4_000.0, 10_000.0)
        const val BACK_JOIN_M = 150.0
        /** A way that rides more than this much road a second time is out and back, not a way on. */
        const val RIDDEN_TWICE_M = 300.0
        /** At most this many ways back are blocked one after another (the branches of a junction behind). */
        const val MAX_BLOCKS = 4

        /**
         * A no-go circle on [goingBack]'s path just past the end of the first road segment (the one
         * the rider is on), so that segment stays usable for riding on ahead.
         */
        fun blockBehind(goingBack: Route): NoGo? {
            val segmentEnd = goingBack.points.drop(1).firstOrNull { it.distanceFromStart > 2 }?.distanceFromStart ?: return null
            if (segmentEnd + NO_GO_RADIUS_M + 5 > goingBack.distanceMeters) return null
            return NoGo(Routes.pointAt(goingBack, segmentEnd + NO_GO_RADIUS_M + 5).first, NO_GO_RADIUS_M)
        }

        /**
         * [goingBack] rides on a little, U-turns and comes back past the rider: a no-go circle on the
         * way back just past the far end of the rider's road segment (behind them).
         */
        fun blockReturn(goingBack: Route, position: LatLon): NoGo? {
            val u = goingBack.maneuvers.firstOrNull { it.type == ManeuverType.U_TURN } ?: return null
            val pts = goingBack.points
            // The segment of the way back that passes the rider; its far end is the node behind them.
            var best = -1
            var bestD = PASSING_M
            for (i in u.pointIndex until pts.lastIndex) {
                if (pts[i].distanceFromStart - u.distanceFromStart > 3_000) break
                val d = segmentDistance(position, pts[i], pts[i + 1])
                if (d < bestD) { bestD = d; best = i }
            }
            val along = if (best >= 0) pts[best + 1].distanceFromStart + NO_GO_RADIUS_M + 5
                else u.distanceFromStart + NO_GO_RADIUS_M + 5 // turns off before coming past: block just after the U-turn
            if (along > goingBack.distanceMeters) return null
            return NoGo(Routes.pointAt(goingBack, along).first, NO_GO_RADIUS_M)
        }

        private fun segmentDistance(p: LatLon, a: RoutePoint, b: RoutePoint): Double {
            val kx = 111_320.0 * kotlin.math.cos(Math.toRadians(p.lat))
            val ky = 110_574.0
            val ax = (a.lon - p.lon) * kx; val ay = (a.lat - p.lat) * ky
            val bx = (b.lon - p.lon) * kx; val by = (b.lat - p.lat) * ky
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            return kotlin.math.hypot(ax + t * dx, ay + t * dy)
        }

        private const val PASSING_M = 30.0

        /** True when [leg] starts by going back the way the rider came (a U-turn where they are). */
        fun turnsBack(leg: Route, position: LatLon, headingDeg: Double): Boolean {
            val start = leg.points.firstOrNull() ?: return false
            val firstFar = leg.points.firstOrNull { Geo.distance(start.lat, start.lon, it.lat, it.lon) > 30 } ?: return false
            return abs(Geo.angleDiff(headingDeg, Geo.bearing(start.lat, start.lon, firstFar.lat, firstFar.lon))) > 110
        }

        /**
         * True when [leg] turns round on the road a little further on (a U-turn instruction).
         * Riding on to a roundabout or round a block and back is fine: that is how you turn round
         * on a divided road, and not a U-turn on the road itself.
         */
        fun uTurnsAhead(leg: Route): Boolean =
            leg.maneuvers.any { it.type == ManeuverType.U_TURN && it.distanceFromStart < U_TURN_AHEAD_M }

        /** U-turns further on than this are part of the route, not a way of turning round here. */
        const val U_TURN_AHEAD_M = 2_000.0

        /** True when [leg] reaches the route riding against it (it would turn round on joining). */
        fun arrivesAgainst(leg: Route, routeBearingDeg: Double): Boolean {
            if (leg.distanceMeters < 60) return false
            val (_, arriving) = Routes.pointAt(leg, leg.distanceMeters - 20)
            return abs(Geo.angleDiff(arriving, routeBearingDeg)) > 120
        }

        /**
         * A way ahead is "quick" when it costs at most [maxExtraSeconds] more than turning back, and
         * at most [maxExtraFraction] of the trip that is left. Under [ALWAYS_QUICK_S] it always is:
         * asking about a minute is only a distraction.
         */
        fun isQuick(ahead: WayBack, back: WayBack, maxExtraSeconds: Double, maxExtraFraction: Double): Boolean {
            val extra = ahead.totalSeconds - back.totalSeconds
            return extra <= maxOf(ALWAYS_QUICK_S, minOf(maxExtraSeconds, maxExtraFraction * back.totalSeconds))
        }

        const val ALWAYS_QUICK_S = 60.0

        private const val NO_GO_RADIUS_M = 12.0
    }
}
