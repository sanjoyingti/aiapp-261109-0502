package com.aiapp.ui

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Create tab: free, unlimited, no-key images. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen(vm: CreateViewModel, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    var prompt by rememberSaveable { mutableStateOf("") }
    var modelMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val pickPhoto = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.attach(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Create")
                        Text("Free · no key · unlimited", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !vm.video, onClick = { vm.video = false }, label = { Text("Image") })
                FilterChip(selected = vm.video, onClick = { vm.video = true; if (vm.shape == "square") vm.shape = "landscape" }, label = { Text("Video") })
            }
            if (vm.video) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = vm.videoKind == "story", onClick = { vm.videoKind = "story" }, label = { Text("Story video · unlimited") })
                    FilterChip(selected = vm.videoKind == "ai", onClick = { vm.videoKind = "ai" }, label = { Text("AI clip · daily limit") })
                }
                Text(
                    if (vm.videoKind == "story") {
                        "Free and unlimited: AI draws each scene, then your phone animates them into a cinematic video with camera moves, fades and captions."
                    } else {
                        "Real AI motion from open models (LTX-Video, Wan) on free servers. 3–5 s clips, a few per day; add a free Hugging Face token in Settings for more."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!vm.video || vm.videoKind == "story") Column {
                OutlinedButton(onClick = { modelMenu = true }) {
                    Text(vm.models.firstOrNull { it.id == vm.model }?.name ?: vm.model.ifBlank { "Loading models…" })
                }
                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    vm.models.forEach { m ->
                        DropdownMenuItem(text = { Text(m.name) }, onClick = {
                            vm.model = m.id
                            modelMenu = false
                        })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val shapes = if (vm.video) listOf("landscape" to "Landscape 16:9", "portrait" to "Portrait 9:16")
                else listOf("square" to "Square", "portrait" to "Portrait", "landscape" to "Landscape")
                shapes.forEach { (id, label) ->
                    FilterChip(selected = vm.shape == id, onClick = { vm.shape = id }, label = { Text(label) })
                }
            }
            if (vm.video && vm.videoKind == "story") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Scenes:", style = MaterialTheme.typography.bodyMedium)
                    listOf(4, 6, 8).forEach { n ->
                        FilterChip(selected = vm.scenes == n, onClick = { vm.scenes = n }, label = { Text("$n (${n * 4}s)") })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Captions", modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = vm.captions, onCheckedChange = { vm.captions = it })
                }
            }

            // Attach a photo to base the new image on.
            if (!vm.video) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickPhoto.launch(arrayOf("image/*")) }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(if (vm.reference == null) " Attach photo" else " Change photo")
                }
                vm.reference?.let { ref ->
                    val bmp = remember(ref.path) { BitmapFactory.decodeFile(ref.path, BitmapFactory.Options().apply { inSampleSize = 4 }) }
                    bmp?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp)) }
                    TextButton(onClick = { vm.reference = null }) { Text("Remove") }
                }
            }
            vm.attachProgress?.let { UploadBar("Attaching photo…", it) }
            if (vm.reference != null) {
                Text("✓ Photo ready — describe what to change or make from it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text(if (vm.video) "Describe the video" else "Describe the image") },
                placeholder = { Text("A cinematic photo of a tea garden in Assam at sunrise, mist, golden light") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            if (vm.busy) {
                UploadBar(vm.status ?: "Working…", vm.progress ?: -1f)
                OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
            } else {
                Button(
                    onClick = { if (vm.video) vm.generateVideo(prompt) else vm.generate(prompt) },
                    enabled = prompt.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (vm.video) "Create video" else "Create image") }
                vm.status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            vm.resultBytes?.let { bytes ->
                val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                bmp?.let {
                    Image(it.asImageBitmap(), contentDescription = "Created image", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                }
            }
            vm.resultUri?.let { uri ->
                val mime = if (vm.resultIsVideo) "video/mp4" else "image/*"
                if (vm.resultIsVideo) {
                    // Plays right here; tap for controls.
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            android.widget.VideoView(ctx).apply {
                                setMediaController(android.widget.MediaController(ctx).also { it.setAnchorView(this) })
                                setVideoURI(uri)
                                setOnPreparedListener { mp -> mp.isLooping = true; start() }
                            }
                        },
                        update = { v -> if (v.tag != uri) { v.tag = uri; v.setVideoURI(uri) } },
                        modifier = Modifier.fillMaxWidth().aspectRatio(if (vm.shape == "portrait") 9f / 16f else 16f / 9f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }
                    }) { Text(if (vm.resultIsVideo) "Open in player" else "Open") }
                    OutlinedButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        runCatching { context.startActivity(Intent.createChooser(share, "Share")) }
                    }) { Text("Share") }
                }
                OutlinedButton(onClick = { vm.saveResultToGallery() }, enabled = !vm.inGallery) {
                    Text(if (vm.inGallery) "In Gallery ✓" else "Save to Gallery")
                }
            }
            Text(
                "Free images by Pollinations.AI (open models, no key). Story videos are made on your phone, unlimited. Results stay inside the app and are removed when you uninstall, unless you tap Save to Gallery. AI clips use free Hugging Face servers with a daily limit.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

