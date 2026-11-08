package com.aiapp.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Busy or rate-limited servers: worth waiting a moment and trying again. */
fun ProviderException.isTemporary() = code == 429 || code == 500 || code == 502 || code == 503 || code == 504 ||
    (message?.contains("high demand", true) == true) || (message?.contains("overloaded", true) == true)

/** Daily/monthly free allowance is used up. */
fun ProviderException.isQuotaUsedUp() = message?.let {
    it.contains("quota", true) || it.contains("exhausted", true) || it.contains("per day", true) ||
        it.contains("limit exceeded", true) || it.contains("free-models-per-day", true)
} == true

/** The chosen model can't be used at all (no longer free, no credits, removed): switch right away. */
fun ProviderException.needsOtherModel() = code == 402 ||
    (message?.contains("unavailable for free", true) == true) ||
    (message?.contains("no endpoints", true) == true) ||
    (message?.contains("insufficient credits", true) == true) ||
    (message?.contains("not a valid model", true) == true)

/**
 * Runs the request; if the server is busy or rate-limited before any text arrives, waits and tries
 * again (2 s, 5 s, 10 s). If [fallback] is given, the last try uses that model instead.
 */
fun resilient(
    attempt: (model: String) -> Flow<String>,
    model: String,
    fallback: (suspend () -> String?)? = null,
    onRetry: (String) -> Unit = {},
): Flow<String> = flow {
    val waits = listOf(2_000L, 5_000L, 10_000L)
    var current = model
    for (i in 0..waits.size) {
        var emitted = false
        try {
            attempt(current).collect {
                emitted = true
                emit(it)
            }
            return@flow
        } catch (e: ProviderException) {
            if (!emitted && e.needsOtherModel() && fallback != null) {
                val other = fallback()
                if (other != null && other != current) {
                    onRetry("$current isn't available — switching to $other")
                    current = other
                    continue
                }
            }
            if (e.isQuotaUsedUp()) throw e // waiting won't help; the next model takes over
            if (emitted || !e.isTemporary() || i == waits.size) throw e
            if (i == waits.lastIndex && fallback != null) {
                val other = fallback()
                if (other != null && other != current) {
                    onRetry("$current is busy — switching to $other")
                    current = other
                    continue
                }
            }
            onRetry("Server busy — retrying in ${waits[i] / 1000} s")
            delay(waits[i])
        }
    }
}

/** A different, usually less busy model from the same provider (Flash/Haiku/mini first). */
suspend fun alternativeModel(provider: ProviderId, apiKey: String, current: String): String? {
    if (provider == ProviderId.OFFLINE) return null
    val list = runCatching { Providers.rankedModels(provider, apiKey) }.getOrNull() ?: return null
    // Open models: stay on free models so nothing gets charged.
    if (provider == ProviderId.OPEN) {
        return list.firstOrNull { it.id != current && it.name.startsWith(FREE_MARK) }?.id
    }
    val ids = list.map { it.id }
    val fast = listOf("flash", "haiku", "mini", "sonnet")
    val bad = listOf("lite", "image", "tts", "audio", "live", "realtime", "embed", "transcribe", "search")
    return ids.firstOrNull { id -> id != current && fast.any { id.contains(it) } && bad.none { id.contains(it) } }
        ?: ids.firstOrNull { it != current && bad.none { b -> it.contains(b) } }
}
