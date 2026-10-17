package com.curveon.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelCalibrationTest {

    private fun point(l: Float, fuel: Int, decoy: Int, rpm: Int = 30) =
        FuelPoint(l, mapOf("can:3F0:2" to fuel, "can:5A0:1" to decoy, "can:100:0" to 7, "pid:010C:0" to rpm), 0L)

    private val points = listOf(
        point(0f, 12, 90), point(8f, 130, 10), point(16f, 251, 60), point(4f, 70, 33, rpm = 90)
    )

    @Test fun findsTheSignalThatFollowsTheFuel() {
        val found = FuelAnalysis.candidates(points)
        assertEquals("can:3F0:2", found.first().signal)
        // A decoy that goes up and down, a constant and revs (known as not fuel) are not offered.
        assertTrue(found.none { it.signal == "can:5A0:1" || it.signal == "can:100:0" || it.signal == "pid:010C:0" })
    }

    @Test fun twoLevelsAreNotEnoughToTell() {
        assertTrue(FuelAnalysis.candidates(points.take(2)).isEmpty())
    }

    @Test fun tankTableInterpolatesBetweenTheRegisteredLevels() {
        val c = FuelCalibration(tankLiters = 16f, points = points, source = "can:3F0:2")
        assertEquals(0f, c.liters(12)!!, 0.01f)
        assertEquals(16f, c.liters(251)!!, 0.01f)
        assertEquals(6f, c.liters(100)!!, 0.01f)
        assertEquals(0f, c.liters(0)!!, 0.01f)
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
