package com.vibenav.routing

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The tablet finds the current road's limit by routing a few metres ahead (see Navigator.probeSpeedLimit). */
class SpeedProbeTest {
    @Test
    fun `short route ahead on a motorway reports its limit`() {
        val segments = File(System.getProperty("vibenav.segments") ?: "")
        assumeTrue(File(segments, "E10_N55.rd5").exists())
        val engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("p").toFile()))
        val route = engine.route(listOf(LatLon(59.9139, 10.7522), LatLon(60.7945, 11.0680)), RouteOptions(RoadStyle.FASTEST))
        var probed = 0
        var found = 0
        for (i in route.points.indices step route.points.size / 12) {
            val p = route.points[i]
            val n = route.points.getOrNull(i + 1) ?: continue
            val heading = Geo.bearing(p.lat, p.lon, n.lat, n.lon)
            val ahead = Geo.destination(LatLon(p.lat, p.lon), heading, 60.0)
            val limit = runCatching {
                engine.route(listOf(LatLon(p.lat, p.lon), ahead), RouteOptions(RoadStyle.FASTEST), viaCatchingRangeMeters = 25.0)
                    .points.drop(1).firstNotNullOfOrNull { it.maxSpeedKmh }
            }.getOrNull()
            probed++
            if (limit != null) found++
            println("  ${p.highway} route=${p.maxSpeedKmh} probe=$limit")
        }
        assertTrue("found $found of $probed", found >= probed / 2)
    }
}
