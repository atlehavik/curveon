package com.curveon.routing

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Curviness on real Norwegian roads; skipped when the segment files are not available. */
class CurvinessRoadsTest {

    private lateinit var engine: BRouterEngine

    @Before
    fun setUp() {
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue("No BRouter segments at $segments", File(segments, "E5_N60.rd5").exists())
        engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("profiles").toFile()))
    }

    private fun line(from: LatLon, to: LatLon) =
        engine.route(listOf(from, to), RouteOptions(roadStyle = RoadStyle.FASTEST)).points.map { LatLon(it.lat, it.lon) }

    /** The curviest ~2 km of [points]: where the hairpins are on a road up a mountain. */
    private fun curviest2km(points: List<LatLon>): Double {
        var best = 0.0
        for (i in points.indices step 5) {
            var j = i
            var d = 0.0
            while (j + 1 < points.size && d < 2_000) { d += Geo.distance(points[j], points[j + 1]); j++ }
            if (d >= 1_500) best = maxOf(best, Curviness.degreesPerKm(points.subList(i, j + 1)))
        }
        return best
    }

    @Test
    fun `hairpin roads are the curviest, town streets and motorways are not`() {
        val trollstigen = line(LatLon(62.5675, 7.6870), LatLon(62.4537, 7.6656))
        val ornevegen = line(LatLon(62.1015, 7.2060), LatLon(62.1230, 7.1620))
        val bergen = line(LatLon(60.3925, 5.3240), LatLon(60.3800, 5.3480))
        val motorway = line(LatLon(59.8530, 10.4380), LatLon(59.7400, 10.2200)) // E18 Sandvika–Lier
        println("Trollstigen hairpins %.0f, Ørnevegen %.0f, Bergen centre %.0f, E18 %.0f deg/km".format(
            curviest2km(trollstigen), curviest2km(ornevegen), Curviness.degreesPerKm(bergen), Curviness.degreesPerKm(motorway)))
        // Before junctions were told by shape, a hairpin's 180° mostly fell out as "a junction": 438 and 496.
        assertTrue(curviest2km(trollstigen) > 600)
        assertTrue(curviest2km(ornevegen) > 600)
        assertTrue(Curviness.degreesPerKm(bergen) < 200) // was 231 with every street corner counted
        assertTrue(Curviness.degreesPerKm(motorway) < 100)
    }
}
