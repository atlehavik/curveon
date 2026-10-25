package com.curveon.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The road ahead of a rider who is not navigating, on the E39 at Horneland on Stord. Skipped without segments. */
class RoadAheadTest {
    private lateinit var engine: BRouterEngine
    // On the E39 about 400 m before McDonald's, the road going east-north-east.
    private val onE39 = LatLon(59.76162, 5.45000)

    @Before
    fun setUp() {
        val segments = File(System.getProperty("curveon.segments") ?: "")
        assumeTrue("No BRouter segments at $segments", File(segments, "E5_N55.rd5").exists())
        engine = BRouterEngine(segments, ProfileInstaller.install(Files.createTempDirectory("profiles").toFile()))
    }

    @Test
    fun theRoadAheadIsFoundForAnyHeadingAlongTheRoad() {
        // The road goes 41°; a GPS heading is rarely exact.
        for (heading in listOf(30.0, 40.0, 50.0, 60.0)) {
            val road = RoadAhead.probe(engine, onE39, heading)
            assertNotNull("no road ahead heading $heading", road)
            val far = road!!.points.first { it.distanceFromStart >= 100 }
            val first = road.points.first()
            val way = Geo.bearing(first.lat, first.lon, far.lat, far.lon)
            assertTrue("heading $heading but the probe goes $way", Math.abs(Geo.angleDiff(heading, way)) < 60.0)
            assertTrue("only ${road.distanceMeters} m", road.distanceMeters > 300.0)
            assertTrue("no speed limit on the road ahead", road.points.any { it.maxSpeedKmh != null })
        }
    }

    @Test
    fun noRoadAheadOutAtSea() {
        assertNull(RoadAhead.probe(engine, LatLon(59.70, 5.10), 90.0))
    }

    /** On the eastbound side of the E39 the way west is a 2 km detour round a junction: not "the road ahead". */
    @Test
    fun noRoadAheadWhereTheRiderCannotGoThatWay() {
        assertNull(RoadAhead.probe(engine, onE39, 240.0))
    }

    @Test
    fun aProbeThatGoesTheOtherWayOrAroundIsNotTheRoadAhead() {
        val road = RoadAhead.probe(engine, onE39, 60.0)!!
        // The same road for a rider going the opposite way, or one who is somewhere else: not it.
        assertEquals(false, RoadAhead.isRoadAhead(road, onE39, 240.0, 700.0))
        assertEquals(false, RoadAhead.isRoadAhead(road, LatLon(59.7700, 5.4500), 60.0, 700.0))
        assertEquals(false, RoadAhead.isRoadAhead(road, onE39, 60.0, road.distanceMeters / 2))
    }
}
