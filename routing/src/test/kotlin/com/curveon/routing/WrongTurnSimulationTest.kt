package com.curveon.routing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Rides real roads that differ from the planned route, one GPS fix a second, through the same
 * tracker and off-route decisions the app uses, and checks what navigation would do. Skipped when
 * the Norway segment files are not available.
 */
class WrongTurnSimulationTest {

    private lateinit var engine: BRouterEngine
    private lateinit var planner: OffRoutePlanner

    @Before
    fun setUp() {
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue("No BRouter segments at $segments", File(segments, "E5_N60.rd5").exists())
        engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("profiles").toFile()))
        planner = OffRoutePlanner(engine)
    }

    private data class Event(val atKm: Double, val what: String)

    private data class Outcome(val events: List<Event>, val arrived: Boolean) {
        val rejoins get() = events.count { it.what.startsWith("rejoin") }
        val asks get() = events.count { it.what.startsWith("ask") }
        val turnRounds get() = events.count { it.what.contains("turning round") }
    }

    /**
     * Rides [ridden] while navigating [planned] to its end. When asked, the rider "carries on"
     * (what the app does by itself after 20 s), i.e. a new route from here to the destination.
     */
    private fun ride(planned: Route, ridden: Route, options: RouteOptions, label: String): Outcome {
        val destination = planned.points.last().let { LatLon(it.lat, it.lon) }
        var route = planned
        var tracker = NavigationTracker(route)
        var lastCalcAt = -100.0
        var followUntil = -1.0
        val events = mutableListOf<Event>()
        var prev: LatLon? = null
        var heading: Double? = null
        var arrived = false
        var t = 0.0
        while (t <= ridden.durationSeconds) {
            val (pos, _) = Routes.pointAt(ridden, distanceAtSeconds(ridden, t))
            prev?.let { p -> if (Geo.distance(p, pos) > 3) heading = Geo.bearing(p.lat, p.lon, pos.lat, pos.lon) }
            prev = pos
            val progress = tracker.update(pos, 5.0)
            if (progress.arrived) { arrived = true; break }
            if (progress.offRoute && t - lastCalcAt >= 10) {
                lastCalcAt = t
                val km = distanceAtSeconds(ridden, t) / 1000
                // Like the app: after "carry on", further wrong turns for 5 minutes are taken without asking.
                val following = t < followUntil
                val d = planner.decide(pos, heading, route, progress.distanceAlongRoute, options,
                    if (following) Double.MAX_VALUE else 180.0, if (following) Double.MAX_VALUE else 0.10, listOf(destination))
                when (d) {
                    is OffRouteDecision.Rejoin -> {
                        if (!d.deadEnd) heading?.let { h -> assertFalse("$label: quiet rejoin at %.1f km turns round".format(km), OffRoutePlanner.turnsBack(d.way.route, pos, h)) }
                        events += Event(km, (if (d.deadEnd) "dead end, " else "") + "rejoin +%.0f s at %.1f km of route".format(d.extraSeconds, d.way.joinAlong / 1000))
                        route = d.way.route
                    }
                    is OffRouteDecision.Ask -> {
                        events += Event(km, "ask: ahead " + (d.ahead?.let { "+%.0f s".format(it.totalSeconds - d.back.totalSeconds) } ?: "none"))
                        val (blocks, forward) = planner.forwardOnly(pos, heading!!, listOf(destination), options)
                        route = forward ?: planner.fromHere(pos, heading!!, listOf(destination), options)
                        followUntil = t + 300
                        events += Event(km, if (forward == null) "  carry on: no way ahead, turning round" else "  carry on: new route (${blocks.size} ways back blocked)")
                        if (forward != null) assertFalse("$label: new route from here turns round", OffRoutePlanner.turnsBack(route, pos, heading!!))
                    }
                    OffRouteDecision.Replan -> {
                        events += Event(km, "replan")
                        route = engine.route(listOf(pos, destination), options)
                    }
                }
                tracker = NavigationTracker(route)
            }
            t += 1.0
        }
        println("== $label: ${if (arrived) "arrived" else "ride ended"} after %.1f km, %d events".format(distanceAtSeconds(ridden, t) / 1000, events.size))
        events.forEach { println("   %6.1f km  %s".format(it.atKm, it.what)) }
        return Outcome(events, arrived)
    }

    private fun distanceAtSeconds(r: Route, t: Double): Double {
        val pts = r.points
        val i = pts.indexOfFirst { it.secondsFromStart >= t }
        if (i <= 0) return if (i == 0) 0.0 else r.distanceMeters
        val a = pts[i - 1]; val b = pts[i]
        val span = b.secondsFromStart - a.secondsFromStart
        return a.distanceFromStart + if (span > 0) (b.distanceFromStart - a.distanceFromStart) * (t - a.secondsFromStart) / span else 0.0
    }

    private val curvy = RouteOptions(RoadStyle.CURVY)
    private val fast = RouteOptions(RoadStyle.FASTEST)

    @Test
    fun `riding the planned route raises nothing`() {
        val a = LatLon(60.6280, 6.4180) // Voss
        val b = LatLon(60.5560, 6.7180) // Granvin
        val planned = engine.route(listOf(a, b), fast)
        val o = ride(planned, planned, fast, "on route")
        assertTrue(o.arrived)
        assertTrue("no events expected: ${o.events}", o.events.isEmpty())
    }

    @Test
    fun `a short detour off the route and back is mostly rejoined quietly`() {
        val a = LatLon(60.3913, 5.3221) // Bergen centre
        val b = LatLon(60.3000, 5.2700) // Fana-ish
        val planned = engine.route(listOf(a, b), fast)
        // Leave the route for a side street halfway and come back to it.
        val (mid, bearing) = Routes.pointAt(planned, planned.distanceMeters / 2)
        val side = Geo.destination(mid, bearing + 90, 250.0)
        val ridden = engine.route(listOf(a, side, b), fast)
        val o = ride(planned, ridden, fast, "short detour")
        assertTrue(o.arrived)
        // The side street here is a dead end and the way round costs +81 s on ~12 min left: over the
        // default 10 %, so one question is right. No more than that.
        assertTrue("asked too often: ${o.events}", o.asks <= 1)
        assertTrue("short detour should need few recalculations: ${o.events}", o.events.size <= 7)
    }

    @Test
    fun `heading off on a different road far from the route asks`() {
        val voss = LatLon(60.6280, 6.4180)
        val bergen = LatLon(60.3913, 5.3221)
        val granvin = LatLon(60.5560, 6.7180)
        val planned = engine.route(listOf(voss, bergen), fast)
        val ridden = engine.route(listOf(voss, granvin), fast)
        val o = ride(planned, ridden, fast, "wrong way out of Voss")
        assertTrue("expected a question: ${o.events}", o.asks >= 1)
        // After carrying on, further wrong turns for a while are taken without asking.
        assertTrue("expected a question, not too many: ${o.events}", o.asks in 1..2)
    }

    @Test
    fun `riding the fastest roads while navigating a curvy plan`() {
        val cases = listOf(
            "Voss-Odda" to (LatLon(60.6280, 6.4180) to LatLon(60.0690, 6.5460)),
            "Bergen-Norheimsund" to (LatLon(60.3913, 5.3221) to LatLon(60.3700, 6.1450)),
            "Førde-Sogndal" to (LatLon(61.4520, 5.8570) to LatLon(61.2290, 7.1000))
        )
        for ((name, ends) in cases) {
            val planned = engine.route(listOf(ends.first, ends.second), curvy)
            val ridden = engine.route(listOf(ends.first, ends.second), fast)
            val o = ride(planned, ridden, curvy, name)
            assertTrue("$name: rider should get there", o.arrived)
            assertTrue("$name: asked too often: ${o.events}", o.asks <= 3)
        }
    }
}
