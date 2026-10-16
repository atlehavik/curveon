package com.curveon.phone

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * Spoken navigation through the phone's own text-to-speech engine (Settings → Text-to-speech on
 * the phone; free, offline once its voice is downloaded). Played as navigation guidance like the
 * [CuePlayer] sounds: music dips, and nothing is said while a call has the audio. When the engine
 * has no voice for the language, [speak]'s fallback runs instead (the sound for the same thing).
 */
class Speaker(context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setWillPauseWhenDucked(false)
        .build()

    private var initialized = false
    private var ready = false
    /** Said once the engine has started (it takes a moment the first time). */
    private val waiting = mutableListOf<() -> Unit>()
    private var ids = 0
    /** The utterance whose end gives the audio focus back. */
    @Volatile private var lastId: String? = null

    private val appContext = context.applicationContext
    /** Started on the first thing to say, so riding with sounds only never wakes the engine. */
    private val engine = lazy { createEngine() }
    private val tts: TextToSpeech get() = engine.value

    private fun createEngine() = TextToSpeech(appContext) { status ->
        main.post {
            initialized = true
            ready = status == TextToSpeech.SUCCESS
            if (!ready) Log.w(TAG, "No text-to-speech engine ($status)")
            waiting.forEach { it() }
            waiting.clear()
        }
    }.apply {
        setAudioAttributes(attributes)
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) = Unit
            override fun onDone(id: String) = finished(id)
            override fun onStop(id: String, interrupted: Boolean) = finished(id)
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) = finished(id)
            override fun onError(id: String, errorCode: Int) = finished(id)
        })
    }

    /**
     * Say [text] in [languageTag] (e.g. "nb-NO"). [interrupt] cuts off whatever is being said (the
     * turn is here: the heads-up no longer matters). [fallback] runs when it can't be said.
     */
    fun speak(text: String, languageTag: String, interrupt: Boolean, fallback: () -> Unit) {
        main.post { say(text, languageTag, interrupt, fallback) }
    }

    private fun say(text: String, languageTag: String, interrupt: Boolean, fallback: () -> Unit) {
        tts // starts the engine the first time
        if (!initialized) {
            if (waiting.size < 4) waiting += { say(text, languageTag, interrupt, fallback) }
            return
        }
        if (!ready || !useLanguage(languageTag)) return fallback()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        val id = "u${++ids}"
        lastId = id
        // A moment of silence first, for a headset that has gone quiet.
        tts.playSilentUtterance(LEAD_IN_MS, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, "s$ids")
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    fun shutdown() = main.post {
        if (engine.isInitialized()) { tts.stop(); tts.shutdown() }
        audio.abandonAudioFocusRequest(focus)
    }

    private fun finished(id: String) {
        if (id != lastId) return
        // Let the last of it reach the headset before the music comes back up.
        main.postDelayed({ if (lastId == id) { lastId = null; audio.abandonAudioFocusRequest(focus) } }, TAIL_MS)
    }

    private var language: String? = null

    private fun useLanguage(tag: String): Boolean {
        if (tag == language) return true
        val ok = candidates(tag).any { tts.setLanguage(it) >= TextToSpeech.LANG_AVAILABLE }
        if (!ok) { Log.w(TAG, "No voice for $tag"); return false }
        language = tag
        offlineVoice(tts.voice?.locale ?: Locale.forLanguageTag(tag))?.let { tts.voice = it }
        Log.i(TAG, "Speaking $tag with ${tts.voice?.name}")
        return true
    }

    /**
     * A voice on the phone itself for [locale], so it keeps talking without coverage (engines pick
     * an online voice when they can). Null when none is downloaded: then the engine's own choice.
     */
    private fun offlineVoice(locale: Locale): Voice? = runCatching { tts.voices }.getOrNull().orEmpty()
        .filter { it.locale.language == locale.language && !it.isNetworkConnectionRequired &&
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
        .sortedWith(compareByDescending<Voice> { it.locale.country == locale.country }.thenByDescending { it.quality })
        .firstOrNull()

    private companion object {
        const val TAG = "Speaker"
        const val LEAD_IN_MS = 300L
        const val TAIL_MS = 250L

        /** Norwegian voices go by nb, no or nn depending on the engine. */
        fun candidates(tag: String): List<Locale> {
            val l = Locale.forLanguageTag(tag)
            return if (l.language == "nb" || l.language == "no") listOf(Locale.forLanguageTag("nb-NO"), Locale.forLanguageTag("no-NO"), Locale("nb"), Locale("no"))
            else listOf(l, Locale(l.language))
        }
    }
}
