package com.aiapp.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The Codespace (VS Code) shown inside the app. It keeps running while you go back to the app,
 * so you can switch between the two at any time without reconnecting.
 */
object CodespaceState {
    /** True while the Codespace screen is on top. */
    var visible by mutableStateOf(false)
    /** "owner/repo" of the project whose codespace is loaded. */
    var repo by mutableStateOf<String?>(null)
    var title by mutableStateOf("Codespace")
    var progress by mutableStateOf(0)

    /** The live VS Code page, kept alive in the background. */
    var web: WebView? = null
        private set
    /** Whether a Codespace is running in the background (shows the "Return to Codespace" bar). */
    var alive by mutableStateOf(false)
        private set

    private fun prefs(context: Context) = context.getSharedPreferences("codespaces", Context.MODE_PRIVATE)

    fun chooserUrl(path: String) = "https://codespaces.new/$path?quickstart=1"

    /** Opens (or returns to) the project's codespace. Opens it directly if we've been there before. */
    fun open(context: Context, repoUrl: String) {
        val path = repoUrl.removePrefix("https://github.com/").trimEnd('/')
        if (path != repo || web == null) {
            close()
            repo = path
            web = create(context, prefs(context).getString(path, null) ?: chooserUrl(path))
            alive = true
        }
        visible = true
    }

    /** Back to the app; VS Code keeps running. */
    fun hide() {
        visible = false
    }

    /** Back to VS Code exactly where you left it. */
    fun show() {
        if (web != null) visible = true
    }

    /** Fully closes the Codespace page. */
    fun close() {
        visible = false
        alive = false
        web?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            CookieManager.getInstance().flush()
            it.destroy()
        }
        web = null
    }

    fun forgetSaved(context: Context) {
        val r = repo ?: return
        prefs(context).edit().remove(r).apply()
        web?.loadUrl(chooserUrl(r))
    }

    private fun rememberUrl(context: Context, u: String) {
        val r = repo ?: return
        prefs(context).edit().putString(r, u).apply()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(context: Context, url: String): WebView = WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        // Look like normal Chrome, so GitHub serves the full editor.
        settings.userAgentString = settings.userAgentString.replace("; wv", "").replace(Regex("Version/\\S+ "), "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        val appContext = context.applicationContext
        webViewClient = object : WebViewClient() {
            // Once VS Code opens (https://<name>.github.dev), remember it: next time it opens directly.
            override fun doUpdateVisitedHistory(view: WebView, u: String?, isReload: Boolean) {
                val host = u?.let { android.net.Uri.parse(it).host }.orEmpty()
                if (host.endsWith(".github.dev") && host != "github.dev") rememberUrl(appContext, u!!)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme.orEmpty()
                return scheme != "http" && scheme != "https"
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                CodespaceState.progress = newProgress
            }

            override fun onReceivedTitle(view: WebView, t: String?) {
                if (!t.isNullOrBlank()) CodespaceState.title = t
            }

            // Sign-in pop-ups open in the same view.
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val popup = WebView(view.context)
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        view.loadUrl(request.url.toString())
                        return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }
        loadUrl(url)
    }
}

/** VS Code on top of the app. "App" goes back to the app with VS Code still running. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodespaceScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val web = CodespaceState.web ?: return

    BackHandler {
        if (web.canGoBack()) web.goBack() else CodespaceState.hide()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(CodespaceState.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = CodespaceState::hide) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the app")
                    }
                },
                actions = {
                    TextButton(onClick = CodespaceState::hide) { Text("App") }
                    TextButton(onClick = { web.reload() }) { Text("Reload") }
                    TextButton(onClick = { CodespaceState.forgetSaved(context) }) { Text("Switch") }
                    TextButton(onClick = CodespaceState::close) { Text("Close") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            val p = CodespaceState.progress
            if (p in 1..99) LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    // Reuse the running page: no reconnect.
                    (web.parent as? ViewGroup)?.removeView(web)
                    web
                },
                onRelease = { w -> (w.parent as? ViewGroup)?.removeView(w) },
            )
        }
    }
}

/** Slim bar in the app while a Codespace runs in the background. */
@Composable
fun CodespaceReturnBar() {
    if (!CodespaceState.alive || CodespaceState.visible) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "● Codespace running: ${CodespaceState.repo?.substringAfter('/') ?: ""}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Button(onClick = CodespaceState::show) { Text("Open") }
            TextButton(onClick = CodespaceState::close) { Text("Close") }
        }
    }
}
