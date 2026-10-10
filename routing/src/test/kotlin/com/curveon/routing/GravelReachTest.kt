package com.curveon.routing

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Gravel, but only so far off the ordinary route. */
class GravelReachTest {
    @Test
    fun `gravel stays within reach of the ordinary route`() {
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue(File(segments, "E5_N55.rd5").exists())
        val engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("p").toFile()))
        // Inland, where there are gravel roads to find (on the west coast few are tagged).
        for ((a, b) in listOf(LatLon(59.67, 9.65) to LatLon(59.56, 9.26), LatLon(58.58, 7.80) to LatLon(59.20, 7.53))) {
        val ordinary = engine.route(listOf(a, b), RouteOptions(RoadStyle.CURVY, SurfacePreference.MIXED))
        val free = engine.route(listOf(a, b), RouteOptions(RoadStyle.CURVY, SurfacePreference.GRAVEL))
        println("ordinary %.0f km unpaved %.0f km; free gravel %.0f km unpaved %.0f km off %.1f km".format(ordinary.distanceMeters / 1000,
            ordinary.unpavedMeters / 1000, free.distanceMeters / 1000, free.unpavedMeters / 1000, Routes.farthestFrom(free, ordinary) / 1000))
        assertTrue("gravel found no gravel", free.unpavedMeters > ordinary.unpavedMeters + 3000)
        for (km in listOf(1, 3, 10)) {
            val r = engine.route(listOf(a, b), RouteOptions(RoadStyle.CURVY, SurfacePreference.GRAVEL, gravelReachKm = km))
            val off = Routes.farthestFrom(r, ordinary)
            println("reach $km -> %.0f km unpaved %.0f km off %.1f km".format(r.distanceMeters / 1000, r.unpavedMeters / 1000, off / 1000))
            assertTrue("reach $km went ${off / 1000} km off", off <= km * 1000 + 1500)
        }
        }
    }
}
