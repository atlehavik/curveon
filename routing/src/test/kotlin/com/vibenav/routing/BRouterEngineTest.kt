package com.vibenav.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Runs real routes over Norway; skipped when the segment files are not available. */
class BRouterEngineTest {

    private lateinit var engine: BRouterEngine

    private val oslo = LatLon(59.9139, 10.7522)
    private val bergen = LatLon(60.3913, 5.3221)

    @Before
    fun setUp() {
        val segments = File(System.getProperty("vibenav.segments") ?: "")
        assumeTrue("No BRouter segments at $segments", File(segments, "E10_N55.rd5").exists())
        val profiles = ProfileInstaller.install(Files.createTempDirectory("profiles").toFile())
        engine = BRouterEngine(segments, profiles)
    }

    private fun describe(label: String, r: Route) = println(
        "%-11s %6.1f km %5.1f h  curvy=%3d  unpaved=%5.1f km  ferries=%d  maneuvers=%d".format(
            label, r.distanceMeters / 1000, r.durationSeconds / 3600, r.curviness,
            r.unpavedMeters / 1000, r.ferryCount, r.maneuvers.size
        )
    )

    @Test
    fun `oslo to bergen in every style`() {
        val results = RoadStyle.values().associateWith { style ->
            val t0 = System.currentTimeMillis()
            engine.route(listOf(oslo, bergen), RouteOptions(roadStyle = style)).also {
                describe(style.name, it)
                println("            computed in ${System.currentTimeMillis() - t0} ms")
            }
        }
        val fastest = results.getValue(RoadStyle.FASTEST)
        assertTrue(fastest.distanceMeters in 440_000.0..560_000.0)
        assertTrue(fastest.durationSeconds in 5.5 * 3600..9.0 * 3600)
        assertEquals(ManeuverType.DEPART, fastest.maneuvers.first().type)
        assertEquals(ManeuverType.ARRIVE, fastest.maneuvers.last().type)
        // Curvy styles should actually be curvier than the fastest route
        assertTrue(results.getValue(RoadStyle.VERY_CURVY).curviness > fastest.curviness)
    }

    @Test
    fun `paved only avoids gravel and gravel lover finds it`() {
        val a = LatLon(60.7945, 11.0680) // Hamar
        val b = LatLon(61.1153, 10.4662) // Lillehammer
        val paved = engine.route(listOf(a, b), RouteOptions(RoadStyle.CURVY, SurfacePreference.PAVED))
        val gravel = engine.route(listOf(a, b), RouteOptions(RoadStyle.CURVY, SurfacePreference.GRAVEL))
        describe("paved", paved)
        describe("gravel", gravel)
        assertTrue(gravel.unpavedMeters > paved.unpavedMeters)
    }

    @Test
    fun `roundtrip from oslo heading north is about the requested length`() {
        val planner = RoundtripPlanner(engine)
        for (style in listOf(RoadStyle.FASTEST, RoadStyle.VERY_CURVY)) {
            val t0 = System.currentTimeMillis()
            val r = planner.plan(oslo, 150.0, 0.0, RouteOptions(roadStyle = style))
            describe("RT " + style.name, r)
            println("            overlap=%.0f%%  computed in %d ms".format(RoundtripPlanner.overlapFraction(r) * 100, System.currentTimeMillis() - t0))
            assertTrue(r.distanceMeters in 110_000.0..195_000.0)
            assertTrue(Geo.distance(r.points.first().let { LatLon(it.lat, it.lon) }, r.points.last().let { LatLon(it.lat, it.lon) }) < 500)
            // Mostly north of the start
            val north = r.points.count { it.lat > oslo.lat }.toDouble() / r.points.size
            assertTrue(north > 0.7)
        }
    }
}
