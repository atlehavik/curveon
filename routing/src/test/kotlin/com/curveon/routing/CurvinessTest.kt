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
        /** Where [corner] turned: what a routed line's turn instructions would say. */
        val junctions = mutableListOf<Int>()

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

        /** A bend drawn as one point, the road turning [deg] there. */
        fun bend(deg: Double) { heading += deg }

        /** A junction: the route turns [deg] onto another road. */
        fun corner(deg: Double) { heading += deg; junctions += points.lastIndex }
    }

    private fun Pen.degreesPerKm() = Curviness.degreesPerKm(points, junctions)

    @Test
    fun `hairpins count in full and beat gentle bends`() {
        val hairpins = Pen().apply {
            straight(150.0, 30.0)
            repeat(6) { i -> arc(if (i % 2 == 0) 180.0 else -180.0, 10.0, 4.0); straight(150.0, 30.0) }
        }
        val gentle = Pen().apply {
            repeat(8) { i -> arc(if (i % 2 == 0) 40.0 else -40.0, 200.0, 10.0) }
        }
        val trueHairpins = 6 * 180.0 / ((7 * 150.0 + 6 * PI * 10.0) / 1000)
        val measured = hairpins.degreesPerKm()
        println("hairpins %.0f deg/km (true %.0f), gentle %.0f deg/km".format(measured, trueHairpins, gentle.degreesPerKm()))
        assertEquals(trueHairpins, measured, trueHairpins * 0.15)
        assertTrue(measured > gentle.degreesPerKm())
    }

    @Test
    fun `bends drawn with few points count in full`() {
        // As small and gravel roads often are in the map: points 10-20 m apart round the bend, or one at its middle.
        fun check(name: String, trueDegPerKm: Double, pen: Pen) {
            val measured = pen.degreesPerKm()
            println("%s: %.0f deg/km (true %.0f)".format(name, measured, trueDegPerKm))
            assertEquals(name, trueDegPerKm, measured, trueDegPerKm * 0.2)
        }
        check("hairpins, points 10 m apart", 4 * 180.0 / ((5 * 150.0 + 4 * PI * 10.0) / 1000), Pen().apply {
            straight(150.0, 30.0); repeat(4) { i -> arc(if (i % 2 == 0) 180.0 else -180.0, 10.0, 10.0); straight(150.0, 30.0) }
        })
        check("hairpins, points 15 m apart", 4 * 180.0 / ((5 * 150.0 + 4 * PI * 10.0) / 1000), Pen().apply {
            straight(150.0, 30.0); repeat(4) { i -> arc(if (i % 2 == 0) 180.0 else -180.0, 10.0, 15.0); straight(150.0, 30.0) }
        })
        check("right angles as two points 20 m apart", 4 * 90.0 / ((5 * 200.0 + 4 * 20.0) / 1000), Pen().apply {
            straight(200.0, 50.0)
            repeat(4) { i -> val s = if (i % 2 == 0) 1 else -1; bend(45.0 * s); straight(20.0); bend(45.0 * s); straight(200.0, 50.0) }
        })
        check("60° bends as one point", 6 * 60.0 / (7 * 120.0 / 1000), Pen().apply {
            straight(120.0, 40.0); repeat(6) { i -> bend(if (i % 2 == 0) 60.0 else -60.0); straight(120.0, 40.0) }
        })
    }

    @Test
    fun `turns at junctions in a town are not curves`() {
        // Blocks of 80 m, alternately left and right, with a point halfway along each street.
        val town = Pen().apply {
            repeat(12) { i -> straight(80.0, 40.0); corner(if (i % 2 == 0) 90.0 else -90.0) }
            straight(80.0, 40.0)
        }
        val staggered = Pen().apply {
            repeat(5) { straight(200.0, 50.0); corner(90.0); straight(15.0); corner(-90.0) }
            straight(200.0, 50.0)
        }
        assertTrue(town.degreesPerKm() < 20)
        assertTrue(staggered.degreesPerKm() < 20)
    }

    @Test
    fun `a bend counts, the same turn at a junction does not`() {
        val arc = Pen().apply { straight(300.0, 50.0); arc(90.0, 30.0, 6.0); straight(300.0, 50.0) }
        val bend = Pen().apply { straight(300.0, 50.0); bend(90.0); straight(300.0, 50.0) }
        val junction = Pen().apply { straight(300.0, 50.0); corner(90.0); straight(300.0, 50.0) }
        val km = (600 + 30 * PI / 2) / 1000
        assertEquals(90 / km, arc.degreesPerKm(), 90 / km * 0.2)
        assertEquals(90 / 0.6, bend.degreesPerKm(), 90 / 0.6 * 0.2)
        assertEquals(0.0, junction.degreesPerKm(), 1.0)
    }

    @Test
    fun `the turn instructions of a route say where its junctions are`() {
        val m = listOf(
            Maneuver(ManeuverType.DEPART, 0, 0.0), Maneuver(ManeuverType.STRAIGHT, 3, 0.0), Maneuver(ManeuverType.RIGHT, 5, 0.0),
            Maneuver(ManeuverType.ROUNDABOUT, 9, 0.0, 2), Maneuver(ManeuverType.KEEP_LEFT, 12, 0.0), Maneuver(ManeuverType.ARRIVE, 20, 0.0)
        )
        assertEquals(listOf(5, 9, 12), Curviness.junctions(m))
    }

    @Test
    fun `straight and empty lines score nothing`() {
        assertEquals(0, Curviness.score(Pen().apply { straight(2_000.0, 20.0) }.points))
        assertEquals(0, Curviness.score(emptyList()))
        assertEquals(0, Curviness.score(listOf(LatLon(60.0, 5.0))))
    }
}
