package com.curveon.routing

import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

/**
 * Rides a lot of wrong turns on real roads and prints what navigation did about each, with a flag on
 * everything a rider would be cross about: sent back to the turn they skipped, the same road ridden both
 * ways, turning round where the road goes on, time thrown away, question after question. Not a pass/fail
 * test: run it with -Dcurveon.explore=true and read the list. -Dcurveon.ride=<file> replays a real ride.
 */
class WrongTurnExploreTest {

    private lateinit var engine: BRouterEngine
    private lateinit var planner: OffRoutePlanner

    @Before
    fun setUp() {
        assumeTrue(System.getProperty("curveon.explore") == "true")
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue(File(segments, "E5_N55.rd5").exists() && File(segments, "E5_N60.rd5").exists())
        engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("profiles").toFile()))
        planner = OffRoutePlanner(engine)
    }

    /** Someone riding: along [path], [seconds] into it. */
    private class Rider(var path: Route, var seconds: Double = 0.0) {
        val done get() = seconds >= path.durationSeconds
        fun position(): LatLon = Routes.pointAt(path, distanceAt(path, seconds)).first
        fun follow(route: Route, fromAlong: Double = 0.0) { path = route; seconds = Routes.secondsAt(route, fromAlong) }
    }

    private class Result(val label: String) {
        val flags = mutableListOf<String>()
        val events = mutableListOf<String>()
        var asks = 0; var reroutes = 0; var arrived = false
        var riddenSeconds = 0.0
        val ridden = mutableListOf<LatLon>()
    }

    /**
     * The app's side, one fix a second: the tracker, the 10 s between recalculations, the question that
     * answers itself after 20 s and 150 m ("carry on"), and five minutes of no more questions after it.
     * [next] gives the rider's next fix (null: stopped riding); it is told each new route the app adopts.
     */
    private fun navigate(
        label: String, planned: Route, destination: LatLon, options: RouteOptions, isTrip: Boolean,
        skipped: LatLon? = null, next: (t: Double, appRoute: Route, progress: NavigationProgress?, adopted: Boolean) -> LatLon?
    ): Result {
        val res = Result(label)
        var route = planned
        var tracker = NavigationTracker(route)
        var lastCalc = -100.0; var followUntil = -1.0
        var askedAt: Double? = null; var askedPos: LatLon? = null
        var heading: Double? = null; var anchor: LatLon? = null
        var progress: NavigationProgress? = null
        var adopted = false
        var t = 0.0
        val trip = if (isTrip) listOf(destination) else null

        fun adopt(r: Route, pos: LatLon, what: String, deadEnd: Boolean) {
            route = r; tracker = NavigationTracker(r); adopted = true; res.reroutes++
            val h = heading
            val notes = mutableListOf<String>()
            if (!deadEnd && h != null && OffRoutePlanner.turnsBack(r, pos, h)) notes += "TURNS-ROUND"
            if (!deadEnd && OffRoutePlanner.uTurnsAhead(r)) notes += "U-TURN-AHEAD"
            if (Routes.riddenTwiceMeters(r) > OffRoutePlanner.RIDDEN_TWICE_M) notes += "SAME-ROAD-BOTH-WAYS"
            if (isTrip && skipped != null && Geo.distance(pos, skipped) > 250 && r.points.any { Geo.distance(it.lat, it.lon, skipped.lat, skipped.lon) < 40 }) notes += "BACK-TO-SKIPPED-TURN"
            res.flags += notes
            res.events += "%5.0f s  %s -> %.1f km %.0f min %s".format(t, what, r.distanceMeters / 1000, r.durationSeconds / 60, notes.joinToString(" "))
        }

        while (t < 6 * 3600) {
            val pos = next(t, route, progress, adopted) ?: break
            adopted = false
            res.ridden += pos
            val a = anchor
            if (a == null || Geo.distance(a, pos) > 200) anchor = pos
            else if (Geo.distance(a, pos) >= 20) { heading = Geo.bearing(a.lat, a.lon, pos.lat, pos.lon); anchor = pos }
            val p = tracker.update(pos, 5.0, heading)
            progress = p
            if (p.arrived) { res.arrived = true; break }
            val asked = askedAt
            when {
                asked != null && !p.offRoute -> askedAt = null
                asked != null -> if (t >= asked + 20 && Geo.distance(askedPos!!, pos) > 150) {
                    askedAt = null; lastCalc = t; followUntil = t + 300
                    val h = heading
                    val r = runCatching {
                        if (isTrip) (if (h != null) planner.fromHere(pos, h, listOf(destination), options) else engine.route(listOf(pos, destination), options))
                        else (h?.let { planner.ahead(pos, it, route, p.distanceAlongRoute, options)?.route } ?: planner.back(pos, route, p.distanceAlongRoute, options).route)
                    }.getOrNull()
                    if (r != null) adopt(r, pos, "carried on by itself", false) else res.events += "%5.0f s  carry on FAILED".format(t)
                }
                p.offRoute && t - lastCalc >= 10 -> {
                    lastCalc = t
                    val following = t < followUntil && heading != null
                    val d = runCatching {
                        planner.decide(pos, heading, route, p.distanceAlongRoute, options,
                            if (following) Double.MAX_VALUE else 180.0, if (following) Double.MAX_VALUE else 0.10, trip)
                    }.getOrNull()
                    when (d) {
                        is OffRouteDecision.Rejoin -> adopt(d.way.route, pos, if (d.deadEnd) "dead end, turn round" else "new route (%+.0f s against turning round)".format(d.extraSeconds), d.deadEnd)
                        is OffRouteDecision.Ask -> {
                            res.asks++; askedAt = t; askedPos = pos
                            res.events += "%5.0f s  ASKS: turn back, or carry on %s".format(t, d.ahead?.let { "(+%.0f min)".format((it.totalSeconds - d.back.totalSeconds) / 60) } ?: "(no way ahead found)")
                        }
                        OffRouteDecision.Replan -> runCatching { engine.route(listOf(pos, destination), options) }.getOrNull()?.let { adopt(it, pos, "replanned", false) }
                        null -> res.events += "%5.0f s  decision FAILED".format(t)
                    }
                }
            }
            t += 1.0
        }
        res.riddenSeconds = t
        return res
    }

    /** Metres of [path] ridden along a road it has already ridden (the other way or the same). */
    private fun backtrackMeters(path: List<LatLon>): Double {
        // Thinned to a point every ~40 m.
        val pts = mutableListOf<Pair<LatLon, Double>>()
        var along = 0.0
        path.forEachIndexed { i, p ->
            if (i > 0) along += Geo.distance(path[i - 1], p)
            if (pts.isEmpty() || along - pts.last().second >= 40) pts += p to along
        }
        var again = 0
        for ((i, e) in pts.withIndex()) {
            var j = 0
            while (j < i && pts[j].second < e.second - 500) { if (Geo.distance(pts[j].first, e.first) < 20) { again++; break }; j++ }
        }
        return again * 40.0
    }

    private fun report(r: Result, extra: String = "") {
        val flags = r.flags.groupingBy { it }.eachCount().map { (k, n) -> if (n > 1) "$k x$n" else k }
        println("%s %s: %s, %d new routes, %d questions%s%s".format(if (flags.isEmpty()) "ok  " else "!!  ", r.label,
            if (r.arrived) "arrived after %.0f min".format(r.riddenSeconds / 60) else "ride ended after %.0f min".format(r.riddenSeconds / 60),
            r.reroutes, r.asks, extra, if (flags.isEmpty()) "" else "  <-- " + flags.joinToString(", ")))
        if (flags.isNotEmpty() || r.asks > 0 || r.reroutes > 4) r.events.forEach { println("        $it") }
    }

    private val places = listOf(
        Triple("Stord-Stavland", LatLon(59.759636, 5.443022), LatLon(59.794100, 5.144630)),
        Triple("Leirvik-Langevåg", LatLon(59.7797, 5.5003), LatLon(59.6080, 5.2060)),
        Triple("Haugesund-Skudeneshavn", LatLon(59.4136, 5.2680), LatLon(59.1486, 5.2570)),
        Triple("Os-Bergen", LatLon(60.1880, 5.4700), LatLon(60.3913, 5.3221)),
        Triple("Bergen-Norheimsund", LatLon(60.3913, 5.3221), LatLon(60.3700, 6.1450)),
        Triple("Voss-Odda", LatLon(60.6280, 6.4180), LatLon(60.0690, 6.5460)),
        Triple("Rosendal-Odda", LatLon(59.9860, 6.0090), LatLon(60.0690, 6.5460)),
        Triple("Stavanger-Egersund", LatLon(58.9700, 5.7331), LatLon(58.4510, 5.9990)),
        Triple("Førde-Sogndal", LatLon(61.4520, 5.8570), LatLon(61.2290, 7.1000)),
        Triple("Molde-Kristiansund", LatLon(62.7375, 7.1591), LatLon(63.1105, 7.7280)),
        Triple("Knarvik-Mongstad", LatLon(60.5460, 5.2850), LatLon(60.8050, 5.0350)),
        Triple("Sotra-Bergen", LatLon(60.2960, 5.0900), LatLon(60.3913, 5.3221))
    )

    @Test
    fun `the real ride that sent the rider back`() {
        val file = System.getProperty("curveon.ride")?.let(::File)?.takeIf { it.exists() } ?: return
        val fixes = file.readLines().filter { it.isNotBlank() }.map { l -> l.split(" ").let { LatLon(it[0].toDouble(), it[1].toDouble()) } }
        val curvy = RouteOptions(RoadStyle.CURVY)
        val home = LatLon(59.794100, 5.144630)
        val planned = engine.route(listOf(fixes.first(), home), curvy)
        println("\n== The real ride, Stord → Stavlandsvegen (curvy), ${fixes.size} fixes; planned %.1f km %.0f min".format(planned.distanceMeters / 1000, planned.durationSeconds / 60))
        for (trip in listOf(true, false)) {
            var i = 0
            val r = navigate(if (trip) "as a trip" else "as a saved track", planned, home, curvy, trip) { _, _, _, _ -> fixes.getOrNull(i++) }
            report(r)
            r.events.forEach { println("        $it") }
        }
    }

    /**
     * At turns of the plan the rider goes straight on instead. "Stubborn" then rides the quickest way to the
     * destination whatever the app says; "obedient" rides straight on for 800 m and after that does what the
     * app says, so what they end up riding is what the app made of it.
     */
    @Test
    fun `straight on past the turns of a plan`() {
        val fast = RouteOptions(RoadStyle.FASTEST)
        var runs = 0; var flagged = 0; var wasted = 0
        for ((name, from, to) in places) for (style in listOf(RoadStyle.CURVY, RoadStyle.FASTEST)) {
            val options = RouteOptions(style)
            val planned = runCatching { engine.route(listOf(from, to), options) }.getOrNull() ?: continue
            println("\n== $name, ${style.name.lowercase()}: %.1f km %.0f min".format(planned.distanceMeters / 1000, planned.durationSeconds / 60))
            val turns = planned.maneuvers.filter { it.distanceFromStart > 400 && it.distanceFromStart < planned.distanceMeters - 1500 }
            val picked = if (turns.size <= 5) turns else (0 until 5).map { turns[it * (turns.size - 1) / 4] }
            for (m in picked.distinct()) {
                val (at, _) = Routes.pointAt(planned, m.distanceFromStart)
                val bearing = Routes.pointAt(planned, m.distanceFromStart - 30).second
                // The road straight on, if there is one that is not the plan.
                val straight = listOf(0.0, 25.0, -25.0).firstNotNullOfOrNull { off ->
                    runCatching { engine.route(listOf(at, Geo.destination(at, bearing + off, 1_500.0)), fast, startHeadingDeg = bearing) }.getOrNull()?.takeIf { leg ->
                        if (leg.distanceMeters < 900 || OffRoutePlanner.turnsBack(leg, at, bearing)) return@takeIf false
                        val probe = Routes.pointAt(leg, 400.0).first
                        planned.points.none { abs(it.distanceFromStart - m.distanceFromStart) < 3_000 && Geo.distance(it.lat, it.lon, probe.lat, probe.lon) < 80 }
                    }
                } ?: continue
                val leftAt = Routes.secondsAt(planned, m.distanceFromStart)
                val ideal = runCatching { engine.route(listOf(Routes.pointAt(straight, 150.0).first, to), options, startHeadingDeg = Routes.pointAt(straight, 150.0).second) }.getOrNull()
                for (trip in listOf(true, false)) for (obedient in listOf(true, false)) {
                    val (endAt, endBearing) = Routes.pointAt(straight, straight.distanceMeters - 5)
                    val onward = if (obedient) null else runCatching { engine.route(listOf(endAt, to), fast, startHeadingDeg = endBearing) }.getOrNull()
                    val rider = Rider(planned)
                    var stage = 0 // 0 the plan, 1 straight on, 2 obedient / the quickest way
                    val label = "%s at %.1f km (%s), %s rider, %s".format(m.type.name.lowercase(), m.distanceFromStart / 1000, "%.5f,%.5f".format(at.lat, at.lon), if (obedient) "obedient" else "stubborn", if (trip) "trip" else "track")
                    val r = navigate(label, planned, to, options, trip, skipped = at) { _, appRoute, p, adopted ->
                        if (stage == 0 && rider.seconds >= leftAt) { rider.follow(straight); stage = 1 }
                        if (stage == 1) {
                            if (obedient && distanceAt(rider.path, rider.seconds) >= 800 && p != null && !p.offRoute) { rider.follow(appRoute, p.distanceAlongRoute); stage = 2 }
                            else if (!obedient && rider.done && onward != null) { rider.follow(onward); stage = 2 }
                        } else if (stage == 2 && obedient && adopted) rider.follow(appRoute)
                        if (rider.done) null else rider.position().also { rider.seconds += 1.0 }
                    }
                    runs++
                    val rode = backtrackMeters(r.ridden)
                    if (obedient && rode > 300) r.flags += "RODE-%.1f-KM-TWICE".format(rode / 1000)
                    var extra = ""
                    if (obedient && trip && ideal != null && r.arrived) {
                        val waste = r.riddenSeconds - leftAt - ideal.durationSeconds
                        extra = ", %+.0f s against a fresh route from the turn".format(waste)
                        if (waste > maxOf(120.0, 0.10 * ideal.durationSeconds)) { r.flags += "WASTED-%.0f-MIN".format(waste / 60); wasted++ }
                    }
                    if (obedient && !r.arrived) r.flags += "NEVER-ARRIVED"
                    if (r.asks > 1) r.flags += "ASKED-${r.asks}-TIMES"
                    if (r.reroutes > 6 && obedient) r.flags += "KEPT-RECALCULATING"
                    if (r.flags.isNotEmpty()) flagged++
                    report(r, extra)
                }
            }
        }
        println("\n== $runs rides, $flagged with something a rider would be cross about ($wasted wasted time)")
    }

    private companion object {
        fun distanceAt(r: Route, t: Double): Double {
            val pts = r.points
            val i = pts.indexOfFirst { it.secondsFromStart >= t }
            if (i <= 0) return if (i == 0) 0.0 else r.distanceMeters
            val a = pts[i - 1]; val b = pts[i]
            val span = b.secondsFromStart - a.secondsFromStart
            return a.distanceFromStart + if (span > 0) (b.distanceFromStart - a.distanceFromStart) * (t - a.secondsFromStart) / span else 0.0
        }
    }
}
