package io.github.alalkipgen.autoflow.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.media.render.Quality
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfCard
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.input.InputModel
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

const val PREVIEW_BUTTON = "Preview 540p"
const val EXPORT_BUTTON = "Export 1080p"
const val SAVE_BUTTON = "Save to Gallery"

/**
 * Export (PLAN Q-09 c): what goes into the video (shots, accepted videos, song, lyrics font), Preview / Export,
 * progress + Cancel, then Open / Share. The battery warning (below 20 % and not charging) warns, it does not block.
 */
@Composable
fun ExportScreen(model: ExportModel, modifier: Modifier = Modifier) {
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.refresh() }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPadding).widthIn(max = 760.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AfText("Export", size = 18.sp, bold = true)
        if (state.projectName == null) {
            AfText("Dashboard မှာ project တစ်ခု အရင်ဖွင့်ပါ။ (Open a project on the Dashboard first.)", muted = true)
            return@Column
        }
        AfCard("Video") {
            AfText("${state.shots} shots · ${InputModel.formatDuration(state.durationMs)}")
            val pending = state.shots - state.accepted
            AfText(
                "${state.accepted} accepted video" + (if (state.accepted == 1) "" else "s") + if (pending > 0) " · $pending show as \"pending\" cards" else "",
                color = if (pending > 0) AutoFlowColors.Amber else null,
            )
            AfText(
                state.songFile?.let { "♪ $it" } ?: "No song: the video will be silent (Input → သီချင်းဖိုင် ထည့်မယ်).",
                color = if (state.songFile == null) AutoFlowColors.Amber else null,
            )
            AfText(
                when {
                    state.font == null -> "No Burmese font: lyrics are left out (Settings → Fonts: Pyidaungsu or Noto Sans Myanmar)."
                    state.lyricShots == 0 -> "No lyrics on the shots."
                    else -> "Lyrics on ${state.lyricShots} shots · ${state.font}"
                },
                color = if (state.font == null) AutoFlowColors.Amber else null,
            )
        }
        AfCard("Render") {
            state.unavailable?.let { AfText(it, color = AutoFlowColors.Amber) }
            if (state.lowBattery) AfText("Battery is low and not charging: plug in before a long export.", color = AutoFlowColors.Amber)
            WrapRow {
                AfButton(PREVIEW_BUTTON, { model.render(Quality.PREVIEW) }, enabled = state.canRender)
                AfButton(EXPORT_BUTTON, { model.render(Quality.EXPORT) }, kind = ButtonKind.Primary, enabled = state.canRender)
            }
            AfText("Preview is fast and small; Export is the full-quality 1080p video. Rendering goes on with the screen off.", muted = true)
            state.run?.let { RunStatus(it, model, state.savingToGallery) }
            state.message?.let { AfText(it, color = if (state.messageOk) AutoFlowColors.Green else AutoFlowColors.Amber) }
        }
    }
}

@Composable
private fun RunStatus(run: ExportRun, model: ExportModel, saving: Boolean) {
    val what = ExportModel.label(run.quality)
    when (val s = run.state) {
        RenderState.Queued -> {
            AfText("$what: waiting to start…", Modifier.testTag("export-status"))
            AfButton("Cancel", model::cancel)
        }
        is RenderState.Running -> {
            AfText("$what: rendering… ${(s.fraction * 100).toInt().coerceIn(0, 100)} %", Modifier.testTag("export-status"))
            AfButton("Cancel", model::cancel)
        }
        is RenderState.Done -> {
            AfText(
                "$what done ✓ · ${InputModel.formatDuration(s.durationMs)} · rendered in ${s.wallMs / 1000} s",
                Modifier.testTag("export-status"),
                color = AutoFlowColors.Green,
            )
            AfText(s.output.substringAfterLast('/').let { "exports/$it" }, muted = true, mono = true)
            WrapRow {
                AfButton("Open", model::open, kind = ButtonKind.Primary)
                AfButton("Share…", model::share)
                AfButton(if (saving) "Saving…" else SAVE_BUTTON, model::saveToGallery, enabled = !saving)
            }
        }
        is RenderState.Failed -> {
            AfText("$what failed: ${s.reason}", Modifier.testTag("export-status"), color = AutoFlowColors.Amber)
            if (s.tail.isNotEmpty()) AfText(s.tail.takeLast(6).joinToString("\n"), muted = true, mono = true, size = 11.sp)
        }
        RenderState.Cancelled -> AfText("$what cancelled.", Modifier.testTag("export-status"), muted = true)
    }
}
