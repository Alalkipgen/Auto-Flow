package io.github.alalkipgen.autoflow.ui.recap

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

const val GO_TO_EXPORT = "ထုတ်ယူ သို့ သွားမယ် →"
const val BURN_CAPTIONS = "စာတန်းကို ဗွီဒီယိုထဲ မြှုပ်မယ်"

/** What a fit row says (spec §6): "✓ fits", "+1.8 s: the scene runs on", "No voice", "⚠ Stale". */
fun FitRow.label(language: io.github.alalkipgen.autoflow.ui.i18n.UiLanguage = io.github.alalkipgen.autoflow.ui.i18n.UiLanguage.ENGLISH): String = when (status) {
    FitStatus.FITS -> UiText.FIT_FITS.text(language)
    FitStatus.RUNS_ON -> UiText.FIT_RUNS_ON.text(language, "secondsoverMs" to (seconds(overMs)))
    FitStatus.NO_VOICE -> UiText.NO_VOICE.text(language)
    FitStatus.STALE -> UiText.FIT_STALE.text(language, "textlanguage" to (UiText.STALE.text(language)))
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
                val current = recap.fitChoice(block) ?: FitChoice.EXTEND
                AfText(tr(UiText.FIT_ACTIONS, "overMs" to (seconds(row.overMs))), muted = true, size = 11.sp)
                WrapRow {
                    listOf(
                        FitChoice.EXTEND to tr(UiText.FIT_EXTEND),
                        FitChoice.NEXT_RANGE to tr(UiText.FIT_NEXT_RANGE),
                        FitChoice.SPEED to tr(UiText.FIT_SPEED),
                        FitChoice.SLOW_VIDEO to tr(UiText.FIT_SLOW_VIDEO),
                        FitChoice.FREEZE to tr(UiText.FIT_FREEZE),
                    ).forEach { (choice, label) ->
                        AfChip(label, current == choice, onClick = { model.setFitChoice(block, choice) })
                    }
                }
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
        if (recap.burnCaptions) {
        // Brand Kit save (5B-3, D-51) and Caption Preset save (5B-8, D-51)
        var showBrandKitName by remember { mutableStateOf("") }
        var showCaptionPresetName by remember { mutableStateOf("") }
        WrapRow {
            AfButton(tr(UiText.SAVE_CAPTION_PRESET), {
                showCaptionPresetName = "Caption ${recap.captionStyle.size.label} ${if (recap.captionStyle.box) "box" else "outline"}"
            }, enabled = idle)
        }
        if (showCaptionPresetName.isNotEmpty()) {
            AfTextField(showCaptionPresetName, { showCaptionPresetName = it }, tr(UiText.CAPTION_PRESET_NAME))
            WrapRow {
                AfButton(tr(UiText.SAVE_TO_LIBRARY), {
                    if (showCaptionPresetName.isNotBlank()) { model.saveCaptionPreset(showCaptionPresetName); showCaptionPresetName = "" }
                }, kind = ButtonKind.Primary, enabled = idle && showCaptionPresetName.isNotBlank())
                AfButton(tr(UiText.CANCEL), { showCaptionPresetName = "" })
            }
        }
    }
    AfText(tr(UiText.CAPTIONS_SRT_ALWAYS), muted = true, size = 12.sp)
    // Brand Kit save (5B-3, D-51)
    var brandKitName by remember { mutableStateOf("") }
    WrapRow { AfButton(tr(UiText.SAVE_BRAND_KIT), { brandKitName = "My Brand Kit" }, enabled = idle) }
    if (brandKitName.isNotEmpty()) {
        AfTextField(brandKitName, { brandKitName = it }, tr(UiText.BRAND_KIT_NAME))
        WrapRow {
            AfButton(tr(UiText.SAVE_TO_LIBRARY), {
                if (brandKitName.isNotBlank()) { model.saveBrandKit(brandKitName); brandKitName = "" }
            }, kind = ButtonKind.Primary, enabled = idle && brandKitName.isNotBlank())
            AfButton(tr(UiText.CANCEL), { brandKitName = "" })
        }
    }
        val render = RecapRulesV2.canRender(recap, story, state.lexicon)
        render.blockers.firstOrNull()?.let { AfText(it.text(LocalUiLanguage.current), color = AutoFlowColors.Amber) }
        AfButton(tr(UiText.GO_TO_EXPORT), onGoToExport, kind = ButtonKind.Primary, enabled = render.allowed)
    }
}

/** Sound (5B-4): voice polish, background music with ducking, even loudness. Applied when the video is rendered. */
@Composable
private fun RecapSoundControls(audio: io.github.alalkipgen.autoflow.core.recap.RecapAudio, bgmName: String?, idle: Boolean, model: RecapModel) {
    AfSwitch(tr(UiText.VOICE_POLISH), audio.polish, { if (idle) model.setAudio { a -> a.copy(polish = it) } })
    AfSwitch(tr(UiText.LOUDNESS_NORMALIZE, "toInt" to (audio.loudnessLufs.toInt())), audio.loudness, { if (idle) model.setAudio { a -> a.copy(loudness = it) } })
    AfSwitch(tr(UiText.TRIM_NEW_TAKES), audio.trimSilence, { if (idle) model.setAudio { a -> a.copy(trimSilence = it) } })
    AfText(tr(UiText.BACKGROUND_MUSIC), muted = true, size = 12.sp)
    WrapRow {
        if (audio.bgm == null) {
            AfButton(tr(UiText.ADD_MUSIC), { if (idle) model.importBgm() })
        } else {
            AfText("🎵 ${bgmName ?: "Music"}", size = 12.sp)
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
