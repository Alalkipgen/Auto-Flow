package io.github.alalkipgen.autoflow.ui.input

import io.github.alalkipgen.autoflow.ui.components.paneScroll
import io.github.alalkipgen.autoflow.ui.i18n.*
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
        modifier.fillMaxSize().paneScroll(rememberScrollState()).padding(screenPadding).widthIn(max = 760.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AfText(tr(UiText.INPUT), size = 18.sp, bold = true)
        if (state.projectName == null) {
            AfText(tr(UiText.OPEN_PROJECT_FIRST), muted = true)
            return@Column
        }
        val locked = job.running != null
        AfCard(tr(UiText.INPUT_LYRICS)) {
            AfTextField(
                state.lyrics, model::setLyrics, tr(UiText.LYRICS_HINT),
                lines = 10, mono = false, enabled = !locked,
            )
        }
        SongCard(state, model, locked)
        state.songFile?.let { LyricSyncCard(model, it, locked, job) }
        VideoCard(state, model, locked)
        CharactersCard(state, model, locked)
        GenerateCard(state, job, jobs, model.openProject, onStart = { model.generate { if (open.value) goOn() } })
    }
}

@Composable
private fun SongCard(state: InputUiState, model: InputModel, locked: Boolean) {
    val scope = rememberCoroutineScope()
    AfCard(tr(UiText.SONG)) {
        WrapRow {
            AfButton(
                if (state.songFile == null) tr(UiText.IMPORT_SONG) else tr(UiText.REPLACE_SONG),
                { scope.launch { model.importSong() } },
                enabled = !locked && !state.importingSong,
            )
            AfText(
                when {
                    state.importingSong -> tr(UiText.IMPORTING)
                    state.songFile != null -> "♪ ${state.songFile}"
                    else -> tr(UiText.NO_SONG_FILE)
                },
                muted = state.songFile == null || state.importingSong,
            )
        }
        state.songMessage?.let { AfText(state.songNotice?.text(LocalUiLanguage.current) ?: it, color = if (state.songMessageOk) AutoFlowColors.Green else AutoFlowColors.Amber) }
        if (LocalCompact.current) {
            AfTextField(state.durationText, model::setDuration, tr(UiText.DURATION_INPUT), Modifier.widthIn(max = 160.dp), enabled = !locked)
            AfTextField(state.settings.songStyle, model::setSongStyle, tr(UiText.SONG_STYLE_HINT), mono = false, enabled = !locked)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AfTextField(state.durationText, model::setDuration, tr(UiText.DURATION_INPUT), Modifier.widthIn(max = 160.dp), enabled = !locked)
                AfTextField(state.settings.songStyle, model::setSongStyle, tr(UiText.SONG_STYLE_HINT), Modifier.weight(1f), mono = false, enabled = !locked)
            }
        }
        AfTextField(state.settings.mood, model::setMood, tr(UiText.MOOD_HINT), mono = false, enabled = !locked)
    }
}

@Composable
private fun VideoCard(state: InputUiState, model: InputModel, locked: Boolean) {
    val s = state.settings
    AfCard(tr(UiText.VIDEO)) {
        AfText(tr(UiText.VIDEO_STYLE), muted = true)
        val language = LocalUiLanguage.current
        ChipRow(InputModel.VIDEO_STYLES, { videoStyleLabel(it, language) }, { it == s.videoStyle }, model::pickVideoStyle, locked)
        AfTextField(s.videoStyle, model::setVideoStyle, tr(UiText.CUSTOM_VIDEO_STYLE), mono = false, enabled = !locked)
        AfText(tr(UiText.RATIO), muted = true)
        ChipRow(Ratio.entries, { it.label }, { it == s.ratio }, model::setRatio, locked)
        AfText(tr(UiText.CLIP_LENGTH), muted = true)
        ChipRow(SettingsModel.CLIP_LENGTHS_MS, { "${it / 1000}s" }, { it == s.clipMs }, model::setClipMs, locked)
        AfText(tr(UiText.STORYBOARD_MODE), muted = true)
        ChipRow(StoryboardMode.entries, { storyboardModeLabel(it, language) }, { it == s.storyboardMode }, model::setMode, locked)
    }
}

/** Character Bible (PLAN Phase 2): each character once; the compiler puts its description into every shot. */
@Composable
private fun CharactersCard(state: InputUiState, model: InputModel, locked: Boolean) {
    AfCard(tr(UiText.CHARACTERS)) {
        AfText(tr(UiText.CHARACTERS_HELP), muted = true, size = 12.sp)
        state.characters.forEachIndexed { i, c ->
            Column(
                Modifier.fillMaxWidth().clip(AutoFlowShapes.Card)
                    .border(AutoFlowShapes.Border, AutoFlowColors.Line, AutoFlowShapes.Card).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AfTextField(c.token, { model.setCharacterToken(i, it) }, tr(UiText.CHARACTER_TOKEN_HINT), Modifier.widthIn(max = 260.dp), enabled = !locked)
                AfTextField(c.nameMy, { model.setCharacterName(i, it) }, tr(UiText.CHARACTER_NAME_HINT), mono = false, enabled = !locked)
                AfTextField(
                    c.descriptionEn, { model.setCharacterDescription(i, it) },
                    tr(UiText.CHARACTER_ENGLISH_HINT),
                    lines = 3, mono = false, enabled = !locked,
                )
                AfButton(tr(UiText.REMOVE), { model.removeCharacter(i) }, kind = ButtonKind.Danger, enabled = !locked)
            }
        }
        AfButton(tr(UiText.ADD_CHARACTER), model::addCharacter, enabled = !locked)
        state.characterProblems.forEach { AfText("⚠ $it", color = AutoFlowColors.Amber, size = 12.sp) }
        if (state.hasStoryboard && state.characters.isNotEmpty()) AfText(tr(UiText.CHARACTERS_AFTER_STORYBOARD), muted = true, size = 12.sp)
    }
}

const val ADD_CHARACTER_BUTTON = "+ Add character"
const val CHARACTERS_HELP = "ဇာတ်ကောင်တစ်ယောက်ကို တစ်ခါပဲ ရေးပါ — shot တိုင်းမှာ အဲဒီ English description အတိုင်း ထည့်ပေးမယ်၊ " +
    "ဒါကြောင့် ရုပ်တူနေမယ်။ (Write each character once: every shot that uses its token gets the same description.)"
const val CHARACTERS_AFTER_STORYBOARD = "The storyboard uses the characters when it is generated (again); after a description " +
    "changes, the shots that use it show ⚠ Stale on Shots — ↻ Recompile them."

@Composable
private fun <T> ChipRow(items: List<T>, label: (T) -> String, selected: (T) -> Boolean, onPick: (T) -> Unit, locked: Boolean) {
    WrapRow {
        items.forEach { item -> AfChip(label(item), selected(item), { if (!locked) onPick(item) }) }
    }
}

@Composable
private fun GenerateCard(state: InputUiState, job: JobState, jobs: StoryboardJobs,
    open: io.github.alalkipgen.autoflow.ui.projects.OpenProject?, onStart: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    val running = job.running
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.blockers.forEach { AfText("• ${it.text(LocalUiLanguage.current)}", color = AutoFlowColors.Amber) }
        state.message?.let { AfText(state.notice?.text(LocalUiLanguage.current) ?: it, color = AutoFlowColors.Amber) }
        job.lastFor(open)?.takeIf { it != StoryboardResult.Done && job.gemini?.action != UiText.JOB_LYRIC_TIMINGS }?.message(LocalUiLanguage.current)?.let { AfText(it, color = AutoFlowColors.Amber) }
        if (confirming && running == null) {
            AfCard(if (state.hasStoryboard) tr(UiText.NEW_STORYBOARD_VERSION) else tr(UiText.GENERATE_STORYBOARD_QUESTION)) {
                AfText(confirmText(state, LocalUiLanguage.current), muted = true)
                WrapRow {
                    AfButton(tr(UiText.GENERATE), { confirming = false; onStart() }, kind = ButtonKind.Primary)
                    AfButton(tr(UiText.BACK), { confirming = false })
                }
            }
        } else {
            AfButton(tr(UiText.GENERATE_STORYBOARD), { jobs.clearLast(); confirming = true }, kind = ButtonKind.Primary, enabled = state.blockers.isEmpty() && running == null)
        }
    }
}

/** What generating will do and cost (DESIGN.md §5): shot count from ShotSplitter, AI requests, what becomes stale. */
internal fun confirmText(state: InputUiState, language: UiLanguage = UiLanguage.ENGLISH): String {
    val s = state.settings
    val shots = (ShotSplitter.split(s.durationMs, s.clipMs) as? CoreResult.Ok)?.value?.size ?: 0
    val base = UiText.STORYBOARD_COST.text(language, "shots" to shots, "seconds" to s.clipMs / 1000)
    return if (state.hasStoryboard) "$base ${UiText.STORYBOARD_REGEN_WARNING.text(language)}" else base
}

/** Labels only: chip values remain the original English generation-prompt values. */
internal fun videoStyleLabel(value: String, language: UiLanguage): String = when (value) {
    "Cinematic" -> UiText.STYLE_CINEMATIC.text(language)
    "Anime" -> UiText.STYLE_ANIME.text(language)
    "3D animation" -> UiText.STYLE_3D.text(language)
    "Watercolor" -> UiText.STYLE_WATERCOLOR.text(language)
    "Documentary" -> UiText.STYLE_DOCUMENTARY.text(language)
    "Retro film" -> UiText.STYLE_RETRO.text(language)
    else -> value
}

internal fun storyboardModeLabel(value: StoryboardMode, language: UiLanguage): String = when (value) {
    StoryboardMode.NARRATIVE -> UiText.MODE_NARRATIVE
    StoryboardMode.PERFORMANCE -> UiText.MODE_PERFORMANCE
    StoryboardMode.ABSTRACT -> UiText.MODE_ABSTRACT
    StoryboardMode.MIXED -> UiText.MODE_MIXED
}.text(language)
