package io.github.alalkipgen.autoflow.android.selftest

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.BuildConfig
import io.github.alalkipgen.autoflow.android.render.Battery
import io.github.alalkipgen.autoflow.android.Share
import io.github.alalkipgen.autoflow.android.selftest.SelfTestState.Status
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfCard
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import java.io.File

/** PLAN S6 Self-test screen (Settings → About → long-press the version). */
class SelfTestActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Show the last report after the app was closed (the service may have finished in the background).
        val saved = File(File(getExternalFilesDir(null) ?: filesDir, "selftest"), SelfTestRunner.REPORT)
        runCatching { saved.takeIf { it.isFile }?.readText() }.getOrNull()?.let(SelfTestState::showSaved)
        // A render-job check queued before the app process was restarted: follow it again.
        SelfTestJob.resume(this)
        setContent { Screen() }
    }

    private fun run(media3Only: Boolean) {
        askNotificationsOnce()
        SelfTestService.start(this, media3Only)
    }

    /** PLAN 1F check: one real render through the WorkManager queue, started while this screen is visible. */
    private fun runJob() {
        if (Battery.read(this).lowWarning) {
            android.app.AlertDialog.Builder(this)
                .setMessage("Battery is below 20% and not charging. Rendering may use a lot of power. Continue?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Render") { _, _ -> startJob() }
                .show()
        } else startJob()
    }

    private fun startJob() {
        askNotificationsOnce()
        SelfTestJob.start(this)
    }

    private fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Screen() {
        val ui by SelfTestState.ui.collectAsState()
        Column(
            Modifier.fillMaxSize().background(AutoFlowColors.Background).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AfText("Self-test", size = 18.sp, bold = true)
            AfText("Auto Flow ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA}) · signing ${BuildConfig.SIGNING}", muted = true, size = 12.sp)
            AfText(
                "Renders 3 short test videos with the bundled ffmpeg (about 2–10 minutes). You can turn the screen off: " +
                    "it keeps running with a notification. Then tap Render job: one 21 s 1080p video through the real " +
                    "render queue. Then copy the report and send it back.",
                muted = true,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val idle = !ui.running && !ui.jobRunning
                AfButton("Run all", { run(media3Only = false) }, kind = ButtonKind.Primary, enabled = idle)
                AfButton("Render job (WorkManager)", { runJob() }, kind = ButtonKind.Primary, enabled = idle)
                AfButton("Run Media3 path", { run(media3Only = true) }, enabled = idle)
                AfButton(
                    "Cancel",
                    { if (ui.jobRunning) SelfTestJob.cancel() else SelfTestService.cancel(this@SelfTestActivity) },
                    kind = ButtonKind.Danger,
                    enabled = !idle,
                )
            }
            if (ui.progress.isNotEmpty()) AfText(ui.progress, color = AutoFlowColors.Cyan)
            if (ui.steps.isNotEmpty()) {
                AfCard("Steps") {
                    ui.steps.forEach { s ->
                        val (mark, color) = when (s.status) {
                            Status.RUNNING -> "…" to AutoFlowColors.Cyan
                            Status.OK -> "✓" to AutoFlowColors.Text
                            Status.FAILED -> "✗" to AutoFlowColors.Amber
                            Status.SKIPPED -> "–" to AutoFlowColors.Muted
                        }
                        AfText("$mark ${s.label}" + if (s.detail.isNotEmpty()) " — ${s.detail}" else "", color = color)
                    }
                }
            }
            if (ui.frames.isNotEmpty()) {
                var big by remember { mutableStateOf<String?>(null) }
                AfCard("Frames (check the Burmese text and the placeholder card; tap to enlarge)") {
                    big?.let { path -> Frame(SelfTestState.Frame("", path), Modifier.fillMaxWidth()) { big = null } }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ui.frames.forEach { f -> Frame(f, Modifier.width(100.dp)) { big = f.path } }
                    }
                }
            }
            ui.report?.let { report ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AfButton("Copy report", { Share.copy(this@SelfTestActivity, "Auto Flow self-test", report) }, kind = ButtonKind.Primary)
                    AfButton("Share report", { Share.send(this@SelfTestActivity, "Auto Flow self-test report", report) })
                }
                SelectionContainer { AfText(report, mono = true, size = 11.sp) }
            }
        }
    }

    @Composable
    private fun Frame(frame: SelfTestState.Frame, modifier: Modifier, onClick: () -> Unit) {
        val bitmap = remember(frame.path) { SelfTestRunner.thumbnail(frame.path)?.asImageBitmap() }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (bitmap != null) {
                Image(bitmap, frame.label.ifEmpty { "frame" }, modifier.clickable(onClick = onClick), contentScale = ContentScale.FillWidth)
            } else {
                AfText("(no image)", muted = true)
            }
            if (frame.label.isNotEmpty()) AfText(frame.label, muted = true, size = 10.sp)
        }
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, SelfTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
