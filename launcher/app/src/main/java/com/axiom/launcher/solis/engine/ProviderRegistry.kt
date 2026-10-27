package com.axiom.launcher.solis.engine

import com.axiom.launcher.solis.catalog.CatalogParsers
import com.axiom.launcher.solis.catalog.CatalogSnapshot
import com.axiom.launcher.solis.catalog.Freshness
import com.axiom.launcher.solis.catalog.ModelCatalog
import com.axiom.launcher.solis.cortex.QualityPriors
import com.axiom.launcher.solis.llm.LlmProvider

/**
 * Holds the configured inference providers and their discovered model catalogs. Catalogs are cached in
 * the brain store with their fetch time, so routing works offline and freshness is always visible.
 */
class ProviderRegistry(
    private val store: BrainStore,
    val catalog: ModelCatalog = ModelCatalog(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val providers = LinkedHashMap<String, LlmProvider>()
    @Volatile private var priorsCache: Pair<Int, QualityPriors>? = null
    @Volatile var revision = 0; private set

    @Synchronized
    fun register(p: LlmProvider) { providers[p.id] = p }

    fun get(id: String): LlmProvider? = synchronized(this) { providers[id] }
    fun all(): List<LlmProvider> = synchronized(this) { providers.values.toList() }

    /** Providers that can serve a call right now. Cloud providers need connectivity and no forced offline mode. */
    fun usable(online: Boolean, forcedOffline: Boolean): Set<String> = all()
        .filter { it.isConfigured() && (it.isLocal || (online && !forcedOffline)) }
        .map { it.id }.toSet()

    fun loadCache() {
        all().forEach { p ->
            store.kvGet(key(p.id))?.let { text -> runCatching { CatalogParsers.fromCache(text) }.getOrNull()?.let { catalog.replace(it) } }
        }
        bump()
    }

    fun freshness(providerId: String): Freshness = catalog.snapshot(providerId)?.freshness(clock()) ?: Freshness.MISSING

    /** Fetches current model metadata from a provider and caches a slim copy. */
    suspend fun refresh(providerId: String): CatalogSnapshot {
        val p = get(providerId) ?: throw IllegalArgumentException("Unknown provider $providerId")
        val snap = CatalogSnapshot(p.id, clock(), p.listModels())
        catalog.replace(snap)
        store.kvPut(key(p.id), CatalogParsers.toCache(snap))
        bump()
        return snap
    }

    /** Refreshes catalogs that are missing or older than their TTL; failures keep the cached copy. */
    suspend fun refreshStale(): List<String> {
        val refreshed = ArrayList<String>()
        for (p in all()) {
            if (freshness(p.id) == Freshness.FRESH) continue
            if (!p.isConfigured() && !p.id.startsWith("openrouter")) continue
            runCatching { refresh(p.id) }.onSuccess { refreshed += p.id }
        }
        return refreshed
    }

    fun forget(providerId: String) {
        catalog.remove(providerId)
        store.kvPut(key(providerId), null)
        bump()
    }

    fun priors(): QualityPriors {
        val c = priorsCache
        if (c != null && c.first == revision) return c.second
        val p = QualityPriors(catalog.all())
        priorsCache = revision to p
        return p
    }

    private fun bump() { revision++ }

    companion object {
        fun key(id: String) = "catalog:$id"
    }
}
