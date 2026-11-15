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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.recap.Recap
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.core.recap.RecapRegister
import io.github.alalkipgen.autoflow.core.recap.RecapRulesV2
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
internal fun ScriptCard(model: RecapModel, state: RecapUiState, recap: RecapV2, blockIndex: Int? = null) {
    AfCard("3 · Script") {
        RecapRulesV2.canWriteManually(recap).blockers.firstOrNull()?.let {
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
        // Subtitle track selection (5B-1): show embedded tracks found in the movie.
        if (recap.movie != null && recap.subtitles == null) {
            val movieTracks = state.movieSubtitleTracks
            if (movieTracks.isNotEmpty()) {
                AfText("Or extract from movie", muted = true, size = 12.sp)
                WrapRow {
                    movieTracks.forEach { track ->
                        val label = buildString {
                            append("Track ${track.index}")
                            track.language?.let { append(" [$it]") }
                            track.title?.let { append(" — $it") }
                            append(if (track.isText) "" else " (image)")
                        }
                        AfChip(label, false, onClick = {
                            if (!state.busy) {
                                if (track.isText) model.extractSubtitleTrack(track)
                                else model.setSubtitleTrack(track.index)
                            }
                        })
                    }
                }
                if (movieTracks.any { !it.isText }) {
                    AfText("Image-based subtitle tracks cannot be extracted automatically. Use Transcribe instead.", muted = true, size = 11.sp)
                }
            } else if (recap.movie != null) {
                AfText("No embedded subtitle tracks found in this movie.", muted = true, size = 12.sp)
            }
        }
        TranscribeSection(model, state, recap)
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
        // Hook style (5B-7): opening tone for segment 1
        AfText("Opening hook for segment 1", muted = true, size = 12.sp)
        WrapRow {
            io.github.alalkipgen.autoflow.core.recap.RecapHookStyle.entries.forEach { hook ->
                val label = when (hook) {
                    io.github.alalkipgen.autoflow.core.recap.RecapHookStyle.DEFAULT -> "AI decides"
                    io.github.alalkipgen.autoflow.core.recap.RecapHookStyle.QUESTION -> "Question"
                    io.github.alalkipgen.autoflow.core.recap.RecapHookStyle.QUOTE -> "Quote"
                    io.github.alalkipgen.autoflow.core.recap.RecapHookStyle.ACTION -> "Action"
                }
                AfChip(label, recap.hookStyle == hook, onClick = { model.setHookStyle(hook) })
            }
        }
        // Credit line (5B-7)
        AfText("Source / credit line (appended to last segment)", muted = true, size = 12.sp)
        var creditDraft by remember(recap.creditLine) { mutableStateOf(recap.creditLine) }
        io.github.alalkipgen.autoflow.ui.components.AfTextField(
            creditDraft, { creditDraft = it }, "e.g. ကား — The Avengers (2012) မှ",
            Modifier.fillMaxWidth(), enabled = !state.busy,
        )
        if (creditDraft != recap.creditLine) {
            AfButton("Save credit", { model.setCreditLine(creditDraft) })
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
            val gate = RecapRulesV2.canGenerateScript(recap, state.summary, if (recap.subtitles != null || state.transcribe.lines > 0) "srt" else "")
            if (!gate.allowed) AfText(gate.blockers.first().text(), muted = true, size = 12.sp)
            WrapRow {
                AfButton(WRITE_SCRIPT, { if (script == null) model.writeScript() else confirming = WRITE_SCRIPT },
                    kind = ButtonKind.Primary, enabled = gate.allowed && !state.busy)
                AfButton(WRITE_MYSELF, { if (script == null) model.writeManually() else confirming = WRITE_MYSELF }, enabled = !state.busy)
            }
        }
        if (script == null) return@AfCard
        // v2 rules on the story (audit F05): outdated only when a clip lies outside the movie.
        val story = state.story
        val current = story != null && RecapRulesV2.isCurrent(recap, story)
        if (!current) AfText(RecapRulesV2.canApproveScript(recap, story).blockers.first().text(), color = AutoFlowColors.Amber, bold = true)
        script.segments.forEachIndexed { i, seg -> if (blockIndex == null || i == blockIndex) SegmentRow(model, state, i, seg, enabled = current && !state.busy) }
        if (recap.scriptApproved && current) {
            AfText("✓ Script approved. Next: Voice.", color = AutoFlowColors.Green, bold = true)
        } else if (current) {
            val gate = RecapRulesV2.canApproveScript(recap, story)
            if (!gate.allowed) AfText(gate.blockers.first().text(), muted = true, size = 12.sp)
            AfButton(APPROVE_SCRIPT, { model.approveScript() }, kind = ButtonKind.Primary, enabled = gate.allowed && !state.busy)
        }
    }
}

/**
 * "Transcribe picked scenes" (5B-1, D-44): offered for picked scenes without `.srt` lines; a confirm card (scenes,
 * requests, today's usage) before anything is sent; progress with Cancel; Retry for a paused or failed job (only the
 * requests left are sent).
 */
@Composable
private fun TranscribeSection(model: RecapModel, state: RecapUiState, recap: RecapV2) {
    val t = state.transcribe
    if (!t.available || recap.movie == null) return
    if (t.lines > 0 || t.transcribedScenes > 0) {
        AfText("🎙 Transcript ✓ ${t.lines} lines from ${t.transcribedScenes} scene(s)", Modifier.testTag("transcribe-summary"), color = AutoFlowColors.Green)
    }
    val running = state.job?.startsWith("Transcribing") == true
    val confirm = t.confirm
    val job = t.job
    when {
        running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfText(state.job.orEmpty(), Modifier.weight(1f).testTag("transcribe-progress"), muted = true, size = 12.sp)
            AfButton("Cancel", { model.cancelTranscribe() }, Modifier.testTag("transcribe-cancel"), kind = ButtonKind.Danger)
        }
        confirm != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AfText(
                "Transcribe ${confirm.scenes} picked scene(s) without subtitles: ${confirm.requests} Gemini request(s), " +
                    "${clock(confirm.audioMs)} of sound. Only these scenes' sound is sent — never the picture or the whole movie. " +
                    "Today so far: ${confirm.usedToday?.toString() ?: "…"} request(s).",
                Modifier.testTag("transcribe-confirm"), color = AutoFlowColors.Amber,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AfButton("Start", { model.transcribeScenes(confirmed = true) }, Modifier.testTag("transcribe-confirm-start"), kind = ButtonKind.Primary)
                AfButton("Back", { model.dismissTranscribe() }, Modifier.testTag("transcribe-confirm-back"))
            }
        }
        job != null && (job.state == AiJobState.FAILED || job.state == AiJobState.PAUSED) -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AfText(
                (job.error ?: "Transcribe stopped.") + " ${job.items.size} request(s) left.",
                Modifier.testTag("transcribe-failed"), color = AutoFlowColors.Amber,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AfButton("Retry", { model.retryTranscribe() }, Modifier.testTag("transcribe-retry"), kind = ButtonKind.Primary, enabled = !state.busy)
                AfButton("Cancel job", { model.cancelTranscribe() }, Modifier.testTag("transcribe-cancel"), kind = ButtonKind.Danger)
            }
        }
        job != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfText("Transcribe is running (${job.items.size} request(s) left): see Jobs.", Modifier.weight(1f).testTag("transcribe-progress"), muted = true, size = 12.sp)
            AfButton("Cancel", { model.cancelTranscribe() }, Modifier.testTag("transcribe-cancel"), kind = ButtonKind.Danger)
        }
        t.todoScenes > 0 -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AfText("${t.todoScenes} picked scene(s) have no subtitles. Gemini can transcribe just their sound for the script.", muted = true, size = 12.sp)
            AfButton("🎙 Transcribe picked scenes", { model.transcribeScenes() }, Modifier.testTag("transcribe-start"), enabled = !state.busy)
        }
    }
}

@Composable
private fun SegmentRow(model: RecapModel, state: RecapUiState, index: Int, seg: RecapSegment, enabled: Boolean) {
    var editing by remember(seg) { mutableStateOf(false) }
    var editTiming by remember(seg) { mutableStateOf(false) }
    var draft by remember(seg) { mutableStateOf(seg.textMy) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfText("#${index + 1} · ${clock(seg.startMs)}–${clock(seg.endMs)} · ${(seg.durationMs + 500) / 1000} s", Modifier.weight(1f), muted = true, size = 12.sp)
            if (!editing && !editTiming) {
                AfButton("✎", { editing = true }, enabled = enabled)
                AfButton("⏱", { editTiming = true }, enabled = enabled)
            }
        }
        TakeVoiceLabel(state.recap?.takeFor(state.story!!.blocks[index].id), state.recap?.voiceLock)
        if (editTiming) {
            // Caption timing (D-53, audit F05): in the voice take, saved as captions.chunkEdits; the movie clip
            // shown above is not changed.
            val window = state.captionWindow(index)
            if (window != null) {
                TimingEditRow(window.first, window.second, state.hasCaptionTiming(index), { model.resetCaptionTiming(index) }) { s, e ->
                    model.saveSegmentTiming(index, s, e).also { if (it) editTiming = false }
                }
            }
            AfButton("Back", { editTiming = false })
        } else if (editing) {
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

/** Format ms as `hh:mm:ss` for display. */
private fun clock(ms: Long): String {
    val s = ms / 1000; return "${s / 3600}:${(s / 60 % 60).toString().padStart(2,'0')}:${(s % 60).toString().padStart(2,'0')}"
}


