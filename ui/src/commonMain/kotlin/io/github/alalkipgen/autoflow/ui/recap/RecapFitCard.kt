package io.github.alalkipgen.autoflow.ui.recap

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
    FitStatus.FITS -> "✓ ကိုက်တယ်"
    FitStatus.RUNS_ON -> "+${seconds(overMs)}: ပြကွက်က ပိုရှည်နေတယ်"
    FitStatus.NO_VOICE -> UiText.NO_VOICE.text(language)
    FitStatus.STALE -> "⚠ ${UiText.STALE.text(language)}: ပြန်သွင်း ဒါမှမဟုတ် ပြန်ပြောပါ"
}

/** Footer: "Recap 4:51 (target 5:00) · 12 of 14 voiced". */
fun fitFooter(recap: RecapV2, rows: List<FitRow>): String =
    "Recap ${clock(RecapFit.totalMs(rows))} (ပစ်မှတ် ${clock(recap.targetMs)}) · ${rows.size} ခုအနက် ${rows.count { it.takeMs != null }} ခု အသံပါ"

/** Card 5 · Fit check (docs/specs/recap.md §6): one row per scene, movie sound, looks, captions, then Export. */
@Composable
internal fun FitCard(model: RecapModel, state: RecapUiState, recap: RecapV2, onGoToExport: () -> Unit) {
    AfCard("5 · ကိုက်ညီမှု စစ်ဆေး") {
        val story = state.story
        RecapRulesV2.canVoice(recap, story).blockers.firstOrNull()?.let {
            AfText(it.text(), muted = true)
            return@AfCard
        }
        story!!
        // v2 rows (audit F07): by block id, every clip of a block, the chosen take.
        val rows = RecapFit.rows(recap, story, state.lexicon)
        rows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AfText("${row.segment + 1}", muted = true, mono = true)
                AfText("ပြကွက် ${seconds(row.sceneMs)}" + (row.takeMs?.let { " · အသံ ${seconds(it)}" } ?: ""), Modifier.weight(1f))
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
                AfText("ပြင်ရန် (+${seconds(row.overMs)} ပို):", muted = true, size = 11.sp)
                WrapRow {
                    listOf(
                        FitChoice.EXTEND to "Clip ဆန့်မယ်",
                        FitChoice.NEXT_RANGE to "နောက်အပိုင်း ယူမယ်",
                        FitChoice.SPEED to "အသံ မြန်စေမယ်",
                        FitChoice.SLOW_VIDEO to "ဗွီဒီယို နှေးစေမယ်",
                        FitChoice.FREEZE to "ပုံ ရပ်ထားမယ်",
                    ).forEach { (choice, label) ->
                        AfChip(label, current == choice, onClick = { model.setFitChoice(block, choice) })
                    }
                }
            }
        }
        AfText(fitFooter(recap, rows), bold = true)
        val idle = !state.busy && state.recording == null
        AfText("အသံအောက်က ရုပ်ရှင်အသံ", muted = true, size = 12.sp)
        WrapRow {
            RecapModel.MOVIE_VOLUMES.forEach { (name, v) -> AfChip(name, recap.movieVolume == v, { if (idle) model.setMovieVolume(v) }) }
        }
        RecapSoundControls(recap.audio, state.bgmName, idle, model)
        AfText("Looks (ရုပ်ရှင်ပုံ ပြောင်းမယ်)", muted = true, size = 12.sp)
        WrapRow {
            RecapModel.LOOKS.forEach { (look, name) -> AfChip(name, look in recap.looks, { if (idle) model.toggleLook(look) }) }
        }
        RecapPictureControls(recap, idle, model)
        AfSwitch(BURN_CAPTIONS, recap.burnCaptions, { if (idle) model.setBurnCaptions(it) })
        // Caption style settings are in RecapPictureControls → ✍️ Captions section (D-58).
        if (recap.burnCaptions) {
        // Brand Kit save (5B-3, D-51) and Caption Preset save (5B-8, D-51)
        var showBrandKitName by remember { mutableStateOf("") }
        var showCaptionPresetName by remember { mutableStateOf("") }
        WrapRow {
            AfButton("စာတန်းပုံစံ သိမ်းမယ်…", {
                showCaptionPresetName = "Caption ${recap.captionStyle.size.label} ${if (recap.captionStyle.box) "box" else "outline"}"
            }, enabled = idle)
        }
        if (showCaptionPresetName.isNotEmpty()) {
            AfTextField(showCaptionPresetName, { showCaptionPresetName = it }, "စာတန်း preset အမည်")
            WrapRow {
                AfButton("စာကြည့်တိုက်ထဲ သိမ်းမယ်", {
                    if (showCaptionPresetName.isNotBlank()) { model.saveCaptionPreset(showCaptionPresetName); showCaptionPresetName = "" }
                }, kind = ButtonKind.Primary, enabled = idle && showCaptionPresetName.isNotBlank())
                AfButton(tr(UiText.CANCEL), { showCaptionPresetName = "" })
            }
        }
    }
    AfText("စာတန်းကို ဗွီဒီယိုဘေးမှာ .srt ဖိုင်အဖြစ်လည်း အမြဲ သိမ်းတယ်။", muted = true, size = 12.sp)
    // Brand Kit save (5B-3, D-51)
    var brandKitName by remember { mutableStateOf("") }
    WrapRow { AfButton("💼 Brand Kit အဖြစ် သိမ်းမယ်…", { brandKitName = "My Brand Kit" }, enabled = idle) }
    if (brandKitName.isNotEmpty()) {
        AfTextField(brandKitName, { brandKitName = it }, "Brand Kit အမည်")
        WrapRow {
            AfButton("စာကြည့်တိုက်ထဲ သိမ်းမယ်", {
                if (brandKitName.isNotBlank()) { model.saveBrandKit(brandKitName); brandKitName = "" }
            }, kind = ButtonKind.Primary, enabled = idle && brandKitName.isNotBlank())
            AfButton(tr(UiText.CANCEL), { brandKitName = "" })
        }
    }
        val render = RecapRulesV2.canRender(recap, story, state.lexicon)
        render.blockers.firstOrNull()?.let { AfText(it.text(), color = AutoFlowColors.Amber) }
        AfButton(GO_TO_EXPORT, onGoToExport, kind = ButtonKind.Primary, enabled = render.allowed)
    }
}

/** Sound (5B-4): voice polish, background music with ducking, even loudness. Applied when the video is rendered. */
@Composable
private fun RecapSoundControls(audio: io.github.alalkipgen.autoflow.core.recap.RecapAudio, bgmName: String?, idle: Boolean, model: RecapModel) {
    AfSwitch("အသံ သန့်စင်မယ် (ဆူညံသံ လျော့၊ အသံအဆင့် ငြိမ်)", audio.polish, { if (idle) model.setAudio { a -> a.copy(polish = it) } })
    AfSwitch("အသံကျယ်မှု ညီအောင် (${audio.loudnessLufs.toInt()} LUFS၊ YouTube / TikTok လို)", audio.loudness, { if (idle) model.setAudio { a -> a.copy(loudness = it) } })
    AfSwitch("take အသစ်တွေမှာ ရပ်နားချိန်ရှည်တွေ ဖြတ်မယ် (သွင်း၊ ထည့်၊ ပြော)", audio.trimSilence, { if (idle) model.setAudio { a -> a.copy(trimSilence = it) } })
    AfText("နောက်ခံတေး", muted = true, size = 12.sp)
    WrapRow {
        if (audio.bgm == null) {
            AfButton("🎵 တေးဂီတ ထည့်မယ်…", { if (idle) model.importBgm() })
        } else {
            AfText("🎵 ${bgmName ?: "Music"}", size = 12.sp)
            AfButton("Change…", { if (idle) model.importBgm() })
            AfButton("Remove", { if (idle) model.setAudio { it.copy(bgm = null) } }, kind = ButtonKind.Danger)
        }
    }
    if (audio.bgm != null) {
        WrapRow {
            RecapModel.BGM_VOLUMES.forEach { (name, v) -> AfChip(name, audio.bgmVolume == v, { if (idle) model.setAudio { it.copy(bgmVolume = v) } }) }
        }
        AfSwitch("အသံပြောနေချိန် တေးဂီတ အသံ လျှော့မယ်", audio.duck, { if (idle) model.setAudio { a -> a.copy(duck = it) } })
    }
}

/** Caption look (5B-8): size, colour, outline or a dark box behind the text. */
@Composable
private fun CaptionStyleControls(style: io.github.alalkipgen.autoflow.core.recap.CaptionStyle, idle: Boolean, model: RecapModel) {
    WrapRow {
        AfText("Caption size", muted = true, size = 12.sp)
        io.github.alalkipgen.autoflow.core.recap.CaptionSize.entries.forEach { s ->
            AfChip(s.label, style.size == s, { if (idle) model.setCaptionStyle { it.copy(size = s) } })
        }
    }
    WrapRow {
        AfText("Colour", muted = true, size = 12.sp)
        io.github.alalkipgen.autoflow.core.recap.CaptionStyle.COLOURS.forEach { (name, hex) ->
            AfChip(name, style.colour.equals(hex, ignoreCase = true), { if (idle) model.setCaptionStyle { it.copy(colour = hex) } })
        }
    }
    AfSwitch("Dark box behind the captions", style.box, { on -> if (idle) model.setCaptionStyle { it.copy(box = on) } })
}
