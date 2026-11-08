package com.aiapp.media

import com.aiapp.llm.Http
import com.aiapp.llm.ProviderException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Free AI video from open models (LTX-Video, Wan) running on free Hugging Face Spaces. Each Space's
 * API is discovered at runtime, so the app adapts when a Space changes. Free GPU time is limited
 * per day; a free Hugging Face token (Settings) raises the limit.
 */
object HfVideo {
    /** Spaces tried in order; the first that works is used. */
    val defaultSpaces = listOf(
        "Lightricks/ltx-video-distilled",
        "Wan-AI/Wan-2.2-5B",
        "Lightricks/LTX-Video-Playground",
    )

    private val jsonType = "application/json".toMediaType()

    private fun host(space: String) =
        "https://" + space.trim().lowercase().replace("/", "-").replace(".", "-").replace("_", "-") + ".hf.space"

    private fun headers(token: String) = if (token.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token")

    private suspend fun get(url: String, token: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            Http.client.newCall(Request.Builder().url(url).apply { headers(token).forEach { (k, v) -> header(k, v) } }.build())
                .execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        }.getOrNull()
    }

    private data class Endpoint(val prefix: String, val name: String, val data: JsonArray)

    /** Finds a text-to-video endpoint and builds its inputs (your prompt + the Space's own defaults). */
    private suspend fun discover(space: String, token: String, prompt: String, portrait: Boolean): Endpoint? {
        val h = host(space)
        for (prefix in listOf("/gradio_api", "")) {
            val text = get("$h$prefix/info", token) ?: continue
            val info = runCatching { Http.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
            val named = info["named_endpoints"] as? JsonObject ?: continue
            val candidates = named.entries.mapNotNull { (name, ep) ->
                val o = ep as? JsonObject ?: return@mapNotNull null
                val params = (o["parameters"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                val returns = (o["returns"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                val returnsVideo = returns.any { (it["component"] as? JsonPrimitive)?.contentOrNull?.contains("Video", true) == true }
                val hasPrompt = params.any { p -> label(p).contains("prompt") && !label(p).contains("negative") }
                if (!returnsVideo || !hasPrompt) return@mapNotNull null
                // Prefer text-to-video endpoints.
                val score = (if (name.contains("text") || name.contains("t2v")) 0 else 1) +
                    params.count { requiresFile(it) } * 5
                Triple(name, params, score)
            }.sortedBy { it.third }
            val (name, params, _) = candidates.firstOrNull() ?: continue
            val data = buildJsonArray {
                params.forEach { p -> add(valueFor(p, prompt, portrait)) }
            }
            return Endpoint(prefix, name, data)
        }
        return null
    }

    private fun label(p: JsonObject) = (
        ((p["parameter_name"] as? JsonPrimitive)?.contentOrNull ?: "") + " " +
            ((p["label"] as? JsonPrimitive)?.contentOrNull ?: "")
        ).lowercase()

    private fun requiresFile(p: JsonObject): Boolean {
        val comp = (p["component"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val hasDefault = (p["parameter_has_default"] as? JsonPrimitive)?.booleanOrNull == true
        return (comp.contains("Image", true) || comp.contains("Video", true)) && !hasDefault
    }

    private fun valueFor(p: JsonObject, prompt: String, portrait: Boolean): JsonElement {
        val l = label(p)
        val default = p["parameter_default"] ?: JsonNull
        val comp = (p["component"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when {
            l.contains("negative") -> default.takeIf { it !is JsonNull }
                ?: JsonPrimitive("worst quality, blurry, jittery, distorted, watermark, text")
            l.contains("prompt") -> JsonPrimitive(prompt)
            l.contains("mode") && comp.contains("Radio", true) || l.trim().startsWith("mode") -> JsonPrimitive("text-to-video")
            (l.contains("height")) && default is JsonPrimitive && portrait -> swapDim(p, wanted = "height")
            (l.contains("width")) && default is JsonPrimitive && portrait -> swapDim(p, wanted = "width")
            l.contains("randomize") && l.contains("seed") -> JsonPrimitive(true)
            comp.contains("Image", true) || comp.contains("Video", true) -> default
            else -> default
        }
    }

    /** Portrait: use the larger default for height and the smaller for width. */
    private fun swapDim(p: JsonObject, wanted: String): JsonElement {
        val d = (p["parameter_default"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: return p["parameter_default"] ?: JsonNull
        // Typical landscape defaults are 704×512 or 1152×768; for portrait swap them.
        val swapped = when (wanted) {
            "height" -> if (d < 700) 704.0 else d
            else -> if (d > 600) 512.0 else d
        }
        return JsonPrimitive(swapped.toInt())
    }

    /**
     * Makes a short AI video and returns the MP4 bytes. [onStatus] gets queue/progress messages.
     * Tries each Space in [spaces] until one works.
     */
    suspend fun generate(
        prompt: String,
        portrait: Boolean,
        token: String,
        spaces: List<String> = defaultSpaces,
        onStatus: (String) -> Unit,
    ): ByteArray {
        var lastError = "No free video server is available right now."
        for (space in spaces) {
            try {
                onStatus("Connecting to $space…")
                val ep = discover(space, token, prompt, portrait) ?: continue
                return run(space, ep, token, onStatus)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: lastError
            }
        }
        throw ProviderException(503, friendly(lastError))
    }

    private fun friendly(msg: String): String = when {
        msg.contains("quota", true) || msg.contains("ZeroGPU", true) || msg.contains("exceeded", true) ->
            "Today's free AI video limit is used up. Add a free Hugging Face token in Settings for more, try again later, or make a Story video (unlimited)."
        else -> msg
    }

    private suspend fun run(space: String, ep: Endpoint, token: String, onStatus: (String) -> Unit): ByteArray = withContext(Dispatchers.IO) {
        val h = host(space)
        val callUrl = "$h${ep.prefix}/call${if (ep.name.startsWith("/")) ep.name else "/" + ep.name}"
        val start = Request.Builder().url(callUrl)
            .apply { headers(token).forEach { (k, v) -> header(k, v) } }
            .post(buildJsonObject { put("data", ep.data) }.toString().toRequestBody(jsonType))
            .build()
        val eventId = Http.client.newCall(start).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ProviderException(r.code, "$space: HTTP ${r.code}")
            (Http.json.parseToJsonElement(t).jsonObject["event_id"] as? JsonPrimitive)?.contentOrNull
                ?: throw ProviderException(500, "$space didn't start the job")
        }
        onStatus("In the queue on $space…")
        val began = System.currentTimeMillis()
        var result: JsonElement? = null
        var error: String? = null
        Http.client.newCall(
            Request.Builder().url("$callUrl/$eventId").apply { headers(token).forEach { (k, v) -> header(k, v) } }.build(),
        ).execute().use { r ->
            if (!r.isSuccessful) throw ProviderException(r.code, "$space: HTTP ${r.code}")
            val source = r.body!!.source()
            var event = ""
            while (!source.exhausted()) {
                ensureActive()
                val line = source.readUtf8Line() ?: break
                when {
                    line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        val data = line.removePrefix("data:").trim()
                        val secs = (System.currentTimeMillis() - began) / 1000
                        when (event) {
                            "complete" -> result = runCatching { Http.json.parseToJsonElement(data) }.getOrNull()
                            "error" -> error = data.trim('"').ifBlank { "The video server reported an error." }
                            "generating" -> onStatus("Generating AI video… ${secs}s")
                            "heartbeat" -> onStatus("Working… ${secs}s")
                        }
                    }
                }
                if (result != null || error != null) break
            }
        }
        error?.let { throw ProviderException(500, it) }
        val url = findVideoUrl(result, h, ep.prefix) ?: throw ProviderException(500, "$space returned no video")
        onStatus("Downloading video…")
        Http.client.newCall(Request.Builder().url(url).apply { headers(token).forEach { (k, v) -> header(k, v) } }.build())
            .execute().use { r ->
                if (!r.isSuccessful) throw ProviderException(r.code, "Video download failed (HTTP ${r.code})")
                r.body!!.bytes()
            }
    }

    /** Finds the video file URL anywhere in the result (Gradio wraps it differently by version). */
    private fun findVideoUrl(e: JsonElement?, host: String, prefix: String): String? = when (e) {
        is JsonArray -> e.firstNotNullOfOrNull { findVideoUrl(it, host, prefix) }
        is JsonObject -> {
            val url = (e["url"] as? JsonPrimitive)?.contentOrNull
            val path = (e["path"] as? JsonPrimitive)?.contentOrNull
            when {
                url != null && (url.contains(".mp4") || url.contains(".webm") || url.contains("/file=")) -> url
                path != null && (path.endsWith(".mp4") || path.endsWith(".webm")) -> "$host$prefix/file=$path"
                else -> e.values.firstNotNullOfOrNull { findVideoUrl(it, host, prefix) }
            }
        }
        else -> null
    }
}
