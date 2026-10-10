package com.curveon.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Elm327Test {
    @Test
    fun `reads RPM and fuel answers with or without spaces`() {
        assertEquals(listOf(0x1A, 0xF8), Elm327.parse("010C", "41 0C 1A F8\r\r"))
        assertEquals(listOf(0x1A, 0xF8), Elm327.parse("010C", "SEARCHING...\r410C1AF8\r"))
        assertEquals(listOf(0x80), Elm327.parse("012F", "412F80"))
    }

    @Test
    fun `values the bike doesn't report are null`() {
        assertNull(Elm327.parse("012F", "NO DATA\r"))
        assertNull(Elm327.parse("012F", "?\r"))
        assertNull(Elm327.parse("010C", "UNABLE TO CONNECT\r"))
    }

    @Test
    fun `reads the battery voltage`() {
        assertEquals(12.6f, Elm327.voltage("12.6V\r")!!, 0.01f)
        assertNull(Elm327.voltage("?"))
    }
}
