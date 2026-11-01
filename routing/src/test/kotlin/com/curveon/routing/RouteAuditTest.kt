package com.curveon.routing

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Not a test of one place but a count: many rides between places all over the country, with the old
 * profile (CURVEON_AUDIT_OLD) and the new, and every place where a route turns back on itself.
 * Needs CURVEON_AUDIT=<dir with signs.csv, whose points are all on roads>; writes <dir>/routes-report.txt.
 */
class RouteAuditTest {
    private fun reversals(r: Route) = Reversals.find(r).map { "%.5f,%.5f".format(it.lat, it.lon) }

    @Test
    fun audit() {
        val dir = File(System.getenv("CURVEON_AUDIT") ?: "")
        val oldDir = File(System.getenv("CURVEON_AUDIT_OLD") ?: "")
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue(File(dir, "signs.csv").exists() && File(oldDir, "curveon-moto.brf").exists() && File(segments, "E5_N55.rd5").exists())
        val rides = (System.getenv("CURVEON_AUDIT_RIDES") ?: "1500").toInt()
        val places = File(dir, "signs.csv").readLines().map { l -> l.substringAfter(',').substringBefore(';').split(' ').let { LatLon(it[0].toDouble(), it[1].toDouble()) } }
        val rnd = Random(20261101)
        val pairs = ArrayList<Pair<LatLon, LatLon>>()
        while (pairs.size < rides) {
            val a = places[rnd.nextInt(places.size)]
            // Near neighbours by chance are rare: look for one within reach.
            val b = generateSequence { places[rnd.nextInt(places.size)] }.take(4000).firstOrNull { Geo.distance(it, a) in 3_000.0..60_000.0 } ?: continue
            pairs += a to b
        }
        val newEngine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("p").toFile()))
        val oldEngine = BRouterEngine(segments, oldDir, checkReversals = false)
        val searchOnly = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("q").toFile()), checkReversals = false)
        val report = File(dir, "routes-report.txt").printWriter()
        val pool = Executors.newFixedThreadPool(4)
        class One(val pair: Pair<LatLon, LatLon>, val old: List<String>, val new: List<String>, val oldKm: Double, val newKm: Double, val newFailed: Boolean, val oldFailed: Boolean, val search: List<String>)
        val results = pairs.mapIndexed { i, p ->
            pool.submit<One> {
                val style = if (i % 3 == 2) RoadStyle.CURVY else RoadStyle.FASTEST
                val o = runCatching { oldEngine.route(listOf(p.first, p.second), RouteOptions(style), maxMillis = 30_000) }.getOrNull()
                val n = runCatching { newEngine.route(listOf(p.first, p.second), RouteOptions(style), maxMillis = 30_000) }.getOrNull()
                val q = runCatching { searchOnly.route(listOf(p.first, p.second), RouteOptions(style), maxMillis = 30_000) }.getOrNull()
                One(p, o?.let(::reversals).orEmpty(), n?.let(::reversals).orEmpty(), (o?.distanceMeters ?: 0.0) / 1000, (n?.distanceMeters ?: 0.0) / 1000, n == null, o == null, q?.let(::reversals).orEmpty())
            }
        }.map { it.get() }
        pool.shutdown()
        val both = results.filter { !it.newFailed && !it.oldFailed }
        report.println("rides ${results.size}; both found ${both.size}; only new failed ${results.count { it.newFailed && !it.oldFailed }}; only old failed ${results.count { it.oldFailed && !it.newFailed }}")
        report.println("OLD: ${both.count { it.old.isNotEmpty() }} rides with a reversal, ${both.sumOf { it.old.size }} reversals")
        report.println("search rules only: ${both.count { it.search.isNotEmpty() }} rides with a reversal, ${both.sumOf { it.search.size }} reversals")
        report.println("NEW: ${both.count { it.new.isNotEmpty() }} rides with a reversal, ${both.sumOf { it.new.size }} reversals")
        report.println("length: old %.0f km, new %.0f km; new more than 2 km longer on %d rides, longest extra %.1f km".format(
            both.sumOf { it.oldKm }, both.sumOf { it.newKm }, both.count { it.newKm - it.oldKm > 2 }, both.maxOfOrNull { it.newKm - it.oldKm } ?: 0.0))
        for (r in both.filter { it.old.isNotEmpty() }) report.println("OLD ${r.pair.first} -> ${r.pair.second}: ${r.old}")
        for (r in both.filter { it.search.isNotEmpty() }) report.println("SEARCH ${r.pair.first} -> ${r.pair.second}: ${r.search}")
        for (r in both.filter { it.new.isNotEmpty() }) report.println("NEW ${r.pair.first} -> ${r.pair.second}: ${r.new}")
        for (r in results.filter { it.newFailed && !it.oldFailed }) report.println("NEW FAILED ${r.pair.first} -> ${r.pair.second}")
        for (r in both.sortedByDescending { it.newKm - it.oldKm }.take(10)) report.println("LONGER %.1f -> %.1f km %s -> %s".format(r.oldKm, r.newKm, r.pair.first, r.pair.second))
        report.close()
    }
}
