package io.github.alalkipgen.autoflow.ui.input

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.Character
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.model.StoryboardMode
import io.github.alalkipgen.autoflow.core.workflow.ShotSplitter
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.AfTextField
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.CardTile
import io.github.alalkipgen.autoflow.ui.components.GlassButton
import io.github.alalkipgen.autoflow.ui.components.GlassCallout
import io.github.alalkipgen.autoflow.ui.components.GlassDialog
import io.github.alalkipgen.autoflow.ui.components.GlassDialogAction
import io.github.alalkipgen.autoflow.ui.components.GlassDialogButtons
import io.github.alalkipgen.autoflow.ui.components.GlassFilterChip
import io.github.alalkipgen.autoflow.ui.components.GlassGroupLabel
import io.github.alalkipgen.autoflow.ui.components.GlassIconButton
import io.github.alalkipgen.autoflow.ui.components.GlassProgress
import io.github.alalkipgen.autoflow.ui.components.GlassScrollColumn
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.components.SoftTile
import io.github.alalkipgen.autoflow.ui.components.TileCard
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.components.glassDashedBox
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.settings.SettingsModel
import io.github.alalkipgen.autoflow.ui.storyboard.JobState
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardJobs
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardResult
import io.github.alalkipgen.autoflow.ui.storyboard.message
import io.github.alalkipgen.autoflow.ui.storyboard.text
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.GlassColors

const val GENERATE_BUTTON = "Storyboard ထုတ်မယ်"
const val IMPORT_SONG_BUTTON = "သီချင်းဖိုင် ထည့်မယ်"
const val REPLACE_SONG_BUTTON = "သီချင်းဖိုင် ပြောင်းမယ်"
const val NO_PROJECT_TEXT = "Dashboard မှာ project တစ်ခု အရင်ဖွင့်ပါ။ (Open a project on the Dashboard first.)"

/** The Music Video card tiles (Group 3 B): Fill icon + colour, which also tints the card. */
internal object MvTiles {
    val Lyrics = CardTile(AfIcons.Fill.TextAlignLeft, AutoFlowColors.Cyan)
    val Song = CardTile(AfIcons.Fill.MusicNote, AutoFlowColors.Purple)
    val Video = CardTile(AfIcons.Fill.FilmStrip, GlassColors.MvVideoTile)
    val Characters = CardTile(AfIcons.Fill.UsersThree, AutoFlowColors.Green)
    val LyricsTiming = CardTile(AfIcons.Fill.Timer, AutoFlowColors.Cyan)
}

/** The locked form's cards (a Gemini job runs): α .55, the controls are disabled as before. */
internal fun Modifier.dimmed(locked: Boolean): Modifier = if (locked) alpha(.55f) else this

/**
 * Input (DESIGN.md §3, Group 3 B): glass cards for the lyrics, song, video settings and characters, then "Storyboard
 * ထုတ်မယ်" behind a confirm dialog (it uses AI requests, DESIGN.md §5). No page title — the project bar names the page.
 * Text fields save when the screen closes; [onGenerated] runs only if it is still open.
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
    // GlassScrollColumn: the padding sits inside the scroll viewport, so the Generate button's 48 dp target (2 dp past its
    // slot) still counts as on screen at the end of the page.
    GlassScrollColumn(modifier.fillMaxSize(), PaddingValues(screenPadding), Arrangement.spacedBy(16.dp)) {
        if (state.projectName == null) {
            NoProjectBox()
            return@GlassScrollColumn
        }
        val locked = job.running != null
        if (locked) GlassCallout(AfIcons.Fill.Hourglass, tr(UiText.V3_INPUT_LOCKED), AutoFlowColors.Cyan, Modifier.testTag("input-locked"))
        TileCard(tr(UiText.INPUT_LYRICS), MvTiles.Lyrics, Modifier.dimmed(locked)) {
            LabelledField(tr(UiText.LYRICS_HINT), state.lyrics, model::setLyrics, lines = 10, enabled = !locked, labelBelow = true)
        }
        SongCard(state, model, locked)
        state.songFile?.let { LyricSyncCard(model, it, locked, job) }
        VideoCard(state, model, locked)
        CharactersCard(state, model, locked)
        GenerateCard(state, job, jobs, model.openProject, onStart = { model.generate { if (open.value) goOn() } })
    }
}

/** No project open (sample 02): a dashed box with the Duotone folder and OPEN_PROJECT_FIRST. */
@Composable
private fun NoProjectBox() {
    Column(Modifier.fillMaxWidth().glassDashedBox().padding(horizontal = 16.dp, vertical = 22.dp).testTag("input-no-project"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AfIcon(AfIcons.Duotone.Folder, tint = AutoFlowColors.Purple, size = 32.dp)
        AfText(My.breakSyllables(tr(UiText.OPEN_PROJECT_FIRST)), color = AutoFlowColors.Muted, textAlign = TextAlign.Center)
    }
}

/** A 12.5 sp Muted label (Group 2 `inputF`) above — or, for the lyrics, below — a glass field that shows no placeholder. */
@Composable
private fun LabelledField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, lines: Int = 1,
    mono: Boolean = false, enabled: Boolean = true, error: Boolean = false, labelBelow: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!labelBelow) FieldLabel(label)
        AfTextField(value, onChange, label, Modifier.fillMaxWidth(), lines = lines, mono = mono, enabled = enabled, glass = true,
            error = error, showPlaceholder = false)
        if (labelBelow) FieldLabel(label)
    }
}

@Composable
private fun FieldLabel(text: String) = AfText(My.breakSyllables(text), color = AutoFlowColors.Muted, type = AfType.Caption)

@Composable
internal fun SongCard(state: InputUiState, model: InputModel, locked: Boolean) {
    val scope = rememberCoroutineScope()
    val import = { scope.launch { model.importSong() }; Unit }
    TileCard(tr(UiText.SONG), MvTiles.Song, Modifier.dimmed(locked)) {
        val file = state.songFile
        if (file == null && !state.importingSong) {
            Column(Modifier.fillMaxWidth().glassDashedBox().padding(horizontal = 14.dp, vertical = 18.dp).testTag("song-drop-box"),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AfIcon(AfIcons.Duotone.FileAudio, tint = AutoFlowColors.Purple, size = 32.dp)
                AfText(My.breakSyllables(tr(UiText.NO_SONG_FILE)), color = AutoFlowColors.Muted, type = AfType.Caption, textAlign = TextAlign.Center)
                GlassButton(tr(UiText.IMPORT_SONG), import, kind = ButtonKind.Primary, enabled = !locked, icon = AfIcons.Regular.Upload)
            }
        } else {
            SongFileRow(file, if (state.importingSong) tr(UiText.IMPORTING) else state.durationText.takeIf { it.isNotBlank() })
            if (state.importingSong) GlassProgress(null, Modifier.testTag("song-importing"))
            else GlassButton(tr(UiText.REPLACE_SONG), import, enabled = !locked, icon = AfIcons.Regular.Swap)
        }
        state.songMessage?.let {
            val text = state.songNotice?.text(LocalUiLanguage.current) ?: it
            if (state.songMessageOk) GlassCallout(AfIcons.Fill.CheckCircle, text, AutoFlowColors.Green)
            else GlassCallout(AfIcons.Fill.Warn, text, AutoFlowColors.Amber)
        }
        val duration = @Composable { m: Modifier ->
            LabelledField(tr(UiText.DURATION_INPUT), state.durationText, model::setDuration, m, mono = true, enabled = !locked)
        }
        val style = @Composable { m: Modifier ->
            LabelledField(tr(UiText.SONG_STYLE_HINT), state.settings.songStyle, model::setSongStyle, m, enabled = !locked)
        }
        if (LocalCompact.current) {
            duration(Modifier.widthIn(max = 160.dp))
            style(Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                duration(Modifier.widthIn(max = 160.dp))
                style(Modifier.weight(1f))
            }
        }
        LabelledField(tr(UiText.MOOD_HINT), state.settings.mood, model::setMood, Modifier.fillMaxWidth(), enabled = !locked)
    }
}

/** The song's file row (radius 14, White .04 + .10 border): soft music tile, the mono file name (one line) and [detail] Muted. */
@Composable
private fun SongFileRow(file: String?, detail: String?) {
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(GlassColors.OptionFill).border(1.dp, GlassColors.OptionBorder, shape)
        .padding(horizontal = 12.dp, vertical = 10.dp).testTag("song-file"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SoftTile(AfIcons.Fill.MusicNote, AutoFlowColors.Purple, size = 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // One line; a long name (font 2.0) ends in "…" and stays readable in full as the content description (D-100).
            if (file != null) AfText(file, Modifier.semantics { contentDescription = file }, size = 14.sp, mono = true, maxLines = 1, ellipsis = true)
            detail?.let { AfText(it, color = AutoFlowColors.Muted, type = AfType.Caption) }
        }
    }
}

@Composable
internal fun VideoCard(state: InputUiState, model: InputModel, locked: Boolean) {
    val s = state.settings
    val language = LocalUiLanguage.current
    TileCard(tr(UiText.VIDEO), MvTiles.Video, Modifier.dimmed(locked)) {
        OptionGroup(tr(UiText.VIDEO_STYLE)) {
            ChipRow(InputModel.VIDEO_STYLES, { videoStyleLabel(it, language) }, { it == s.videoStyle }, model::pickVideoStyle, locked)
        }
        LabelledField(tr(UiText.CUSTOM_VIDEO_STYLE), s.videoStyle, model::setVideoStyle, Modifier.fillMaxWidth(), enabled = !locked)
        OptionGroup(tr(UiText.RATIO)) { ChipRow(Ratio.entries, { it.label }, { it == s.ratio }, model::setRatio, locked) }
        OptionGroup(tr(UiText.CLIP_LENGTH)) {
            ChipRow(SettingsModel.CLIP_LENGTHS_MS, { "${it / 1000}s" }, { it == s.clipMs }, model::setClipMs, locked)
        }
        OptionGroup(tr(UiText.STORYBOARD_MODE)) {
            ChipRow(StoryboardMode.entries, { storyboardModeLabel(it, language) }, { it == s.storyboardMode }, model::setMode, locked)
        }
    }
}

/** A Muted group label (Group 1 `GroupLabel`) over its chips. */
@Composable
private fun OptionGroup(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassGroupLabel(label, Modifier.padding(top = 2.dp))
        content()
    }
}

/** Character Bible (PLAN Phase 2): each character once; the compiler puts its description into every shot. */
@Composable
internal fun CharactersCard(state: InputUiState, model: InputModel, locked: Boolean) {
    var removing by remember { mutableStateOf<Character?>(null) }
    TileCard(tr(UiText.CHARACTERS), MvTiles.Characters, Modifier.dimmed(locked)) {
        AfText(My.breakSyllables(tr(UiText.CHARACTERS_HELP)), color = AutoFlowColors.Muted, type = AfType.Caption)
        state.characters.forEachIndexed { i, c -> CharacterBox(i, c, model, locked, onRemove = { removing = c }) }
        GlassButton(tr(UiText.ADD_CHARACTER), model::addCharacter, enabled = !locked, icon = AfIcons.Regular.UserPlus)
        state.characterProblems.forEach {
            GlassCallout(AfIcons.Fill.Warn, My.breakSyllables(characterProblemText(it, LocalUiLanguage.current)), AutoFlowColors.Amber)
        }
        if (state.hasStoryboard && state.characters.isNotEmpty()) {
            GlassCallout(AfIcons.Regular.Info, tr(UiText.CHARACTERS_AFTER_STORYBOARD), AutoFlowColors.Muted, Modifier.testTag("characters-after-storyboard"))
        }
    }
    removing?.let { c ->
        val close = { removing = null }
        GlassDialog(close, AfIcons.Fill.Trash, AutoFlowColors.Failed, My.breakSyllables(tr(UiText.V3_REMOVE_CHARACTER_TITLE, "name" to c.label())),
            Modifier.testTag("confirm-remove-character")) {
            AfText(My.breakSyllables(tr(UiText.V3_REMOVE_CHARACTER_BODY, "token" to "{{${c.token}}}")))
            GlassDialogButtons(GlassDialogAction(My.breakSyllables(tr(UiText.CANCEL_KEEP)), close, tag = "character-remove-cancel"),
                GlassDialogAction(My.breakSyllables(tr(UiText.REMOVE)), {
                    close()
                    state.characters.indexOf(c).takeIf { it >= 0 }?.let(model::removeCharacter)
                }, ButtonKind.Danger, AfIcons.Regular.Trash, tag = "character-remove-confirm"))
        }
    }
}

/** A character's name in its header and the remove dialog: the Burmese name, else its token. */
private fun Character.label(): String = nameMy.ifBlank { token }

/**
 * One character (sample 01): a glass box (radius 14, White .04 + .10 border, padding 12) — header = Green `user` tile,
 * the name (bold), the token pill (mono, Green) and a danger trash button — then the Token, Burmese name and English fields.
 */
@Composable
private fun CharacterBox(i: Int, c: Character, model: InputModel, locked: Boolean, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(GlassColors.OptionFill).border(1.dp, GlassColors.OptionBorder, shape)
        .padding(12.dp).testTag("character-$i"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftTile(AfIcons.Fill.User, AutoFlowColors.Green, size = 30.dp)
            WrapRow(Modifier.weight(1f), spacing = 8.dp) {
                if (c.label().isNotBlank()) AfText(My.breakSyllables(c.label()), Modifier.align(Alignment.CenterVertically), bold = true)
                if (c.token.isNotBlank()) TokenPill(c.token, Modifier.align(Alignment.CenterVertically))
            }
            GlassIconButton(AfIcons.Regular.Trash, if (c.label().isBlank()) tr(UiText.REMOVE) else tr(UiText.V3_REMOVE_NAMED, "name" to c.label()),
                onRemove, enabled = !locked, tone = AutoFlowColors.Failed)
        }
        LabelledField(tr(UiText.CHARACTER_TOKEN_HINT), c.token, { model.setCharacterToken(i, it) }, Modifier.fillMaxWidth(), mono = true, enabled = !locked)
        LabelledField(tr(UiText.CHARACTER_NAME_HINT), c.nameMy, { model.setCharacterName(i, it) }, Modifier.fillMaxWidth(), enabled = !locked)
        LabelledField(tr(UiText.CHARACTER_ENGLISH_HINT), c.descriptionEn, { model.setCharacterDescription(i, it) }, Modifier.fillMaxWidth(),
            lines = 3, enabled = !locked, error = c.token.isNotBlank() && c.descriptionEn.isBlank())
    }
}

/** The token pill: mono 11 sp Green on Green .12 with a .35 border. */
@Composable
private fun TokenPill(token: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    AfText(token, modifier.clip(shape).background(AutoFlowColors.Green.copy(alpha = .12f)).border(1.dp, AutoFlowColors.Green.copy(alpha = .35f), shape)
        .padding(horizontal = 6.dp, vertical = 1.dp), mono = true, size = 11.sp, color = AutoFlowColors.Green)
}

const val ADD_CHARACTER_BUTTON = "Add character"
const val CHARACTERS_HELP = "ဇာတ်ကောင်တစ်ယောက်ကို တစ်ခါပဲ ရေးပါ — shot တိုင်းမှာ အဲဒီ English description အတိုင်း ထည့်ပေးမယ်၊ " +
    "ဒါကြောင့် ရုပ်တူနေမယ်။ (Write each character once: every shot that uses its token gets the same description.)"

/** Glass filter chips (selected = primary gradient with a check); taps are ignored while [locked]. */
@Composable
private fun <T> ChipRow(items: List<T>, label: (T) -> String, selected: (T) -> Boolean, onPick: (T) -> Unit, locked: Boolean) {
    WrapRow(spacing = 8.dp) {
        items.forEach { item ->
            val on = selected(item)
            GlassFilterChip(label(item), on, { if (!locked) onPick(item) }, enabled = !locked,
                leading = if (on) { ink -> AfIcon(AfIcons.Regular.Check, tint = ink, size = 14.dp) } else null)
        }
    }
}

/**
 * Generate (Group 3 B 5): blockers as amber rows, the last job's error as a red callout, the full-width (phones) primary
 * button, and the confirm dialog (title, what it does and costs, Back / Generate). No sticky bar (🆕-13 not approved).
 */
@Composable
internal fun GenerateCard(state: InputUiState, job: JobState, jobs: StoryboardJobs,
    open: io.github.alalkipgen.autoflow.ui.projects.OpenProject?, onStart: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    val running = job.running
    val language = LocalUiLanguage.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.blockers.forEach { GlassCallout(AfIcons.Fill.Warn, it.text(language), AutoFlowColors.Amber, Modifier.testTag("generate-blocker")) }
        state.message?.let { GlassCallout(AfIcons.Fill.Warn, state.notice?.text(language) ?: it, AutoFlowColors.Amber) }
        job.lastFor(open)?.takeIf { it != StoryboardResult.Done && job.gemini?.action != UiText.JOB_LYRIC_TIMINGS }?.let { last ->
            last.message(language)?.let {
                if (last == StoryboardJobs.CANCELLED) GlassCallout(AfIcons.Fill.Info, it, AutoFlowColors.Muted)
                else GlassCallout(AfIcons.Fill.XCircle, it, AutoFlowColors.Failed, Modifier.testTag("generate-error"))
            }
        }
        GlassButton(tr(UiText.GENERATE_STORYBOARD), { jobs.clearLast(); confirming = true },
            if (LocalCompact.current) Modifier.fillMaxWidth() else Modifier, ButtonKind.Primary,
            enabled = state.blockers.isEmpty() && running == null, icon = AfIcons.Regular.Spark)
    }
    if (confirming && running == null) {
        val back = { confirming = false }
        GlassDialog(back, AfIcons.Fill.Spark, AutoFlowColors.Purple,
            My.breakSyllables(if (state.hasStoryboard) tr(UiText.NEW_STORYBOARD_VERSION) else tr(UiText.GENERATE_STORYBOARD_QUESTION)),
            Modifier.testTag("confirm-generate")) {
            AfText(My.breakSyllables(confirmText(state, language)))
            GlassDialogButtons(GlassDialogAction(My.breakSyllables(tr(UiText.BACK)), back, tag = "confirm-generate-back"),
                GlassDialogAction(My.breakSyllables(tr(UiText.GENERATE)), { confirming = false; onStart() }, ButtonKind.Primary,
                    AfIcons.Regular.Spark, tag = "confirm-generate-ok"))
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
