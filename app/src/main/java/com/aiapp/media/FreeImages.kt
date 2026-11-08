package com.aiapp.media

import com.aiapp.llm.Attachment
import com.aiapp.llm.Http
import com.aiapp.llm.ModelInfo
import com.aiapp.llm.ProviderException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.Base64

/** Free image generation with no key or signup (Pollinations.AI: Flux and other open models). */
object FreeImages {
    private const val IMAGE = "https://image.pollinations.ai"
    private const val TEXT = "https://text.pollinations.ai"

    private val fallback = listOf(ModelInfo("flux", "Flux (free)"), ModelInfo("turbo", "Turbo (free, fast)"))

    suspend fun models(): List<ModelInfo> {
        val list = runCatching { Http.getJsonArray("$IMAGE/models") }.getOrNull() ?: return fallback
        val models = list.mapNotNull { el ->
            when (el) {
                is JsonPrimitive -> el.contentOrNull?.let { ModelInfo(it, it.replaceFirstChar(Char::uppercase) + " (free)") }
                is JsonObject -> {
                    val name = (el["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val tier = (el["tier"] as? JsonPrimitive)?.contentOrNull
                    if (tier != null && tier != "anonymous") null
                    else ModelInfo(name, name.replaceFirstChar(Char::uppercase) + " (free)")
                }
                else -> null
            }
        }
        return models.ifEmpty { fallback }.sortedBy { if (it.id == "flux") 0 else 1 }
    }

    /**
     * Makes an image. With [reference], a free vision model first describes the photo, and the new
     * image is made from that description plus your prompt.
     */
    suspend fun image(prompt: String, model: String, width: Int, height: Int, reference: Attachment?, onStatus: (String) -> Unit): ByteArray {
        var fullPrompt = prompt.trim()
        if (reference != null && reference.isImage) {
            onStatus("Looking at your photo…")
            val described = runCatching { describe(reference) }.getOrNull()
            if (!described.isNullOrBlank()) fullPrompt = "$described. Change: $fullPrompt"
        }
        onStatus("Creating image…")
        val q = URLEncoder.encode(fullPrompt.take(1500), "UTF-8").replace("+", "%20")
        val seed = (1..1_000_000).random()
        val url = "$IMAGE/prompt/$q?model=${URLEncoder.encode(model, "UTF-8")}&width=$width&height=$height" +
            "&seed=$seed&nologo=true&private=true&enhance=true&referrer=aiapp-android"
        return withContext(Dispatchers.IO) {
            var last: Exception? = null
            for (attempt in 0 until 3) {
                try {
                    Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        val type = r.header("Content-Type").orEmpty()
                        if (r.isSuccessful && type.startsWith("image/")) return@withContext r.body!!.bytes()
                        throw ProviderException(r.code, if (r.code == 429) "The free image server is busy. Trying again…" else "Image failed (HTTP ${r.code}).")
                    }
                } catch (e: Exception) {
                    last = e
                    kotlinx.coroutines.delay(3_000L * (attempt + 1))
                }
            }
            throw last ?: ProviderException(500, "Image failed.")
        }
    }

    /** Describes a photo with a free vision model, so a new image can be based on it. */
    private suspend fun describe(a: Attachment): String {
        val data = Base64.getEncoder().encodeToString(File(a.path).readBytes())
        val body = buildJsonObject {
            put("model", "openai")
            put("private", true)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", "Describe this image in one detailed paragraph for an image generator: subject, appearance, setting, style, colours, lighting, camera angle. No preamble.")
                        }
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", "data:${a.mime};base64,$data") }
                        }
                    }
                }
            }
        }.toString()
        val o = Http.postJson("$TEXT/openai", emptyMap(), body)
        val choice = (o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        return ((choice?.get("message") as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull.orEmpty()
    }
}
