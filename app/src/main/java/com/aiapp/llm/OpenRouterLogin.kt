package com.aiapp.llm

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.coroutines.coroutineContext

/**
 * "Sign in with OpenRouter" (OAuth PKCE). The user logs in once in the browser; OpenRouter sends
 * the browser back to a tiny server on this phone (localhost), and the app swaps the code for a key.
 * No key to copy and paste.
 */
object OpenRouterLogin {

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /** Opens the sign-in page with [open] and returns the new API key. */
    suspend fun signIn(packageName: String, open: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val verifier = b64(ByteArray(48).also { SecureRandom().nextBytes(it) })
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val code = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 2000
            val callback = "http://localhost:${server.localPort}/callback"
            val url = "https://openrouter.ai/auth?callback_url=" + Uri.encode(callback) +
                "&code_challenge=$challenge&code_challenge_method=S256"
            withContext(Dispatchers.Main) { open(url) }
            val deadline = System.currentTimeMillis() + 10 * 60 * 1000
            var found: String? = null
            while (found == null) {
                coroutineContext.ensureActive()
                if (System.currentTimeMillis() > deadline) throw ProviderException(408, "Sign-in timed out. Tap Sign in again.")
                val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                socket.use { s ->
                    s.soTimeout = 5000
                    val line = runCatching { s.getInputStream().bufferedReader().readLine() }.getOrNull().orEmpty()
                    val path = line.split(" ").getOrNull(1).orEmpty()
                    found = Uri.parse("http://localhost$path").getQueryParameter("code")
                    val back = "intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=$packageName;end"
                    val html = if (found != null) {
                        "<html><head><meta name=viewport content='width=device-width'></head>" +
                            "<body style='font-family:sans-serif;text-align:center;padding:40px'>" +
                            "<h2>✓ Signed in</h2><p>Go back to AI App.</p>" +
                            "<p><a href='$back' style='font-size:20px'>Return to AI App</a></p>" +
                            "<script>setTimeout(function(){location.href='$back'},300)</script></body></html>"
                    } else {
                        "<html><body>Waiting for sign-in…</body></html>"
                    }
                    val bytes = html.toByteArray()
                    s.getOutputStream().apply {
                        write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(),
                        )
                        write(bytes)
                        flush()
                    }
                }
            }
            found!!
        }
        val body = buildJsonObject {
            put("code", code)
            put("code_verifier", verifier)
            put("code_challenge_method", "S256")
        }.toString()
        val resp = Http.postJson("https://openrouter.ai/api/v1/auth/keys", emptyMap(), body)
        (resp["key"] as? JsonPrimitive)?.contentOrNull ?: throw ProviderException(500, "OpenRouter didn't return a key. Try again.")
    }
}
