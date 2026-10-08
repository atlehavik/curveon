package com.curveon.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Stavlandsvegen 197: in OSM the rider's road (way 520497619, service) is joined to the network only
 * through the cycle path (the map is wrong). Routes must still start and end at the house, using as
 * little path as needed at the ends, and never a path in the middle of the trip.
 */
class OwnRoadTest {
    private val segments = File(System.getProperty("curveon.segments") ?: "")
    private val home = LatLon(59.794257, 5.144397)
    private val bergen = LatLon(60.39, 5.32)

    private fun engine() = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("p").toFile()))

    private fun pathMetres(r: Route, from: Double = 0.0, to: Double = Double.MAX_VALUE) = r.points.zipWithNext()
        .filter { (a, b) -> b.highway in OwnRoad.PATHS && a.distanceFromStart >= from && b.distanceFromStart <= to }
        .sumOf { (a, b) -> b.distanceFromStart - a.distanceFromStart }

    /** Paths only near the ends (getting away from / to the house), none in between. */
    private fun assertPathsOnlyAtEnds(r: Route) {
        println("  path metres: %.0f in all, %.0f in the middle".format(pathMetres(r), pathMetres(r, 2_500.0, r.distanceMeters - 2_500.0)))
        assertEquals(0.0, pathMetres(r, 2_500.0, r.distanceMeters - 2_500.0), 0.1)
        assertTrue("as little path as possible", pathMetres(r) < 400)
    }

    @Test
    fun `leaving home starts on the own road`() {
        assumeTrue(File(segments, "E5_N55.rd5").exists())
        for (style in RoadStyle.entries) {
            val r = engine().route(listOf(home, bergen), RouteOptions(style))
            val first = r.points.first()
            println("$style: starts %.0f m from home on %s, first roads %s, path %.0f m".format(
                Geo.distance(home, LatLon(first.lat, first.lon)), first.highway, r.points.take(12).map { it.highway }.distinct(), pathMetres(r)))
            assertTrue(Geo.distance(home, LatLon(first.lat, first.lon)) < 35)
            assertPathsOnlyAtEnds(r)
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
        assertPathsOnlyAtEnds(r)
    }
}
