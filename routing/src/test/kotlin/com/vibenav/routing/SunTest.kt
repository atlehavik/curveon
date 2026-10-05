package com.vibenav.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class SunTest {
    private fun t(s: String) = ZonedDateTime.parse(s).toInstant().toEpochMilli()

    @Test
    fun `oslo midsummer noon is high, midwinter midnight is dark`() {
        // Max elevation in Oslo at summer solstice ≈ 90 - 59.9 + 23.4 = 53.5°
        assertEquals(53.5, Sun.elevationDeg(59.91, 10.75, t("2026-06-21T13:20:00+02:00")), 1.0)
        assertFalse(Sun.isDay(59.91, 10.75, t("2026-12-21T00:00:00+01:00")))
    }

    @Test
    fun `midnight sun in tromso, polar night at noon`() {
        assertTrue(Sun.isDay(69.65, 18.96, t("2026-06-21T01:00:00+02:00")))
        assertTrue(Sun.elevationDeg(69.65, 18.96, t("2026-12-21T12:00:00+01:00")) < 0)
    }
}
