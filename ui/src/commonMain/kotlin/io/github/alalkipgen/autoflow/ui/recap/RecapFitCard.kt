package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.recap.FitChoice
import io.github.alalkipgen.autoflow.core.recap.FitRow
import io.github.alalkipgen.autoflow.core.recap.FitStatus
import io.github.alalkipgen.autoflow.core.recap.Recap
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.core.recap.RecapFit
import io.github.alalkipgen.autoflow.core.recap.RecapRulesV2
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfCard
import io.github.alalkipgen.autoflow.ui.components.AfChip
import io.github.alalkipgen.autoflow.ui.components.AfSwitch
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.AfTextField
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.recap.RecapModel.Companion.clock
import io.github.alalkipgen.autoflow.ui.recap.RecapModel.Companion.seconds
import io.github.alalkipgen.autoflow.ui.storyboard.text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.GlassColors
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.components.GlassButton
import io.github.alalkipgen.autoflow.ui.components.GlassCard
import io.github.alalkipgen.autoflow.ui.components.GlassHairline
import io.github.alalkipgen.autoflow.ui.components.SoftTile
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

const val GO_TO_EXPORT = "ထုတ်ယူ သို့ သွားမယ် →"
const val BURN_CAPTIONS = "စာတန်းကို ဗွီဒီယိုထဲ မြှုပ်မယ်"

/** What a fit row says (spec §6): "✓ fits", "+1.8 s: the scene runs on", "No voice", "⚠ Stale". */
fun FitRow.label(language: io.github.alalkipgen.autoflow.ui.i18n.UiLanguage = io.github.alalkipgen.autoflow.ui.i18n.UiLanguage.ENGLISH): String = when (status) {
    FitStatus.FITS -> UiText.FIT_FITS.text(language)
    FitStatus.RUNS_ON -> UiText.FIT_RUNS_ON.text(language, "secondsoverMs" to (seconds(overMs)))
    FitStatus.NO_VOICE -> UiText.NO_VOICE.text(language)
    FitStatus.STALE -> UiText.FIT_STALE.text(language, "textlanguage" to (UiText.STALE.text(language)))
}

/** The icon a fit row's label shows before it (Group 2 C 15): check (green) · warning (amber) · waveform (muted, no voice). */
internal fun FitRow.statusIcon(): Pair<ImageVector, Color> = when (status) {
    FitStatus.FITS -> AfIcons.Regular.Check to AutoFlowColors.Green
    FitStatus.RUNS_ON, FitStatus.STALE -> AfIcons.Fill.Warn to AutoFlowColors.Amber
    FitStatus.NO_VOICE -> AfIcons.Regular.Wave to AutoFlowColors.Muted
}

/** Footer: "Recap 4:51 (target 5:00) · 12 of 14 voiced". */
fun fitFooter(recap: RecapV2, rows: List<FitRow>, language: UiLanguage = UiLanguage.ENGLISH): String =
    UiText.FIT_FOOTER.text(language, "totalMsrows" to (clock(RecapFit.totalMs(rows))), "targetMs" to (clock(recap.targetMs)), "size" to (rows.size), "takeMsnull" to (rows.count { it.takeMs != null }))

/** Card 5 · Fit check (docs/specs/recap.md §6): one row per scene, movie sound, looks, captions, then Export. */
@Composable
internal fun FitCard(model: RecapModel, state: RecapUiState, recap: RecapV2, onGoToExport: () -> Unit) {
    AfCard(tr(UiText.RECAP_FIT_CARD)) {
        val story = state.story
        RecapRulesV2.canVoice(recap, story).blockers.firstOrNull()?.let {
            AfText(it.text(LocalUiLanguage.current), muted = true)
            return@AfCard
        }
        story!!
        // v2 rows (audit F07): by block id, every clip of a block, the chosen take.
        val rows = RecapFit.rows(recap, story, state.lexicon)
        rows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AfText("${row.segment + 1}", muted = true, mono = true)
                AfText(tr(UiText.FIT_SCENE_LENGTH, "sceneMs" to (seconds(row.sceneMs))) + (row.takeMs?.let { tr(UiText.FIT_VOICE_LENGTH, "secondsit" to (seconds(it))) } ?: ""), Modifier.weight(1f))
                // The ✓ / ⚠ left the labels (Group 2 C §3): the row's icon goes before it.
                row.statusIcon().let { (icon, tint) -> AfIcon(icon, tint = tint, size = 16.dp) }
                AfText(
                    row.label(io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage.current),
                    color = when (row.status) {
                        FitStatus.FITS -> AutoFlowColors.Green
                        FitStatus.RUNS_ON, FitStatus.STALE -> AutoFlowColors.Amber
                        FitStatus.NO_VOICE -> null
                    },
                    muted = row.status == FitStatus.NO_VOICE,
                )
            }
            // Smart Fit choices (5A-8): shown when the take runs over by > 400 ms
            if (row.status == FitStatus.RUNS_ON && row.overMs > 400) {
                val block = row.block ?: story.blocks[row.segment].id
                SmartFitChoices(model, recap, block, row)
            }
        }
        AfText(fitFooter(recap, rows, LocalUiLanguage.current), bold = true)
        val idle = !state.busy && state.recording == null
        AfText(tr(UiText.MOVIE_SOUND_UNDER_VOICE), muted = true, size = 12.sp)
        WrapRow {
            RecapModel.MOVIE_VOLUMES.forEach { (name, v) -> AfChip(movieVolumeLabel(v), recap.movieVolume == v, { if (idle) model.setMovieVolume(v) }) }
        }
        RecapSoundControls(recap.audio, state.bgmName, idle, model)
        AfText(tr(UiText.LOOKS_HINT), muted = true, size = 12.sp)
        WrapRow {
            RecapModel.LOOKS.forEach { (look, name) -> AfChip(look.uiLabel(), look in recap.looks, { if (idle) model.toggleLook(look) }) }
        }
        RecapPictureControls(recap, idle, model)
        AfSwitch(tr(UiText.BURN_CAPTIONS), recap.burnCaptions, { if (idle) model.setBurnCaptions(it) })
        // Caption style settings are in RecapPictureControls → ✍️ Captions section (D-58).
        if (recap.burnCaptions) SaveCaptionPresetControls(recap, idle, model)
        AfText(tr(UiText.CAPTIONS_SRT_ALWAYS), muted = true, size = 12.sp)
        SaveBrandKitControls(recap, idle, model)
        val render = RecapRulesV2.canRender(recap, story, state.lexicon)
        render.blockers.firstOrNull()?.let { AfText(it.text(LocalUiLanguage.current), color = AutoFlowColors.Amber) }
        AfButton(tr(UiText.GO_TO_EXPORT), onGoToExport, kind = ButtonKind.Primary, enabled = render.allowed)
    }
}

/** The existing Smart Fit choices, also reached from a Voice block; policy remains in the engine. */
@Composable
internal fun SmartFitChoices(model: RecapModel, recap: RecapV2, block: String, row: FitRow, enabled: Boolean = true, showOverrunHint: Boolean = true,
    glass: Boolean = false) {
    val current = recap.fitChoice(block) ?: FitChoice.EXTEND
    if (showOverrunHint) {
        if (glass) AfText(tr(UiText.FIT_ACTIONS, "overMs" to seconds(row.overMs)), muted = true, type = AfType.Caption)
        else AfText(tr(UiText.FIT_ACTIONS, "overMs" to seconds(row.overMs)), muted = true, size = 11.sp)
    }
    WrapRow {
        listOf(FitChoice.EXTEND to UiText.FIT_EXTEND, FitChoice.NEXT_RANGE to UiText.FIT_NEXT_RANGE,
            FitChoice.SPEED to UiText.FIT_SPEED, FitChoice.SLOW_VIDEO to UiText.FIT_SLOW_VIDEO,
            FitChoice.FREEZE to UiText.FIT_FREEZE).forEach { (choice, text) ->
            // [glass] (block detail, Group 2 C 11): glass filter chips.
            if (glass) RecapChip(tr(text), current == choice, { model.setFitChoice(block, choice) }, enabled = enabled)
            else AfChip(tr(text), current == choice, onClick = { model.setFitChoice(block, choice) }, enabled = enabled)
        }
    }
}

/** Sound (5B-4): voice polish, background music with ducking, even loudness. Applied when the video is rendered. */
@Composable
internal fun RecapSoundControls(audio: io.github.alalkipgen.autoflow.core.recap.RecapAudio, bgmName: String?, idle: Boolean, model: RecapModel,
    glass: Boolean = false) {
    if (glass) {
        GlassSoundControls(audio, bgmName, idle, model)
        return
    }
    AfSwitch(tr(UiText.VOICE_POLISH), audio.polish, { if (idle) model.setAudio { a -> a.copy(polish = it) } })
    AfSwitch(tr(UiText.LOUDNESS_NORMALIZE, "toInt" to (audio.loudnessLufs.toInt())), audio.loudness, { if (idle) model.setAudio { a -> a.copy(loudness = it) } })
    AfSwitch(tr(UiText.TRIM_NEW_TAKES), audio.trimSilence, { if (idle) model.setAudio { a -> a.copy(trimSilence = it) } })
    AfText(tr(UiText.BACKGROUND_MUSIC), muted = true, size = 12.sp)
    WrapRow {
        if (audio.bgm == null) {
            AfButton(tr(UiText.ADD_MUSIC), { if (idle) model.importBgm() }, icon = AfIcons.Regular.MusicNote)
        } else {
            // The 🎵 left the file name (Group 2 C §3): a music-note icon before it.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AfIcon(AfIcons.Regular.MusicNote, tint = AutoFlowColors.Muted, size = 16.dp)
                AfText(bgmName ?: tr(UiText.MUSIC_NAME), size = 12.sp)
            }
            AfButton(tr(UiText.CHANGE), { if (idle) model.importBgm() })
            AfButton(tr(UiText.REMOVE), { if (idle) model.setAudio { it.copy(bgm = null) } }, kind = ButtonKind.Danger)
        }
    }
    if (audio.bgm != null) {
        WrapRow {
            RecapModel.BGM_VOLUMES.forEach { (name, v) -> AfChip(bgmVolumeLabel(v), audio.bgmVolume == v, { if (idle) model.setAudio { it.copy(bgmVolume = v) } }) }
        }
        AfSwitch(tr(UiText.DUCK_MUSIC), audio.duck, { if (idle) model.setAudio { a -> a.copy(duck = it) } })
    }
}

/**
 * Studio Style · Audio (Group 2 C 17 · 2–3): glass switches, a hairline, "Background music", the file = a teal
 * music-note tile + its name, small Change… / Remove (danger), glass volume chips, ducking; no music = Add music….
 */
@Composable
private fun GlassSoundControls(audio: io.github.alalkipgen.autoflow.core.recap.RecapAudio, bgmName: String?, idle: Boolean, model: RecapModel) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        RecapSwitch(tr(UiText.VOICE_POLISH), audio.polish, { if (idle) model.setAudio { a -> a.copy(polish = it) } })
        RecapSwitch(tr(UiText.LOUDNESS_NORMALIZE, "toInt" to (audio.loudnessLufs.toInt())), audio.loudness, { if (idle) model.setAudio { a -> a.copy(loudness = it) } })
        RecapSwitch(tr(UiText.TRIM_NEW_TAKES), audio.trimSilence, { if (idle) model.setAudio { a -> a.copy(trimSilence = it) } })
    }
    GlassHairline()
    AfText(tr(UiText.BACKGROUND_MUSIC), muted = true, type = AfType.Caption)
    if (audio.bgm == null) {
        WrapRow { GlassButton(tr(UiText.ADD_MUSIC), { if (idle) model.importBgm() }, icon = AfIcons.Regular.MusicNote, small = true) }
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SoftTile(RecapTiles.Music.icon, RecapTiles.Music.colour, size = 30.dp)
        val name = bgmName ?: tr(UiText.MUSIC_NAME)
        // One line like the sample; the full name stays readable (D-100).
        AfText(name, Modifier.weight(1f).semantics { contentDescription = name }, type = AfType.Body, maxLines = 1, ellipsis = true)
    }
    WrapRow {
        GlassButton(tr(UiText.CHANGE), { if (idle) model.importBgm() }, icon = AfIcons.Regular.ArrowsClockwise, small = true)
        GlassButton(tr(UiText.REMOVE), { if (idle) model.setAudio { it.copy(bgm = null) } }, kind = ButtonKind.Danger, icon = AfIcons.Regular.Close, small = true)
    }
    WrapRow {
        RecapModel.BGM_VOLUMES.forEach { (_, v) -> RecapChip(bgmVolumeLabel(v), audio.bgmVolume == v, { if (idle) model.setAudio { it.copy(bgmVolume = v) } }) }
    }
    RecapSwitch(tr(UiText.DUCK_MUSIC), audio.duck, { if (idle) model.setAudio { a -> a.copy(duck = it) } })
}

/** Caption look (5B-8): size, colour, outline or a dark box behind the text. */
@Composable
private fun CaptionStyleControls(style: io.github.alalkipgen.autoflow.core.recap.CaptionStyle, idle: Boolean, model: RecapModel) {
    WrapRow {
        AfText(tr(UiText.CAPTION_SIZE), muted = true, size = 12.sp)
        io.github.alalkipgen.autoflow.core.recap.CaptionSize.entries.forEach { s ->
            AfChip(s.uiLabel(), style.size == s, { if (idle) model.setCaptionStyle { it.copy(size = s) } })
        }
    }
    WrapRow {
        AfText(tr(UiText.COLOUR), muted = true, size = 12.sp)
        io.github.alalkipgen.autoflow.core.recap.CaptionStyle.COLOURS.forEach { (name, hex) ->
            AfChip(colourPresetLabel(name), style.colour.equals(hex, ignoreCase = true), { if (idle) model.setCaptionStyle { it.copy(colour = hex) } })
        }
    }
    AfSwitch(tr(UiText.CAPTION_DARK_BOX), style.box, { on -> if (idle) model.setCaptionStyle { it.copy(box = on) } })
}

/** Same Library copy operations as Classic; names are transient and project-bound in Studio. */
@Composable
internal fun SaveCaptionPresetControls(recap: RecapV2, idle: Boolean, model: RecapModel, projectKey: String? = null, glass: Boolean = false) {
    var name by remember(projectKey) { mutableStateOf("") }
    val open = { name = "Caption ${recap.captionStyle.size.label} ${if (recap.captionStyle.box) "box" else "outline"}" }
    if (glass) {
        // Group 2 C 16 / 17 · 9: Save caption style… (floppy-disk) → a glass card with the name and Save to Library.
        if (name.isEmpty()) WrapRow { GlassButton(tr(UiText.SAVE_CAPTION_PRESET), open, enabled = idle, icon = AfIcons.Regular.FloppyDisk) }
        else GlassCard(padding = PaddingValues(12.dp), gap = 8.dp) {
            LibraryNameForm(name, { name = it }, tr(UiText.CAPTION_PRESET_NAME), idle, { model.saveCaptionPreset(name); name = "" }, { name = "" })
        }
        return
    }
    WrapRow {
        AfButton(tr(UiText.SAVE_CAPTION_PRESET), open, enabled = idle)
    }
    if (name.isNotEmpty()) {
        AfTextField(name, { name = it }, tr(UiText.CAPTION_PRESET_NAME))
        WrapRow {
            AfButton(tr(UiText.SAVE_TO_LIBRARY), {
                if (name.isNotBlank()) { model.saveCaptionPreset(name); name = "" }
            }, kind = ButtonKind.Primary, enabled = idle && name.isNotBlank())
            AfButton(tr(UiText.CANCEL), { name = "" })
        }
    }
}

@Composable
internal fun SaveBrandKitControls(recap: RecapV2, idle: Boolean, model: RecapModel, projectKey: String? = null, glass: Boolean = false) {
    var name by remember(projectKey) { mutableStateOf("") }
    if (glass) {
        // Group 2 C 15 / 17 · 8: Save as Brand Kit… (briefcase) → an inner glass card with the name and Save to Library.
        if (name.isEmpty()) WrapRow { GlassButton(tr(UiText.SAVE_BRAND_KIT), { name = "My Brand Kit" }, enabled = idle, icon = AfIcons.Regular.Briefcase) }
        else InnerGlassCard(null, null, gap = 8.dp) {
            LibraryNameForm(name, { name = it }, tr(UiText.BRAND_KIT_NAME), idle, { model.saveBrandKit(name); name = "" }, { name = "" })
        }
        return
    }
    WrapRow { AfButton(tr(UiText.SAVE_BRAND_KIT), { name = "My Brand Kit" }, enabled = idle, icon = AfIcons.Regular.Briefcase) }
    if (name.isNotEmpty()) {
        AfTextField(name, { name = it }, tr(UiText.BRAND_KIT_NAME))
        WrapRow {
            AfButton(tr(UiText.SAVE_TO_LIBRARY), {
                if (name.isNotBlank()) { model.saveBrandKit(name); name = "" }
            }, kind = ButtonKind.Primary, enabled = idle && name.isNotBlank())
            AfButton(tr(UiText.CANCEL), { name = "" })
        }
    }
}

/** A Library name form (Group 2 C 17 · 8–9): the label above a glass field, Save to Library (books, primary) and Cancel. */
@Composable
private fun LibraryNameForm(name: String, onName: (String) -> Unit, label: String, idle: Boolean, onSave: () -> Unit, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AfText(label, muted = true, type = AfType.Caption)
        AfTextField(name, onName, label, Modifier.fillMaxWidth(), mono = false, glass = true)
    }
    WrapRow {
        GlassButton(tr(UiText.SAVE_TO_LIBRARY), { if (name.isNotBlank()) onSave() }, kind = ButtonKind.Primary,
            enabled = idle && name.isNotBlank(), icon = AfIcons.Regular.Book)
        GlassButton(tr(UiText.CANCEL), onCancel)
    }
}
