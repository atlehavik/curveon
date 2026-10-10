package com.curveon.routing

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A stop just off the way (a side street in Haugesund, on the way from Bømlo to the centre) is
 * ridden to, not ridden past: BRouter on its own cuts the out-and-back to it short (by 200 m here).
 */
class StopsVisitedTest {
    private val segments = File(System.getProperty("curveon.segments") ?: "")

    @Test
    fun `stops down a side street are visited`() {
        assumeTrue(File(segments, "E5_N55.rd5").exists())
        val engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("p").toFile()))
        val from = LatLon(59.7790, 5.2050) // Bømlo
        val to = LatLon(59.413807, 5.268125) // Haugesund centre
        val o = RouteOptions(RoadStyle.FASTEST, SurfacePreference.PAVED)
        for (stop in listOf(LatLon(59.4247, 5.2687))) { // Stiklestadgata x Rogalandsgata, Haugesund
            // Where the stop meets the road network: the end of a route to it.
            val atStop = engine.route(listOf(from, stop), o).points.last().let { LatLon(it.lat, it.lon) }
            val r = engine.route(listOf(from, stop, to), o, visitEveryVia = true)
            val gap = r.points.minOf { Geo.distance(atStop, LatLon(it.lat, it.lon)) }
            println("stop $stop: route passes %.0f m from it".format(gap))
            assertTrue("route rides past the stop at $stop (%.0f m away)".format(gap), gap < 15)
        }
    }

    @Test
    fun `a trip without stops is one way, not there and back`() {
        assumeTrue(File(segments, "E5_N55.rd5").exists())
        val engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("p").toFile()))
        val from = LatLon(59.7790, 5.2050) // Bømlo
        val to = LatLon(59.413807, 5.268125) // Haugesund centre
        val o = RouteOptions(RoadStyle.FASTEST, SurfacePreference.PAVED)
        val plain = engine.route(listOf(from, to), o)
        val r = engine.route(listOf(from, to), o, visitEveryVia = true)
        println("Bømlo-Haugesund %.0f km, with stops visited %.0f km".format(plain.distanceMeters / 1000, r.distanceMeters / 1000))
        assertTrue("there and back: %.0f km".format(r.distanceMeters / 1000), r.distanceMeters < plain.distanceMeters * 1.05)
        val end = r.points.last()
        assertTrue("ends back at the start", Geo.distance(to, LatLon(end.lat, end.lon)) < 500)
    }
}
