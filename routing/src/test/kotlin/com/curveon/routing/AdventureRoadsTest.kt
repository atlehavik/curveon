package com.curveon.routing

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * An adventure bike rides any road: a destination out on a forest or farm track is reached on
 * that track whatever the surface setting (Tarmac just makes tracks a last resort).
 */
class AdventureRoadsTest {
    private val segments = File(System.getProperty("curveon.segments") ?: "")
    private val home = LatLon(59.794257, 5.144397)

    private fun endGap(to: LatLon, surface: SurfacePreference): Double {
        val engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("p").toFile()))
        val r = engine.route(listOf(home, to), RouteOptions(RoadStyle.CURVY, surface))
        val last = r.points.last()
        return Geo.distance(to, LatLon(last.lat, last.lon)).also {
            println("$surface -> %s: ends %.0f m away, last road %s".format(to, it, r.points.last().highway))
        }
    }

    @Test
    fun `tracks are reachable with every surface setting`() {
        assumeTrue(File(segments, "E5_N55.rd5").exists())
        val gravelTrack = LatLon(59.81316, 5.375099)   // way 537278368, no tracktype, no access tags
        val privateGrassTrack = LatLon(59.783324, 5.126093) // way 1330351121, grade4, access=private
        for (surface in SurfacePreference.entries) {
            assertTrue("gravel track, $surface", endGap(gravelTrack, surface) < 30)
            assertTrue("grade4 track, $surface", endGap(privateGrassTrack, surface) < 30)
        }
    }

    @Test
    fun `tarmac trips between towns never take a track as a short cut`() {
        assumeTrue(File(segments, "E5_N55.rd5").exists())
        val engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("p").toFile()))
        val trips = listOf(home to LatLon(60.39, 5.32), LatLon(60.628, 6.415) to LatLon(60.39, 5.32), home to LatLon(59.41, 5.27))
        for ((from, to) in trips) for (style in RoadStyle.entries) {
            val r = engine.route(listOf(from, to), RouteOptions(style, SurfacePreference.PAVED))
            val trackM = r.points.zipWithNext().filter { (_, b) -> b.highway == "track" }.sumOf { (a, b) -> b.distanceFromStart - a.distanceFromStart }
            println("$style $from -> $to: %.0f km, %.0f m on tracks".format(r.distanceMeters / 1000, trackM))
            assertTrue("$style track metres $trackM", trackM < 1.0)
        }
    }
}
