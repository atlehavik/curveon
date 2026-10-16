package com.curveon.data.routing

import com.curveon.protocol.Message.CueSide
import com.curveon.protocol.Message.NavCue
import com.curveon.routing.ManeuverType
import com.curveon.routing.NavigationProgress
import com.curveon.routing.Route

/** Navigation alerts (Settings → Navigation → Sound): none, sounds, or spoken. */
enum class NavAlerts { OFF, SOUNDS, VOICE }

/** Something to tell the rider: its [sound], in which ear, and for rejoining whether by a new route. */
data class Cue(val sound: NavCue, val side: CueSide = CueSide.CENTER, val newRoute: Boolean = false)

/**
 * Decides when a navigation sound plays, from the rider's progress along the route: one before
 * each turn and a tick at it (timed by speed, so they come as early on a highway as in town),
 * one on arriving, and for riding off the route one when it happens, one when the rider is asked
 * what to do and one when a new route is ready or they are back on it. Each plays once.
 */
class NavCues {
    var alerts = NavAlerts.SOUNDS
    var offRouteAlerts = true

    private var route: Route? = null
    /** The maneuver (by distance along the route) whose sounds have played. */
    private var prepared: Double? = null
    private var turned: Double? = null
    private var wasOff = false
    private var wasAsking = false
    private var wasArrived = false
    private var lastCueAt = Long.MIN_VALUE / 2
    private var lastOffAt = Long.MIN_VALUE / 2

    /** Navigation stopped: the next route starts afresh. */
    fun reset() {
        route = null; prepared = null; turned = null
        wasOff = false; wasAsking = false; wasArrived = false
    }

    fun update(
        route: Route, p: NavigationProgress, speedMps: Double,
        asking: Boolean, approaching: Boolean, nowMs: Long
    ): List<Cue> {
        val out = mutableListOf<Cue>()
        fun cue(c: NavCue, side: CueSide = CueSide.CENTER, newRoute: Boolean = false) { out += Cue(c, side, newRoute); lastCueAt = nowMs }

        if (route !== this.route) {
            // A new route while off the old one: a reroute is ready.
            if (this.route != null && wasOff && offRouteAlerts) cue(NavCue.BACK_ON_ROUTE, newRoute = true)
            this.route = route
            prepared = null; turned = null; wasOff = false; wasArrived = p.arrived
        }

        val off = p.offRoute && !approaching
        if (offRouteAlerts) {
            if (off && !wasOff && nowMs - lastOffAt >= OFF_ROUTE_REPEAT_MS) { cue(NavCue.OFF_ROUTE); lastOffAt = nowMs }
            if (!off && wasOff) cue(NavCue.BACK_ON_ROUTE)
            if (asking && !wasAsking) cue(NavCue.CHOOSE)
        }
        wasOff = off
        wasAsking = asking

        if (alerts != NavAlerts.OFF) {
            if (p.arrived && !wasArrived) cue(NavCue.ARRIVE)
            else if (!off && !approaching && !p.arrived) maneuverCue(p, speedMps, nowMs)?.let { cue(it.first, it.second) }
        }
        wasArrived = p.arrived
        return out
    }

    private fun maneuverCue(p: NavigationProgress, speedMps: Double, nowMs: Long): Pair<NavCue, CueSide>? {
        val m = p.nextManeuver
        val side = sideOf(m.type) ?: return null
        val key = m.distanceFromStart
        val v = maxOf(speedMps, MIN_SPEED_MPS)
        val d = p.distanceToNextManeuver
        val turnAt = (v * (TURN_S + LAG_S)).coerceIn(TURN_MIN_M, TURN_MAX_M)
        val prepareAt = (v * (PREPARE_S + LAG_S)).coerceIn(PREPARE_MIN_M, PREPARE_MAX_M)
        return when {
            d <= turnAt -> if (turned == key) null else { turned = key; prepared = key; NavCue.TURN to side }
            // Not right after another sound (turns close together): it comes on the next fix instead.
            d <= prepareAt && prepared != key && d > turnAt + v * PREPARE_GAP_S && nowMs - lastCueAt >= MIN_GAP_MS ->
                { prepared = key; NavCue.PREPARE to side }
            else -> null
        }
    }

    companion object {
        /** Which ear a maneuver sounds in; null for those that need no sound. */
        fun sideOf(type: ManeuverType): CueSide? = when (type) {
            ManeuverType.SLIGHT_LEFT, ManeuverType.LEFT, ManeuverType.SHARP_LEFT, ManeuverType.KEEP_LEFT,
            ManeuverType.EXIT_LEFT, ManeuverType.U_TURN -> CueSide.LEFT
            ManeuverType.SLIGHT_RIGHT, ManeuverType.RIGHT, ManeuverType.SHARP_RIGHT, ManeuverType.KEEP_RIGHT,
            ManeuverType.EXIT_RIGHT -> CueSide.RIGHT
            ManeuverType.ROUNDABOUT -> CueSide.CENTER
            ManeuverType.DEPART, ManeuverType.STRAIGHT, ManeuverType.ARRIVE -> null
        }

        /** Slower than this (about 30 km/h) the sounds keep the distances they have at it. */
        const val MIN_SPEED_MPS = 8.0
        const val PREPARE_S = 12.0
        const val PREPARE_MIN_M = 80.0
        const val PREPARE_MAX_M = 600.0
        const val TURN_S = 3.0
        const val TURN_MIN_M = 30.0
        const val TURN_MAX_M = 160.0
        /**
         * How far behind the rider a sound lands: the GPS fix is ~1.5 s old (see RiderMotion's lead),
         * fixes come once a second, and the sound has its lead-in and the Bluetooth delay. Without it
         * the tick came as the rider was already turning (demo ride, 2026-10-16).
         */
        const val LAG_S = 2.0
        /** The heads-up must come at least this long before the tick, or it is left out. */
        const val PREPARE_GAP_S = 3.0
        const val MIN_GAP_MS = 2_000L
        /** Off the route again soon after: no second sound for this long. */
        const val OFF_ROUTE_REPEAT_MS = 30_000L
    }
}
