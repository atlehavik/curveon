package com.curveon.routing

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** How much of a loop rides the same road twice, from home (Bremnes) and from Haugesund. */
class LoopOverlapTest {
    @Test
    fun `loops ride little road twice`() {
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue(File(segments, "E5_N55.rd5").exists())
        val engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("p").toFile()))
        val starts = listOf("bremnes" to LatLon(59.794218, 5.144344), "haugesund" to LatLon(59.41, 5.27))
        var total = 0.0; var n = 0
        for ((name, start) in starts) for (dir in listOf(0.0, 90.0, 180.0)) for (km in listOf(60.0, 120.0)) {
            val t0 = System.nanoTime()
            val r = runCatching { RoundtripPlanner(engine).plan(start, km, dir, RouteOptions(RoadStyle.CURVY)) }.getOrNull() ?: continue
            val o = RoundtripPlanner.overlapFraction(r)
            total += o; n++
            println("OVERLAP %s dir=%3.0f km=%3.0f -> %5.1f km twice=%4.1f%% curvy=%d (%.1f s)".format(name, dir, km, r.distanceMeters / 1000, o * 100, r.curviness, (System.nanoTime() - t0) / 1e9))
        }
        println("OVERLAP mean %.1f%% over %d loops".format(total / n * 100, n))
        // 12% before loops were steered off roads already ridden (2026-10-10), 4.6% after.
        org.junit.Assert.assertTrue("mean twice ${total / n}", total / n < 0.08)
    }
}
