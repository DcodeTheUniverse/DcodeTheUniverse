package com.axiom.launcher.solis

import com.axiom.launcher.solis.catalog.ModelInfo
import com.axiom.launcher.solis.cortex.CallRole
import com.axiom.launcher.solis.cortex.DeviceSnapshot
import com.axiom.launcher.solis.cortex.PrivacyConstraint
import com.axiom.launcher.solis.cortex.QualityPriors
import com.axiom.launcher.solis.cortex.RouteNeeds
import com.axiom.launcher.solis.cortex.RouteQuery
import com.axiom.launcher.solis.cortex.Router
import com.axiom.launcher.solis.cortex.StatsBook
import com.axiom.launcher.solis.cortex.Tier
import com.axiom.launcher.solis.cortex.PolicyBook
import com.axiom.launcher.solis.engine.InMemoryBrainStore
import com.axiom.launcher.solis.engine.LeanTools
import com.axiom.launcher.solis.engine.LocalUse
import com.axiom.launcher.solis.engine.SideEffect
import com.axiom.launcher.solis.engine.SolisEngine
import com.axiom.launcher.solis.engine.SolisSettings
import com.axiom.launcher.solis.engine.SolisTool
import com.axiom.launcher.solis.engine.SubmitResult
import com.axiom.launcher.solis.engine.ThreadStatus
import com.axiom.launcher.solis.engine.ToolGroup
import com.axiom.launcher.solis.engine.ToolResult
import com.axiom.launcher.solis.llm.ChatMessage
import com.axiom.launcher.solis.llm.ChatRequest
import com.axiom.launcher.solis.llm.ChatRole
import com.axiom.launcher.solis.llm.ContentPart
import com.axiom.launcher.solis.llm.Effort
import com.axiom.launcher.solis.llm.ErrorKind
import com.axiom.launcher.solis.llm.FinishReason
import com.axiom.launcher.solis.llm.LlmEvent
import com.axiom.launcher.solis.llm.ProviderException
import com.axiom.launcher.solis.llm.ToolCall
import com.axiom.launcher.solis.llm.ToolSpec
import com.axiom.launcher.solis.memory.Space
import com.axiom.launcher.solis.ondevice.DeviceSpec
import com.axiom.launcher.solis.ondevice.Fit
import com.axiom.launcher.solis.ondevice.GemmaTemplate
import com.axiom.launcher.solis.ondevice.LocalEvent
import com.axiom.launcher.solis.ondevice.LocalMessage
import com.axiom.launcher.solis.ondevice.LocalRequest
import com.axiom.launcher.solis.ondevice.LocalRequests
import com.axiom.launcher.solis.ondevice.LocalStats
import com.axiom.launcher.solis.ondevice.LocalToolCall
import com.axiom.launcher.solis.ondevice.OnDeviceCatalog
import com.axiom.launcher.solis.ondevice.OnDeviceConfig
import com.axiom.launcher.solis.ondevice.OnDeviceProvider
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OnDeviceTest {
    @After
    fun stopEngines() = Rig.killAll()

    /** A Galaxy S24+: 12 GB of RAM (about 11.2 GB visible to apps), Snapdragon 8 Gen 3 or Exynos 2400. */
    private val s24plus = DeviceSpec(totalRamBytes = 11_200_000_000L, freeStorageBytes = 180_000_000_000L, arm64 = true, soc = "SM8650", model = "SM-S926B")

    private val e4b = OnDeviceCatalog.GEMMA4_E4B

    private fun config(thinking: Boolean = true, backend: String = "auto") = OnDeviceConfig(
        modelId = e4b.id, name = e4b.name, path = "/data/models/${e4b.file}", backend = backend, contextTokens = 8192,
        template = GemmaTemplate.ID, vision = true, tools = true, thinking = thinking, speculative = true, quality = e4b.quality,
    )

    // ------------------------------------------------------------------ catalog

    @Test
    fun theBestModelForAGalaxyS24PlusIsGemma4E4b() {
        assertEquals(OnDeviceCatalog.GEMMA4_E4B, OnDeviceCatalog.recommend(s24plus))
        assertEquals(Fit.GOOD, OnDeviceCatalog.fit(e4b, s24plus))
        assertEquals("Snapdragon 8 Gen 3", OnDeviceCatalog.chipName("SM8650"))
        assertEquals("Exynos 2400", OnDeviceCatalog.chipName("s5e9945"))

        // Smaller phones get the smaller model, tiny or non-ARM phones none.
        assertEquals(OnDeviceCatalog.GEMMA4_E2B, OnDeviceCatalog.recommend(s24plus.copy(totalRamBytes = 6_000_000_000L)))
        assertEquals(Fit.TIGHT, OnDeviceCatalog.fit(e4b, s24plus.copy(totalRamBytes = 6_000_000_000L)))
        assertNull(OnDeviceCatalog.recommend(s24plus.copy(totalRamBytes = 3_000_000_000L)))
        assertNull(OnDeviceCatalog.recommend(s24plus.copy(arm64 = false)))

        // Never fill the phone: the download needs its size plus a margin, unless it's already there.
        val full = s24plus.copy(freeStorageBytes = e4b.sizeBytes + 100_000_000L)
        assertEquals(Fit.NO_STORAGE, OnDeviceCatalog.fit(e4b, full))
        assertEquals(Fit.GOOD, OnDeviceCatalog.fit(e4b, full, installed = true))

        // What runs is exactly what was checked: pinned revision and checksum, Hugging Face resolve URL.
        OnDeviceCatalog.all.forEach { m ->
            assertTrue(m.revision.matches(Regex("[0-9a-f]{40}")))
            assertTrue(m.sha256.matches(Regex("[0-9a-f]{64}")))
            assertEquals("https://huggingface.co/${m.repo}/resolve/${m.revision}/${m.file}", m.url)
            assertTrue(m.id.startsWith("google/"))
        }
        assertEquals("3.7 GB", OnDeviceCatalog.size(e4b.sizeBytes))
    }

    // ------------------------------------------------------------------ requests

    @Test
    fun requestsBecomeGemmaShapedLocalRequests() {
        val call1 = ToolCall("a", "set_timer", """{"seconds":720,"label":"pasta"}""")
        val call2 = ToolCall("b", "note_add", """{"text":"buy basil"}""")
        val img = ContentPart.Image("image/png", "iVBORw0KGgo=")
        val req = ChatRequest(
            model = e4b.id, system = "You are Solis.",
            messages = listOf(
                ChatMessage(ChatRole.USER, listOf(img, ContentPart.Document("menu.pdf", "application/pdf", "JVBE"), ContentPart.Text("Pasta timer and a note"))),
                ChatMessage(ChatRole.ASSISTANT, listOf(ContentPart.Text("On it.")), listOf(call1, call2)),
                ChatMessage.toolResult(call1, "Timer set · 12 min", false),
                ChatMessage.toolResult(call2, "No notes app", true),
            ),
            tools = listOf(ToolSpec("set_timer", "Start a timer", """{"type":"object","properties":{"seconds":{"type":"integer"}}}""")),
            maxOutputTokens = 20_000, effort = Effort.HIGH, jsonMode = false,
        )
        val saved = ArrayList<ContentPart.Image>()
        val r = LocalRequests.from(req, "r1", "/m.litertlm", "auto", 8192, GemmaTemplate.ID, vision = true, speculative = true, thinking = true,
            extraSystem = GemmaTemplate.TOOL_REPLY_RULE) { saved += it; "/cache/img1.png" }

        assertEquals(listOf("user", "model", "tool"), r.messages.map { it.role })
        assertEquals(listOf("/cache/img1.png"), r.messages[0].images)
        assertTrue("the model is told about files it can't read", r.messages[0].text.contains("menu.pdf"))
        assertEquals(listOf("set_timer", "note_add"), r.messages[1].toolCalls.map { it.name })
        assertEquals("both results of one round become one tool message", listOf("set_timer", "note_add"), r.messages[2].toolResults.map { it.name })
        assertEquals("Error: No notes app", r.messages[2].toolResults[1].content)
        assertTrue(r.system.startsWith("You are Solis.") && r.system.endsWith(GemmaTemplate.TOOL_REPLY_RULE))
        assertEquals(1024, r.thinkingBudget)
        assertEquals("output is capped to half the context", 4096, r.maxOutputTokens)
        assertEquals(1, saved.size)

        // No tools offered: no tool rule; quick work doesn't think; images stay out when the model can't see.
        val plain = LocalRequests.from(req.copy(tools = emptyList(), effort = Effort.LOW), "r2", "/m", "cpu", 8192, null, vision = false,
            speculative = true, thinking = true, extraSystem = GemmaTemplate.TOOL_REPLY_RULE) { fail("no images without vision"); "" }
        assertEquals("You are Solis.", plain.system)
        assertEquals(0, plain.thinkingBudget)
        assertTrue(plain.messages[0].images.isEmpty())
        assertEquals(0, LocalRequests.from(req, "r3", "/m", "cpu", 8192, null, false, true, thinking = false, extraSystem = null) { "" }.thinkingBudget)
    }

    @Test
    fun requestsSurviveTheTripToTheModelProcess() {
        val r = LocalRequest(
            id = "x", modelId = e4b.id, modelPath = "/m", backend = "gpu", contextTokens = 16384, system = "sys",
            messages = listOf(LocalMessage("user", "hi", images = listOf("/i.png")), LocalMessage("model", "", toolCalls = listOf(LocalToolCall("t", """{"b":1,"a":2}""")))),
            tools = listOf(com.axiom.launcher.solis.ondevice.LocalTool("t", "d", """{"type":"object"}""")), maxOutputTokens = 300, thinkingBudget = 512,
            jsonMode = true, template = GemmaTemplate.ID, vision = true, speculative = false,
        )
        assertEquals(r, LocalRequest.fromJson(JSONObject(r.toJson().toString())))
        val s = LocalStats(10, 20, 30, 5, 100.0, 15.5, 700, 4200, "gpu", "tool_calls")
        assertEquals(s, LocalStats.fromJson(JSONObject(s.toJson().toString())))

        // Argument order doesn't change a message's identity; the prompt prefix key does change with what shapes it.
        assertEquals(LocalMessage("model", toolCalls = listOf(LocalToolCall("t", """{"a":2,"b":1}"""))).signature(), r.messages[1].signature())
        assertFalse(r.prefixKey() == r.copy(system = "other").prefixKey())
        assertFalse(r.prefixKey() == r.copy(thinkingBudget = 0).prefixKey())
        assertEquals(r.prefixKey(), r.copy(messages = r.messages.take(1), id = "y").prefixKey())
    }

    // ------------------------------------------------------------------ provider

    @Test
    fun providerTurnsModelEventsIntoSolisEvents() = runBlocking {
        val seen = ArrayList<LocalRequest>()
        var stats: LocalStats? = null
        val p = OnDeviceProvider(
            runtime = { req ->
                seen += req
                flowOf(
                    LocalEvent.Thought("thinking…"), LocalEvent.Text("Setting it"), LocalEvent.Call(LocalToolCall("set_timer", """{"seconds":720}""")),
                    LocalEvent.Call(LocalToolCall("note_add", """{"text":"x"}""")),
                    LocalEvent.Done(LocalStats(prefillTokens = 40, decodeTokens = 22, contextTokens = 562, reusedTokens = 500, decodeTps = 17.0, backend = "gpu", finish = "tool_calls")),
                )
            },
            config = { config() }, saveImage = { "/img" }, onStats = { _, s -> stats = s },
        )
        assertTrue(p.isLocal && p.isConfigured())
        val info = p.listModels().single()
        assertEquals(listOf(Effort.NONE, Effort.MEDIUM, Effort.HIGH), info.efforts)
        assertTrue(info.isLocal && info.compactTools && info.priorQuality == e4b.quality && info.estimateCost(1000, 1000) == 0.0)
        assertEquals("google", info.family)

        val events = p.stream(ChatRequest(e4b.id, "sys", listOf(ChatMessage.user("pasta timer")), tools = listOf(ToolSpec("set_timer", "t", "{}")))).toList()
        assertEquals(LlmEvent.ReasoningDelta("thinking…"), events[0])
        assertEquals(LlmEvent.TextDelta("Setting it"), events[1])
        val calls = events.filterIsInstance<LlmEvent.ToolCallReady>().map { it.call }
        assertEquals(listOf("set_timer", "note_add"), calls.map { it.name })
        assertEquals("each call gets its own id", 2, calls.map { it.id }.toSet().size)
        val usage = events.filterIsInstance<LlmEvent.Usage>().single()
        assertEquals(540, usage.inputTokens)
        assertEquals(22, usage.outputTokens)
        assertEquals("the part kept in the model's memory counts as cached", 500, usage.cachedInputTokens)
        assertEquals(0.0, usage.costUsd!!, 0.0)
        assertEquals(FinishReason.TOOL_CALLS, events.filterIsInstance<LlmEvent.Finish>().single().reason)
        assertEquals(17.0, stats!!.decodeTps, 0.0)

        val sent = seen.single()
        assertEquals(GemmaTemplate.ID, sent.template)
        assertEquals("auto", sent.backend)
        assertTrue("small models are told to confirm after tools", sent.system.contains(GemmaTemplate.TOOL_REPLY_RULE))
    }

    @Test
    fun providerFailuresLetTheRouterMoveOn() = runBlocking {
        fun failing(kind: String) = OnDeviceProvider(runtime = { flowOf(LocalEvent.Failed(kind, "boom")) }, config = { config() }, saveImage = { "" })
        val req = ChatRequest(e4b.id, "s", listOf(ChatMessage.user("hi")))
        for ((kind, expected) in listOf("CONTEXT_LENGTH" to ErrorKind.CONTEXT_LENGTH, "CRASHED" to ErrorKind.UNAVAILABLE,
            "CANCELLED" to ErrorKind.UNAVAILABLE, "UNAVAILABLE" to ErrorKind.UNAVAILABLE, "UNKNOWN" to ErrorKind.UNKNOWN)) {
            val e = runCatching { failing(kind).stream(req).toList() }.exceptionOrNull() as ProviderException
            assertEquals(kind, expected, e.kind)
            assertTrue("switching models helps", e.modelFallbackHelps)
        }
        // A stream that ends without an answer is a failure, not an empty reply.
        val silent = OnDeviceProvider(runtime = { flow { emit(LocalEvent.Text("par")) } }, config = { config() }, saveImage = { "" })
        assertEquals(ErrorKind.UNAVAILABLE, (runCatching { silent.stream(req).toList() }.exceptionOrNull() as ProviderException).kind)
        // Nothing installed (or turned off): not configured, no models, and calls are refused.
        val off = OnDeviceProvider(runtime = { fail("not called"); flowOf() }, config = { null }, saveImage = { "" })
        assertFalse(off.isConfigured())
        assertTrue(off.listModels().isEmpty())
        assertEquals(ErrorKind.UNAVAILABLE, (runCatching { off.stream(req).toList() }.exceptionOrNull() as ProviderException).kind)
    }

    // ------------------------------------------------------------------ tools & routing

    private fun tool(name: String, group: ToolGroup, desc: String = "does $name") =
        SolisTool(name, desc, group, SideEffect.NONE, mapOf("x" to SolisTool.str("x"))) { _, _ -> ToolResult("ok", true, "ok") }

    @Test
    fun theSmallModelGetsOnlyTheToolsARequestNeeds() {
        val all = listOf(
            tool("set_timer", ToolGroup.CLOCK), tool("set_alarm", ToolGroup.CLOCK), tool("calendar_agenda", ToolGroup.CALENDAR),
            tool("compose_sms", ToolGroup.COMMUNICATION), tool("play_media", ToolGroup.MEDIA), tool("open_app", ToolGroup.APPS),
            tool("note_add", ToolGroup.NOTES), tool("recall", ToolGroup.MEMORY), tool("remember", ToolGroup.MEMORY),
            tool("start_task", ToolGroup.DELEGATE), tool("code_status", ToolGroup.CODE), tool("read_web_page", ToolGroup.WEB),
        )
        fun names(text: String) = LeanTools.pick(text, all).map { it.name }.toSet()
        assertEquals(setOf("set_timer", "set_alarm", "recall", "remember"), names("Set a timer for 12 minutes for the pasta"))
        assertEquals(setOf("calendar_agenda", "compose_sms", "recall", "remember"), names("Text Sam what's on my calendar tomorrow"))
        assertEquals("nothing specific: everyday actions", setOf("open_app", "set_timer", "set_alarm", "note_add", "recall", "remember"), names("hello there"))
        assertTrue("never spawns other tasks", all.none { false } && "start_task" !in names("start a task to research and plan everything"))
        // A long tool list is trimmed to the budget, keeping memory.
        val many = (1..80).map { tool("t$it", ToolGroup.CLOCK, "x".repeat(200)) } + tool("recall", ToolGroup.MEMORY)
        val picked = LeanTools.pick("set an alarm", many)
        assertTrue(picked.sumOf { LeanTools.cost(it) } <= LeanTools.BUDGET_CHARS + LeanTools.cost(many.last()))
        assertTrue(picked.any { it.name == "recall" } && picked.size < many.size)
    }

    private val phone = OnDeviceConfig(
        modelId = e4b.id, name = e4b.name, path = "/m", backend = "auto", contextTokens = 8192, template = GemmaTemplate.ID,
        vision = true, tools = true, thinking = true, speculative = true, quality = e4b.quality,
    ).modelInfo()

    @Test
    fun routerPutsWorkOnThePhoneWhenAskedAndKeepsTheCloudAsFallback() {
        val router = Router(StatsBook())
        val policy = PolicyBook(InMemoryBrainStore()).also { it.load() }.snapshot()
        val models = Models.all + phone
        val priors = QualityPriors(models)
        assertEquals("the phone model's own prior counts until it is measured", e4b.quality, priors.of(phone), 1e-9)
        fun q(tier: Tier, preferLocal: Boolean, privacy: PrivacyConstraint = PrivacyConstraint(), needs: RouteNeeds = RouteNeeds(), exclude: Set<com.axiom.launcher.solis.llm.ModelRef> = emptySet()) =
            RouteQuery(CallRole.WORKER, tier, needs, 800, 300, privacy, null, exclude = exclude, preferLocal = preferLocal)
        val usable = setOf("fake", OnDeviceProvider.ID)

        assertEquals(phone.ref, router.route(q(Tier.T1, preferLocal = true), models, usable, policy, priors).model.ref)
        assertEquals("even below a tier's quality floor when the user asked for it", phone.ref,
            router.route(q(Tier.T3, preferLocal = true), models, usable, policy, priors).model.ref)
        assertFalse("without the preference, hard work goes to stronger cloud models",
            router.route(q(Tier.T3, preferLocal = false), models, usable, policy, priors).model.isLocal)
        assertFalse("if the phone failed, the cloud takes over",
            router.route(q(Tier.T1, preferLocal = true, exclude = setOf(phone.ref)), models, usable, policy, priors).model.isLocal)
        // "When needed": the phone stays out while any other model can do the job, even when it would rank well.
        val held = q(Tier.T1, preferLocal = false).copy(holdBackOnDevice = true)
        assertFalse(router.route(held, models, usable, policy, priors).model.onDevice)
        assertEquals("…and steps in when nothing else is reachable", phone.ref, router.route(held, models, setOf(OnDeviceProvider.ID), policy, priors).model.ref)
        assertEquals("local-only privacy: only the phone", phone.ref,
            router.route(q(Tier.T2, preferLocal = false, privacy = PrivacyConstraint(cloudAllowed = false)), models, usable, policy, priors).model.ref)
        // Context: the full tool set wouldn't fit 8K, the request's lean tool set does.
        val tight = RouteNeeds(tools = true, minContext = 20_000, leanMinContext = 3_000)
        assertEquals(phone.ref, router.route(q(Tier.T1, true, needs = tight), models, usable, policy, priors).model.ref)
        assertFalse(router.route(q(Tier.T1, true, needs = tight.copy(leanMinContext = 12_000)), models, usable, policy, priors).model.isLocal)
    }

    // ------------------------------------------------------------------ the engine, end to end

    private fun isCoordinator(r: ChatRequest) = r.system.startsWith("You are the coordinator")

    private fun rig(use: LocalUse, cloud: FakeProvider?, device: DeviceSnapshot = DeviceSnapshot()): Pair<Rig, FakeProvider> {
        val onPhone = FakeProvider(id = OnDeviceProvider.ID, isLocal = true, script = { r, _ ->
            if (isCoordinator(r)) Script.text("""{"tier":"T1","confidence":0.9,"ambiguity":0.1,"risk":"LOW","domain":"general","needs":[],"steps":[{"id":"s1","goal":"Answer","dependsOn":[],"tier":"T1","needs":[]}],"clarify":null,"reasons":["simple"]}""")
            else Script.text("From the phone", cost = 0.0)
        })
        val providers = listOfNotNull(cloud, onPhone)
        val rig = Rig(providers = providers, models = (if (cloud != null) Models.all else emptyList()) + phone,
            settings = SolisSettings(localUse = use), device = device).start()
        return rig to onPhone
    }

    private fun accepted(r: SubmitResult) = (r as SubmitResult.Accepted).threadId

    @Test
    fun quickQuestionsRunOnThePhoneWhenTheUserChoseThat() {
        val cloud = FakeProvider(script = { r, _ -> if (isCoordinator(r)) Script.text("{}") else Script.text("From the cloud") })
        val (rig, onPhone) = rig(LocalUse.QUICK, cloud)
        val id = accepted(rig.engine.submit("What's a good name for a grey cat?", Space.PERSONAL, "personal"))
        awaitStatus(rig, id, ThreadStatus.DONE)
        assertEquals("From the phone", rig.thread(id).lastResult)
        val sent = onPhone.requests.single()
        assertTrue("lean tools: no task spawning", sent.tools.none { it.name == "start_task" })
        assertTrue(sent.tools.any { it.name == "recall" })
        val receipt = rig.engine.receipts(id).first()
        assertTrue("the receipt says it never left the phone", receipt.onDevice && receipt.local)
        assertEquals(0.0, rig.thread(id).usage.costUsd, 0.0)
    }

    @Test
    fun thePhoneModelsToolsFollowTheUsersWordsNotTheDeviceLine() {
        val onPhone = FakeProvider(id = OnDeviceProvider.ID, isLocal = true, script = { r, _ ->
            if (isCoordinator(r)) Script.text("{}") else Script.text("Saved.", cost = 0.0)
        })
        val rig = Rig(providers = listOf(onPhone), models = listOf(phone), settings = SolisSettings(localUse = LocalUse.ALWAYS),
            contextLine = "now: Tue 14:05 | battery: 85% | network: wifi | storage free: 80 GB of 256 GB | next alarm: Wed 07:00").start()
        val id = accepted(rig.engine.submit("Add a note to buy oat milk", Space.PERSONAL, "personal"))
        awaitStatus(rig, id, ThreadStatus.DONE)
        val worker = onPhone.requests.last { it.tools.isNotEmpty() }
        assertTrue("the device line rides along", worker.messages.last().text.contains("next alarm"))
        // The note tool and memory, nothing else: not the alarm tool because the device line mentions an alarm.
        assertEquals(setOf("note_add", "recall", "remember", "update_memory"), worker.tools.map { it.name }.toSet())
    }

    @Test
    fun whenNeededMeansTheCloudFirstAndThePhoneOffline() {
        val cloud = FakeProvider(script = { r, _ -> if (isCoordinator(r)) Script.text("{}") else Script.text("From the cloud") })
        val (rig, onPhone) = rig(LocalUse.NEEDED, cloud)
        val a = accepted(rig.engine.submit("What's a good name for a grey cat?", Space.PERSONAL, "personal"))
        awaitStatus(rig, a, ThreadStatus.DONE)
        assertEquals("From the cloud", rig.thread(a).lastResult)
        assertTrue(onPhone.requests.isEmpty())

        // No connection: the phone answers instead of the task waiting for the network.
        rig.device = DeviceSnapshot(online = false)
        rig.engine.onConditionsChanged()
        val b = accepted(rig.engine.submit("What's a good name for a black cat?", Space.PERSONAL, "personal"))
        awaitStatus(rig, b, ThreadStatus.DONE)
        assertEquals("From the phone", rig.thread(b).lastResult)
        assertTrue(rig.engine.mood.value != com.axiom.launcher.solis.engine.Mood.OFFLINE)
    }

    @Test
    fun thePhoneModelIsNeverInterruptedForStatusLines() {
        val plan = """{"tier":"T2","confidence":0.8,"steps":[{"id":"s1","goal":"part one"},{"id":"s2","goal":"part two"},{"id":"s3","goal":"part three","dependsOn":["s1","s2"]}]}"""
        fun runMultiStep(model: ModelInfo): Pair<Rig, FakeProvider> {
            val p = FakeProvider(id = OnDeviceProvider.ID, isLocal = true, script = { r, _ ->
                when {
                    isCoordinator(r) -> Script.text(plan)
                    r.system == SolisEngine.NARRATOR_SYSTEM -> Script.text("Three parts done")
                    else -> Script.text("part done", cost = 0.0)
                }
            })
            val rig = Rig(providers = listOf(p), models = listOf(model), settings = SolisSettings(localUse = LocalUse.ALWAYS)).start()
            runBlocking { rig.engine.awaitStarted() }
            val m = rig.engine.policies.active().matrix
            rig.engine.policies.applyUserEdit(m.copy(narrator = m.narrator.copy(coalesceMs = 1_200, minIntervalMs = 60_000)), "narrator for test")
            val id = accepted(rig.engine.submit("Research A and then compare B, then plan C carefully with a detailed itinerary", Space.PERSONAL, "personal"))
            awaitStatus(rig, id, ThreadStatus.DONE, timeoutMs = 15_000)
            Thread.sleep(1_500)
            return rig to p
        }
        // An ordinary local model narrates a multi-step task...
        val (_, lan) = runMultiStep(phone.copy(compactTools = false, onDevice = false))
        assertTrue("control: the narrator runs for this task", lan.requests.any { it.system == SolisEngine.NARRATOR_SYSTEM })
        Rig.killAll()
        // ...the phone's own model never gets a status-line call in the middle of the work.
        val (rig, onPhone) = runMultiStep(phone)
        assertTrue("no narrator call reached the phone", onPhone.requests.none { it.system == SolisEngine.NARRATOR_SYSTEM })
        assertTrue(onPhone.requests.isNotEmpty() && rig.engine.threads.value.all { it.narration == null })
    }

    @Test
    fun settingsTravelButModelFilesDont() {
        val keys = listOf("ondevice_on", "ondevice_model", "ondevice_backend", "ondevice_ctx", "ondevice_use", "ondevice_think", "ondevice_keep", "ondevice_wifi")
        keys.forEach { assertEquals(it, com.axiom.launcher.data.SettingsBackup.Group.SOLIS, com.axiom.launcher.data.SettingsBackup.group(it)) }
        assertNull("which GPU failed is this phone's business", com.axiom.launcher.data.SettingsBackup.group("ondevice_gpu_block"))
        assertNull(com.axiom.launcher.data.SettingsBackup.group("ondevice_stats"))
    }

    @Test
    fun modelInfoKeepsOnDeviceFieldsThroughTheCatalogCache() {
        val snap = com.axiom.launcher.solis.catalog.CatalogSnapshot(OnDeviceProvider.ID, 1L, listOf(phone))
        val back = com.axiom.launcher.solis.catalog.CatalogParsers.fromCache(com.axiom.launcher.solis.catalog.CatalogParsers.toCache(snap)).models.single()
        assertEquals(phone.priorQuality, back.priorQuality)
        assertTrue(back.compactTools && back.isLocal)
        assertEquals(phone.efforts, back.efforts)
        val plain: ModelInfo = Models.cheap
        val plainBack = com.axiom.launcher.solis.catalog.CatalogParsers.fromCache(com.axiom.launcher.solis.catalog.CatalogParsers.toCache(
            com.axiom.launcher.solis.catalog.CatalogSnapshot("fake", 1L, listOf(plain)))).models.single()
        assertNull(plainBack.priorQuality)
        assertFalse(plainBack.compactTools)
    }
}
