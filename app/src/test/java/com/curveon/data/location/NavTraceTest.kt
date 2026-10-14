package com.curveon.data.location

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class NavTraceTest {
    @Test
    fun numbersAreWrittenAsStringFormatWould() {
        val values = listOf(59.1234567891, -0.00000004, 10.5, -10.5, 0.0, 359.6, 12.345, -7.0, 1e-9, 0.04999, 99.99999999)
        for (v in values) for (d in listOf(0, 1, 2, 6, 7)) {
            val want = "%.${d}f".format(Locale.US, v).let { if (it.startsWith("-") && it.trim('-', '0', '.').isEmpty()) it.drop(1) else it }
            assertEquals("$v with $d decimals", want, NavTrace.num(v, d))
        }
    }
}
