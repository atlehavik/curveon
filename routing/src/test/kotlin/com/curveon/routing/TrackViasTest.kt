package com.curveon.routing

import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Makes a real GPX track into via points over Norway data; skipped without segments or a track in CURVEON_TEST_TRACK ("lat lon" per line). */
class TrackViasTest {
    private lateinit var engine: BRouterEngine

    @Before
    fun setUp() {
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue("No BRouter segments at $segments", File(segments, "E10_N55.rd5").exists())
        engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("profiles").toFile()))
    }

    /** Share of the track (by 100 m samples) further than [limit] from [route]. */
    private fun offShare(track: List<LatLon>, route: Route, limit: Double): Double {
        val pts = TrackFollower.sample(track, 100.0)
        val line = route.points.map { LatLon(it.lat, it.lon) }
        val off = pts.count { p -> line.minOf { Geo.distance(p, it) } > limit }
        return off.toDouble() / pts.size
    }

    @Test
    fun routeThroughViasKeepsToTheTrack() {
        val path = System.getenv("CURVEON_TEST_TRACK")
        assumeTrue("No track", path != null && File(path).exists())
        val track = File(path!!).readLines().filter { it.isNotBlank() }.map { l -> l.split(" ").let { LatLon(it[0].toDouble(), it[1].toDouble()) } }
        val options = RouteOptions()
        val t0 = System.currentTimeMillis()
        val vias = TrackFollower(engine).viaPoints(track, options)
        val t1 = System.currentTimeMillis()
        val route = engine.route(listOf(track.first()) + vias + track.last(), options)
        println("vias=${vias.size} in ${t1 - t0} ms, route ${route.distanceMeters / 1000} km, track off by >300 m: %.1f %%".format(offShare(track, route, 300.0) * 100))
        println("  >120 m: %.1f %%".format(offShare(track, route, 120.0) * 100))
        val old = com.curveon.routing.TrackFollower.sample(track, 1e9) // ends only
        val coarse = engine.route(listOf(track.first()) + (1..29).map { track[track.size * it / 30] } + track.last(), options)
        println("old 30 vias: route ${coarse.distanceMeters / 1000} km, >300 m: %.1f %%".format(offShare(track, coarse, 300.0) * 100))
    }
}
