package com.axiom.launcher.platform

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Speech in and out for voice mode and spoken replies: one recognizer (on the phone when it can, see
 * [Recognizers]) and one text-to-speech engine. All calls and callbacks are on the main thread.
 */
class Voice(private val context: Context) {
    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        /** Nothing was said (or nothing recognized) before the recognizer gave up. */
        fun onSilence()
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var queued: Pair<String, String>? = null
    private val done = HashMap<String, () -> Unit>()
    private var seq = 0
    /** Bumped on every listen/stop, so callbacks from a cancelled session are ignored. */
    private var session = 0

    /** Starts listening; false when the phone has no usable recognizer. */
    fun listen(l: Listener): Boolean {
        val r = recognizer ?: Recognizers.create(context)?.also { recognizer = it } ?: return false
        val mine = ++session
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                if (mine != session) return
                first(partialResults)?.let { l.onPartial(it) }
            }
            override fun onResults(results: Bundle?) {
                if (mine != session) return
                session++
                val text = first(results)
                if (text.isNullOrBlank()) l.onSilence() else l.onFinal(text)
            }
            override fun onError(error: Int) {
                if (mine != session) return
                session++
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> l.onSilence()
                    // A busy or reset recognizer: start over with a fresh one.
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> {
                        recognizer?.destroy(); recognizer = null
                        val next = session
                        main.postDelayed({ if (next == session && !listen(l)) l.onError("Speech recognition stopped working") }, 400)
                    }
                    else -> l.onError(message(error))
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        return runCatching { r.startListening(intent) }.isSuccess
    }

    fun stopListening() {
        session++
        runCatching { recognizer?.cancel() }
    }

    /** Reads [text] aloud, then calls [onDone] (also when speaking fails or is stopped). */
    fun speak(text: String, onDone: () -> Unit = {}) {
        val id = "axiom-${++seq}"
        done[id] = onDone
        val t = tts
        if (t == null) {
            queued = id to text
            tts = TextToSpeech(context) { status ->
                main.post {
                    ttsReady = status == TextToSpeech.SUCCESS
                    val q = queued.also { queued = null } ?: return@post
                    if (ttsReady) { tts?.language = Locale.getDefault(); say(q.first, q.second) } else finish(q.first)
                }
            }.also {
                it.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { utteranceId?.let { u -> main.post { finish(u) } } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { utteranceId?.let { u -> main.post { finish(u) } } }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceId?.let { u -> main.post { finish(u) } } }
                })
            }
        } else if (!ttsReady) {
            queued?.let { finish(it.first) }
            queued = id to text
        } else say(id, text)
    }

    private fun say(id: String, text: String) {
        val r = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) ?: TextToSpeech.ERROR
        if (r != TextToSpeech.SUCCESS) finish(id)
    }

    private fun finish(id: String) { done.remove(id)?.invoke() }

    /** Stops speech without running the pending "done" callbacks (the caller is changing course). */
    fun stopSpeaking() {
        done.clear()
        queued = null
        runCatching { tts?.stop() }
    }

    fun release() {
        stopSpeaking()
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { tts?.shutdown() }
        tts = null
    }

    private fun first(b: Bundle?): String? = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

    private fun message(error: Int) = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow the microphone for voice mode"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            "Speech recognition needs a connection right now"
        SpeechRecognizer.ERROR_AUDIO -> "The microphone isn't available"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Speech recognition doesn't support this language yet"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Speech recognition is busy; try again in a moment"
        else -> "Speech recognition failed ($error)"
    }

    companion object {
        /** Shorter text for reading aloud: no markdown, and long answers end with a pointer to the screen. */
        fun spoken(text: String, max: Int = 420): String {
            val clean = text
                .replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " (code on screen) ")
                .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
                .replace(Regex("https?://\\S+"), "a link")
                .replace(Regex("[*`#_>|]"), "")
                .replace(Regex("\\s+"), " ").trim()
            if (clean.length <= max) return clean
            val cut = clean.take(max)
            val end = maxOf(cut.lastIndexOf(". "), cut.lastIndexOf("! "), cut.lastIndexOf("? "))
            return (if (end > max / 3) cut.take(end + 1) else cut.substringBeforeLast(' ') + "…") + " The rest is on screen."
        }
    }
}
