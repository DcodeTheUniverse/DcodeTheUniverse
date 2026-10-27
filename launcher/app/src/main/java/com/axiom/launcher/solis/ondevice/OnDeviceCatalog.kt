package com.axiom.launcher.solis.ondevice

import java.util.Locale

/**
 * A model Axiom can download and run on the phone with LiteRT-LM. Files are pinned to an exact Hugging Face
 * revision and SHA-256, so what runs is exactly what was checked.
 */
data class OnDeviceModel(
    /** Solis model id; the vendor prefix makes second opinions treat it as a Google model. */
    val id: String,
    val name: String,
    val blurb: String,
    val repo: String,
    val revision: String,
    val file: String,
    val sizeBytes: Long,
    val sha256: String,
    /** Total RAM a phone should have to run it next to the launcher and other apps. */
    val minRamGb: Double,
    val defaultContext: Int,
    val maxContext: Int,
    val vision: Boolean,
    val audio: Boolean,
    val thinking: Boolean,
    val tools: Boolean,
    /** Chat format to use instead of the one in the file ([GemmaTemplate.ID] for Gemma 4), or null. */
    val template: String?,
    /** Routing prior (0..1) until Solis has measured it; cloud models are ranked against it. */
    val quality: Double,
) {
    val url: String get() = "https://huggingface.co/$repo/resolve/$revision/$file"
}

/** What the model manager knows about the phone it runs on. */
data class DeviceSpec(
    val totalRamBytes: Long,
    val freeStorageBytes: Long,
    val arm64: Boolean,
    /** Build.SOC_MODEL, e.g. "SM8650" or "s5e9945". */
    val soc: String = "",
    val model: String = "",
) {
    /** Marketing RAM (a "12 GB" phone reports about 11 GB to apps). */
    val ramGb: Double get() = totalRamBytes / 1e9
}

enum class Fit { GOOD, TIGHT, NO_STORAGE, UNSUPPORTED }

object OnDeviceCatalog {
    /** Largest Gemma 4 built for phones: text, images and audio, tool calls and thinking. */
    val GEMMA4_E4B = OnDeviceModel(
        id = "google/gemma-4-e4b-it",
        name = "Gemma 4 E4B",
        blurb = "Google's most capable phone-sized Gemma 4. Reads images, calls Solis's tools, can think step by step.",
        repo = "litert-community/gemma-4-E4B-it-litert-lm",
        revision = "e6a3892a8c22cf87ff99583996149cf3cb32452b",
        file = "gemma-4-E4B-it.litertlm",
        sizeBytes = 3_659_530_240L,
        sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        minRamGb = 7.5,
        defaultContext = 8192,
        maxContext = 32768,
        vision = true, audio = true, thinking = true, tools = true,
        template = GemmaTemplate.ID,
        quality = 0.42,
    )

    /** About twice as fast and 1 GB smaller; for phones with less memory or when speed matters most. */
    val GEMMA4_E2B = OnDeviceModel(
        id = "google/gemma-4-e2b-it",
        name = "Gemma 4 E2B",
        blurb = "The faster, smaller Gemma 4: same features, quicker answers, a little less capable.",
        repo = "litert-community/gemma-4-E2B-it-litert-lm",
        revision = "1caa527c1e88b55f046fcd8d2b4265606680663d",
        file = "gemma-4-E2B-it.litertlm",
        sizeBytes = 2_588_147_712L,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        minRamGb = 5.5,
        defaultContext = 8192,
        maxContext = 32768,
        vision = true, audio = true, thinking = true, tools = true,
        template = GemmaTemplate.ID,
        quality = 0.36,
    )

    val all: List<OnDeviceModel> = listOf(GEMMA4_E4B, GEMMA4_E2B)

    fun byId(id: String?): OnDeviceModel? = all.firstOrNull { it.id == id }

    fun bySha(sha: String?): OnDeviceModel? = all.firstOrNull { it.sha256.equals(sha, ignoreCase = true) }

    /** Free space kept after a download, so the phone never fills up because of a model. */
    const val STORAGE_MARGIN = 1_000_000_000L

    fun fit(m: OnDeviceModel, d: DeviceSpec, installed: Boolean = false): Fit = when {
        !d.arm64 -> Fit.UNSUPPORTED
        !installed && d.freeStorageBytes < m.sizeBytes + STORAGE_MARGIN -> Fit.NO_STORAGE
        d.ramGb < m.minRamGb -> Fit.TIGHT
        else -> Fit.GOOD
    }

    /** The most capable model that runs well on this phone (Gemma 4 E4B on a Galaxy S24+), ignoring storage. */
    fun recommend(d: DeviceSpec): OnDeviceModel? =
        all.firstOrNull { d.arm64 && d.ramGb >= it.minRamGb } ?: all.lastOrNull()?.takeIf { d.arm64 && d.ramGb >= 4.0 }

    /** Friendly chip names for the phones people are likely to have. */
    fun chipName(soc: String): String? = when (soc.uppercase(Locale.ROOT)) {
        "SM8650" -> "Snapdragon 8 Gen 3"
        "SM8750" -> "Snapdragon 8 Elite"
        "SM8850" -> "Snapdragon 8 Elite Gen 5"
        "SM8550" -> "Snapdragon 8 Gen 2"
        "SM8635" -> "Snapdragon 8s Gen 3"
        "S5E9945" -> "Exynos 2400"
        "S5E9955" -> "Exynos 2500"
        "S5E9935" -> "Exynos 2200"
        else -> null
    }

    /** "3.7 GB" */
    fun size(bytes: Long): String = when {
        bytes >= 10_000_000_000L -> String.format(Locale.US, "%.0f GB", bytes / 1e9)
        bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
        bytes >= 1_000_000L -> String.format(Locale.US, "%.0f MB", bytes / 1e6)
        else -> String.format(Locale.US, "%.0f KB", bytes / 1e3)
    }
}
