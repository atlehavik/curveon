package com.curveon.phone

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.curveon.protocol.Message.VoiceAvailability
import java.util.Locale

/**
 * Spoken navigation through the phone's own text-to-speech engine (Settings → Text-to-speech on
 * the phone; free, offline once its voice is downloaded). Played as navigation guidance like the
 * [CuePlayer] sounds: music dips, and nothing is said while a call has the audio. When the engine
 * has no voice for the language, [speak]'s fallback runs instead (the sound for the same thing).
 */
class Speaker(
    context: Context,
    /** After each thing to say: how the language could be spoken, so the rider can be told to download it. */
    private val onVoice: (language: String, VoiceAvailability) -> Unit = { _, _ -> }
) {

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
            override fun onError(id: String) = failed(id)
            override fun onError(id: String, errorCode: Int) = failed(id)
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
        val availability = if (ready) useLanguage(languageTag) else VoiceAvailability.MISSING
        onVoice(languageTag, availability)
        // Only an online voice and no connection: it would fail, so the sound instead.
        if (availability == VoiceAvailability.MISSING || (availability == VoiceAvailability.ONLINE_ONLY && !online())) return fallback()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        val id = "u${++ids}"
        lastId = id
        fallbacks[id] = fallback
        // A moment of silence first, for a headset that has gone quiet.
        tts.playSilentUtterance(LEAD_IN_MS, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, "s$ids")
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    fun shutdown() = main.post {
        if (engine.isInitialized()) { tts.stop(); tts.shutdown() }
        audio.abandonAudioFocusRequest(focus)
    }

    /** Couldn't be said after all (e.g. the online voice lost its connection): the sound instead. */
    private fun failed(id: String) {
        fallbacks.remove(id)?.let { main.post(it) }
        finished(id)
    }

    private fun finished(id: String) {
        fallbacks.remove(id)
        if (id != lastId) return
        // Let the last of it reach the headset before the music comes back up.
        main.postDelayed({ if (lastId == id) { lastId = null; audio.abandonAudioFocusRequest(focus) } }, TAIL_MS)
    }

    private val fallbacks = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    /** The language set up with a voice on the phone; others are looked at again each time (it may have been downloaded since). */
    private var language: String? = null

    private fun useLanguage(tag: String): VoiceAvailability {
        if (tag == language) return VoiceAvailability.OFFLINE
        val ok = candidates(tag).any { tts.setLanguage(it) >= TextToSpeech.LANG_AVAILABLE }
        if (!ok) { Log.w(TAG, "No voice for $tag"); return VoiceAvailability.MISSING }
        offlineVoice(tts.voice?.locale ?: Locale.forLanguageTag(tag))?.let { tts.voice = it }
        Log.i(TAG, "Speaking $tag with ${tts.voice?.name}")
        if (tts.voice?.isNetworkConnectionRequired == true) return VoiceAvailability.ONLINE_ONLY
        language = tag
        return VoiceAvailability.OFFLINE
    }

    private fun online(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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
