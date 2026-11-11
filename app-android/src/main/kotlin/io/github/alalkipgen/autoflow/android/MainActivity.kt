package io.github.alalkipgen.autoflow.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import io.github.alalkipgen.autoflow.platform.AndroidFilePicker
import org.koin.android.ext.android.inject
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.android.selftest.SelfTestActivity
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.shell.App
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

class MainActivity : ComponentActivity() {
    private val filePicker: AndroidFilePicker by inject()

    // Single-file picker (image/video import)
    private val singlePick = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        filePicker.onResult(listOfNotNull(uri))
    }

    // Multi-file picker (future use)
    private val multiPick = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        filePicker.onResult(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Wire the picker launcher into AndroidFilePicker
        filePicker.launchPickIntent = { _, extensions, multiple ->
            val mime = when {
                extensions.any { it in setOf("mp4", "mov") } && extensions.any { it in setOf("png", "jpg", "jpeg") } -> "*/*"
                extensions.any { it in setOf("mp4", "mov") } -> "video/*"
                extensions.any { it in setOf("png", "jpg", "jpeg") } -> "image/*"
                extensions.any { it in setOf("mp3", "m4a", "wav") } -> "audio/*"
                else -> "*/*"
            }
            if (multiple) multiPick.launch(mime) else singlePick.launch(mime)
        }
        val crash = CrashLog.pending(this)
        setContent {
            var report by remember { mutableStateOf(crash) }
            Box(Modifier.fillMaxSize().background(AutoFlowColors.Background).safeDrawingPadding()) {
                val r = report
                if (r == null) {
                    App(backHandler = { enabled, onBack -> BackHandler(enabled, onBack) })
                } else {
                    CrashScreen(r) { CrashLog.clear(this@MainActivity); report = null }
                }
            }
        }
    }

    /** Shown once after a crash: the owner copies or shares the text back (there is no adb on the phone). */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun CrashScreen(text: String, onDismiss: () -> Unit) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AfText("Auto Flow stopped last time", size = 18.sp, bold = true)
            AfText("Copy or share this report and send it back, then continue.", muted = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AfButton("Copy report", { Share.copy(this@MainActivity, "Auto Flow crash", text) }, kind = ButtonKind.Primary)
                AfButton("Share report", { Share.send(this@MainActivity, "Auto Flow crash report", text) })
                AfButton("Open Self-test", { SelfTestActivity.open(this@MainActivity) })
                AfButton("Dismiss", onDismiss)
            }
            AfText(text, mono = true, size = 11.sp)
        }
    }
}
