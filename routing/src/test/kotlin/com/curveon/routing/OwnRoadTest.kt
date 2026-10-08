package com.curveon.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Stavlandsvegen 197: the rider's road (way 520497619, service) ends on the cycle path 6 m from
 * Stavlandsvegen without being joined to it in OSM. Routes must still start and end at the house,
 * crossing those metres, and never ride along the cycle path.
 */
class OwnRoadTest {
    private val segments = File(System.getProperty("curveon.segments") ?: "")
    private val home = LatLon(59.794257, 5.144397)
    private val bergen = LatLon(60.39, 5.32)

    private fun engine() = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("p").toFile()))

    private fun pathMetres(r: Route) = r.points.zipWithNext()
        .filter { (_, b) -> b.highway in OwnRoad.PATHS }.sumOf { (a, b) -> b.distanceFromStart - a.distanceFromStart }

    @Test
    fun `leaving home starts on the own road`() {
        assumeTrue(File(segments, "E5_N55.rd5").exists())
        for (style in RoadStyle.entries) {
            val r = engine().route(listOf(home, bergen), RouteOptions(style))
            val first = r.points.first()
            println("$style: starts %.0f m from home on %s, first roads %s, path %.0f m".format(
                Geo.distance(home, LatLon(first.lat, first.lon)), first.highway, r.points.take(12).map { it.highway }.distinct(), pathMetres(r)))
            assertTrue(Geo.distance(home, LatLon(first.lat, first.lon)) < 35)
            assertEquals(0.0, pathMetres(r), 0.1)
            assertTrue("route should go to Bergen", r.distanceMeters > 90_000)
        }
    }

    @Test
    fun `riding home ends on the own road`() {
        assumeTrue(File(segments, "E5_N55.rd5").exists())
        val r = engine().route(listOf(bergen, home), RouteOptions(RoadStyle.CURVY))
        val last = r.points.last()
        println("home: ends %.0f m from home, last roads %s".format(
            Geo.distance(home, LatLon(last.lat, last.lon)), r.points.takeLast(12).map { it.highway }.distinct()))
        assertTrue(Geo.distance(home, LatLon(last.lat, last.lon)) < 35)
        assertEquals(0.0, pathMetres(r), 0.1)
    }
}
