package io.github.alalkipgen.autoflow.ui.input

import androidx.compose.foundation.border
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
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
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.settings.SettingsModel
import io.github.alalkipgen.autoflow.ui.storyboard.JobState
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardJobs
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardResult
import io.github.alalkipgen.autoflow.ui.storyboard.message
import io.github.alalkipgen.autoflow.ui.storyboard.text
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes

const val GENERATE_BUTTON = "Storyboard ထုတ်မယ်"
const val IMPORT_SONG_BUTTON = "သီချင်းဖိုင် ထည့်မယ်"
const val REPLACE_SONG_BUTTON = "သီချင်းဖိုင် ပြောင်းမယ်"
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
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPadding).widthIn(max = 760.dp),
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
        CharactersCard(state, model, locked)
        GenerateCard(state, job, jobs, onStart = { model.generate { if (open.value) goOn() } })
    }
}

@Composable
private fun SongCard(state: InputUiState, model: InputModel, locked: Boolean) {
    val scope = rememberCoroutineScope()
    AfCard("Song") {
        WrapRow {
            AfButton(
                if (state.songFile == null) IMPORT_SONG_BUTTON else REPLACE_SONG_BUTTON,
                { scope.launch { model.importSong() } },
                enabled = !locked && !state.importingSong,
            )
            AfText(
                when {
                    state.importingSong -> "Importing…"
                    state.songFile != null -> "♪ ${state.songFile}"
                    else -> "No song file yet (mp3, m4a, wav…)"
                },
                muted = state.songFile == null || state.importingSong,
            )
        }
        state.songMessage?.let { AfText(it, color = if (state.songMessageOk) AutoFlowColors.Green else AutoFlowColors.Amber) }
        if (LocalCompact.current) {
            AfTextField(state.durationText, model::setDuration, "Duration (m:ss)", Modifier.widthIn(max = 160.dp), enabled = !locked)
            AfTextField(state.settings.songStyle, model::setSongStyle, "Song style (e.g. Burmese pop ballad)", mono = false, enabled = !locked)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AfTextField(state.durationText, model::setDuration, "Duration (m:ss)", Modifier.widthIn(max = 160.dp), enabled = !locked)
                AfTextField(state.settings.songStyle, model::setSongStyle, "Song style (e.g. Burmese pop ballad)", Modifier.weight(1f), mono = false, enabled = !locked)
            }
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

/** Character Bible (PLAN Phase 2): each character once; the compiler puts its description into every shot. */
@Composable
private fun CharactersCard(state: InputUiState, model: InputModel, locked: Boolean) {
    AfCard("Characters") {
        AfText(CHARACTERS_HELP, muted = true, size = 12.sp)
        state.characters.forEachIndexed { i, c ->
            Column(
                Modifier.fillMaxWidth().clip(AutoFlowShapes.Card)
                    .border(AutoFlowShapes.Border, AutoFlowColors.Line, AutoFlowShapes.Card).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AfTextField(c.token, { model.setCharacterToken(i, it) }, "Token (e.g. HERO)", Modifier.widthIn(max = 260.dp), enabled = !locked)
                AfTextField(c.nameMy, { model.setCharacterName(i, it) }, "Burmese name (e.g. မောင်မောင်)", mono = false, enabled = !locked)
                AfTextField(
                    c.descriptionEn, { model.setCharacterDescription(i, it) },
                    "English description (e.g. a young East Asian man in his 20s, short black hair, grey sweater)",
                    lines = 3, mono = false, enabled = !locked,
                )
                AfButton("Remove", { model.removeCharacter(i) }, kind = ButtonKind.Danger, enabled = !locked)
            }
        }
        AfButton(ADD_CHARACTER_BUTTON, model::addCharacter, enabled = !locked)
        state.characterProblems.forEach { AfText("⚠ $it", color = AutoFlowColors.Amber, size = 12.sp) }
        if (state.hasStoryboard && state.characters.isNotEmpty()) AfText(CHARACTERS_AFTER_STORYBOARD, muted = true, size = 12.sp)
    }
}

const val ADD_CHARACTER_BUTTON = "+ Add character"
const val CHARACTERS_HELP = "ဇာတ်ကောင်တစ်ယောက်ကို တစ်ခါပဲ ရေးပါ — shot တိုင်းမှာ အဲဒီ English description အတိုင်း ထည့်ပေးမယ်၊ " +
    "ဒါကြောင့် ရုပ်တူနေမယ်။ (Write each character once: every shot that uses its token gets the same description.)"
const val CHARACTERS_AFTER_STORYBOARD = "The storyboard uses the characters when it is generated (again); a changed description " +
    "reaches a shot's prompt when it is recompiled on Shots (↻ Recompile)."

@Composable
private fun <T> ChipRow(items: List<T>, label: (T) -> String, selected: (T) -> Boolean, onPick: (T) -> Unit, locked: Boolean) {
    WrapRow {
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
        // A running job (with Cancel) shows in the shell's JobBanner.
        if (confirming && running == null) {
            AfCard(if (state.hasStoryboard) "Make a new storyboard version?" else "Generate the storyboard?") {
                AfText(confirmText(state), muted = true)
                WrapRow {
                    AfButton("Generate", { confirming = false; onStart() }, kind = ButtonKind.Primary)
                    AfButton("Back", { confirming = false })
                }
            }
        } else {
            AfButton(GENERATE_BUTTON, { jobs.clearLast(); confirming = true }, kind = ButtonKind.Primary, enabled = state.blockers.isEmpty() && running == null)
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
