package com.aiapp.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Free models with no key and no signup (DeepSeek, GPT, Mistral and others), served by
 * Pollinations.AI. Works straight after installing the app.
 */
class FreeProvider : LlmProvider {
    override val id = ProviderId.FREE

    private val base = "https://text.pollinations.ai"

    /** Used when the model list can't be loaded. */
    private val fallbackModels = listOf(
        ModelInfo("openai", "GPT (free)"),
        ModelInfo("deepseek", "DeepSeek (free)"),
        ModelInfo("mistral", "Mistral (free)"),
    )

    override suspend fun listModels(apiKey: String): List<ModelInfo> {
        val list = runCatching { Http.getJsonArray("$base/models") }.getOrNull() ?: return fallbackModels
        val models = list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull (el as? JsonPrimitive)?.contentOrNull?.let { ModelInfo(it, it) }
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            // Only models open to everyone without a key or sign-in.
            val tier = (o["tier"] as? JsonPrimitive)?.contentOrNull
            if (tier != null && tier != "anonymous") return@mapNotNull null
            val output = (o["output_modalities"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (output != null && "text" !in output) return@mapNotNull null
            if ((o["uncensored"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
            val desc = (o["description"] as? JsonPrimitive)?.contentOrNull
            ModelInfo(name, (desc?.take(48) ?: name) + " · free")
        }
        if (models.isEmpty()) return fallbackModels
        // Strongest well-known families first.
        val order = listOf("deepseek", "openai-large", "openai", "qwen", "mistral", "llama", "gemini")
        return models.sortedBy { m -> order.indexOfFirst { m.id.contains(it) }.let { if (it < 0) 99 else it } }
    }

    override fun stream(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        effort: Effort,
        system: String,
    ): Flow<String> = fallbackOn400(
        primary = { request(model, messages, system, stream = true) },
        fallback = { request(model, messages, system, stream = false) },
    )

    private fun body(model: String, messages: List<ChatMessage>, system: String, stream: Boolean) = buildJsonObject {
        put("model", model)
        put("stream", stream)
        put("private", true) // don't show prompts in Pollinations' public feed
        put("referrer", "aiapp-android")
        putJsonArray("messages") {
            addJsonObject {
                put("role", "system")
                put("content", system)
            }
            val withFiles = AttachmentContent.binaryIndices(messages)
            messages.forEachIndexed { i, m ->
                addJsonObject {
                    put("role", if (m.role == Role.USER) "user" else "assistant")
                    put("content", AttachmentContent.openAi(m, i in withFiles))
                }
            }
        }
    }.toString()

    private fun request(model: String, messages: List<ChatMessage>, system: String, stream: Boolean): Flow<String> {
        val json = body(model, messages, system, stream)
        if (stream) {
            return Http.sse("$base/openai", emptyMap(), json).mapNotNull { data -> parse(data) }
        }
        return flow {
            val o = Http.postJson("$base/openai", emptyMap(), json)
            val choice = (o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            val text = ((choice?.get("message") as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull
            if (!text.isNullOrEmpty()) emit(text)
        }
    }

    private fun parse(data: String): String? {
        val o = runCatching { Http.json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
        (o["error"] as? JsonObject)?.let { err ->
            throw ProviderException(500, (err["message"] as? JsonPrimitive)?.contentOrNull ?: "Model error")
        }
        val choice = (o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val delta = choice["delta"] as? JsonObject ?: return null
        val content = (delta["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val reasoning = ((delta["reasoning_content"] ?: delta["reasoning"]) as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when {
            content.isNotEmpty() -> content
            reasoning.isNotEmpty() -> thought(reasoning)
            else -> null
        }
    }
}
