package com.curveon.phone

import com.curveon.protocol.Message.CueSide
import com.curveon.protocol.Message.NavCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CueSoundsTest {

    @Test
    fun `sounds are short, start silent and end silent`() {
        for (cue in NavCue.entries) {
            val pcm = CueSounds.pcm(cue, CueSide.CENTER)
            val seconds = pcm.size / 2.0 / CueSounds.RATE
            assertTrue("$cue lasts $seconds s", seconds in CueSounds.LEAD_IN_S + 0.05..CueSounds.LEAD_IN_S + 0.8)
            val lead = (CueSounds.LEAD_IN_S * CueSounds.RATE).toInt() * 2
            assertTrue((0 until lead).all { pcm[it].toInt() == 0 })
            assertTrue("$cue clicks at the end", abs(pcm[pcm.size - 1].toInt()) < 200)
            assertTrue("$cue is too loud", pcm.maxOf { abs(it.toInt()) } < Short.MAX_VALUE * 0.7)
        }
    }

    @Test
    fun `a left turn is louder in the left ear`() {
        val pcm = CueSounds.pcm(NavCue.PREPARE, CueSide.LEFT)
        val left = pcm.filterIndexed { i, _ -> i % 2 == 0 }.sumOf { abs(it.toLong()) }
        val right = pcm.filterIndexed { i, _ -> i % 2 == 1 }.sumOf { abs(it.toLong()) }
        assertTrue(left > 2 * right)
        assertEquals(pcm.size % 2, 0)
    }
}
