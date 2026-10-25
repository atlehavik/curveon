package com.curveon.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

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
    fun `only NO DATA and an unknown command mean the bike lacks a value`() {
        assertTrue(Elm327.notReported("NO DATA\r"))
        assertTrue(Elm327.notReported("SEARCHING...\rNO DATA\r"))
        assertTrue(Elm327.notReported("?\r"))
        assertFalse(Elm327.notReported("UNABLE TO CONNECT\r"))
        assertFalse(Elm327.notReported("CAN ERROR\r"))
        assertFalse(Elm327.notReported("")) // timed out
    }

    @Test
    fun `reads the battery voltage`() {
        assertEquals(12.6f, Elm327.voltage("12.6V\r")!!, 0.01f)
        assertNull(Elm327.voltage("?"))
    }

    /** An adapter that answers each command after [delayMs] of it (by command), with the answer of [answers]. */
    private class FakeAdapter(private val answers: Map<String, String>, private val delayMs: Map<String, Long> = emptyMap()) {
        private val toUs = LinkedBlockingQueue<Int>()
        val input = object : InputStream() {
            override fun read(): Int = toUs.take().also { if (it < 0) throw IOException("closed") }

            // Like a Bluetooth socket: what has come so far, not a full buffer.
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b[off] = read().toByte()
                var n = 1
                while (n < len) {
                    val c = toUs.peek() ?: break
                    if (c < 0) break
                    b[off + n++] = toUs.take().toByte()
                }
                return n
            }
        }
        val output = object : OutputStream() {
            private val line = StringBuilder()
            override fun write(b: Int) {
                if (b.toChar() != '\r') { line.append(b.toChar()); return }
                val cmd = line.toString().also { line.clear() }
                val answer = answers[cmd] ?: "?"
                thread {
                    Thread.sleep(delayMs[cmd] ?: 0)
                    "$answer\r\r>".forEach { toUs.put(it.code) }
                }
            }
        }
        fun close() = toUs.put(-1)
    }

    @Test
    fun `an answer that comes too late is not taken for the next command's`() {
        val adapter = FakeAdapter(mapOf("010C" to "41 0C 1A F8", "010D" to "41 0D 3C"), mapOf("010C" to 300L))
        val elm = Elm327(adapter.input, adapter.output)
        assertEquals("", elm.send("010C", 100).trim())
        val speed = elm.send("010D", 2_000)
        assertEquals(listOf(0x3C), Elm327.parse("010D", speed))
        // And in step again afterwards.
        assertEquals(listOf(0x1A, 0xF8), Elm327.parse("010C", elm.send("010C", 2_000)))
    }

    @Test(expected = IOException::class)
    fun `a closed link is an error, not an empty answer`() {
        val adapter = FakeAdapter(emptyMap())
        val elm = Elm327(adapter.input, adapter.output)
        adapter.close()
        elm.send("010C", 2_000)
    }
}
