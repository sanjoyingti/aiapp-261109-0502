package com.aiapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aiapp.ui.AppTheme
import com.aiapp.ui.BuildScreen
import com.aiapp.ui.BuildViewModel
import com.aiapp.ui.ChatScreen
import com.aiapp.ui.ChatViewModel
import com.aiapp.ui.CreateScreen
import com.aiapp.ui.CreateViewModel
import com.aiapp.ui.HistoryScreen
import com.aiapp.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // App hidden or memory low: drop the offline model and image cache (they reload when needed).
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            com.aiapp.ui.ImageLoader.clear()
            if (!com.aiapp.builder.BuildRunner.running) com.aiapp.llm.Providers.offlineIfLoaded?.release()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                val vm: ChatViewModel = viewModel()
                var screen by rememberSaveable { mutableStateOf("chat") }
                BackHandler(enabled = screen != "chat") { screen = "chat" }
                val buildVm: BuildViewModel = viewModel()
                val createVm: CreateViewModel = viewModel()
                // VS Code (Codespace) on top when open; it keeps running while you use the app.
                if (com.aiapp.ui.CodespaceState.visible) {
                    com.aiapp.ui.CodespaceScreen()
                    return@AppTheme
                }
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier.fillMaxSize(),
                ) {
                    if (com.aiapp.ui.CodespaceState.alive) {
                        androidx.compose.foundation.layout.Box(
                            androidx.compose.ui.Modifier.windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars),
                        ) { com.aiapp.ui.CodespaceReturnBar() }
                    }
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)) {
                when (screen) {
                    "chat" -> ChatScreen(
                        vm,
                        onOpenSettings = { screen = "settings" },
                        onOpenBuild = { screen = "build" },
                        onOpenHistory = { screen = "history" },
                        onOpenCreate = { screen = "create" },
                    )
                    "history" -> HistoryScreen(vm, onBack = { screen = "chat" })
                    "create" -> CreateScreen(createVm, onBack = { screen = "chat" }, onOpenSettings = { screen = "settings" })
                    "build" -> BuildScreen(vm, buildVm, onBack = { screen = "chat" }, onOpenSettings = { screen = "settings" })
                    else -> SettingsScreen(vm, onBack = { screen = "chat" })
                }
                    }
                }
            }
        }
    }
}
