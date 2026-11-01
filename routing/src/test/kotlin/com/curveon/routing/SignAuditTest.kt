package com.curveon.routing

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Not a test of one place but a count over the country: rides up to every stop sign (and a sample of
 * give way signs) in the speed limits file, from the side the sign faces, and sees which the code
 * shows. Needs CURVEON_AUDIT=<dir with signs.csv and priority.csv>; writes <dir>/signs-report.txt.
 */
class SignAuditTest {
    private class Piece(val lat1: Double, val lon1: Double, val lat2: Double, val lon2: Double)

    private fun cell(lat: Double, lon: Double) = floor(lat * 100).toLong() * 100_000 + floor(lon * 100).toLong()

    @Test
    fun audit() {
        val dir = File(System.getenv("CURVEON_AUDIT") ?: "")
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue(File(dir, "signs.csv").exists() && File(segments, "E5_N55.rd5").exists())
        val index = HashMap<Long, MutableList<Piece>>()
        File(dir, "priority.csv").forEachLine { l ->
            val pts = l.substringAfter(',').split(';').map { p -> p.split(' ').let { it[0].toDouble() to it[1].toDouble() } }
            for ((a, b) in pts.zipWithNext()) {
                val cells = setOf(cell(a.first, a.second), cell(b.first, b.second))
                for (c in cells) index.getOrPut(c) { ArrayList(2) } += Piece(a.first, a.second, b.first, b.second)
            }
        }
        val priority: (Double, Double) -> RoadPiece? = { lat, lon ->
            val kx = 111_320.0 * cos(Math.toRadians(lat))
            var best: Piece? = null
            var bestD = 20.0
            for (dy in -1..1) for (dx in -1..1) for (r in index[cell(lat + dy * 0.01, lon + dx * 0.01)].orEmpty()) {
                val ax = (r.lon1 - lon) * kx; val ay = (r.lat1 - lat) * 110_574.0
                val sx = (r.lon2 - r.lon1) * kx; val sy = (r.lat2 - r.lat1) * 110_574.0
                val len2 = sx * sx + sy * sy
                val t = if (len2 == 0.0) 0.0 else (-(ax * sx + ay * sy) / len2).coerceIn(0.0, 1.0)
                val d = hypot(ax + t * sx, ay + t * sy)
                if (d < bestD) { bestD = d; best = r }
            }
            best?.let { RoadPiece(it.lat1, it.lon1, it.lat2, it.lon2) }
        }
        val all = File(dir, "signs.csv").readLines().map { l ->
            val v = l.substringBefore(',').toInt()
            val p = l.substringAfter(',').split(';').map { q -> q.split(' ').let { LatLon(it[0].toDouble(), it[1].toDouble()) } }
            GiveWaySign(p[0].lat, p[0].lon, p[1].lat, p[1].lon, v)
        }
        val stops = all.filter { it.stop }
        val sampleEvery = (System.getenv("CURVEON_AUDIT_SAMPLE") ?: "20").toInt()
        val giveWays = all.filter { !it.stop }.filterIndexed { i, _ -> i % sampleEvery == 0 }
        val engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("p").toFile()))
        val report = File(dir, "signs-report.txt").printWriter()

        // Rides up to [s] the way it applies; every ride on which the sign counts without looking at priority roads.
        fun rides(s: GiveWaySign): List<Route> {
            val kx = 111_320.0 * cos(Math.toRadians(s.lat))
            val dx = (s.lon2 - s.lon) * kx; val dy = (s.lat2 - s.lat) * 110_574.0
            val len = hypot(dx, dy)
            if (len < 1.0) return emptyList()
            val ux = dx / len; val uy = dy / len
            fun at(along: Double, across: Double) = LatLon( // across > 0: to the right of the road's direction
                s.lat + (uy * along - ux * across) / 110_574.0, s.lon + (ux * along + uy * across) / kx)
            val pairs = if (s.kind == 0) listOf(at(-150.0, 0.0) to at(150.0, 0.0), at(-300.0, 0.0) to at(300.0, 0.0), at(-150.0, 0.0) to at(20.0, 60.0), at(-150.0, 0.0) to at(20.0, -60.0))
            else {
                val sides = when (s.kind) { 1 -> listOf(1.0); 2 -> listOf(-1.0); else -> listOf(1.0, -1.0) }
                sides.flatMap { side -> listOf(60.0, 150.0, 300.0).flatMap { off -> listOf(at(0.0, side * off) to at(300.0, 0.0), at(0.0, side * off) to at(-300.0, 0.0)) } }
            }
            return pairs.mapNotNull { (a, b) ->
                runCatching { engine.route(listOf(a, b), RouteOptions(RoadStyle.FASTEST), viaCatchingRangeMeters = 300.0, maxMillis = 15_000) }.getOrNull()
            }.filter { GiveWay.alongRoute(it, listOf(s)).any { g -> g.stop == s.stop && Geo.distance(g.lat, g.lon, s.lat, s.lon) < 60 } }
        }

        // A second opinion from another source: OSM tags the give way or stop on the road that has to give way.
        val osm = File(dir, "osm-signs.csv").takeIf { it.exists() }?.readLines().orEmpty().map { l -> l.split(',').let { LatLon(it[1].toDouble(), it[2].toDouble()) } }
        val osmIndex = osm.groupBy { cell(it.lat, it.lon) }
        fun osmSaysOnRide(s: GiveWaySign, r: Route): Boolean {
            val near = (-1..1).flatMap { dy -> (-1..1).flatMap { dx -> osmIndex[cell(s.lat + dy * 0.01, s.lon + dx * 0.01)].orEmpty() } }
                .filter { Geo.distance(it.lat, it.lon, s.lat, s.lon) < 40 }
            return near.any { n -> r.points.any { Geo.distance(it.lat, it.lon, n.lat, n.lon) < 4 } }
        }

        class Result(val sign: GiveWaySign, val rides: Int, val newShown: Int, val note: String, val osmDisagrees: Boolean)

        fun check(s: GiveWaySign): Result {
            val rs = rides(s)
            var new = 0
            val notes = ArrayList<String>()
            var osmDisagrees = false
            for (r in rs) {
                val here = { g: Double, h: Double -> Geo.distance(g, h, s.lat, s.lon) < 60 }
                val n = GiveWay.alongRoute(r, listOf(s), priority).any { here(it.lat, it.lon) }
                if (n) new++
                if (!n) {
                    if (osmSaysOnRide(s, r)) osmDisagrees = true
                    val road = r.points.minByOrNull { Geo.distance(it.lat, it.lon, s.lat, s.lon) }?.highway
                    notes += "$road"
                }
            }
            return Result(s, rs.size, new, notes.distinct().joinToString("/"), osmDisagrees)
        }

        val pool = Executors.newFixedThreadPool(4)
        fun run(name: String, signs: List<GiveWaySign>) {
            val results = signs.map { s -> pool.submit<Result> { check(s) } }.map { it.get() }
            val reached = results.filter { it.rides > 0 }
            val newLost = reached.filter { it.newShown < it.rides }
            report.println("== $name: ${signs.size} signs, ridden up to ${reached.size}; not reached ${signs.size - reached.size}")
            report.println("   hidden from some ride ${newLost.size} (never shown ${newLost.count { it.newShown == 0 }})")
            report.println("   of those new code hides, OSM has a give way/stop on the very road ridden: ${newLost.count { it.osmDisagrees }}")
            for (r in newLost.filter { it.osmDisagrees }) report.println("   OSM-DISAGREES kind=%d %.5f,%.5f rides=%d shown=%d road=%s".format(r.sign.kind, r.sign.lat, r.sign.lon, r.rides, r.newShown, r.note))
            for (r in newLost) report.println("   LOST kind=%d %.5f,%.5f rides=%d shown=%d road=%s".format(r.sign.kind, r.sign.lat, r.sign.lon, r.rides, r.newShown, r.note))
            // Not reachable the way the file says it faces: try it the other way, which would mean the file has it backwards.
            val notReached = results.filter { it.rides == 0 }
            val flipped = notReached.filter { r ->
                val s = r.sign
                val back = if (s.kind == 0) GiveWaySign(s.lat, s.lon, 2 * s.lat - s.lat2, 2 * s.lon - s.lon2, s.value)
                    else GiveWaySign(s.lat, s.lon, s.lat2, s.lon2, s.value / 10 * 10 + 3)
                rides(back).isNotEmpty()
            }
            report.println("   not reached: ${notReached.size}, of which reached the other way (kind 0) or from the other side: ${flipped.size}")
            for (r in notReached) report.println("   NOT REACHED kind=%d %.5f,%.5f other-way=%s".format(r.sign.kind, r.sign.lat, r.sign.lon, r in flipped))
            report.flush()
        }
        run("STOP", stops)
        run("GIVE WAY sample 1/$sampleEvery", giveWays)
        pool.shutdown(); pool.awaitTermination(1, TimeUnit.MINUTES)
        report.close()
    }
}
