package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        }
        AfText(fitFooter(recap, rows), bold = true)
        val idle = !state.busy && state.recording == null
        AfText("Movie sound under the voice", muted = true, size = 12.sp)
        WrapRow {
            RecapModel.MOVIE_VOLUMES.forEach { (name, v) -> AfChip(name, recap.movieVolume == v, { if (idle) model.setMovieVolume(v) }) }
        }
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
