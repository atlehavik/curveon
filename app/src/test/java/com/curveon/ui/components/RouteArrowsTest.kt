package com.curveon.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the route line is ridden both ways, for its two rows of direction arrows. */
class RouteArrowsTest {
    // About 1 m in degrees at 60° N.
    private val mLat = 1 / 110_540.0
    private val mLon = 1 / (111_320.0 * 0.5)

    private fun line(vararg pts: Pair<Double, Double>) = Pair(
        DoubleArray(pts.size) { 60.0 + pts[it].second * mLat },
        DoubleArray(pts.size) { 5.0 + pts[it].first * mLon }
    )

    @Test
    fun `a straight line is ridden one way`() {
        val (lat, lon) = line(0.0 to 0.0, 0.0 to 200.0, 0.0 to 400.0)
        assertTrue(bothWaysSegments(lat, lon).none { it })
    }

    @Test
    fun `out to a turning place and back along the same road is ridden both ways`() {
        // North 300 m, round a small turning loop, and back south 300 m along the same road, then on east.
        val (lat, lon) = line(
            0.0 to 0.0, 0.0 to 100.0, 0.0 to 300.0, 6.0 to 310.0, 0.0 to 320.0, -6.0 to 310.0,
            0.0 to 300.0, 0.0 to 100.0, 0.0 to 0.0, 200.0 to 0.0
        )
        val both = bothWaysSegments(lat, lon)
        assertEquals(lat.size - 1, both.size)
        assertTrue("in", both[1])
        assertTrue("back", both[6])
        assertFalse("on east, after the stretch ridden twice", both[8])
    }

    @Test
    fun `a road crossed, or a loop ridden round, is not ridden both ways`() {
        // A square loop that ends where it began, crossing its own start at right angles.
        val (lat, lon) = line(0.0 to -50.0, 0.0 to 200.0, 200.0 to 200.0, 200.0 to 0.0, -50.0 to 0.0)
        assertTrue(bothWaysSegments(lat, lon).none { it })
    }

    @Test
    fun `two lanes of a road a little apart still count`() {
        // Back along the other carriageway 5 m to the side.
        val (lat, lon) = line(0.0 to 0.0, 0.0 to 300.0, 5.0 to 305.0, 5.0 to 0.0)
        val both = bothWaysSegments(lat, lon)
        assertTrue(both[0] && both[2])
    }

    @Test
    fun `eastern longitudes do not distort north-south lanes`() {
        // At lon=30°E (e.g. Kirkenes/Finland), absolute lon*cos(lat) sheared north-south roads by ~24°.
        val mLon70 = 1 / (111_320.0 * kotlin.math.cos(Math.toRadians(70.0)))
        val pts = listOf(0.0 to 0.0, 0.0 to 300.0, 5.0 to 305.0, 5.0 to 0.0)
        val lat = DoubleArray(pts.size) { 70.0 + pts[it].second * mLat }
        val lon = DoubleArray(pts.size) { 30.0 + pts[it].first * mLon70 }
        val both = bothWaysSegments(lat, lon)
        assertTrue(both[0] && both[2])
    }
}
