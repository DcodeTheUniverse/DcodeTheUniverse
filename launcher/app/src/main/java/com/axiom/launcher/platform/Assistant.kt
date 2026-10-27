package com.axiom.launcher.platform

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.View
import androidx.core.graphics.scale
import com.axiom.launcher.MainActivity
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** A view on the screen, reduced to what [ScreenText] needs (lets tests build screens without the framework). */
interface ScreenNode {
    val text: CharSequence?
    val description: CharSequence?
    val inputType: Int
    val autofillHints: Array<String>?
    val visible: Boolean
    val children: List<ScreenNode>
}

/** The readable text of a screen, top to bottom, without passwords, card numbers or one-time codes. */
object ScreenText {
    private val SECRET_HINTS = listOf("password", "creditcard", "cardnumber", "securitycode", "cvc", "cvv", "otp", "onetimecode", "pin", "smsotp")

    fun collect(roots: List<ScreenNode>, maxChars: Int = 12_000): String {
        val out = LinkedHashSet<String>()
        var total = 0
        fun walk(n: ScreenNode) {
            if (!n.visible || total >= maxChars) return
            if (!secret(n)) {
                val line = (n.text?.toString()?.takeIf { it.isNotBlank() } ?: n.description?.toString()?.takeIf { it.isNotBlank() })
                    ?.replace(Regex("\\s+"), " ")?.trim()
                if (line != null && line.length > 1 && out.add(line)) total += line.length + 1
            }
            n.children.forEach { walk(it) }
        }
        roots.forEach { walk(it) }
        return out.joinToString("\n").take(maxChars)
    }

    fun secret(n: ScreenNode): Boolean {
        val cls = n.inputType and InputType.TYPE_MASK_CLASS
        val variation = n.inputType and InputType.TYPE_MASK_VARIATION
        if (cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) return true
        if (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return true
        return n.autofillHints.orEmpty().any { h -> val k = h.lowercase().replace(Regex("[^a-z]"), ""); SECRET_HINTS.any { k.contains(it) } }
    }
}

private class ViewNodeAdapter(private val n: AssistStructure.ViewNode) : ScreenNode {
    override val text get() = n.text
    override val description get() = n.contentDescription
    override val inputType get() = n.inputType
    override val autofillHints: Array<String>? get() = n.autofillHints
    override val visible get() = n.visibility == View.VISIBLE
    override val children get() = (0 until n.childCount).map { ViewNodeAdapter(n.getChildAt(it)) }
}

/** Makes Axiom selectable as the phone's digital assistant (Settings › Apps › Default apps). */
class AxiomAssistService : VoiceInteractionService()

class AxiomAssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AxiomAssistSession(this)
}

/**
 * Runs when the user holds Home (or uses the assistant gesture): takes what Android hands over — the
 * screen's text and, if allowed, a screenshot — and opens Solis with it attached. Nothing is sent anywhere
 * until the user asks something. Apps that block capture (banking, FLAG_SECURE) hand over nothing.
 */
class AxiomAssistSession(context: Context) : VoiceInteractionSession(context) {
    private val main = Handler(Looper.getMainLooper())
    private val texts = ArrayList<String>()
    private var shot: Bitmap? = null
    private var wantStructure = false
    private var wantShot = false
    private var gotStructure = false
    private var gotShot = false
    private var handedOff = false
    private val timeout = Runnable { handOff() }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        wantStructure = showFlags and SHOW_WITH_ASSIST != 0
        wantShot = showFlags and SHOW_WITH_SCREENSHOT != 0
        if (!wantStructure && !wantShot) { handOff(); return }
        main.postDelayed(timeout, 2_000)
        // The data may already have arrived.
        if (gotStructure || gotShot) maybeDone()
    }

    override fun onHandleAssist(state: AssistState) {
        runCatching {
            state.assistStructure?.let { s -> texts += ScreenText.collect((0 until s.windowNodeCount).map { ViewNodeAdapter(s.getWindowNodeAt(it).rootViewNode) }) }
            state.assistContent?.let { c -> pageOf(c)?.let { texts += "Page: $it" } }
        }
        if (state.index >= state.count - 1) { gotStructure = true; maybeDone() }
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        shot = screenshot
        gotShot = true
        maybeDone()
    }

    private fun pageOf(c: AssistContent): String? = c.webUri?.toString()?.takeIf { it.startsWith("http") }

    private fun maybeDone() {
        if (!wantStructure && !wantShot) return // not shown yet; onShow decides
        if ((!wantStructure || gotStructure) && (!wantShot || gotShot)) handOff()
    }

    private fun handOff() {
        if (handedOff) return
        handedOff = true
        main.removeCallbacks(timeout)
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val image = shot?.let { b ->
            runCatching {
                val s = 1568f / maxOf(b.width, b.height)
                val scaled = if (s < 1f) b.scale((b.width * s).toInt(), (b.height * s).toInt()) else b
                File(dir, "screen-$stamp.jpg").also { f -> f.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
            }.getOrNull()
        }
        val text = texts.joinToString("\n").trim().takeIf { it.isNotEmpty() }?.let { t ->
            runCatching { File(dir, "screen-$stamp.txt").also { it.writeText(t) } }.getOrNull()
        }
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_SOLIS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        image?.let { intent.putExtra(MainActivity.EXTRA_SCREEN_IMAGE, it.path) }
        text?.let { intent.putExtra(MainActivity.EXTRA_SCREEN_TEXT, it.path) }
        runCatching { startAssistantActivity(intent) }.onFailure { runCatching { context.startActivity(intent) } }
        finish()
    }

    override fun onHide() {
        main.removeCallbacks(timeout)
        super.onHide()
    }
}

/**
 * While Axiom is the assistant, Android also routes other apps' speech recognition to it. This hands each
 * request to the phone's on-device recognizer, or to another installed one, and passes the results back; a
 * recognizer that turns the language down before anything was heard hands over to the next one.
 * Android checks that the calling app may use the microphone before any of this runs.
 */
class AxiomRecognitionService : RecognitionService() {
    private val main = Handler(Looper.getMainLooper())
    private var delegate: SpeechRecognizer? = null
    /** Bumped on every start and cancel, so a pending hand-over for an older request is dropped. */
    private var request = 0

    override fun onStartListening(intent: Intent, callback: Callback) {
        // Keep the caller's language, minus extensions recognizers turn down.
        val language = intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)?.let(Recognizers::cleanTag)
        val asked = Intent(intent).apply { if (language != null) putExtra(RecognizerIntent.EXTRA_LANGUAGE, language) }
        start(asked, callback, Recognizers.plan(this, language), ++request)
    }

    private fun start(intent: Intent, callback: Callback, plan: List<Recognizers.Attempt>, mine: Int) {
        delegate?.destroy()
        delegate = null
        val attempt = plan.firstOrNull()
        val d = attempt?.let { Recognizers.create(this, it) }
        if (d == null) {
            if (plan.size > 1) start(intent, callback, plan.drop(1), mine) else runCatching { callback.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        delegate = d
        d.setRecognitionListener(Forward(callback) { error ->
            val language = Recognizers.isLanguageError(error)
            if (language) Recognizers.refused(attempt, error, d, intent)
            val handOver = language && plan.size > 1
            if (handOver) main.post { if (mine == request && delegate === d) start(intent, callback, plan.drop(1), mine) }
            handOver
        })
        d.startListening(intent)
    }

    override fun onStopListening(callback: Callback) { delegate?.stopListening() }
    override fun onCancel(callback: Callback) { request++; delegate?.cancel() }

    override fun onDestroy() {
        request++
        delegate?.destroy()
        delegate = null
        super.onDestroy()
    }

    /** Passes events back to the caller; [handOver] may take an error raised before anything was heard. */
    private class Forward(private val cb: Callback, private val handOver: (Int) -> Boolean) : RecognitionListener {
        private var heard = false
        private fun safe(block: () -> Unit) { runCatching(block) }
        override fun onReadyForSpeech(params: Bundle?) = safe { cb.readyForSpeech(params ?: Bundle()) }
        override fun onBeginningOfSpeech() { heard = true; safe { cb.beginningOfSpeech() } }
        override fun onRmsChanged(rmsdB: Float) = safe { cb.rmsChanged(rmsdB) }
        override fun onBufferReceived(buffer: ByteArray?) = safe { buffer?.let { cb.bufferReceived(it) } }
        override fun onEndOfSpeech() = safe { cb.endOfSpeech() }
        override fun onError(error: Int) { if (heard || !handOver(error)) safe { cb.error(error) } }
        override fun onResults(results: Bundle?) = safe { cb.results(results ?: Bundle()) }
        override fun onPartialResults(partialResults: Bundle?) { heard = true; safe { cb.partialResults(partialResults ?: Bundle()) } }
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onSegmentResults(segmentResults: Bundle) { if (Build.VERSION.SDK_INT >= 33) safe { cb.segmentResults(segmentResults) } }
        override fun onEndOfSegmentedSession() { if (Build.VERSION.SDK_INT >= 33) safe { cb.endOfSegmentedSession() } }
    }
}

/**
 * Picks speech recognizers that aren't Axiom's own pass-through. The phone's on-device recognizer comes first
 * (audio stays on the phone), but it only knows the languages whose offline pack is installed and answers
 * anything else with a language error, so callers walk a [plan] and move on when one turns the language down.
 */
object Recognizers {
    private val PREFERRED = listOf("com.google.android.as", "com.google.android.tts", "com.google.android.googlequicksearchbox")

    /** One way to listen: a recognition service ([service] null: the phone's on-device recognizer) and the language to ask for. */
    data class Attempt(val service: ComponentName?, val language: String?) {
        val onDevice get() = service == null
        internal val key get() = "${service?.flattenToShortString() ?: "on-device"}|${language.orEmpty()}"
    }

    /**
     * When each attempt turned its language down. Plans skip it for [RETRY_MS], then try it again, so the on-device
     * recognizer gets its turn back once its pack has downloaded.
     */
    private val refused = ConcurrentHashMap<String, Long>()
    /** Languages the on-device recognizer was already asked to download in this run. */
    private val fetching = ConcurrentHashMap.newKeySet<String>()
    private const val RETRY_MS = 30 * 60_000L
    internal var clock: () -> Long = System::currentTimeMillis

    /** Installed recognition services other than Axiom's, Google's first. */
    fun others(context: Context): List<ComponentName> = runCatching {
        context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName != context.packageName }
            .sortedBy { c -> PREFERRED.indexOf(c.packageName).let { if (it < 0) PREFERRED.size else it } }
    }.getOrDefault(emptyList())

    fun other(context: Context): ComponentName? = others(context).firstOrNull()

    fun onDeviceAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    /**
     * The recognizers to try for [language], best first: on-device, then the installed ones, minus any that turned
     * this language down lately. With [lastResort], ends with the best installed recognizer asked for no language
     * at all, so it uses its own setting. If every one was turned down lately, tries them all again.
     */
    fun plan(context: Context, language: String?, lastResort: Boolean = false): List<Attempt> {
        val services = buildList { if (onDeviceAvailable(context)) add(null); addAll(others(context)) }
        val all = services.map { Attempt(it, language) }.toMutableList()
        if (lastResort && language != null && services.isNotEmpty()) all += Attempt(services.firstOrNull { it != null } ?: services.first(), null)
        val now = clock()
        return all.filter { a -> refused[a.key]?.let { now - it >= RETRY_MS } ?: true }.ifEmpty { all }
    }

    fun create(context: Context, attempt: Attempt): SpeechRecognizer? = runCatching {
        when {
            attempt.service != null -> SpeechRecognizer.createSpeechRecognizer(context, attempt.service)
            Build.VERSION.SDK_INT >= 31 -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else -> null
        }
    }.getOrNull()

    fun isLanguageError(error: Int) =
        error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE

    /**
     * Remembers that [attempt] turned its language down. When the on-device recognizer knows the language but lacks
     * its pack, asks it to fetch the pack, once per run: Android 14 and later download it quietly (or when on Wi-Fi),
     * so later requests stay on the phone. Android 13 would show a dialog instead, so it isn't asked there.
     */
    fun refused(attempt: Attempt, error: Int, recognizer: SpeechRecognizer, intent: Intent) {
        refused[attempt.key] = clock()
        if (attempt.onDevice && error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE && Build.VERSION.SDK_INT >= 34 && fetching.add(attempt.language.orEmpty()))
            runCatching { recognizer.triggerModelDownload(intent) }
    }

    /** For tests: forget refusals. */
    internal fun reset() { refused.clear(); fetching.clear(); clock = System::currentTimeMillis }

    /**
     * The phone's language as a plain tag such as "en-GB". Regional preferences (Android 14) put extensions in the
     * default locale ("en-GB-u-mu-celsius"), and recognizers turn those tags down as an unsupported language.
     */
    fun languageTag(locale: Locale = Locale.getDefault()): String = runCatching {
        Locale.Builder().setLanguage(locale.language).setScript(locale.script).setRegion(locale.country).build().toLanguageTag()
    }.getOrNull()?.takeIf { locale.language.isNotEmpty() } ?: "en-US"

    /** [tag] without extensions or variants; tags Java can't parse (like "en_US") pass through untouched. */
    fun cleanTag(tag: String): String = Locale.forLanguageTag(tag).let { if (it.language.isEmpty()) tag else languageTag(it) }
}
