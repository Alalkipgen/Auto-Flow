package io.github.alalkipgen.autoflow.ui.recap

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
import io.github.alalkipgen.autoflow.core.recap.RecapFit
import io.github.alalkipgen.autoflow.core.recap.RecapRules
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfCard
import io.github.alalkipgen.autoflow.ui.components.AfChip
import io.github.alalkipgen.autoflow.ui.components.AfSwitch
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.recap.RecapModel.Companion.clock
import io.github.alalkipgen.autoflow.ui.recap.RecapModel.Companion.seconds
import io.github.alalkipgen.autoflow.ui.storyboard.text
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

const val GO_TO_EXPORT = "Go to Export →"
const val BURN_CAPTIONS = "Burn captions into the video"

/** What a fit row says (spec §6): "✓ fits", "+1.8 s: the scene runs on", "No voice", "⚠ Stale". */
fun FitRow.label(): String = when (status) {
    FitStatus.FITS -> "✓ fits"
    FitStatus.RUNS_ON -> "+${seconds(overMs)}: the scene runs on"
    FitStatus.NO_VOICE -> "No voice"
    FitStatus.STALE -> "⚠ Stale: record or speak it again"
}

/** Footer: "Recap 4:51 (target 5:00) · 12 of 14 voiced". */
fun fitFooter(recap: Recap, rows: List<FitRow>): String =
    "Recap ${clock(RecapFit.totalMs(rows))} (target ${clock(recap.targetMs)}) · ${rows.count { it.takeMs != null }} of ${rows.size} voiced"

/** Card 5 · Fit check (docs/specs/recap.md §6): one row per scene, movie sound, looks, captions, then Export. */
@Composable
internal fun FitCard(model: RecapModel, state: RecapUiState, recap: Recap, onGoToExport: () -> Unit) {
    AfCard("5 · Fit check") {
        val script = state.script
        RecapRules.canVoice(recap, script).blockers.firstOrNull()?.let {
            AfText(it.text(), muted = true)
            return@AfCard
        }
        script!!
        val rows = RecapFit.rows(recap, script)
        rows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AfText("${row.segment + 1}", muted = true, mono = true)
                AfText("${seconds(row.sceneMs)} scene" + (row.takeMs?.let { " · ${seconds(it)} voice" } ?: ""), Modifier.weight(1f))
                AfText(
                    row.label(),
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
                val current = recap.fit[row.segment] ?: FitChoice.EXTEND
                AfText("Fix (+${seconds(row.overMs)} over):", muted = true, size = 11.sp)
                WrapRow {
                    listOf(
                        FitChoice.EXTEND to "Extend clip",
                        FitChoice.NEXT_RANGE to "Next range",
                        FitChoice.SPEED to "Speed voice",
                        FitChoice.SLOW_VIDEO to "Slow video",
                        FitChoice.FREEZE to "Freeze frame",
                    ).forEach { (choice, label) ->
                        AfChip(label, current == choice, onClick = { model.setFitChoice(row.segment, choice) })
                    }
                }
            }
        }
        AfText(fitFooter(recap, rows), bold = true)
        val idle = !state.busy && state.recording == null
        AfText("Movie sound under the voice", muted = true, size = 12.sp)
        WrapRow {
            RecapModel.MOVIE_VOLUMES.forEach { (name, v) -> AfChip(name, recap.movieVolume == v, { if (idle) model.setMovieVolume(v) }) }
        }
        RecapSoundControls(recap.audio, state.bgmName, idle, model)
        AfText("Looks (change the movie picture)", muted = true, size = 12.sp)
        WrapRow {
            RecapModel.LOOKS.forEach { (look, name) -> AfChip(name, look in recap.looks, { if (idle) model.toggleLook(look) }) }
        }
        AfSwitch(BURN_CAPTIONS, recap.burnCaptions, { if (idle) model.setBurnCaptions(it) })
        AfText("Captions are always saved as an .srt file next to the video, too.", muted = true, size = 12.sp)
        val render = RecapRules.canRender(recap, script)
        render.blockers.firstOrNull()?.let { AfText(it.text(), color = AutoFlowColors.Amber) }
        AfButton(GO_TO_EXPORT, onGoToExport, kind = ButtonKind.Primary, enabled = render.allowed)
    }
}

/** Sound (5B-4): voice polish, background music with ducking, even loudness. Applied when the video is rendered. */
@Composable
private fun RecapSoundControls(audio: io.github.alalkipgen.autoflow.core.recap.RecapAudio, bgmName: String?, idle: Boolean, model: RecapModel) {
    AfSwitch("Clean up the voice (less noise and rumble, steadier level)", audio.polish, { if (idle) model.setAudio { a -> a.copy(polish = it) } })
    AfSwitch("Even loudness (${audio.loudnessLufs.toInt()} LUFS, like YouTube / TikTok)", audio.loudness, { if (idle) model.setAudio { a -> a.copy(loudness = it) } })
    AfText("Background music", muted = true, size = 12.sp)
    WrapRow {
        if (audio.bgm == null) {
            AfButton("🎵 Add music…", { if (idle) model.importBgm() })
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
        AfSwitch("Lower the music while the voice speaks", audio.duck, { if (idle) model.setAudio { a -> a.copy(duck = it) } })
    }
}
