package com.axiom.launcher

import android.content.ComponentName
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.speech.RecognitionService
import android.text.InputType
import android.util.Xml
import androidx.test.core.app.ApplicationProvider
import com.axiom.launcher.AxiomTestKit.idleUntil
import com.axiom.launcher.AxiomTestKit.installFakeProvider
import com.axiom.launcher.data.IconPacks
import com.axiom.launcher.data.Prefs
import com.axiom.launcher.data.SettingsBackup
import com.axiom.launcher.platform.CrashLog
import com.axiom.launcher.platform.HomeWidget
import com.axiom.launcher.platform.ScreenNode
import com.axiom.launcher.platform.ScreenText
import com.axiom.launcher.platform.Voice
import com.axiom.launcher.solis.engine.SubmitResult
import com.axiom.launcher.solis.engine.ThreadStatus
import com.axiom.launcher.solis.llm.ContentPart
import com.axiom.launcher.solis.memory.MemoryKind
import com.axiom.launcher.solis.memory.Scope
import com.axiom.launcher.solis.memory.Space
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.StringReader

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LauncherFeaturesTest {
    private val app: AxiomApp get() = ApplicationProvider.getApplicationContext()

    // ---------------------------------------------------------------- settings backup

    @Test
    fun settingsTravelButSolisSettingsOnlyWhenAsked() {
        val prefs = Prefs(app)
        prefs.theme = "paper"; prefs.userName = "Dee"; prefs.favorites = listOf("kPhone", "kMaps"); prefs.showBadges = false
        prefs.notes = listOf(com.axiom.launcher.data.Note(1, "Pick up film", 10, pinned = true))
        prefs.dailyCapUsd = 3f; prefs.localBaseUrl = "http://192.168.1.20:11434"; prefs.savedPrompts = listOf("Plan my day")
        prefs.widgetsJson = """[{"id":5,"provider":"a/b","h":180}]"""
        val json = SettingsBackup.export(prefs, "2.2.0", now = 42)
        val o = JSONObject(json)
        assertEquals("axiom-settings", o.getString("format"))
        val values = o.getJSONObject("values")
        assertFalse("widget ids belong to this phone", values.has("widgets"))
        assertTrue(values.has("cap_day") && values.has("theme") && values.has("saved_prompts"))

        // A different phone with its own note and caps.
        prefs.theme = "void"; prefs.userName = ""; prefs.dailyCapUsd = 1f; prefs.localBaseUrl = ""; prefs.savedPrompts = emptyList()
        prefs.notes = listOf(com.axiom.launcher.data.Note(1, "Call the bank", 20))
        val parsed = SettingsBackup.parse(json)
        assertEquals("2.2.0", parsed.appVersion)
        assertTrue(parsed.count(SettingsBackup.Group.SOLIS) >= 2)
        SettingsBackup.apply(prefs, parsed, includeSolis = false)
        assertEquals("paper", prefs.theme)
        assertEquals("Dee", prefs.userName)
        assertEquals(listOf("kPhone", "kMaps"), prefs.favorites)
        assertEquals(listOf("Plan my day"), prefs.savedPrompts)
        assertEquals("caps don't change unless asked", 1f, prefs.dailyCapUsd)
        assertEquals("", prefs.localBaseUrl)
        assertEquals("notes are merged, not replaced", setOf("Pick up film", "Call the bank"), prefs.notes.map { it.text }.toSet())
        assertEquals(2, prefs.notes.map { it.id }.toSet().size)
        SettingsBackup.apply(prefs, parsed, includeSolis = true)
        assertEquals(3f, prefs.dailyCapUsd)
        assertEquals("http://192.168.1.20:11434", prefs.localBaseUrl)
    }

    @Test
    fun brokenOrHostileExportsAreRefusedBeforeAnythingIsWritten() {
        fun file(values: JSONObject) = JSONObject().put("format", "axiom-settings").put("version", 1).put("values", values).toString()
        listOf("not json", """{"format":"something-else"}""", JSONObject().put("format", "axiom-settings").put("version", 99).toString()).forEach {
            try { SettingsBackup.parse(it); fail("$it should be refused") } catch (e: IllegalArgumentException) { /* expected */ }
        }
        val p = SettingsBackup.parse(file(JSONObject()
            .put("modes", JSONObject().put("t", "s").put("v", "[{\"name\":\"no id\"}]"))
            .put("notes", JSONObject().put("t", "s").put("v", "oops"))
            .put("local_url", JSONObject().put("t", "s").put("v", "file:///data/data/com.axiom.launcher"))
            .put("cap_day", JSONObject().put("t", "f").put("v", 1e9))
            .put("max_concurrent", JSONObject().put("t", "i").put("v", 50))
            .put("theme", JSONObject().put("t", "b").put("v", true))
            .put("unknown_key", JSONObject().put("t", "s").put("v", "x"))
            .put("user_name", JSONObject().put("t", "s").put("v", "Ana"))))
        assertEquals(setOf("cap_day", "max_concurrent", "user_name"), p.values.keys)
        assertEquals("caps are clamped", 1000f, p.values["cap_day"])
        assertEquals(6, p.values["max_concurrent"])
        try { SettingsBackup.parse(file(JSONObject().put("modes", JSONObject().put("t", "s").put("v", "[]")))); fail("nothing usable") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("Nothing")) }
    }

    @Test
    fun importReloadsTheLauncherLive() {
        val vm = LauncherViewModel(app)
        val json = SettingsBackup.export(Prefs(app).also { it.theme = "netrunner"; it.showIcons = true; it.savedPrompts = listOf("Brief me") }, "2.2.0")
        Prefs(app).theme = "void"; Prefs(app).showIcons = false; Prefs(app).savedPrompts = emptyList()
        vm.importPreview = SettingsBackup.parse(json)
        vm.applyImport(includeSolis = false)
        assertNull(vm.importPreview)
        assertTrue(vm.showIcons)
        assertEquals(listOf("Brief me"), vm.savedPrompts)
        idleUntil("theme reloaded") { vm.userTheme.id == "netrunner" }
    }

    // ---------------------------------------------------------------- icon packs

    @Test
    fun iconPackFiltersMapActivitiesAndFallBackByApp() {
        val xml = """
            <resources>
              <iconback img1="back_a" img2="back_b"/>
              <iconmask img1="mask"/>
              <iconupon img1="glass"/>
              <scale factor="0.75"/>
              <item component="ComponentInfo{com.spotify.music/com.spotify.music.MainActivity}" drawable="spotify"/>
              <item component="ComponentInfo{org.telegram.messenger/.DefaultIcon}" drawable="telegram"/>
              <item component="ComponentInfo{broken}" drawable="x"/>
              <item component="ComponentInfo{com.a/com.a.Main}" drawable=""/>
            </resources>
        """.trimIndent()
        val parser = Xml.newPullParser().apply { setInput(StringReader(xml)) }
        val pack = IconPacks.parse("pack.test", null, parser)
        assertEquals(2, pack.size)
        assertEquals("spotify", pack.drawableFor(ComponentName("com.spotify.music", "com.spotify.music.MainActivity")))
        assertEquals("relative class names are expanded", "telegram", pack.drawableFor(ComponentName("org.telegram.messenger", "org.telegram.messenger.DefaultIcon")))
        assertEquals("another activity of a covered app still gets its icon", "spotify", pack.drawableFor(ComponentName("com.spotify.music", "com.spotify.music.Other")))
        assertNull(pack.drawableFor(ComponentName("com.unknown", "com.unknown.Main")))
        assertEquals(ComponentName("a.b", "a.b.C"), IconPacks.componentOf("a.b/.C"))
        assertNull(IconPacks.componentOf("nonsense"))
        assertTrue("no packs installed in the test phone", IconPacks.installed(app).isEmpty())
    }

    // ---------------------------------------------------------------- ask about this screen

    private class Node(
        override val text: CharSequence? = null, override val description: CharSequence? = null, override val inputType: Int = 0,
        override val autofillHints: Array<String>? = null, override val visible: Boolean = true, override val children: List<ScreenNode> = emptyList(),
    ) : ScreenNode

    @Test
    fun screenTextSkipsPasswordsCodesAndHiddenViews() {
        val pwd = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val screen = Node(children = listOf(
            Node("Sign in to Bank"),
            Node("ana@example.pt"),
            Node("hunter2", inputType = pwd),
            Node("4111 1111 1111 1111", autofillHints = arrayOf("creditCardNumber")),
            Node("123456", autofillHints = arrayOf("smsOTPCode")),
            Node("Secret menu", visible = false, children = listOf(Node("Inside hidden"))),
            Node(description = "Settings button"),
            Node("Sign in to Bank"),
            Node(children = listOf(Node("Forgot password?"))),
        ))
        assertEquals("Sign in to Bank\nana@example.pt\nSettings button\nForgot password?", ScreenText.collect(listOf(screen)))
        assertEquals(20, ScreenText.collect(listOf(Node(children = List(100) { Node("Line number $it") })), maxChars = 20).length)
    }

    @Test
    fun assistantAndRecognizerAreRegistered() {
        val pm = app.packageManager
        val vis = pm.queryIntentServices(Intent("android.service.voice.VoiceInteractionService").setPackage(app.packageName), android.content.pm.PackageManager.GET_META_DATA)
        assertEquals("com.axiom.launcher.platform.AxiomAssistService", vis.single().serviceInfo.name)
        assertEquals("android.permission.BIND_VOICE_INTERACTION", vis.single().serviceInfo.permission)
        val rec = pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE).setPackage(app.packageName), 0)
        assertEquals("com.axiom.launcher.platform.AxiomRecognitionService", rec.single().serviceInfo.name)
        assertNull("our own pass-through is never picked as the recognizer to pass to", com.axiom.launcher.platform.Recognizers.other(app))
    }

    @Test
    fun screenCapturesBecomeAnAttachment() {
        val vm = LauncherViewModel(app)
        val dir = File(app.cacheDir, "shared").apply { mkdirs() }
        val txt = File(dir, "screen-1.txt").apply { writeText("Flight LX 1234 · Gate B7 <b>boarding</b>") }
        vm.receiveScreen(null, txt.path)
        idleUntil("attachment ready") { vm.pendingAttachment != null }
        val part = vm.pendingAttachment!!.parts.single() as ContentPart.Text
        assertTrue(part.text, part.text.startsWith("<screen_text>") && part.text.contains("Gate B7 ‹b›boarding‹/b›"))
        assertEquals(PAGE_SOLIS, vm.pageRequest)
        assertEquals("Screen text", vm.pendingAttachment!!.name)
    }

    // ---------------------------------------------------------------- widgets

    @Test
    fun widgetPlacementsPersistInOrder() {
        val w = app.core.widgets
        w.debugPlace(HomeWidget(101, "com.clock/.Big", 180))
        w.debugPlace(HomeWidget(102, "com.weather/.Now", 110))
        w.resize(101, 260)
        w.move(102, -1)
        assertEquals(listOf(102, 101), w.placed.value.map { it.id })
        val saved = JSONArray(Prefs(app).widgetsJson)
        assertEquals(260, saved.getJSONObject(1).getInt("h"))
        w.remove(102)
        assertEquals(listOf(101), w.placed.value.map { it.id })
        w.move(101, 5)
        assertEquals(listOf(101), w.placed.value.map { it.id })
        assertFalse("no real widget behind the placement", w.available(w.placed.value.single()))
        w.releaseOrphans()
    }

    // ---------------------------------------------------------------- voice

    @Test
    fun spokenRepliesAreShortAndClean() {
        assertEquals("Done: alarm set for 6:30.", Voice.spoken("**Done:** alarm set for `6:30`."))
        assertEquals("See a link for details.", Voice.spoken("See [the docs](https://x.io) for details.").replace("the docs", "a link"))
        val long = Voice.spoken("First sentence here. " + "More words follow and follow. ".repeat(40), max = 120)
        assertTrue(long, long.endsWith("The rest is on screen.") && long.length < 170)
        assertTrue(Voice.spoken("Run ```kotlin\nval x = 1\n``` then").contains("(code on screen)"))
    }

    // ---------------------------------------------------------------- reliability

    @Test
    fun crashesAreKeptLocallyAndSummarized() {
        CrashLog.clear(app)
        CrashLog.write(app, "main", IllegalStateException("boom in settings"))
        val summary = CrashLog.latestSummary(app)!!
        assertTrue(summary, summary.contains("IllegalStateException: boom in settings"))
        repeat(7) { CrashLog.write(app, "t$it", RuntimeException("again $it")); Thread.sleep(2) }
        assertEquals("only the newest five are kept", 5, CrashLog.recent(app).size)
        assertTrue(CrashLog.exitReasons(app).isNotBlank())
        CrashLog.clear(app)
        assertNull(CrashLog.latestSummary(app))
    }

    @Test
    fun diagnosticsReportLeavesPrivateContentOut() = runBlocking {
        app.installFakeProvider()
        val vm = LauncherViewModel(app)
        vm.addNote("PRIVATE-NOTE-7731")
        vm.updateName("PRIVATE-NAME-7731")
        app.solis.memory.remember("PRIVATE-MEMORY-7731", MemoryKind.FACT, Scope(Space.PERSONAL), "you")
        val id = (app.solis.submit("PRIVATE-TASK-7731 summarize") as SubmitResult.Accepted).threadId
        idleUntil("task done") { app.solis.engine.thread(id)?.status == ThreadStatus.DONE }
        val checks = app.solis.selfTest.run()
        assertTrue(checks.any { it.area == "AI" && it.text.contains("connected") })
        val report = app.solis.selfTest.report(checks)
        listOf("PRIVATE-NOTE", "PRIVATE-NAME", "PRIVATE-MEMORY", "PRIVATE-TASK").forEach { assertFalse("$it leaked into the report", report.contains(it)) }
        assertTrue(report.contains("== Self-test ==") && report.contains("== Process exits (Android) =="))
        val f = app.solis.selfTest.saveReport(report)
        assertEquals(File(app.cacheDir, "shared"), f.parentFile)
    }

    // ---------------------------------------------------------------- lock

    @Test
    fun lockHidesSolisUntilUnlocked() {
        app.installFakeProvider()
        val vm = LauncherViewModel(app)
        val id = (app.solis.submit("Plan the PRIVATE trip") as SubmitResult.Accepted).threadId
        idleUntil("thread mirrored") { vm.threads.any { it.id == id } }
        val bio = org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowBiometricManager>(app.getSystemService(BiometricManager::class.java))
        bio.setCanAuthenticate(false)
        vm.updateBioLock(true)
        assertFalse("can't turn it on without a screen lock", vm.bioLock)
        bio.setCanAuthenticate(true)
        bio.setAuthenticatorType(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        vm.updateBioLock(true)
        assertTrue(vm.bioLock)
        assertFalse("turning it on counts as unlocked", vm.locked)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        idleUntil("screen off locks again") { vm.locked }
        vm.query = "PRIVATE trip"
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        idleUntil("search ran") { vm.results.isNotEmpty() }
        assertTrue("locked: no task results in search", vm.results.none { it is Hit.Thread })
        vm.onBackgrounded()
        vm.onForegrounded()
        assertTrue(vm.locked)
        vm.updateBioLock(false)
        assertFalse(vm.locked)
    }

    // ---------------------------------------------------------------- saved prompts

    @Test
    fun savedPromptsShowUpInThePalette() {
        val vm = LauncherViewModel(app)
        vm.savePrompt("  What's on my calendar this week?  ")
        vm.savePrompt("What's on my calendar this week?")
        assertEquals(listOf("What's on my calendar this week?"), vm.savedPrompts)
        assertTrue(vm.palette("calendar").any { it.title == "Ask: What's on my calendar this week?" })
        assertTrue(vm.palette("widget").any { it.id == "widget" })
        vm.deletePrompt("What's on my calendar this week?")
        assertTrue(Prefs(app).savedPrompts.isEmpty())
    }
}
