package com.curveon.routing

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Turning round on the road (2026-10-23, Bømlo, 80 zone by Circle K Automat Bømlo): a friend rode a trip shared
 * from where the rider stood on the main road, and was routed south to that point and told to turn round there,
 * because the shared line went north. A stop on the road was ridden to and turned round at the same way.
 * Skipped without segments.
 */
class StopUTurnTest {
    private lateinit var engine: BRouterEngine

    @Before
    fun setUp() {
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue("No BRouter segments at $segments", File(segments, "E5_N55.rd5").exists())
        engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("profiles").toFile()))
    }

    /** Places round the roundabouts by Circle K where the old routing turned round on the road. */
    private val stopByTheRoad = LatLon(59.79470, 5.23790)
    private val lineStart = LatLon(59.79470, 5.23550)
    private val village = LatLon(59.815588, 5.268089)
    private val westOfIt = listOf(LatLon(59.7937, 5.1504), LatLon(59.80, 5.20))

    @Test
    fun aStopOnTheRoadIsLeftWithoutTurningRoundThere() {
        for (from in westOfIt) for (style in RoadStyle.entries) {
            val r = engine.route(listOf(from, stopByTheRoad, village), RouteOptions(roadStyle = style), visitEveryVia = true)
            assertTrue("$style from $from: turns round on the road at ${hairpins(r)}", hairpins(r).isEmpty())
            assertTrue("$style from $from: misses the stop", r.points.minOf { Geo.distance(stopByTheRoad.lat, stopByTheRoad.lon, it.lat, it.lon) } < 40)
        }
    }

    @Test
    fun aSharedLineIsJoinedWithoutTurningRoundOnIt() {
        val line = engine.route(listOf(lineStart, village), RouteOptions(RoadStyle.FASTEST)).points.map { LatLon(it.lat, it.lon) }
        for (from in westOfIt) for (heading in listOf(null, 90.0)) {
            val r = TrackFollower(engine).route(from, line, RouteOptions(RoadStyle.CURVY), headingDeg = heading)
            assertTrue("from $from heading $heading: turns round on the road at ${hairpins(r)}", hairpins(r).isEmpty())
            val end = r.points.last()
            assertTrue("from $from: does not end where the line ends", Geo.distance(end.lat, end.lon, village.lat, village.lon) < 60)
        }
    }

    companion object {
        /** Where [r] turns round on the spot: the 25 m in and the 25 m out point opposite ways. */
        fun hairpins(r: Route): List<Pair<Double, LatLon>> {
            val out = mutableListOf<Pair<Double, LatLon>>()
            var d = 30.0
            while (d < r.distanceMeters - 30) {
                val (a, _) = Routes.pointAt(r, d - 25)
                val (m, _) = Routes.pointAt(r, d)
                val (b, _) = Routes.pointAt(r, d + 25)
                val turn = kotlin.math.abs(Geo.angleDiff(Geo.bearing(a.lat, a.lon, m.lat, m.lon), Geo.bearing(m.lat, m.lon, b.lat, b.lon)))
                if (turn > 150 && Geo.distance(a, b) < 15) { out += d to m; d += 100 } else d += 5
            }
            return out
        }
    }
}
