package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.recap.Recap
import io.github.alalkipgen.autoflow.core.recap.RecapRegister
import io.github.alalkipgen.autoflow.core.recap.RecapRules
import io.github.alalkipgen.autoflow.core.recap.RecapSegment
import io.github.alalkipgen.autoflow.core.recap.RecapStyle
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfCard
import io.github.alalkipgen.autoflow.ui.components.AfChip
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.AfTextField
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.recap.RecapModel.Companion.clock
import io.github.alalkipgen.autoflow.ui.storyboard.text
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

const val WRITE_SCRIPT = "✨ Write script (Gemini)"
const val WRITE_MYSELF = "✎ Write it myself"
const val APPROVE_SCRIPT = "✓ Approve script"

/** Card 3 · Script (docs/specs/recap.md §4): summary, subtitles, style, Gemini or manual script, edit, approve. */
@Composable
internal fun ScriptCard(model: RecapModel, state: RecapUiState, recap: Recap) {
    AfCard("3 · Script") {
        RecapRules.canWriteManually(recap).blockers.firstOrNull()?.let {
            AfText(it.text(), muted = true)
            return@AfCard
        }
        AfText("Story summary (Burmese or English): who the people are and what happens. Gemini follows it.", muted = true, size = 12.sp)
        AfTextField(state.summary, { model.setSummary(it) }, "Summary", Modifier.fillMaxWidth(), lines = 5, mono = false, enabled = !state.busy)
        WrapRow {
            if (recap.subtitles == null) {
                AfButton("📄 Import subtitles (.srt)", { model.importSubtitles() }, enabled = !state.busy)
            } else {
                AfText("📄 subtitles.srt ✓", color = AutoFlowColors.Green)
                AfButton("Replace", { model.importSubtitles() }, enabled = !state.busy)
                AfButton("Remove", { model.removeSubtitles() }, kind = ButtonKind.Danger, enabled = !state.busy)
            }
        }
        // Subtitle track selection (5B-1): when the movie has embedded tracks, let the owner pick one.
        if (recap.movie != null && recap.subtitles == null) {
            AfText("Or extract from movie", muted = true, size = 12.sp)
            AfText("Track index (0-based ffprobe stream index, leave blank to auto-detect):", muted = true, size = 11.sp)
            var trackInput by remember { mutableStateOf(recap.subtitleTrack?.toString() ?: "") }
            WrapRow {
                io.github.alalkipgen.autoflow.ui.components.AfTextField(
                    trackInput, { trackInput = it }, "Track index",
                    Modifier, enabled = !state.busy,
                )
                AfButton("Set track", {
                    model.setSubtitleTrack(trackInput.trim().toIntOrNull())
                }, enabled = !state.busy)
                recap.subtitleTrack?.let { AfText("Selected: track $it", color = AutoFlowColors.Cyan, size = 12.sp) }
            }
        }
        AfText("Style", muted = true, size = 12.sp)
        WrapRow {
            AfChip("Plain", recap.style == RecapStyle.PLAIN, onClick = { model.setStyle(RecapStyle.PLAIN) })
            AfChip("Commentary", recap.style == RecapStyle.COMMENTARY, onClick = { model.setStyle(RecapStyle.COMMENTARY) })
        }
        AfText("Burmese register (affects TTS pronunciation)", muted = true, size = 12.sp)
        WrapRow {
            AfChip("Spoken (casual)", recap.register == RecapRegister.SPOKEN, onClick = { model.setRegister(RecapRegister.SPOKEN) })
            AfChip("Written (formal)", recap.register == RecapRegister.WRITTEN, onClick = { model.setRegister(RecapRegister.WRITTEN) })
        }
        val script = state.script
        var confirming by remember { mutableStateOf<String?>(null) }
        val running = state.job == RecapModel.WRITING
        if (running) {
            AfButton("Cancel", { model.cancelScript() }, kind = ButtonKind.Danger)
        } else if (confirming != null) {
            val gemini = confirming == WRITE_SCRIPT
            AfText(
                if (gemini) "Gemini writes ${recap.picked.size} segments, one per picked scene (one request)." +
                    (if (script != null) " The current script is replaced (it stays in the project as an older version)." else "")
                else "This starts an empty script, one segment per picked scene; the current script is replaced.",
                color = AutoFlowColors.Amber,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AfButton(if (gemini) "Write" else "Start", {
                    confirming = null
                    if (gemini) model.writeScript() else model.writeManually()
                }, kind = ButtonKind.Primary)
                AfButton("Back", { confirming = null })
            }
        } else {
            val gate = RecapRules.canGenerateScript(recap, state.summary, if (recap.subtitles != null) "srt" else "")
            if (!gate.allowed) AfText(gate.blockers.first().text(), muted = true, size = 12.sp)
            WrapRow {
                AfButton(WRITE_SCRIPT, { if (script == null) model.writeScript() else confirming = WRITE_SCRIPT },
                    kind = ButtonKind.Primary, enabled = gate.allowed && !state.busy)
                AfButton(WRITE_MYSELF, { if (script == null) model.writeManually() else confirming = WRITE_MYSELF }, enabled = !state.busy)
            }
        }
        if (script == null) return@AfCard
        val current = RecapRules.isCurrent(recap, script)
        if (!current) AfText(RecapRules.canApproveScript(recap, script).blockers.first().text(), color = AutoFlowColors.Amber, bold = true)
        script.segments.forEachIndexed { i, seg -> SegmentRow(model, i, seg, enabled = current && !state.busy) }
        if (recap.scriptApproved && current) {
            AfText("✓ Script approved. Next: Voice.", color = AutoFlowColors.Green, bold = true)
        } else if (current) {
            val gate = RecapRules.canApproveScript(recap, script)
            if (!gate.allowed) AfText(gate.blockers.first().text(), muted = true, size = 12.sp)
            AfButton(APPROVE_SCRIPT, { model.approveScript() }, kind = ButtonKind.Primary, enabled = gate.allowed && !state.busy)
        }
    }
}

@Composable
private fun SegmentRow(model: RecapModel, index: Int, seg: RecapSegment, enabled: Boolean) {
    var editing by remember(seg) { mutableStateOf(false) }
    var draft by remember(seg) { mutableStateOf(seg.textMy) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfText("#${index + 1} · ${clock(seg.startMs)}–${clock(seg.endMs)} · ${(seg.durationMs + 500) / 1000} s", Modifier.weight(1f), muted = true, size = 12.sp)
            if (!editing) AfButton("✎", { editing = true }, enabled = enabled)
        }
        if (editing) {
            AfTextField(draft, { draft = it }, "Narration for scene ${index + 1}", Modifier.fillMaxWidth(), lines = 3, mono = false)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AfButton("Save", { if (model.saveSegment(index, draft)) editing = false }, kind = ButtonKind.Primary)
                AfButton("Back", { editing = false; draft = seg.textMy })
            }
        } else {
            AfText(seg.textMy.ifBlank { "(empty — tap ✎ to write)" }, muted = seg.textMy.isBlank())
        }
    }
}
