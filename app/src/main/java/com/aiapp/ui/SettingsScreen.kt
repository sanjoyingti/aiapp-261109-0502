package com.aiapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aiapp.llm.OfflineModel
import com.aiapp.llm.ProviderId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                "API keys are stored encrypted on this phone only.",
                style = MaterialTheme.typography.bodyMedium,
            )
            ProviderId.entries.filter { it.needsKey }.forEach { KeyField(it, vm) }
            GithubTokenField(vm)
            OfflineSection(vm)
        }
    }
}

private fun keyUrl(p: ProviderId): String = when (p) {
    ProviderId.CLAUDE -> "console.anthropic.com/settings/keys"
    ProviderId.GEMINI -> "aistudio.google.com/apikey"
    ProviderId.OPENAI -> "platform.openai.com/api-keys"
    ProviderId.OPEN -> "openrouter.ai/keys, or just tap Sign in with OpenRouter above"
    ProviderId.OFFLINE -> ""
    ProviderId.FREE -> ""
    ProviderId.AUTO -> ""
}

@Composable
private fun KeyField(p: ProviderId, vm: ChatViewModel) {
    var text by remember(p) { mutableStateOf(vm.key(p)) }
    var note by remember(p) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (p == ProviderId.OPEN) {
            val uri = androidx.compose.ui.platform.LocalUriHandler.current
            HorizontalDivider()
            Text("Free open models (DeepSeek, Kimi, Qwen and more)", style = MaterialTheme.typography.titleMedium)
            when {
                vm.openRouterSigningIn -> {
                    Text("Finish signing in on the OpenRouter page, then come back here.")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = vm::cancelOpenRouterSignIn) { Text("Cancel") }
                }
                vm.key(p).isNotBlank() -> {
                    Text(
                        "✓ Connected. In Chat and Build pick Open models; models marked 🆓 are free (the list updates live).",
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutlinedButton(onClick = { vm.saveKey(p, "") }) { Text("Sign out") }
                }
                else -> {
                    Button(onClick = { vm.signInOpenRouter { link -> runCatching { uri.openUri(link) } } }) {
                        Text("Sign in with OpenRouter")
                    }
                    Text(
                        "One-time sign-in (Google works). No key to copy. Free models cost nothing; about 200 messages a day.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            vm.openRouterError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        if (p == ProviderId.OPEN && vm.key(p).isNotBlank()) return@Column
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                note = null
            },
            label = { Text("${p.label} API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Get one at ${keyUrl(p)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                vm.saveKey(p, text)
                note = "Saved"
            }) { Text("Save") }
            TextButton(onClick = {
                text = ""
                vm.saveKey(p, "")
                note = "Cleared"
            }) { Text("Clear") }
            note?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun OfflineSection(vm: ChatViewModel) {
    val recommended = vm.recommendedOffline()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Text("Offline models", style = MaterialTheme.typography.titleMedium)
        Text(
            "Run on this phone with no internet or API key. Your phone has about ${vm.deviceRamGb()} GB " +
                "of RAM, so ${recommended.name} is recommended. Keep the app open while it downloads; " +
                "an interrupted download resumes where it stopped.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        vm.offlineModels().forEach { OfflineRow(it, vm) }
        BrowseOfflineModels(vm)
        var showLink by remember { mutableStateOf(false) }
        TextButton(onClick = { showLink = !showLink }) { Text(if (showLink) "Hide" else "Add from a link (advanced)") }
        if (showLink) AddOfflineModel(vm)
    }
}

@Composable
private fun OfflineRow(m: OfflineModel, vm: ChatViewModel) {
    val progress = vm.downloads[m.id]
    val downloaded = vm.offlineDownloaded(m)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(m.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            when {
                downloaded -> "Downloaded · ready offline"
                progress != null -> "Downloading… ${(progress * 100).toInt()}%"
                m.approxSizeGb == 0.0 -> "From huggingface.co/${m.repo}"
                else -> "About ${m.approxSizeGb} GB · needs ${m.minRamGb} GB+ RAM"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                downloaded -> {
                    Button(onClick = { vm.useOffline(m) }) { Text("Use") }
                    OutlinedButton(onClick = { vm.deleteOffline(m) }) { Text("Delete") }
                }
                progress != null -> OutlinedButton(onClick = { vm.cancelDownload(m) }) { Text("Pause") }
                else -> {
                    Button(onClick = { vm.downloadOffline(m) }) { Text("Download") }
                    if (m.id.startsWith("hf-")) OutlinedButton(onClick = { vm.deleteOffline(m) }) { Text("Remove") }
                }
            }
        }
        vm.downloadErrors[m.id]?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun GithubTokenField(vm: ChatViewModel) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var showAdvanced by remember { mutableStateOf(false) }
    var clientId by remember { mutableStateOf(vm.githubClientId()) }
    var token by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("GitHub (for Build app)", style = MaterialTheme.typography.titleMedium)
        val code = vm.githubCode
        when {
            vm.githubToken().isNotBlank() -> {
                Text(
                    "Signed in as ${vm.githubUser.ifBlank { "your GitHub account" }}. " +
                        "New apps get their own repo on your account automatically.",
                )
                OutlinedButton(onClick = vm::signOutGithub) { Text("Sign out") }
            }
            code != null -> {
                Text("Enter this code on GitHub, then come back:")
                Text(code.userCode, style = MaterialTheme.typography.headlineMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(code.userCode)) }) { Text("Copy code") }
                    OutlinedButton(onClick = { uri.openUri(code.verificationUri) }) { Text("Open GitHub") }
                }
                Text("Waiting for you to approve…", style = MaterialTheme.typography.bodySmall)
            }
            else -> {
                Button(onClick = { vm.signInGithub { link -> uri.openUri(link) } }) { Text("Sign in with GitHub") }
                Text(
                    "You approve once on github.com; the app can then create repos and build APKs for you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        vm.githubLoginError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) "Hide advanced" else "Advanced") }
        if (showAdvanced) {
            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it },
                label = { Text("GitHub OAuth Client ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "From github.com/settings/developers → OAuth Apps (tick \"Enable Device Flow\").",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { vm.saveGithubClientId(clientId.trim()) }) { Text("Save Client ID") }
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Or paste a personal access token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Classic token with public_repo and workflow scopes, from github.com/settings/tokens.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = {
                vm.saveGithubToken(token)
                token = ""
            }, enabled = token.isNotBlank()) { Text("Save token") }
        }
    }
}

@Composable
private fun AddOfflineModel(vm: ChatViewModel) {
    var link by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var token by remember { mutableStateOf(vm.hfToken()) }
    var tokenNote by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Add a model", style = MaterialTheme.typography.titleSmall)
        Text(
            "Paste a Hugging Face link to any LiteRT-LM model (.litertlm), for example from huggingface.co/litert-community.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = link,
            onValueChange = {
                link = it
                error = null
            },
            label = { Text("Hugging Face link") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            error = vm.addOfflineModel(link)
            if (error == null) link = ""
        }, enabled = link.isNotBlank()) { Text("Add and download") }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        OutlinedTextField(
            value = token,
            onValueChange = {
                token = it
                tokenNote = null
            },
            label = { Text("Hugging Face token (optional)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Only needed for models that ask you to accept a licence. Create one at huggingface.co/settings/tokens.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                vm.saveHfToken(token)
                tokenNote = "Saved"
            }) { Text("Save token") }
            tokenNote?.let { Text("  $it", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

/** All free offline models, each with a one-tap Download button — no website needed. */
@Composable
private fun BrowseOfflineModels(vm: ChatViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("More free offline models", style = MaterialTheme.typography.titleSmall)
        val list = vm.browseList
        when {
            vm.browsing -> UploadBar("Loading the list…", -1f)
            list == null -> OutlinedButton(onClick = vm::browseOfflineModels) { Text("Show all free offline models") }
            list.isEmpty() -> Text("Couldn't load the list. Check your connection and try again.", style = MaterialTheme.typography.bodySmall)
            else -> list.take(40).forEach { m ->
                val added = vm.offlineModels().any { it.repo.equals(m.repo, true) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.repo.substringAfter('/'), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${m.repo.substringBefore('/')} · ${m.downloads} downloads",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (added) Text("Added", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    else Button(onClick = { vm.downloadFromBrowse(m.repo) }) { Text("Download") }
                }
            }
        }
    }
}
