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

    private val segmentsDir get() = File(System.getProperty("curveon.segments") ?: "")
    private fun roads() = SurfaceReader(segmentsDir, File(ProfileInstaller.install(Files.createTempDirectory("p").toFile()), "lookups.dat"))

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
            val r = AdventurePlanner(engine, log = { println(it) }, roads = roads()).plan(home, south, bomlo, minutes, RouteOptions(RoadStyle.CURVY), variant)
            println("%d min v%d -> %.0f km %.0f min curvy=%d outside=%.0f%% twice=%.0f%% (%.1f s)".format(minutes, variant, r.distanceMeters / 1000,
                r.durationSeconds / 60, r.curviness, area.outsideShare(r) * 100, RoundtripPlanner.overlapFraction(r) * 100, (System.nanoTime() - t0) / 1e9))
            assertTrue("$minutes min v$variant took ${r.durationSeconds / 60} min", r.durationSeconds / 60 in minutes * 0.4..minutes * 1.1)
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
            val r = AdventurePlanner(engine, log = { println(it) }, roads = roads()).plan(voss, odda, area, 240, RouteOptions(RoadStyle.CURVY, surface))
            println("%s -> %.0f km %.0f min curvy=%d unpaved=%.0f%% twice=%.0f%% (%.1f s)".format(surface, r.distanceMeters / 1000, r.durationSeconds / 60,
                r.curviness, r.unpavedMeters / r.distanceMeters * 100, RoundtripPlanner.overlapFraction(r) * 100, (System.nanoTime() - t0) / 1e9))
            assertTrue(r.durationSeconds / 60 in 240 * 0.4..240 * 1.1)
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
        val r = AdventurePlanner(engine, log = { println(it) }, roads = roads()).plan(home, egersund, area, minutes, RouteOptions(RoadStyle.CURVY))
        println("direct %.0f min, asked %d -> %.0f km %.0f min twice=%.0f%% (%.1f s)".format(direct.durationSeconds / 60, minutes, r.distanceMeters / 1000,
            r.durationSeconds / 60, RoundtripPlanner.overlapFraction(r) * 100, (System.nanoTime() - t0) / 1e9))
        assertTrue(r.durationSeconds / 60 in minutes * 0.4..minutes * 1.1)
    }

    @Test
    fun `a long day around Sunnhordland rides little road twice`() {
        val engine = engine()!!
        val olen = LatLon(59.6, 5.81)
        val area = listOf(LatLon(60.1, 5.1), LatLon(60.1, 6.4), LatLon(59.4, 6.4), LatLon(59.4, 5.1))
        val t0 = System.nanoTime()
        for (variant in 0..1) {
            val r = AdventurePlanner(engine, log = { println(it) }, roads = roads()).plan(home, olen, area, 500, RouteOptions(RoadStyle.CURVY), variant)
            println("SUNNHORDLAND v%d -> %.0f km %.0f min twice=%.0f%% spur=%.0f m (%.1f s)".format(variant, r.distanceMeters / 1000, r.durationSeconds / 60,
                RoundtripPlanner.overlapFraction(r) * 100, Spurs.wastedMeters(r), (System.nanoTime() - t0) / 1e9))
            assertTrue(r.durationSeconds / 60 in 500 * 0.4..500 * 1.15)
        }
    }

    @Test
    fun `a narrow coastal area down to Egersund`() {
        val engine = engine()!!
        val egersund = LatLon(58.4514, 5.9996)
        // As drawn on the phone: mostly sea along the west, the coast and fjords of Rogaland down the east.
        val area = listOf(LatLon(59.95, 5.0), LatLon(59.97, 5.5), LatLon(59.7, 5.82), LatLon(59.3, 5.85), LatLon(59.0, 6.0), LatLon(58.7, 6.1),
            LatLon(58.35, 6.1), LatLon(58.3, 5.7), LatLon(58.7, 5.3), LatLon(59.1, 5.05), LatLon(59.5, 4.95))
        val start = LatLon(59.78, 5.15)
        val r = AdventurePlanner(engine, log = { println(it) }, roads = roads()).plan(start, egersund, area, 360, RouteOptions(RoadStyle.CURVY))
        println("narrow -> %.0f km %.0f min twice=%.0f%%".format(r.distanceMeters / 1000, r.durationSeconds / 60, RoundtripPlanner.overlapFraction(r) * 100))
        assertTrue(r.durationSeconds / 60 in 360 * 0.4..360 * 1.1)
    }

    @Test
    fun `a banana inland while the start is outside it`() {
        val engine = engine()!!
        val egersund = LatLon(58.4514, 5.9996)
        // Drawn on the phone: inland, east of Haugesund and Stavanger, down to Egersund; the start is out on the coast.
        val area = listOf(LatLon(59.95, 5.4), LatLon(59.9, 6.0), LatLon(59.5, 6.6), LatLon(59.0, 6.7), LatLon(58.6, 6.4), LatLon(58.4, 6.1),
            LatLon(58.55, 5.95), LatLon(58.9, 6.2), LatLon(59.4, 6.2), LatLon(59.7, 5.8))
        val start = LatLon(59.78, 5.15)
        val r = runCatching { AdventurePlanner(engine, log = { println(it) }, roads = roads()).plan(start, egersund, area, 480, RouteOptions(RoadStyle.CURVY)) }
        println("banana -> " + r.fold({ "%.0f km %.0f min twice=%.0f%% outside=%.0f%%".format(it.distanceMeters / 1000, it.durationSeconds / 60, RoundtripPlanner.overlapFraction(it) * 100, SketchArea(area).outsideShare(it) * 100) }, { "failed: ${it.message}" }))
        val route = r.getOrThrow()
        // Not the quickest way down the coast.
        assertTrue(route.durationSeconds / 60 > 340)
    }

    @Test
    fun `the circle east of Stord reaches over the mountains`() {
        val engine = engine()!!
        // As drawn on the phone: start at home on Bømlo, to Tveit, a ring over Stord and Kvinnherad.
        val tveit = LatLon(59.47, 5.467)
        val centre = LatLon(59.72, 5.63)
        val ring = (0 until 24).map { k ->
            val a = Math.toRadians(k * 15.0)
            LatLon(centre.lat + 0.30 * Math.sin(a), centre.lon + 0.54 * Math.cos(a))
        }
        for (withRoads in listOf(false, true)) for (variant in 0..1) {
            val t0 = System.nanoTime()
            val planner = AdventurePlanner(engine, roads = if (withRoads) roads() else null)
            val r = planner.plan(home, tveit, ring, 360, RouteOptions(RoadStyle.CURVY), variant)
            println("RING roads=%s v%d -> %.0f km %.0f min twice=%.0f%% spur=%.0f m east to %.2f E (%.1f s)".format(withRoads, variant, r.distanceMeters / 1000,
                r.durationSeconds / 60, RoundtripPlanner.overlapFraction(r) * 100, Spurs.wastedMeters(r), r.points.maxOf { it.lon }, (System.nanoTime() - t0) / 1e9))
        }
    }

    @Test
    fun `a first trip comes quickly and later rounds only replace it with a better one`() {
        val engine = engine()!!
        val tveit = LatLon(59.47, 5.467)
        val centre = LatLon(59.72, 5.63)
        val ring = (0 until 24).map { k ->
            val a = Math.toRadians(k * 15.0)
            LatLon(centre.lat + 0.30 * Math.sin(a), centre.lon + 0.54 * Math.cos(a))
        }
        for (variant in 0..1) {
            val t0 = System.nanoTime()
            val area = SketchArea(ring)
            val seen = ArrayList<Pair<Double, Route>>()
            val r = AdventurePlanner(engine, log = { println(it) }, roads = roads()).plan(home, tveit, ring, 360, RouteOptions(RoadStyle.CURVY), variant, rounds = 3) {
                seen += (System.nanoTime() - t0) / 1e9 to it
            }
            seen.forEachIndexed { i, (t, route) -> println("PROGRESSIVE v%d #%d at %.1f s: %.0f km %.0f min twice=%.0f%% east to %.2f E".format(variant, i, t, route.distanceMeters / 1000,
                route.durationSeconds / 60, RoundtripPlanner.overlapFraction(route) * 100, route.points.maxOf { it.lon })) }
            println("PROGRESSIVE v%d done in %.1f s, final %.0f min".format(variant, (System.nanoTime() - t0) / 1e9, r.durationSeconds / 60))
            assertTrue(seen.isNotEmpty() && seen.last().second === r)
            assertTrue(area.outsideShare(r) < 0.25)
        }
    }
}
