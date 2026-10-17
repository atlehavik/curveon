package com.curveon.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelCalibrationTest {

    private val tank = 16f

    /** A refuelling that put in [pumped] litres: the before and the after reading, [at] ms apart from the start of the day. */
    private fun fill(at: Long, pumped: Float, signals: (Float) -> Map<String, Int>) = listOf(
        FuelPoint(tank - pumped, signals(tank - pumped), at, session = at),
        FuelPoint(tank, signals(tank), at + 600_000, session = at)
    )

    /** A bike with the fuel on 3F0 byte 3 (rising, 15 counts a litre), a drifting counter, a decoy and revs. */
    private fun bike(day: Int) = { litres: Float ->
        mapOf("can:3F0:2" to 12 + (litres * 15).toInt(), "can:5B0:0-1" to 1_000 + day * 400, "can:5A0:1" to (litres * 7).toInt() % 50,
            "can:100:0" to 7, "pid:010C:0" to 30)
    }

    private val points = fill(1_000_000, 10f, bike(0)) + fill(2_000_000_000, 6f, bike(1)) + fill(3_000_000_000, 3f, bike(2))

    @Test fun findsTheSignalThatFollowsTheFuel() {
        val found = FuelAnalysis.candidates(points)
        assertEquals("can:3F0:2", found.first().signal)
        // A counter that drifts with the days, a decoy that goes up and down, a constant and revs are not offered.
        assertTrue(found.none { it.signal in setOf("can:5B0:0-1", "can:5A0:1", "can:100:0", "pid:010C:0") })
    }

    @Test fun oneRefuellingIsNotEnoughToTell() {
        assertTrue(FuelAnalysis.candidates(fill(1_000_000, 10f, bike(0))).isEmpty())
    }

    @Test fun aSignalThatDriftsWithTimeIsNotTheFuelEvenIfItFitsTheLevels() {
        // Reads higher every day and happens to line up with the litres: the full tank gives different values on different days.
        val drifting = { day: Int -> { litres: Float -> mapOf("can:7E0:0" to 20 + (litres * 10).toInt() + day * 60, "can:3F0:2" to 12 + (litres * 15).toInt()) } }
        val found = FuelAnalysis.candidates(fill(1L, 10f, drifting(0)) + fill(2L, 6f, drifting(1)) + fill(3L, 3f, drifting(2)))
        assertEquals(listOf("can:3F0:2"), found.map { it.signal })
    }

    @Test fun aSignalThatDoesNotMoveAtThePumpIsNotTheFuel() {
        // Slowly follows the litres between days, but a refuelling does not move it.
        val sluggish = { day: Int -> { litres: Float -> mapOf("can:7E0:0" to 100 + day, "can:3F0:2" to 12 + (litres * 15).toInt()) } }
        val found = FuelAnalysis.candidates(fill(1L, 10f, sluggish(0)) + fill(2L, 6f, sluggish(1)) + fill(3L, 3f, sluggish(2)))
        assertEquals(listOf("can:3F0:2"), found.map { it.signal })
    }

    @Test fun aFallingGaugeIsFoundToo() {
        val falling = { litres: Float -> mapOf("can:3F0:2" to 250 - (litres * 15).toInt()) }
        assertEquals("can:3F0:2", FuelAnalysis.candidates(fill(1L, 10f, falling) + fill(2L, 6f, falling) + fill(3L, 4f, falling)).first().signal)
    }

    @Test fun readingsOfTheSameMomentKeepOnlyWhatHeldStill() {
        val kept = FuelAnalysis.stable(mapOf("a" to 100, "b" to 10, "c" to 5), mapOf("a" to 102, "b" to 60))
        assertEquals(mapOf("a" to 101), kept)
    }

    @Test fun aRefuellingMakesTheBeforeAndAfterLevelsFromThePump() {
        val start = FuelCalibration(tankLiters = tank, before = PendingFill(mapOf("k" to 1), at = 1_000))
        val done = start.withRefuel(mapOf("k" to 9), pumped = 10f, now = 61_000)!!
        assertNull(done.before)
        assertEquals(listOf(6f, 16f), done.points.map { it.liters })
        assertEquals(1, done.sessions)
    }

    @Test fun takingBackARefuellingRemovesBothLevelsAndTheReadingItNoLongerSupports() {
        val c = FuelCalibration(tankLiters = tank, points = points, source = "can:3F0:2")
        // Either level of a refuelling takes the pair with it; three refuellings still support the reading.
        val one = c.without(1_000_000)
        assertEquals(4, one.points.size)
        assertEquals("can:3F0:2", one.source)
        // Two are enough, one is not: the reading is dropped rather than kept on one refuelling.
        val two = one.without(2_000_600_000)
        assertEquals(2, two.points.size)
        assertNull(two.source)
        assertEquals(1, two.sessions)
    }

    @Test fun aRefuellingThatCantBeRightIsRefused() {
        val start = FuelCalibration(tankLiters = tank, before = PendingFill(mapOf("k" to 1), at = 1_000))
        assertNull(start.withRefuel(mapOf("k" to 9), pumped = 20f, now = 2_000))          // more than the tank
        assertNull(start.withRefuel(mapOf("k" to 9), pumped = 0.4f, now = 2_000))         // too little to tell
        assertNull(start.withRefuel(mapOf("k" to 9), pumped = 10f, now = 1_000 + FuelCalibration.MAX_FILL_GAP_MS + 1)) // rode in between
        assertNull(start.copy(tankLiters = null).withRefuel(mapOf("k" to 9), pumped = 10f, now = 2_000))
        assertNull(FuelCalibration(tankLiters = tank).withRefuel(mapOf("k" to 9), pumped = 10f, now = 2_000))
    }

    @Test fun tankTableInterpolatesBetweenTheRegisteredLevels() {
        val c = FuelCalibration(tankLiters = 16f, points = points, source = "can:3F0:2")
        val full = 12 + 16 * 15
        assertEquals(16f, c.liters(full)!!, 0.01f)
        assertEquals(6f, c.liters(12 + 6 * 15)!!, 0.01f)
        assertEquals(8f, c.liters(12 + 8 * 15)!!, 0.05f)   // between the 6 L and 10 L levels
        assertEquals(6f, c.liters(0)!!, 0.01f)       // below the lowest level seen: the lowest level
        assertEquals(16f, c.liters(255)!!, 0.01f)
        assertNull(c.copy(source = null).liters(100))
    }

    @Test fun frameParsing() {
        assertEquals("3F0" to listOf(1, 2, 255), Elm327.frame("3F0 01 02 FF"))
        assertNull(Elm327.frame("BUFFER FULL"))
        assertNull(Elm327.frame("3F0 1 2"))
        assertNotNull(Elm327.frame("18DAF110 03 41 0C 1A"))
    }

    @Test fun signalKeys() {
        val s = FuelSignal.parse("pid:0151:0-1")!!
        assertEquals(0x0102, s.value(listOf(1, 2, 3)))
        assertEquals("pid:0151:0-1", s.key)
        assertNull(FuelSignal.parse("nonsense"))
    }
}
