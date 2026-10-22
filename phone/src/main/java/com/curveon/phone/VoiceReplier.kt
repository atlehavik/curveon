package com.curveon.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.curveon.protocol.PhoneErrorCode
import com.curveon.protocol.Message
import com.curveon.protocol.Message.VoiceState

/**
 * Listens for a spoken reply on the phone (the rider talks through the intercom headset, which
 * is the phone's Bluetooth mic) and reports the transcript to the tablet. Nothing is sent until
 * the rider confirms on the tablet.
 */
class VoiceReplier(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var currentKey: String? = null

    fun start(key: String) = main.post {
        cancelInternal()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            PhoneHub.broadcast(Message.VoiceReplyState(key, VoiceState.ERROR, "Speech recognition not available on the phone", PhoneErrorCode.NO_RECOGNIZER))
            return@post
        }
        currentKey = key
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(Listener(key))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Give the rider time to think in a noisy helmet.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
        r.startListening(intent)
        PhoneHub.broadcast(Message.VoiceReplyState(key, VoiceState.LISTENING))
    }

    fun cancel() = main.post { cancelInternal() }

    private fun cancelInternal() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        currentKey = null
    }

    private inner class Listener(private val key: String) : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) {
            best(partialResults)?.let { PhoneHub.broadcast(Message.VoiceReplyState(key, VoiceState.PARTIAL, it)) }
        }

        override fun onResults(results: Bundle?) {
            val text = best(results)
            PhoneHub.broadcast(
                if (text.isNullOrBlank()) Message.VoiceReplyState(key, VoiceState.ERROR, "Didn't catch that", PhoneErrorCode.NOT_CAUGHT)
                else Message.VoiceReplyState(key, VoiceState.RESULT, text)
            )
            main.post { cancelInternal() }
        }

        override fun onError(error: Int) {
            val (reason, code) = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that" to PhoneErrorCode.NOT_CAUGHT
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission missing on the phone" to PhoneErrorCode.MIC_NOT_ALLOWED
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a connection" to PhoneErrorCode.NEEDS_CONNECTION
                else -> "Speech recognition error ($error)" to PhoneErrorCode.RECOGNIZER_ERROR
            }
            PhoneHub.broadcast(Message.VoiceReplyState(key, VoiceState.ERROR, reason, code))
            main.post { cancelInternal() }
        }

        private fun best(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
