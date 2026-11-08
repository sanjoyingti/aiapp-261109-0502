package com.aiapp.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * "Auto": always uses the most powerful model you can reach right now, then the next most powerful,
 * and so on. When one runs out of free quota, is busy or offline, the next one carries on by itself.
 *
 * Order (only sources you have set up are used):
 * Claude → ChatGPT → Gemini Pro → strongest free open models (OpenRouter) → Gemini Flash →
 * other free open models → Free (no key) → Offline models on the phone.
 */
object AutoChain {
    const val AUTO_MODEL = "auto"

    /** Reads saved keys. Set once at startup by [Providers.init]. */
    var keyFor: (ProviderId) -> String = { "" }

    private var cache: List<Pair<ProviderId, String>> = emptyList()
    private var cachedAt = 0L

    private val bad = listOf("image", "tts", "audio", "live", "realtime", "embed", "transcribe", "search", "lite", "vision", "deep-think", "batch", "omni")

    private suspend fun ranked(p: ProviderId): List<ModelInfo> =
        runCatching { Providers.rankedModels(p, keyFor(p)) }.getOrDefault(emptyList())
            .filter { m -> bad.none { m.id.lowercase().contains(it) } }

    /** Every reachable model, strongest first. Cached for 10 minutes. */
    suspend fun candidates(): List<Pair<ProviderId, String>> {
        val now = System.currentTimeMillis()
        if (cache.isNotEmpty() && now - cachedAt < 10 * 60_000) return cache
        val out = mutableListOf<Pair<ProviderId, String>>()
        fun has(p: ProviderId) = keyFor(p).isNotBlank()

        if (has(ProviderId.CLAUDE)) ranked(ProviderId.CLAUDE).firstOrNull()?.let { out += ProviderId.CLAUDE to it.id }
        if (has(ProviderId.OPENAI)) ranked(ProviderId.OPENAI).firstOrNull()?.let { out += ProviderId.OPENAI to it.id }
        val gemini = if (has(ProviderId.GEMINI)) ranked(ProviderId.GEMINI) else emptyList()
        gemini.firstOrNull { it.id.contains("pro") }?.let { out += ProviderId.GEMINI to it.id }
        val freeOpen = if (has(ProviderId.OPEN)) {
            ranked(ProviderId.OPEN).filter { it.name.startsWith(FREE_MARK) }
        } else emptyList()
        freeOpen.take(2).forEach { out += ProviderId.OPEN to it.id }
        gemini.firstOrNull { it.id.contains("flash") }?.let { out += ProviderId.GEMINI to it.id }
        freeOpen.drop(2).take(3).forEach { out += ProviderId.OPEN to it.id }
        ranked(ProviderId.FREE).take(2).forEach { out += ProviderId.FREE to it.id }
        runCatching {
            Providers.offline.allModels().filter { Providers.offline.isDownloaded(it) }
                .forEach { out += ProviderId.OFFLINE to it.id }
        }
        cache = out.distinct()
        cachedAt = now
        return cache
    }

    fun forget() {
        cache = emptyList()
    }

    private fun label(p: ProviderId, model: String) =
        if (p == ProviderId.OFFLINE) "Offline model" else "${p.label} · ${model.substringAfterLast('/').removeSuffix(":free")}"

    /**
     * Streams from [first] (the model you picked, or null for Auto), and if it can't answer,
     * moves down the chain. Text already shown is never thrown away: a switch only happens
     * before the first word arrives.
     */
    fun stream(
        first: Pair<ProviderId, String>?,
        messages: List<ChatMessage>,
        effort: Effort,
        system: String,
        onStatus: (String) -> Unit = {},
    ): Flow<String> = flow {
        val chain = (listOfNotNull(first) + candidates()).distinct()
        if (chain.isEmpty()) throw ProviderException(503, "No model available. Add a key in Settings or download an offline model.")
        var lastError: Throwable? = null
        for ((index, pair) in chain.withIndex()) {
            val (p, model) = pair
            if (index > 0) onStatus("Switching to ${label(p, model)}…")
            var emitted = false
            try {
                resilient(
                    attempt = { m -> Providers.get(p).stream(keyFor(p), m, messages, effort, system) },
                    model = model,
                    onRetry = onStatus,
                ).collect {
                    emitted = true
                    emit(it)
                }
                return@flow
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (emitted) throw e
                lastError = e
                onStatus("${label(p, model)} unavailable (${e.message?.take(60) ?: "error"})")
            }
        }
        forget()
        throw lastError ?: ProviderException(503, "Every model is unavailable right now. Try again in a minute.")
    }
}

/** The "Auto" choice in the provider picker. */
class AutoProvider : LlmProvider {
    override val id = ProviderId.AUTO

    override suspend fun listModels(apiKey: String): List<ModelInfo> =
        listOf(ModelInfo(AutoChain.AUTO_MODEL, "⚡ Strongest first, then next"))

    override fun stream(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        effort: Effort,
        system: String,
    ): Flow<String> = AutoChain.stream(null, messages, effort, system)
}
