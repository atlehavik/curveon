package com.curveon.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

class SpeedSignsTest {
    /** A line going east along 60°N: each of [metres] a corner that far from the start, and [limit] by distance from the start. */
    private fun east(vararg metres: Double, maneuvers: List<Maneuver> = emptyList(), osm: Int? = null): Route {
        val pts = metres.map { m -> RoutePoint(60.0, lonAt(m), 0.0, m, 0.0, null, osm, false, false) }
        return Route(pts, maneuvers, metres.last(), 0.0, 0.0, 0, 0.0, 0)
    }

    private fun lonAt(metres: Double) = 5.0 + metres / (111_320.0 * cos(Math.toRadians(60.0)))
    private fun metresAt(lon: Double) = (lon - 5.0) * (111_320.0 * cos(Math.toRadians(60.0)))

    /** The limit is [before] up to [at] metres from the start, then [after]. */
    private fun limit(at: Double, before: Int, after: Int): (Double, Double, Double) -> Int? =
        { _, lon, _ -> if (metresAt(lon) < at) before else after }

    @Test
    fun aChangeStandsWhereItIsAndNotAtTheNextCorner() {
        // A straight road with corners far apart, as on the rider's Fv542 at Bremnes.
        val changes = SpeedSigns.along(east(0.0, 130.0, 270.0, 600.0), limit(135.0, 60, 80))
        assertEquals(1, changes.size)
        assertEquals(80, changes[0].limit)
        assertTrue("at ${changes[0].along}", abs(changes[0].along - 135.0) < 4.0)
    }

    @Test
    fun noSignForTheLimitTheRiderBeginsWith() {
        assertEquals(emptyList<SpeedChange>(), SpeedSigns.along(east(0.0, 500.0)) { _, _, _ -> 80 })
    }

    @Test
    fun aFirstReadingOfAnotherRoadIsNotAChange() {
        // The very start reads a road beside it; the rider's road is 80 from there on.
        assertEquals(emptyList<SpeedChange>(), SpeedSigns.along(east(0.0, 500.0), limit(10.0, 50, 80)))
    }

    @Test
    fun aJunctionsOtherRoadForOneSampleMakesNoSign() {
        val blip: (Double, Double, Double) -> Int? = { _, lon, _ -> if (metresAt(lon) in 195.0..205.0) 50 else 80 }
        assertEquals(emptyList<SpeedChange>(), SpeedSigns.along(east(0.0, 500.0), blip))
    }

    @Test
    fun aShortHoleInTheRegisterIsNotFilledFromOpenStreetMap() {
        val hole: (Double, Double, Double) -> Int? = { _, lon, _ -> if (metresAt(lon) in 200.0..260.0) null else 80 }
        assertEquals(emptyList<SpeedChange>(), SpeedSigns.along(east(0.0, 500.0, osm = 50), hole))
    }

    @Test
    fun withoutARegisterOpenStreetMapsLimitsCount() {
        val pts = listOf(0.0 to null, 200.0 to 60, 500.0 to 80).map { (m, l) -> RoutePoint(60.0, lonAt(m), 0.0, m, 0.0, null, l, false, false) }
        val changes = SpeedSigns.along(Route(pts, emptyList(), 500.0, 0.0, 0.0, 0, 0.0, 0)) { _, _, _ -> null }
        assertEquals(listOf(80), changes.map { it.limit })
        assertTrue("at ${changes[0].along}", abs(changes[0].along - 200.0) < 4.0)
    }

    @Test
    fun aLimitThatBeginsAtATurnStandsIntoTheRoadTurnedOnto() {
        val turn = Maneuver(ManeuverType.LEFT, 1, 300.0)
        val changes = SpeedSigns.along(east(0.0, 300.0, 800.0, maneuvers = listOf(turn)), limit(305.0, 80, 50))
        assertEquals(1, changes.size)
        assertEquals(340.0, changes[0].along, 0.5)
        assertEquals(lonAt(340.0), changes[0].lon, 1e-6)
    }

    @Test
    fun aLimitWellPastATurnStaysWhereItIs() {
        val turn = Maneuver(ManeuverType.LEFT, 1, 300.0)
        val changes = SpeedSigns.along(east(0.0, 300.0, 800.0, maneuvers = listOf(turn)), limit(500.0, 80, 50))
        assertTrue("at ${changes[0].along}", abs(changes[0].along - 500.0) < 4.0)
    }

    @Test
    fun aLimitBeforeATurnIsNotMoved() {
        val turn = Maneuver(ManeuverType.LEFT, 1, 300.0)
        val changes = SpeedSigns.along(east(0.0, 300.0, 800.0, maneuvers = listOf(turn)), limit(200.0, 80, 50))
        assertTrue("at ${changes[0].along}", abs(changes[0].along - 200.0) < 4.0)
    }

    @Test
    fun aLimitPastARoundaboutStandsPastTheRing() {
        val ring = Maneuver(ManeuverType.ROUNDABOUT, 1, 300.0, exitNumber = 2)
        val changes = SpeedSigns.along(east(0.0, 300.0, 800.0, maneuvers = listOf(ring)), limit(310.0, 80, 50))
        assertEquals(370.0, changes[0].along, 0.5)
    }

    private val limitsPastATurn: (Double, Double, Double) -> Int? = { _, lon, _ ->
        when (metresAt(lon)) { in 0.0..100.0 -> 80; in 100.0..310.0 -> 60; in 310.0..600.0 -> 50; else -> 40 }
    }

    @Test
    fun onTheRoadAheadNoSignPastTheFirstTurn() {
        val turn = Maneuver(ManeuverType.RIGHT, 1, 300.0)
        val road = SpeedSigns.onRoadAhead(east(0.0, 300.0, 900.0, maneuvers = listOf(turn)), sideRoads = false, limitsPastATurn)
        assertEquals(listOf(60), road.changes.map { it.limit })
    }

    @Test
    fun onTheRoadAheadASlightBendIsStillStraightOn() {
        for (type in listOf(ManeuverType.SLIGHT_LEFT, ManeuverType.KEEP_RIGHT)) {
            val bend = Maneuver(type, 1, 300.0)
            val road = SpeedSigns.onRoadAhead(east(0.0, 300.0, 900.0, maneuvers = listOf(bend)), sideRoads = false, limit(600.0, 80, 50))
            assertEquals(listOf(50), road.changes.map { it.limit })
        }
    }

    @Test
    fun withSideRoadsOnlyTheLimitJustPastTheFirstTurn() {
        val turn = Maneuver(ManeuverType.RIGHT, 1, 300.0)
        val road = SpeedSigns.onRoadAhead(east(0.0, 300.0, 900.0, maneuvers = listOf(turn)), sideRoads = true, limitsPastATurn)
        assertEquals(listOf(60), road.changes.map { it.limit })
        assertEquals(50, road.sideRoad?.limit)
    }

    @Test
    fun withSideRoadsNothingFarDownASideRoad() {
        val turn = Maneuver(ManeuverType.RIGHT, 1, 300.0)
        val road = SpeedSigns.onRoadAhead(east(0.0, 300.0, 900.0, maneuvers = listOf(turn)), sideRoads = true, limit(600.0, 80, 50))
        assertEquals(emptyList<SpeedChange>(), road.changes)
        assertEquals(null, road.sideRoad)
    }

    @Test
    fun onTheRoadAheadARoundaboutIsNotATurn() {
        val ring = Maneuver(ManeuverType.ROUNDABOUT, 1, 300.0, exitNumber = 2)
        val road = SpeedSigns.onRoadAhead(east(0.0, 300.0, 900.0, maneuvers = listOf(ring)), sideRoads = false, limit(600.0, 80, 50))
        assertEquals(listOf(50), road.changes.map { it.limit })
    }

    @Test
    fun onTheRoadAheadARoundaboutsWayOutToOneSideLeavesTheRoad() {
        // East to the ring at 300 m, then north.
        val north = { m: Double -> 60.0 + (m - 300.0) / 110_574.0 }
        val pts = listOf(0.0, 300.0).map { m -> RoutePoint(60.0, lonAt(m), 0.0, m, 0.0, null, null, false, false) } +
            listOf(500.0, 900.0).map { m -> RoutePoint(north(m), lonAt(300.0), 0.0, m, 0.0, null, null, false, false) }
        val ring = Maneuver(ManeuverType.ROUNDABOUT, 1, 300.0, exitNumber = 1)
        val probe = Route(pts, listOf(ring), 900.0, 0.0, 0.0, 0, 0.0, 0)
        val limits: (Double, Double, Double) -> Int? = { lat, _, _ -> if (lat > north(400.0)) 50 else 80 }
        assertEquals(emptyList<SpeedChange>(), SpeedSigns.onRoadAhead(probe, sideRoads = false, limits).changes)
        assertEquals(50, SpeedSigns.onRoadAhead(probe, sideRoads = true, limits).sideRoad?.limit)
    }

    @Test
    fun theLimitAlongARouteChangesAtItsSign() {
        val limits = SpeedSigns.limits(east(0.0, 130.0, 270.0, 600.0), limit(135.0, 60, 80))
        val sign = limits.changes.single().along
        assertEquals(60, limits.start)
        assertEquals(60, limits.at(0.0))
        assertEquals(60, limits.at(sign - 1.0))
        assertEquals(80, limits.at(sign + 1.0))
    }

    @Test
    fun theLimitStaysOnTheOldRoadUntilItsSignIntoTheRoadTurnedOnto() {
        val turn = Maneuver(ManeuverType.LEFT, 1, 300.0)
        val limits = SpeedSigns.limits(east(0.0, 300.0, 800.0, maneuvers = listOf(turn)), limit(305.0, 80, 50))
        assertEquals(80, limits.at(320.0))
        assertEquals(50, limits.at(345.0))
    }

    @Test
    fun aShortHoleInTheRegisterKeepsTheLimit() {
        val hole: (Double, Double, Double) -> Int? = { _, lon, _ -> if (metresAt(lon) in 200.0..260.0) null else 80 }
        val limits = SpeedSigns.limits(east(0.0, 500.0), hole)
        assertEquals(80, limits.at(230.0))
    }

    @Test
    fun whereNoLimitIsKnownForLongTheCardGetsNone() {
        // E.g. abroad, off a main road onto one without a limit in OpenStreetMap: the old limit must not follow along.
        val pts = listOf(0.0 to 80, 400.0 to 80, 900.0 to null, 1200.0 to 80).map { (m, l) -> RoutePoint(60.0, lonAt(m), 0.0, m, 0.0, null, l, false, false) }
        val limits = SpeedSigns.limits(Route(pts, emptyList(), 1200.0, 0.0, 0.0, 0, 0.0, 0)) { _, _, _ -> null }
        assertEquals(80, limits.at(350.0))
        assertEquals(null, limits.at(700.0))
        assertEquals(80, limits.at(1000.0))
        assertEquals(emptyList<SpeedChange>(), limits.changes)
    }

    /**
     * Fv542 at Bremnes with NVDB's limits as of 2026-11-01 (src/test/resources/speedsigns, cut from the speed limits
     * file; NLOD 2.0, Statens vegvesen). Skipped without segments.
     */
    private val cells by lazy {
        javaClass.getResourceAsStream("/speedsigns/bremnes.csv")!!.bufferedReader().readLines().map { l ->
            val (value, pts) = l.split(",", limit = 2)
            value.toInt() to pts.split(";").map { p -> p.split(" ").let { LatLon(it[0].toDouble(), it[1].toDouble()) } }
        }
    }

    /** As SpeedLimits.at. */
    private fun register(lat: Double, lon: Double, headingDeg: Double): Int? {
        val kx = cos(Math.toRadians(lat)) * 111_320.0
        var best: Int? = null
        var bestD = 20.0
        for ((value, pts) in cells) for ((a, b) in pts.zipWithNext()) {
            val ax = (a.lon - lon) * kx; val ay = (a.lat - lat) * 110_574.0
            val sx = (b.lon - a.lon) * kx; val sy = (b.lat - a.lat) * 110_574.0
            val len2 = sx * sx + sy * sy
            val t = if (len2 == 0.0) 0.0 else (-(ax * sx + ay * sy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(ax + t * sx, ay + t * sy)
            if (d >= bestD) continue
            if (len2 > 1.0) {
                val bearing = Math.toDegrees(kotlin.math.atan2(sx, sy))
                if (abs(((headingDeg - bearing) % 180 + 270) % 180 - 90) > 40.0) continue
            }
            bestD = d
            best = value
        }
        return best
    }

    private val engine by lazy {
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue(File(segments, "E5_N55.rd5").exists())
        BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("p").toFile()))
    }

    /** The sign the rider found on Street View: end of 60 going east from the roundabout, at 59.798768, 5.209998. */
    @Test
    fun theEightyAtBremnesStandsAtItsRealSign() {
        val ride = engine.route(listOf(LatLon(59.79880, 5.20760), LatLon(59.79930, 5.21793)), RouteOptions(RoadStyle.FASTEST))
        val eighty = SpeedSigns.along(ride, ::register).single { it.limit == 80 }
        val off = Geo.distance(eighty.lat, eighty.lon, 59.798768, 5.209998)
        assertTrue("$off m from the real sign", off < 15.0)
    }

    /** A look at the road from different places finds the sign at the same place, so it doesn't jump. */
    @Test
    fun theSignIsAtTheSamePlaceWhereverTheLookStarts() {
        val places = listOf(59.79880 to 5.20760, 59.79879 to 5.20800, 59.79878 to 5.20850, 59.79877 to 5.20900).map { (la, lo) ->
            val look = engine.route(listOf(LatLon(la, lo), LatLon(59.79930, 5.21793)), RouteOptions(RoadStyle.FASTEST))
            SpeedSigns.along(look, ::register).single { it.limit == 80 }
        }
        for (s in places) assertTrue(Geo.distance(s.lat, s.lon, places[0].lat, places[0].lon) < 4.0)
    }
}
