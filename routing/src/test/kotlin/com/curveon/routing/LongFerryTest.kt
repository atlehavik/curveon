package com.curveon.routing

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Bømlo to Florø or Måløy is a ride up the coast, not a ride to Bergen and the Hurtigruten from
 * there, also when avoiding trunk roads (the E39 along the coast).
 */
class LongFerryTest {
    private fun ferryKm(r: Route) = r.points.zipWithNext().sumOf { (a, b) ->
        if (b.ferry) b.distanceFromStart - a.distanceFromStart else 0.0
    } / 1000

    @Test
    fun `curvy styles ride rather than sail up the coast`() {
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue(File(segments, "E5_N55.rd5").exists())
        val engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("p").toFile()))
        val bomlo = LatLon(59.7790, 5.2170)
        val ends = mapOf("Florø" to LatLon(61.5996, 5.0328), "Måløy" to LatLon(61.9353, 5.1136))
        for ((name, end) in ends) for (style in RoadStyle.entries) for (avoidTrunk in listOf(false, true)) {
            val r = engine.route(listOf(bomlo, end), RouteOptions(style, avoidTrunk = avoidTrunk))
            val label = if (avoidTrunk) "$name $style avoiding trunk" else "$name $style"
            println("$label: %.0f km, ferries=%d, %.0f km by ferry, %.1f h".format(r.distanceMeters / 1000, r.ferryCount, ferryKm(r), r.durationSeconds / 3600))
            assertTrue("$label sails ${ferryKm(r)} km", ferryKm(r) < 60) // short fjord ferries add up; the Hurtigruten is 230 km
        }
    }
}
