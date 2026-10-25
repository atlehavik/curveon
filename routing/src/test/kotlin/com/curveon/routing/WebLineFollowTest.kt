package com.curveon.routing

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A trip planned on the web is ridden as the page drew it (2026-10-25, Sandnes): a via point dragged out to the
 * roundabout at Lura made a loop there on the page, but Curveon routed the line again through a point every 1000 m
 * and rode out to the via point and back the same way instead. The line below is the page's own (Forus → the
 * roundabout → Sandnes, Curvy, no motorways or E-roads). Skipped without segments.
 */
class WebLineFollowTest {
    private lateinit var engine: BRouterEngine

    @Before
    fun setUp() {
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue("No BRouter segments at $segments", File(segments, "E5_N55.rd5").exists())
        engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("profiles").toFile()))
    }

    private val line = (
        "58.89460,5.73830;58.89511,5.73866;58.88840,5.72914;58.88603,5.72824;58.88586,5.72805;58.88559,5.72808;58.87981" +
        ",5.72588;58.87599,5.72459;58.87488,5.72472;58.87415,5.72531;58.87404,5.72404;58.87312,5.72458;58.87317,5.72535" +
        ";58.87382,5.72510;58.87381,5.72480;58.87382,5.72510;58.87330,5.72534;58.87317,5.72535;58.87314,5.72448;58.8725" +
        "7,5.72469;58.87146,5.72539;58.87102,5.72541;58.87052,5.72509;58.87043,5.72518;58.87019,5.72633;58.87015,5.7271" +
        "6;58.87077,5.73395;58.87079,5.73944;58.87084,5.73981;58.86865,5.74241;58.86355,5.74291;58.86284,5.74309;58.861" +
        "76,5.74381;58.86014,5.74389;58.85945,5.74358;58.85770,5.74245;58.85706,5.74161;58.85679,5.74159;58.85561,5.739" +
        "63;58.85488,5.73891;58.85460,5.73928;58.85450,5.73925;58.85215,5.73591;58.85082,5.73473;58.85025,5.73388;58.84" +
        "950,5.73516;58.84922,5.73541;58.84950,5.73656;58.84951,5.73700;58.84959,5.73706;58.84963,5.73687;58.84949,5.73" +
        "619").split(';').map { p -> p.split(',').let { LatLon(it[0].toDouble(), it[1].toDouble()) } }

    private val options = RouteOptions(RoadStyle.CURVY, SurfacePreference.PAVED, avoidMotorways = true, avoidTrunk = true)

    @Test
    fun aPlannedLineIsRiddenAsItWasDrawn() {
        val r = TrackFollower(engine).route(line.first(), line, options, fromStart = true, exact = true)
        val route = r.points.map { LatLon(it.lat, it.lon) }
        // Every part of the planned line is ridden (checked every 20 m along it) ...
        val missed = TrackFollower.sample(line, 20.0).map { it to offBy(it, route) }.filter { it.second > 40 }
        assertTrue("leaves the planned line at ${missed.take(5)} (${missed.size} places)", missed.isEmpty())
        // ... and nothing more: no stretch ridden that the line does not have (out and back the same way).
        val extra = TrackFollower.sample(route, 20.0).map { it to offBy(it, line) }.filter { it.second > 40 }
        assertTrue("rides off the planned line at ${extra.take(5)} (${extra.size} places)", extra.isEmpty())
        assertTrue("${r.distanceMeters.toInt()} m ridden, the line is ${length(line).toInt()} m", r.distanceMeters < length(line) * 1.1)
    }

    /** Via points out to the side round Lura that the old following cut off (it missed 320–540 m of the line). */
    @Test
    fun loopsOutToAViaPointAreRiddenWhole() {
        val end = LatLon(58.8496, 5.7361)
        for ((start, via) in listOf(LatLon(58.905, 5.75) to LatLon(58.865, 5.73), LatLon(58.8946, 5.7383) to LatLon(58.87, 5.73),
            LatLon(58.8946, 5.7460) to LatLon(58.8745, 5.7262))) {
            val planned = engine.route(listOf(start, via, end), options, visitEveryVia = true).points.map { LatLon(it.lat, it.lon) }
            val route = TrackFollower(engine).route(planned.first(), planned, options, fromStart = true, exact = true).points.map { LatLon(it.lat, it.lon) }
            val missed = TrackFollower.sample(planned, 20.0).filter { offBy(it, route) > 40 }
            assertTrue("via $via: leaves the planned line at ${missed.take(5)} (${missed.size * 20} m)", missed.isEmpty())
        }
    }

    private fun length(l: List<LatLon>) = l.zipWithNext().sumOf { (a, b) -> Geo.distance(a, b) }

    /** How far [p] is from the nearest part of [l], in metres. */
    private fun offBy(p: LatLon, l: List<LatLon>): Double {
        val kx = Math.cos(Math.toRadians(p.lat)) * 111_320.0
        val ky = 110_540.0
        return l.zipWithNext().minOf { (a, b) ->
            val ax = (a.lon - p.lon) * kx; val ay = (a.lat - p.lat) * ky
            val bx = (b.lon - p.lon) * kx; val by = (b.lat - p.lat) * ky
            val dx = bx - ax; val dy = by - ay
            val len = dx * dx + dy * dy
            val t = if (len == 0.0) 0.0 else (-(ax * dx + ay * dy) / len).coerceIn(0.0, 1.0)
            Math.hypot(ax + t * dx, ay + t * dy)
        }
    }
}
