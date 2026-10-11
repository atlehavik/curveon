package com.curveon.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Stord, E39 at Heiane: the ramp to the Sagvåg roundabout leaves E39 the other way round (it is the
 * exit for traffic from the north-east). Riding north-east, BRouter used to pass the proper exit,
 * reverse at the ramp's start (a U-turn on E39 that saved ten seconds) and take the ramp the wrong
 * way round. Turning onto a ramp now costs by angle, so the exit to the right is taken.
 */
class HairpinRampTest {
    @Test
    fun `McDonald's on Stord is reached by the exit, not a U-turn on E39`() {
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue(File(segments, "E5_N55.rd5").exists())
        val engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("p").toFile()))
        val foyno = LatLon(59.738446, 5.416421)
        val mcdonalds = LatLon(59.76332, 5.45156)
        for (style in RoadStyle.entries) {
            val r = engine.route(listOf(foyno, mcdonalds), RouteOptions(style))
            assertTrue("$style has a U-turn: ${r.maneuvers.map { it.type }}", r.maneuvers.none { it.type == ManeuverType.U_TURN })
            // The exit to the east roundabout leaves E39 at about 5.4539, the hairpin ramp at 5.4550.
            val exit = r.points.first { it.highway == "trunk_link" && it.lon > 5.44 }
            assertEquals("$style", 5.4539, exit.lon, 0.0004)
        }
    }
}
