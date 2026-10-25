package com.curveon.routing

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Bømlo → McDonald's → Elkjøp on Stord (2026-10-25): the E39 passes McDonald's 60 m away, the route goes on
 * round a roundabout and reaches the stop from the other side 750 m later. "You have reached McDonald's"
 * was said on the E39. Skipped without segments.
 */
class StopAlongTest {
    private lateinit var engine: BRouterEngine

    @Before
    fun setUp() {
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue("No BRouter segments at $segments", File(segments, "E5_N55.rd5").exists())
        engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("profiles").toFile()))
    }

    @Test
    fun theStopIsWhereTheRouteGetsToIt() {
        val start = LatLon(59.7731, 5.2280)
        val mcDonalds = LatLon(59.76332, 5.45156)
        val elkjop = LatLon(59.764526, 5.458119)
        for (style in RoadStyle.entries) {
            val route = engine.route(listOf(start, mcDonalds, elkjop), RouteOptions(roadStyle = style), visitEveryVia = true) ?: continue
            val along = RouteCorridor(route).alongClosest(mcDonalds.lat, mcDonalds.lon, 2_000.0)!!
            val nearest = route.points.minBy { Geo.distance(LatLon(it.lat, it.lon), mcDonalds) }
            assertTrue("$style: the stop is at $along, the route is nearest to it at ${nearest.distanceFromStart}",
                Math.abs(along - nearest.distanceFromStart) < 60.0)
            // The rider is by the stop itself (not 60 m off on the E39) when it counts as reached.
            val there = route.points.minBy { Math.abs(it.distanceFromStart - along) }
            assertTrue("$style: ${Geo.distance(LatLon(there.lat, there.lon), mcDonalds)} m from the stop", Geo.distance(LatLon(there.lat, there.lon), mcDonalds) < 40.0)
        }
    }
}
