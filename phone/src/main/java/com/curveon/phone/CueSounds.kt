package com.curveon.phone

import com.curveon.protocol.Message.CueSide
import com.curveon.protocol.Message.NavCue
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The navigation sounds, made here rather than shipped as files: short, soft tones (a sine with a
 * touch of its octave, a quick fade in and a gentle ring out), panned towards the ear the rider
 * turns to. Each starts with a little silence so a headset that has gone quiet wakes up in time.
 */
object CueSounds {
    const val RATE = 44_100

    /** Silence first: intercoms often need a moment to open the audio after a quiet spell. */
    const val LEAD_IN_S = 0.3

    private class Note(val hz: Double, val at: Double, val length: Double, val gain: Double = 1.0, val bright: Double = 0.25)

    private fun notes(cue: NavCue): List<Note> = when (cue) {
        // Two soft rising notes: a turn is coming.
        NavCue.PREPARE -> listOf(Note(660.0, 0.0, 0.16), Note(880.0, 0.14, 0.24))
        // A small tick: turn here.
        NavCue.TURN -> listOf(Note(1320.0, 0.0, 0.09, gain = 0.7, bright = 0.1))
        // Three notes up: there.
        NavCue.ARRIVE -> listOf(Note(523.0, 0.0, 0.18), Note(659.0, 0.15, 0.18), Note(784.0, 0.30, 0.36))
        // Low and muted, twice: off the route.
        NavCue.OFF_ROUTE -> listOf(Note(330.0, 0.0, 0.16, bright = 0.0), Note(294.0, 0.2, 0.22, bright = 0.0))
        // Light and quick, up: back on track (or a new route).
        NavCue.BACK_ON_ROUTE -> listOf(Note(784.0, 0.0, 0.12, gain = 0.8), Note(1047.0, 0.1, 0.2, gain = 0.8))
        // Up a fourth and held, like a question: choose on the screen.
        NavCue.CHOOSE -> listOf(Note(440.0, 0.0, 0.16), Note(587.0, 0.15, 0.4))
    }

    /** 16-bit stereo PCM (left, right interleaved) at [RATE]. */
    fun pcm(cue: NavCue, side: CueSide, volume: Double = 0.3): ShortArray {
        val ns = notes(cue)
        val length = ns.maxOf { it.at + it.length } + LEAD_IN_S
        val frames = (length * RATE).roundToInt()
        val mono = DoubleArray(frames)
        val lead = (LEAD_IN_S * RATE).roundToInt()
        for (n in ns) {
            val start = lead + (n.at * RATE).roundToInt()
            val count = min((n.length * RATE).roundToInt(), frames - start)
            for (i in 0 until count) {
                val t = i.toDouble() / RATE
                val attack = min(1.0, t / ATTACK_S)
                // Ring out, and reach silence at the end so nothing clicks.
                val release = exp(-t * 4.0 / n.length) * (1.0 - t / n.length)
                val w = 2 * PI * n.hz * t
                mono[start + i] += n.gain * attack * release * ((1 - n.bright) * sin(w) + n.bright * sin(2 * w))
            }
        }
        // Equal-power pan; the far ear still hears it, softly.
        val (l, r) = when (side) {
            CueSide.LEFT -> 1.0 to FAR_EAR
            CueSide.RIGHT -> FAR_EAR to 1.0
            CueSide.CENTER -> sqrt(0.5) to sqrt(0.5)
        }
        val out = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val v = (mono[i] * volume).coerceIn(-1.0, 1.0) * Short.MAX_VALUE
            out[2 * i] = (v * l).roundToInt().toShort()
            out[2 * i + 1] = (v * r).roundToInt().toShort()
        }
        return out
    }

    private const val ATTACK_S = 0.01
    private const val FAR_EAR = 0.3
}
