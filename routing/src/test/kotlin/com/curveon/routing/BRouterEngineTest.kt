package com.curveon.routing

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
        val segments = File(System.getProperty("curveon.segments") ?: "")
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
    fun `offroad rides more tracks than gravel`() {
        val a = LatLon(60.7945, 11.0680) // Hamar
        val b = LatLon(61.1153, 10.4662) // Lillehammer
        val gravel = engine.route(listOf(a, b), RouteOptions(RoadStyle.CURVY, SurfacePreference.GRAVEL))
        val offroad = engine.route(listOf(a, b), RouteOptions(RoadStyle.CURVY, SurfacePreference.OFFROAD))
        describe("gravel", gravel)
        describe("offroad", offroad)
        fun Route.trackMeters() = points.zipWithNext().sumOf { (p, q) -> if (q.highway == "track") q.distanceFromStart - p.distanceFromStart else 0.0 }
        println("            tracks: gravel %.1f km, offroad %.1f km".format(gravel.trackMeters() / 1000, offroad.trackMeters() / 1000))
        assertTrue(offroad.trackMeters() > gravel.trackMeters())
    }

    @Test
    fun `roundtrip from oslo heading north is about the requested length`() {
        val planner = RoundtripPlanner(engine)
        for (style in listOf(RoadStyle.FASTEST, RoadStyle.VERY_CURVY)) {
            val t0 = System.currentTimeMillis()
            val r = planner.plan(oslo, 150.0, 0.0, RouteOptions(roadStyle = style))
            describe("RT " + style.name, r)
            println("            overlap=%.0f%%  computed in %d ms".format(RoundtripPlanner.overlapFraction(r) * 100, System.currentTimeMillis() - t0))
            // North of Oslo is mostly forest (Nordmarka), so tarmac-only loops there can come out short.
            assertTrue(r.distanceMeters in 95_000.0..200_000.0)
            assertTrue(Geo.distance(r.points.first().let { LatLon(it.lat, it.lon) }, r.points.last().let { LatLon(it.lat, it.lon) }) < 500)
            // Mostly north of the start
            val north = r.points.count { it.lat > oslo.lat }.toDouble() / r.points.size
            assertTrue(north > 0.7)
        }
    }

    @Test
    fun `follows a gpx-like track from a nearby start`() {
        // Use a real route as the "track", then start 2 km off it.
        val track = engine.route(listOf(LatLon(61.1153, 10.4662), LatLon(61.5940, 9.7500)), RouteOptions(RoadStyle.CURVY))
            .points.map { LatLon(it.lat, it.lon) }
        val start = LatLon(61.1300, 10.4300)
        val r = TrackFollower(engine).route(start, track, RouteOptions(RoadStyle.FASTEST, SurfacePreference.MIXED))
        describe("track", r)
        val trackKm = (1 until track.size).sumOf { Geo.distance(track[it - 1], track[it]) } / 1000
        assertTrue(r.distanceMeters / 1000 in trackKm * 0.9..trackKm * 1.2)
        assertTrue(Geo.distance(LatLon(r.points.last().lat, r.points.last().lon), track.last()) < 200)
    }
}
