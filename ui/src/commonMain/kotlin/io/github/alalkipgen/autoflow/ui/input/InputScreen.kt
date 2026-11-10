package io.github.alalkipgen.autoflow.ui.input

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.model.StoryboardMode
import io.github.alalkipgen.autoflow.core.workflow.ShotSplitter
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfCard
import io.github.alalkipgen.autoflow.ui.components.AfChip
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.AfTextField
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.settings.SettingsModel
import io.github.alalkipgen.autoflow.ui.storyboard.JobState
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardJobs
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardResult
import io.github.alalkipgen.autoflow.ui.storyboard.message
import io.github.alalkipgen.autoflow.ui.storyboard.text
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

const val GENERATE_BUTTON = "Storyboard ထုတ်မယ်"
const val NO_PROJECT_TEXT = "Dashboard မှာ project တစ်ခု အရင်ဖွင့်ပါ။ (Open a project on the Dashboard first.)"

/**
 * Input (DESIGN.md §3): lyrics, song and video settings, then "Storyboard ထုတ်မယ်" behind a confirm card (it uses
 * AI requests, DESIGN.md §5). Text fields save when the screen closes; [onGenerated] runs only if it is still open.
 */
@Composable
fun InputScreen(model: InputModel, jobs: StoryboardJobs, onGenerated: () -> Unit, modifier: Modifier = Modifier) {
    val state by model.state.collectAsState()
    val job by jobs.state.collectAsState()
    val open = remember { mutableStateOf(true) }
    val goOn by rememberUpdatedState(onGenerated)
    DisposableEffect(model) {
        onDispose {
            open.value = false
            model.save()
        }
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 760.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AfText("Input", size = 18.sp, bold = true)
        if (state.projectName == null) {
            AfText(NO_PROJECT_TEXT, muted = true)
            return@Column
        }
        val locked = job.running != null
        AfCard("Lyrics") {
            AfTextField(
                state.lyrics, model::setLyrics, "Lyrics (section markers like [Verse] or [Chorus] are optional)",
                lines = 10, mono = false, enabled = !locked,
            )
        }
        SongCard(state, model, locked)
        VideoCard(state, model, locked)
        GenerateCard(state, job, jobs, onStart = { model.generate { if (open.value) goOn() } })
    }
}

@Composable
private fun SongCard(state: InputUiState, model: InputModel, locked: Boolean) {
    AfCard("Song") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfTextField(state.durationText, model::setDuration, "Duration (m:ss)", Modifier.widthIn(max = 160.dp), enabled = !locked)
            AfTextField(state.settings.songStyle, model::setSongStyle, "Song style (e.g. Burmese pop ballad)", Modifier.weight(1f), mono = false, enabled = !locked)
        }
        AfTextField(state.settings.mood, model::setMood, "Mood / colour (e.g. nostalgic, rainy blue)", mono = false, enabled = !locked)
    }
}

@Composable
private fun VideoCard(state: InputUiState, model: InputModel, locked: Boolean) {
    val s = state.settings
    AfCard("Video") {
        AfText("Video style", muted = true)
        ChipRow(InputModel.VIDEO_STYLES, { it }, { it == s.videoStyle }, model::pickVideoStyle, locked)
        AfTextField(s.videoStyle, model::setVideoStyle, "Custom video style", mono = false, enabled = !locked)
        AfText("Ratio", muted = true)
        ChipRow(Ratio.entries, { it.label }, { it == s.ratio }, model::setRatio, locked)
        AfText("Clip length", muted = true)
        ChipRow(SettingsModel.CLIP_LENGTHS_MS, { "${it / 1000}s" }, { it == s.clipMs }, model::setClipMs, locked)
        AfText("Storyboard mode", muted = true)
        ChipRow(StoryboardMode.entries, { it.name.lowercase().replaceFirstChar(Char::uppercase) }, { it == s.storyboardMode }, model::setMode, locked)
    }
}

@Composable
private fun <T> ChipRow(items: List<T>, label: (T) -> String, selected: (T) -> Boolean, onPick: (T) -> Unit, locked: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item -> AfChip(label(item), selected(item), { if (!locked) onPick(item) }) }
    }
}

@Composable
private fun GenerateCard(state: InputUiState, job: JobState, jobs: StoryboardJobs, onStart: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    val running = job.running
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.blockers.forEach { AfText("• ${it.text()}", color = AutoFlowColors.Amber) }
        state.message?.let { AfText(it, color = AutoFlowColors.Amber) }
        job.last?.takeIf { it != StoryboardResult.Done }?.message()?.let { AfText(it, color = AutoFlowColors.Amber) }
        if (running != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AfText("$running…", color = AutoFlowColors.Cyan)
                AfButton("Cancel", jobs::cancel, kind = ButtonKind.Danger)
            }
        } else if (confirming) {
            AfCard(if (state.hasStoryboard) "Make a new storyboard version?" else "Generate the storyboard?") {
                AfText(confirmText(state), muted = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AfButton("Generate", { confirming = false; onStart() }, kind = ButtonKind.Primary)
                    AfButton("Back", { confirming = false })
                }
            }
        } else {
            AfButton(GENERATE_BUTTON, { jobs.clearLast(); confirming = true }, kind = ButtonKind.Primary, enabled = state.blockers.isEmpty())
        }
    }
}

/** What generating will do and cost (DESIGN.md §5): shot count from ShotSplitter, AI requests, what becomes stale. */
internal fun confirmText(state: InputUiState): String {
    val s = state.settings
    val shots = (ShotSplitter.split(s.durationMs, s.clipMs) as? CoreResult.Ok)?.value?.size ?: 0
    val base = "Analyses the song, then writes a Burmese scene for about $shots shots of ${s.clipMs / 1000}s " +
        "(2 AI requests, one more for each retry)."
    return if (state.hasStoryboard) {
        "$base The current storyboard stays as an older version. Every shot needs approval again, and prompts, " +
            "reference images and videos made from the old scenes become stale."
    } else {
        base
    }
}
