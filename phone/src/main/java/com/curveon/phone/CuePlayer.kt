package com.curveon.phone

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.curveon.protocol.Message.CueSide
import com.curveon.protocol.Message.NavCue
import java.util.concurrent.Executors

/**
 * Plays [CueSounds] as navigation guidance: to the rider's headset when one is connected, music
 * turned down for the moment rather than paused. Nothing plays while a call has the audio (we
 * don't get the focus then). One sound at a time, in order.
 */
class CuePlayer(context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "CuePlayer") }
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun play(cue: NavCue, side: CueSide = CueSide.CENTER) {
        worker.execute { runCatching { playNow(cue, side) }.onFailure { Log.w(TAG, "Cue $cue failed", it) } }
    }

    fun shutdown() = worker.shutdown()

    private fun playNow(cue: NavCue, side: CueSide) {
        val pcm = CueSounds.pcm(cue, side)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(false)
            .build()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(CueSounds.RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build())
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(pcm.size / 2 * 1000L / CueSounds.RATE + TAIL_MS)
        } finally {
            track.release()
            audio.abandonAudioFocusRequest(focus)
        }
    }

    private companion object {
        const val TAG = "CuePlayer"
        /** Let the last of it reach the headset (Bluetooth lags) before the music comes back up. */
        const val TAIL_MS = 250L
    }
}
