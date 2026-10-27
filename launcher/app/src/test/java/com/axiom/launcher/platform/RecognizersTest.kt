package com.axiom.launcher.platform

import android.content.AttributionSource
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.lang.reflect.Proxy
import java.util.Locale

/**
 * Voice mode on a phone whose on-device recognizer lacks the language pack (seen on a Galaxy S24+ speaking
 * English): the recognizer answers with a language error, and Axiom has to hand over instead of giving up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecognizersTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()
    private val google = ComponentName("com.google.android.tts", "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService")
    private val bixby = ComponentName("com.samsung.android.bixby.agent", "com.samsung.android.bixby.agent.asr.RecognitionService")
    private val savedLocale = Locale.getDefault()

    @Before fun setUp() {
        Recognizers.reset()
        ShadowSpeechRecognizer.reset()
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        for (c in listOf(bixby, google)) {
            shadowOf(app.packageManager).addServiceIfNotPresent(c)
            shadowOf(app.packageManager).addIntentFilterForService(c, IntentFilter(RecognitionService.SERVICE_INTERFACE))
        }
        // Android 14 regional preferences end up in the default locale as extensions.
        Locale.setDefault(Locale.forLanguageTag("en-GB-u-mu-celsius"))
    }

    @After fun tearDown() {
        Locale.setDefault(savedLocale)
        Recognizers.reset()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun latest(): SpeechRecognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
    private fun asked(r: SpeechRecognizer): String? = shadowOf(r).lastRecognizerIntent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
    private fun results(text: String) = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) }

    private class Events : Voice.Listener {
        val list = mutableListOf<String>()
        override fun onPartial(text: String) { list += "partial:$text" }
        override fun onFinal(text: String) { list += "final:$text" }
        override fun onSilence() { list += "silence" }
        override fun onError(message: String) { list += "error:$message" }
    }

    @Test
    fun languageTagsLeaveOutRegionalPreferences() {
        assertEquals("en-GB", Recognizers.languageTag())
        assertEquals("en-US", Recognizers.languageTag(Locale.forLanguageTag("en-US-u-fw-mon-mu-fahrenhe")))
        assertEquals("zh-Hant-TW", Recognizers.languageTag(Locale.forLanguageTag("zh-Hant-TW")))
        assertEquals("es-419", Recognizers.languageTag(Locale.forLanguageTag("es-419")))
        assertEquals("de-DE", Recognizers.cleanTag("de-DE-u-fw-mon"))
        assertEquals("tags Java can't read pass through", "en_US", Recognizers.cleanTag("en_US"))
    }

    @Test
    fun planTriesOnDeviceThenGoogleThenTheRestAndRemembersRefusals() {
        val plan = Recognizers.plan(app, "en-GB", lastResort = true)
        assertEquals(
            listOf(Recognizers.Attempt(null, "en-GB"), Recognizers.Attempt(google, "en-GB"), Recognizers.Attempt(bixby, "en-GB"), Recognizers.Attempt(google, null)),
            plan,
        )
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
        Recognizers.refused(plan[0], SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, r, Intent())
        assertEquals("skipped once it turned the language down", google, Recognizers.plan(app, "en-GB").first().service)
        assertNull("other languages still go on-device", Recognizers.plan(app, "fr-FR").first().service)
        val now = System.currentTimeMillis()
        Recognizers.clock = { now + 31 * 60_000L }
        assertNull("tried again later, when the pack may be there", Recognizers.plan(app, "en-GB").first().service)

        // Everything turned the language down lately: try them all again rather than claim there's no recognizer.
        Recognizers.reset()
        for (a in Recognizers.plan(app, "en-GB")) Recognizers.refused(a, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, r, Intent())
        assertEquals(3, Recognizers.plan(app, "en-GB").size)
    }

    @Test
    fun voiceModeHandsOverWhenTheOnDeviceRecognizerLacksTheLanguage() {
        val events = Events()
        val voice = Voice(app)
        assertTrue(voice.listen(events))
        idle()
        val onDevice = latest()
        assertEquals("a plain tag, without the regional preferences", "en-GB", asked(onDevice))

        shadowOf(onDevice).triggerOnError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        idle()
        assertTrue("no error reaches the user: ${events.list}", events.list.isEmpty())
        assertEquals("asks the on-device recognizer to fetch the pack", "en-GB", shadowOf(onDevice).latestModelDownloadIntent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue(shadowOf(onDevice).isDestroyed)
        val next = latest()
        assertNotSame(onDevice, next)
        assertEquals("en-GB", asked(next))
        assertFalse("may use the network now", shadowOf(next).lastRecognizerIntent!!.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))

        shadowOf(next).triggerOnResults(results("what's on today"))
        assertEquals(listOf("final:what's on today"), events.list)

        // The next turn starts with the recognizer that worked, without a failed try first.
        assertTrue(voice.listen(events))
        idle()
        assertSame(next, latest())
        assertEquals("en-GB", asked(next))
        voice.release()
    }

    @Test
    fun whenNoRecognizerTakesTheLanguageTheErrorNamesIt() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        val events = Events()
        val voice = Voice(app)
        assertTrue(voice.listen(events))
        val tried = mutableListOf<String?>()
        repeat(3) {
            idle()
            tried += asked(latest())
            shadowOf(latest()).triggerOnError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
        }
        idle()
        assertEquals("Google, Samsung, then Google with its own language setting", listOf("en-GB", "en-GB", null), tried)
        assertEquals(listOf("error:No speech recognizer on this phone supports English (United Kingdom)"), events.list)
        voice.release()
    }

    @Test
    fun otherErrorsDoNotHandOver() {
        val events = Events()
        val voice = Voice(app)
        voice.listen(events)
        idle()
        val onDevice = latest()
        shadowOf(onDevice).triggerOnError(SpeechRecognizer.ERROR_AUDIO)
        idle()
        assertSame(onDevice, latest())
        assertEquals(listOf("error:The microphone isn't available"), events.list)
        voice.release()
    }

    @Test
    fun assistantPassThroughHandsOverToo() {
        // Another app's voice input, routed to Axiom because it's the assistant.
        val heard = mutableListOf<String>()
        val type = Class.forName("android.speech.IRecognitionListener")
        val caller = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, m, args ->
            when (m.name) {
                "asBinder" -> Binder()
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.get(0)
                "toString" -> "caller"
                "onError" -> { heard += "error:${args[0]}"; null }
                "onResults" -> { heard += "results:${(args[0] as Bundle).getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)}"; null }
                else -> { heard += m.name; null }
            }
        }
        val service = Robolectric.setupService(AxiomRecognitionService::class.java)
        val callback = RecognitionService.Callback::class.java.getDeclaredConstructor(RecognitionService::class.java, type, AttributionSource::class.java)
            .apply { isAccessible = true }
            .newInstance(service, caller, AttributionSource.Builder(Process.myUid()).setPackageName("com.example.notes").build()) as RecognitionService.Callback
        AxiomRecognitionService::class.java.getDeclaredMethod("onStartListening", Intent::class.java, RecognitionService.Callback::class.java)
            .apply { isAccessible = true }
            .invoke(service, Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB-u-mu-celsius"), callback)
        idle()
        val onDevice = latest()
        assertEquals("en-GB", asked(onDevice))
        shadowOf(onDevice).triggerOnError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
        idle()
        val next = latest()
        assertNotSame(onDevice, next)
        assertEquals("en-GB", asked(next))
        shadowOf(next).triggerOnResults(results("note to self"))
        idle()
        assertEquals("the caller never saw the refusal", listOf("results:[note to self]"), heard)
    }
}
