package com.curveon.routing

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Adventurous routing: start → destination in a drawn area, taking about the asked time. */
class AdventurePlannerTest {
    private val home = LatLon(59.794218, 5.144344) // Bremnes, on Bømlo
    private val south = LatLon(59.66, 5.12)
    private val bomlo = listOf(LatLon(59.86, 5.10), LatLon(59.86, 5.25), LatLon(59.72, 5.25), LatLon(59.62, 5.20), LatLon(59.62, 5.08), LatLon(59.74, 5.05))

    private fun engine(): BRouterEngine? {
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue(File(segments, "E5_N55.rd5").exists())
        return BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("p").toFile()))
    }

    @Test
    fun `takes about the asked time and stays in the area`() {
        val engine = engine()!!
        val area = SketchArea(bomlo)
        for (minutes in listOf(90, 150)) for (variant in 0..1) {
            val t0 = System.nanoTime()
            val r = AdventurePlanner(engine, log = { println(it) }).plan(home, south, bomlo, minutes, RouteOptions(RoadStyle.CURVY), variant)
            println("%d min v%d -> %.0f km %.0f min curvy=%d outside=%.0f%% twice=%.0f%% (%.1f s)".format(minutes, variant, r.distanceMeters / 1000,
                r.durationSeconds / 60, r.curviness, area.outsideShare(r) * 100, RoundtripPlanner.overlapFraction(r) * 100, (System.nanoTime() - t0) / 1e9))
            assertTrue("$minutes min v$variant took ${r.durationSeconds / 60} min", r.durationSeconds / 60 in minutes * 0.8..minutes * 1.2)
            assertTrue(area.outsideShare(r) < 0.25)
        }
    }

    @Test
    fun `less time than the quickest way is told`() {
        val engine = engine()!!
        val e = runCatching { AdventurePlanner(engine).plan(home, south, bomlo, 5, RouteOptions()) }.exceptionOrNull()
        assertTrue(e is NoAdventureException && e.directMinutes != null)
    }

    @Test
    fun `inland on gravel`() {
        val engine = engine()!!
        val voss = LatLon(60.628, 6.415)
        val odda = LatLon(60.069, 6.546)
        val area = listOf(LatLon(60.75, 6.0), LatLon(60.75, 7.0), LatLon(60.45, 7.25), LatLon(60.05, 7.1), LatLon(59.95, 6.5), LatLon(60.3, 6.0))
        for (surface in listOf(SurfacePreference.PAVED, SurfacePreference.GRAVEL)) {
            val t0 = System.nanoTime()
            val r = AdventurePlanner(engine, log = { println(it) }).plan(voss, odda, area, 240, RouteOptions(RoadStyle.CURVY, surface))
            println("%s -> %.0f km %.0f min curvy=%d unpaved=%.0f%% twice=%.0f%% (%.1f s)".format(surface, r.distanceMeters / 1000, r.durationSeconds / 60,
                r.curviness, r.unpavedMeters / r.distanceMeters * 100, RoundtripPlanner.overlapFraction(r) * 100, (System.nanoTime() - t0) / 1e9))
            assertTrue(r.durationSeconds / 60 in 240 * 0.75..240 * 1.25)
        }
    }

    @Test
    fun `a long trip in a big area`() {
        val engine = engine()!!
        val egersund = LatLon(58.4514, 5.9996)
        // From Bømlo down the coast and some way inland.
        val area = listOf(LatLon(59.9, 5.0), LatLon(59.9, 6.4), LatLon(59.2, 6.8), LatLon(58.6, 6.9), LatLon(58.3, 6.3), LatLon(58.3, 5.7), LatLon(58.9, 5.3))
        val t0 = System.nanoTime()
        val direct = engine.route(listOf(home, egersund), RouteOptions(RoadStyle.CURVY))
        val minutes = ((direct.durationSeconds / 60) * 1.3 / 30).toInt() * 30
        val r = AdventurePlanner(engine, log = { println(it) }).plan(home, egersund, area, minutes, RouteOptions(RoadStyle.CURVY))
        println("direct %.0f min, asked %d -> %.0f km %.0f min twice=%.0f%% (%.1f s)".format(direct.durationSeconds / 60, minutes, r.distanceMeters / 1000,
            r.durationSeconds / 60, RoundtripPlanner.overlapFraction(r) * 100, (System.nanoTime() - t0) / 1e9))
        assertTrue(r.durationSeconds / 60 in minutes * 0.75..minutes * 1.25)
    }

    @Test
    fun `a narrow coastal area down to Egersund`() {
        val engine = engine()!!
        val egersund = LatLon(58.4514, 5.9996)
        // As drawn on the phone: mostly sea along the west, the coast and fjords of Rogaland down the east.
        val area = listOf(LatLon(59.95, 5.0), LatLon(59.97, 5.5), LatLon(59.7, 5.82), LatLon(59.3, 5.85), LatLon(59.0, 6.0), LatLon(58.7, 6.1),
            LatLon(58.35, 6.1), LatLon(58.3, 5.7), LatLon(58.7, 5.3), LatLon(59.1, 5.05), LatLon(59.5, 4.95))
        val start = LatLon(59.78, 5.15)
        val r = AdventurePlanner(engine, log = { println(it) }).plan(start, egersund, area, 360, RouteOptions(RoadStyle.CURVY))
        println("narrow -> %.0f km %.0f min twice=%.0f%%".format(r.distanceMeters / 1000, r.durationSeconds / 60, RoundtripPlanner.overlapFraction(r) * 100))
        assertTrue(r.durationSeconds / 60 in 360 * 0.75..360 * 1.25)
    }

    @Test
    fun `a banana inland while the start is outside it`() {
        val engine = engine()!!
        val egersund = LatLon(58.4514, 5.9996)
        // Drawn on the phone: inland, east of Haugesund and Stavanger, down to Egersund; the start is out on the coast.
        val area = listOf(LatLon(59.95, 5.4), LatLon(59.9, 6.0), LatLon(59.5, 6.6), LatLon(59.0, 6.7), LatLon(58.6, 6.4), LatLon(58.4, 6.1),
            LatLon(58.55, 5.95), LatLon(58.9, 6.2), LatLon(59.4, 6.2), LatLon(59.7, 5.8))
        val start = LatLon(59.78, 5.15)
        val r = runCatching { AdventurePlanner(engine, log = { println(it) }).plan(start, egersund, area, 480, RouteOptions(RoadStyle.CURVY)) }
        println("banana -> " + r.fold({ "%.0f km %.0f min twice=%.0f%% outside=%.0f%%".format(it.distanceMeters / 1000, it.durationSeconds / 60, RoundtripPlanner.overlapFraction(it) * 100, SketchArea(area).outsideShare(it) * 100) }, { "failed: ${it.message}" }))
        val route = r.getOrThrow()
        // Not the quickest way down the coast.
        assertTrue(route.durationSeconds / 60 > 340)
    }
}
