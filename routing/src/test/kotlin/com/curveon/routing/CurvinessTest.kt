package com.curveon.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class CurvinessTest {

    /** Draws a line in metres east/north of a point in western Norway, turning as told. */
    private class Pen {
        private var x = 0.0
        private var y = 0.0
        private var heading = 90.0 // east
        val points = mutableListOf(at())

        private fun at() = LatLon(62.45 + y / 111_320.0, 7.66 + x / (111_320.0 * cos(62.45 * PI / 180)))

        fun straight(m: Double, nodeEvery: Double = m) {
            var left = m
            while (left > 1e-6) {
                val s = minOf(nodeEvery, left)
                x += s * sin(heading * PI / 180); y += s * cos(heading * PI / 180)
                points += at(); left -= s
            }
        }

        /** An arc of [deg] (positive = right) with radius [r], drawn with a point every [nodeEvery] metres. */
        fun arc(deg: Double, r: Double, nodeEvery: Double) {
            val length = r * Math.toRadians(kotlin.math.abs(deg))
            val n = maxOf(1, (length / nodeEvery).toInt())
            repeat(n) {
                heading += deg / n / 2; straight(length / n); heading += deg / n / 2
            }
        }

        /** A junction: the road turns [deg] at one point. */
        fun corner(deg: Double) { heading += deg }
    }

    @Test
    fun `hairpins count in full and beat gentle bends`() {
        val hairpins = Pen().apply {
            straight(150.0, 30.0)
            repeat(6) { i -> arc(if (i % 2 == 0) 180.0 else -180.0, 10.0, 4.0); straight(150.0, 30.0) }
        }.points
        val gentle = Pen().apply {
            repeat(8) { i -> arc(if (i % 2 == 0) 40.0 else -40.0, 200.0, 10.0) }
        }.points
        val trueHairpins = 6 * 180.0 / ((7 * 150.0 + 6 * PI * 10.0) / 1000)
        val measured = Curviness.degreesPerKm(hairpins)
        println("hairpins %.0f deg/km (true %.0f), gentle %.0f deg/km".format(measured, trueHairpins, Curviness.degreesPerKm(gentle)))
        assertEquals(trueHairpins, measured, trueHairpins * 0.15)
        assertTrue(Curviness.score(hairpins) > Curviness.score(gentle))
    }

    @Test
    fun `hairpins drawn with few points still count`() {
        // Three points round the bend, ~10 m apart: no single one of them holds the turn.
        val coarse = Pen().apply {
            straight(150.0, 30.0)
            repeat(4) { i -> arc(if (i % 2 == 0) 180.0 else -180.0, 10.0, 10.0); straight(150.0, 30.0) }
        }.points
        val trueDeg = 4 * 180.0 / ((5 * 150.0 + 4 * PI * 10.0) / 1000)
        assertEquals(trueDeg, Curviness.degreesPerKm(coarse), trueDeg * 0.2)
    }

    @Test
    fun `turns at junctions in a town are not curves`() {
        // Blocks of 80 m, alternately left and right, with a point halfway along each street.
        val town = Pen().apply {
            repeat(12) { i -> straight(80.0, 40.0); corner(if (i % 2 == 0) 90.0 else -90.0) }
            straight(80.0, 40.0)
        }.points
        val staggered = Pen().apply {
            repeat(5) { straight(200.0, 50.0); corner(90.0); straight(15.0); corner(-90.0) }
            straight(200.0, 50.0)
        }.points
        assertTrue(Curviness.degreesPerKm(town) < 20)
        assertTrue(Curviness.degreesPerKm(staggered) < 20)
    }

    @Test
    fun `a bend drawn as an arc counts, the same bend at a junction does not`() {
        val bend = Pen().apply { straight(300.0, 50.0); arc(90.0, 30.0, 6.0); straight(300.0, 50.0) }.points
        val junction = Pen().apply { straight(300.0, 50.0); corner(90.0); straight(300.0, 50.0) }.points
        val km = (600 + 30 * PI / 2) / 1000
        assertEquals(90 / km, Curviness.degreesPerKm(bend), 90 / km * 0.2)
        assertEquals(0.0, Curviness.degreesPerKm(junction), 1.0)
    }

    @Test
    fun `straight and empty lines score nothing`() {
        assertEquals(0, Curviness.score(Pen().apply { straight(2_000.0, 20.0) }.points))
        assertEquals(0, Curviness.score(emptyList()))
        assertEquals(0, Curviness.score(listOf(LatLon(60.0, 5.0))))
    }
}
