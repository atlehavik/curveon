package com.curveon.routing

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** "No motorways or E-roads" keeps off trunk roads (E39 between Haugesund and Bergen) where it can. */
class AvoidTrunkTest {
    private fun trunkKm(r: Route) = r.points.zipWithNext().sumOf { (a, b) ->
        if (b.highway?.startsWith("trunk") == true) b.distanceFromStart - a.distanceFromStart else 0.0
    } / 1000

    @Test
    fun `avoiding E-roads keeps off trunk roads`() {
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue(File(segments, "E5_N55.rd5").exists())
        val engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("p").toFile()))
        val haugesund = LatLon(59.41, 5.27); val bergen = LatLon(60.39, 5.32)
        for (style in listOf(RoadStyle.FASTEST, RoadStyle.CURVY)) {
            val plain = engine.route(listOf(haugesund, bergen), RouteOptions(style, avoidMotorways = true))
            val noE = engine.route(listOf(haugesund, bergen), RouteOptions(style, avoidMotorways = true, avoidTrunk = true))
            println("$style: trunk %.0f km of %.0f -> %.0f km of %.0f".format(trunkKm(plain), plain.distanceMeters / 1000, trunkKm(noE), noE.distanceMeters / 1000))
            assertTrue("$style kept ${trunkKm(noE)} km trunk", trunkKm(noE) < trunkKm(plain) / 2 || trunkKm(plain) < 5)
        }
    }
}
