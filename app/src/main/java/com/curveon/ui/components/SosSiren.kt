package com.curveon.ui.components

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * The sound of an incoming SOS: three short, three long, three short beeps (Morse S-O-S) on a
 * piercing tone, over and over. Made here instead of using the phone's alarm ringtone, which is
 * often a calm morning melody. Plays on the alarm volume.
 */
internal class SosSiren {
    private var track: AudioTrack? = null

    fun start() {
        stop()
        val pcm = pcm()
        track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build().apply {
                    write(pcm, 0, pcm.size)
                    setLoopPoints(0, pcm.size, -1)
                    play()
                }
        }.getOrNull()
    }

    fun stop() {
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
    }

    private companion object {
        const val RATE = 22_050
        const val UNIT_MS = 170
        const val FADE = 110 // samples, so each beep starts and ends without a click

        /** One round of S-O-S followed by a pause. */
        fun pcm(): ShortArray {
            val out = ArrayList<Short>()
            fun silence(units: Double) = repeat((units * UNIT_MS * RATE / 1000).toInt()) { out.add(0) }
            fun beep(units: Int) {
                val n = units * UNIT_MS * RATE / 1000
                for (i in 0 until n) {
                    val t = i.toDouble() / RATE
                    // A tone with its octave on top, to cut through engine and wind noise.
                    val s = sin(2 * PI * 1000 * t) * 0.6 + sin(2 * PI * 2000 * t) * 0.3
                    val env = minOf(1.0, i.toDouble() / FADE, (n - i).toDouble() / FADE)
                    out.add((s * env * Short.MAX_VALUE * 0.95).toInt().toShort())
                }
            }
            for (letter in listOf(1, 3, 1)) {
                repeat(3) { beep(letter); silence(1.0) }
                silence(2.0)
            }
            silence(4.0)
            return out.toShortArray()
        }
    }
}
