package com.axiom.launcher.solis

import com.axiom.launcher.solis.catalog.CatalogParsers
import com.axiom.launcher.solis.cortex.CallRole
import com.axiom.launcher.solis.cortex.DecisionMatrix
import com.axiom.launcher.solis.cortex.LocalAssessor
import com.axiom.launcher.solis.cortex.PolicyBook
import com.axiom.launcher.solis.cortex.PrivacyConstraint
import com.axiom.launcher.solis.cortex.QualityPriors
import com.axiom.launcher.solis.cortex.RouteNeeds
import com.axiom.launcher.solis.cortex.RouteQuery
import com.axiom.launcher.solis.cortex.Router
import com.axiom.launcher.solis.cortex.StatsBook
import com.axiom.launcher.solis.cortex.Tier
import com.axiom.launcher.solis.engine.InMemoryBrainStore
import com.axiom.launcher.solis.llm.Effort
import com.axiom.launcher.solis.memory.MemoryKind
import com.axiom.launcher.solis.memory.MemoryService
import com.axiom.launcher.solis.memory.Scope
import com.axiom.launcher.solis.memory.Space
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * JVM micro-benchmarks for the brain's hot paths, sized like a heavy real install (5,000 memories, a
 * ~470-model catalog). They are proxies: desktop JVM numbers, not phone measurements; the bounds are
 * generous and only catch regressions. Results print to the test log.
 */
class PerfBenchTest {
    private fun bench(name: String, iterations: Int, block: (Int) -> Unit): Double {
        repeat(iterations / 5 + 1) { block(it) } // warm-up (JIT)
        val start = System.nanoTime()
        repeat(iterations) { block(it) }
        val us = (System.nanoTime() - start) / 1000.0 / iterations
        println("BENCH $name: ${"%.1f".format(us)} µs/op over $iterations ops")
        return us
    }

    private val words = ("flight hotel lisbon standup deploy invoice dentist gym spotify playlist budget taxes passport visa " +
        "meeting deadline report slides coffee allergy peanut vegetarian sister birthday anniversary marathon training " +
        "kotlin android launcher widget battery charger router printer garden tomato recipe pasta sushi museum ticket " +
        "concert guitar piano lesson school pickup daycare doctor pharmacy insurance mortgage rent landlord plumber").split(" ")

    private fun sentence(r: Random, n: Int = 12) = (1..n).joinToString(" ") { words[r.nextInt(words.size)] }

    @Test
    fun memoryRetrievalAt5kItems() {
        val r = Random(7)
        val mem = MemoryService(InMemoryBrainStore())
        val buildStart = System.nanoTime()
        repeat(5_000) { i ->
            mem.remember(sentence(r) + " item$i", if (i % 4 == 0) MemoryKind.PREFERENCE else MemoryKind.FACT,
                Scope(if (i % 3 == 0) Space.WORK else Space.PERSONAL), "bench")
        }
        val buildMs = (System.nanoTime() - buildStart) / 1e6
        println("BENCH memory: 5000 remember() incl. duplicate check in ${"%.0f".format(buildMs)} ms (${"%.2f".format(buildMs / 5000)} ms each)")
        val scopes = setOf(Scope(Space.PERSONAL))
        val queries = (0 until 200).map { sentence(Random(it), 5) }
        val us = bench("memory search (BM25, 5k items)", 2_000) { mem.search(queries[it % queries.size], scopes) }
        assertTrue("search well under a frame budget", us < 8_000)
        assertTrue("bulk build stays practical", buildMs < 60_000)
    }

    private fun catalog(n: Int): List<com.axiom.launcher.solis.catalog.ModelInfo> {
        val r = Random(3)
        val vendors = listOf("acme", "zeta", "orbit", "nova", "lumen", "quark", "atlas", "fable")
        return (0 until n).map { i ->
            val inP = 1e-8 * (1 + r.nextInt(2000))
            Models.m("${vendors[i % vendors.size]}/model-$i", inP, inP * (2 + r.nextInt(4)), if (r.nextInt(5) == 0) null else 10.0 + r.nextInt(70),
                tools = r.nextInt(4) != 0, images = r.nextInt(3) == 0,
                efforts = if (r.nextBoolean()) listOf(Effort.LOW, Effort.MEDIUM, Effort.HIGH) else emptyList(),
                ctx = listOf(8_192, 32_768, 128_000, 200_000, 1_000_000)[r.nextInt(5)])
        }
    }

    @Test
    fun routingOverAFullCatalog() {
        val models = catalog(470)
        val book = PolicyBook(InMemoryBrainStore()).also { it.load() }
        val priors = QualityPriors(models)
        val router = Router(StatsBook())
        val tiers = listOf(Tier.T1, Tier.T2, Tier.T3)
        val us = bench("route() over 470 models", 2_000) { i ->
            router.route(
                RouteQuery(CallRole.WORKER, tiers[i % 3], RouteNeeds(tools = i % 2 == 0), 3_000, 800, PrivacyConstraint(), 1.0,
                    emptySet(), null, null, "bench"),
                models, setOf("fake"), book.snapshot(), priors,
            )
        }
        val priorsUs = bench("quality priors for 470 models", 200) { QualityPriors(models) }
        assertTrue("routing stays sub-millisecond-ish on a JVM", us < 5_000)
        assertTrue(priorsUs < 50_000)
    }

    @Test
    fun catalogParsingOfAFullOpenRouterListing() {
        val subset = JSONObject(javaClass.classLoader!!.getResource("openrouter_models_subset.json")!!.readText()).getJSONArray("data")
        val big = JSONArray()
        for (i in 0 until 470) big.put(JSONObject(subset.getJSONObject(i % subset.length()).toString()).put("id", "v${i % 9}/m$i"))
        val json = JSONObject().put("data", big).toString()
        println("BENCH catalog json: ${json.length / 1024} KB for 470 models")
        val us = bench("parse 470-model catalog", 30) { CatalogParsers.openRouter(json) }
        assertTrue("parse in well under a second", us < 1_000_000)
    }

    @Test
    fun localAssessmentIsCheap() {
        val m = DecisionMatrix.default()
        val texts = listOf(
            "timer 10 min", "Plan a 3-day food trip to Lisbon and book a table for Friday",
            "What's the dosage of ibuprofen for a 30kg child?", "Summarize my notifications and draft replies to anything urgent",
            "Refactor this Kotlin function to use coroutines and explain the tradeoffs",
        )
        val us = bench("LocalAssessor.assess", 20_000) { LocalAssessor.assess(texts[it % texts.size], m) }
        assertTrue(us < 2_000)
    }
}
