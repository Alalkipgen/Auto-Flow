package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import io.github.alalkipgen.autoflow.ui.jobs.ProjectAiJobCard
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
internal fun ScriptCard(model: RecapModel, state: RecapUiState, recap: RecapV2, blockIndex: Int? = null, studioOptions: Boolean = false) {
    val content: @Composable ColumnScope.() -> Unit = content@{
        RecapRulesV2.canWriteManually(recap).blockers.firstOrNull()?.let {
            AfText(it.text(LocalUiLanguage.current), muted = true)
            return@content
        }
        var style by remember(state.projectDir, state.story?.style) { mutableStateOf(state.story?.style ?: recap.style) }
        var register by remember(state.projectDir, state.story?.register) { mutableStateOf(state.story?.register ?: recap.register) }
        LaunchedEffect(state.projectDir, state.story?.style, state.story?.register) {
            if (studioOptions) { model.setStyle(style); model.setRegister(register) }
        }
        if (!studioOptions) {
            SourceSummary(model, state)
            SourceSubtitles(model, state, recap)
            TranscribeSection(model, state, recap)
        }
        if (studioOptions) AfText(My.breakSyllables(tr(UiText.V3_STORY_WRITING_HINT)), muted = true)
        AfText(tr(UiText.STYLE), muted = true, size = 12.sp)
        WrapRow {
            AfChip(tr(UiText.STYLE_PLAIN), (if (studioOptions) style else recap.style) == RecapStyle.PLAIN, onClick = { style = RecapStyle.PLAIN; model.setStyle(style) })
            AfChip(tr(UiText.STYLE_COMMENTARY), (if (studioOptions) style else recap.style) == RecapStyle.COMMENTARY, onClick = { style = RecapStyle.COMMENTARY; model.setStyle(style) })
        }
        AfText(tr(UiText.BURMESE_REGISTER), muted = true, size = 12.sp)
        WrapRow {
            AfChip(tr(UiText.REGISTER_SPOKEN), (if (studioOptions) register else recap.register) == RecapRegister.SPOKEN, onClick = { register = RecapRegister.SPOKEN; model.setRegister(register) })
            AfChip(tr(UiText.REGISTER_WRITTEN), (if (studioOptions) register else recap.register) == RecapRegister.WRITTEN, onClick = { register = RecapRegister.WRITTEN; model.setRegister(register) })
        }
        // Hook style (5B-7): opening tone for segment 1
        AfText(tr(UiText.OPENING_HOOK), muted = true, size = 12.sp)
        WrapRow {
            io.github.alalkipgen.autoflow.core.recap.RecapHookStyle.entries.forEach { hook ->
                val label = when (hook) {
                    io.github.alalkipgen.autoflow.core.recap.RecapHookStyle.DEFAULT -> tr(UiText.HOOK_DEFAULT)
                    io.github.alalkipgen.autoflow.core.recap.RecapHookStyle.QUESTION -> tr(UiText.HOOK_QUESTION)
                    io.github.alalkipgen.autoflow.core.recap.RecapHookStyle.QUOTE -> tr(UiText.HOOK_QUOTE)
                    io.github.alalkipgen.autoflow.core.recap.RecapHookStyle.ACTION -> tr(UiText.HOOK_ACTION)
                }
                AfChip(label, recap.hookStyle == hook, onClick = { model.setHookStyle(hook) })
            }
        }
        // Credit line (5B-7)
        AfText(tr(UiText.SOURCE_CREDIT_LINE), muted = true, size = 12.sp)
        var creditDraft by remember(state.projectDir, recap.creditLine) { mutableStateOf(recap.creditLine) }
        io.github.alalkipgen.autoflow.ui.components.AfTextField(
            creditDraft, { creditDraft = it }, tr(UiText.CREDIT_EXAMPLE),
            Modifier.fillMaxWidth(), enabled = !state.busy,
        )
        if (creditDraft != recap.creditLine) {
            AfButton(tr(UiText.SAVE_CREDIT), { model.setCreditLine(creditDraft) })
        }
        val script = state.script
        var confirming by remember(state.projectDir) { mutableStateOf<String?>(null) }
        val running = state.job == RecapModel.WRITING
        if (running) {
            AfButton(tr(UiText.CANCEL), { model.cancelScript() }, kind = ButtonKind.Danger)
        } else if (confirming != null) {
            val gemini = confirming == WRITE_SCRIPT
            AfText(
                if (gemini) tr(UiText.SCRIPT_GENERATE_CONFIRM, "size" to (recap.picked.size)) +
                    (if (script != null) tr(UiText.SCRIPT_REPLACE_HINT) else "")
                else tr(UiText.SCRIPT_MANUAL_CONFIRM),
                color = AutoFlowColors.Amber,
            )
            WrapRow {
                AfButton(if (gemini) tr(UiText.WRITE) else tr(UiText.START), {
                    confirming = null
                    if (gemini) model.writeScript() else model.writeManually()
                }, kind = ButtonKind.Primary)
                AfButton(tr(UiText.BACK), { confirming = null })
            }
        } else {
            val gate = RecapRulesV2.canGenerateScript(recap, state.summary, if (recap.subtitles != null || state.transcribe.lines > 0) "srt" else "")
            if (!gate.allowed) AfText(gate.blockers.first().text(LocalUiLanguage.current), muted = true, size = 12.sp)
            WrapRow {
                AfButton(tr(UiText.WRITE_SCRIPT), { if (script == null && !studioOptions) model.writeScript() else confirming = WRITE_SCRIPT },
                    kind = ButtonKind.Primary, enabled = gate.allowed && !state.busy)
                AfButton(tr(UiText.WRITE_MYSELF), { if (script == null) model.writeManually() else confirming = WRITE_MYSELF }, enabled = !state.busy)
            }
        }
        if (script == null || studioOptions) return@content
        // v2 rules on the story (audit F05): outdated only when a clip lies outside the movie.
        val story = state.story
        val current = story != null && RecapRulesV2.isCurrent(recap, story)
        if (!current) AfText(RecapRulesV2.canApproveScript(recap, story).blockers.first().text(LocalUiLanguage.current), color = AutoFlowColors.Amber, bold = true)
        script.segments.forEachIndexed { i, seg -> if (blockIndex == null || i == blockIndex) SegmentRow(model, state, i, seg, enabled = current && !state.busy) }
        if (recap.scriptApproved && current) {
            AfText(tr(UiText.SCRIPT_APPROVED_NEXT), color = AutoFlowColors.Green, bold = true)
        } else if (current) {
            val gate = RecapRulesV2.canApproveScript(recap, story)
            if (!gate.allowed) AfText(gate.blockers.first().text(LocalUiLanguage.current), muted = true, size = 12.sp)
            AfButton(tr(UiText.APPROVE_SCRIPT), { model.approveScript() }, kind = ButtonKind.Primary, enabled = gate.allowed && !state.busy)
        }
    }
    if (studioOptions) io.github.alalkipgen.autoflow.ui.components.SectionCard(My.breakSyllables(tr(UiText.V3_STORY_OPTIONS)),
        initiallyExpanded = state.story == null, content = content)
    else AfCard(tr(UiText.RECAP_SCRIPT_CARD), content = content)
}

/** Shared bindings: Studio puts source context on Source; Classic retains its original controls. */
@Composable
internal fun SourceSummary(model: RecapModel, state: RecapUiState) {
    AfText(tr(UiText.STORY_SUMMARY_HINT), muted = true)
    AfTextField(state.summary, { model.setSummary(it) }, tr(UiText.SUMMARY), Modifier.fillMaxWidth().testTag("source-summary"), lines = 5, mono = false, enabled = !state.busy)
}

@Composable
internal fun SourceSubtitles(model: RecapModel, state: RecapUiState, recap: RecapV2) {
    WrapRow {
        if (recap.subtitles == null) {
            AfButton(tr(UiText.IMPORT_SUBTITLES), { model.importSubtitles() }, enabled = !state.busy)
        } else {
            AfText(tr(UiText.SUBTITLES_PRESENT), color = AutoFlowColors.Green)
            AfButton(tr(UiText.REPLACE), { model.importSubtitles() }, enabled = !state.busy)
            AfButton(tr(UiText.REMOVE), { model.removeSubtitles() }, kind = ButtonKind.Danger, enabled = !state.busy)
        }
    }
    if (recap.movie != null && recap.subtitles == null) {
        val movieTracks = state.movieSubtitleTracks
        if (!state.movieSubtitleTracksRead) {
            AfButton(tr(UiText.V3_READ_TRACKS), { model.readMovieSubtitleTracks() }, Modifier.testTag("source-read-tracks"), enabled = !state.busy)
        } else if (movieTracks.isNotEmpty()) {
            AfText(tr(UiText.EXTRACT_FROM_MOVIE), muted = true)
            WrapRow {
                movieTracks.forEach { track ->
                    val label = buildString {
                        append(tr(UiText.SUBTITLE_TRACK, "index" to track.index))
                        track.language?.let { append(" [$it]") }
                        track.title?.let { append(" — $it") }
                        append(if (track.isText) "" else tr(UiText.IMAGE_TRACK_SUFFIX))
                    }
                    AfChip(label, recap.sources.subtitleTrack == track.index, {
                        if (track.isText) model.extractSubtitleTrack(track) else model.setSubtitleTrack(track.index)
                    }, Modifier.testTag("source-track-${track.index}"), enabled = !state.busy)
                }
            }
            if (movieTracks.any { !it.isText }) AfText(tr(UiText.IMAGE_TRACK_HINT), muted = true)
        } else AfText(tr(UiText.NO_EMBEDDED_SUBS), muted = true)
    }
}

/**
 * "Transcribe picked scenes" (5B-1, D-44): offered for picked scenes without `.srt` lines; a confirm card (scenes,
 * requests, today's usage) before anything is sent; progress with Cancel; Retry for a paused or failed job (only the
 * requests left are sent).
 */
@Composable
internal fun TranscribeSection(model: RecapModel, state: RecapUiState, recap: RecapV2) {
    val t = state.transcribe
    if (!t.available || recap.movie == null) return
    val active = t.job?.state in setOf(AiJobState.QUEUED, AiJobState.RUNNING)
    if (t.lines > 0 || t.transcribedScenes > 0) {
        AfText((if (active) tr(UiText.TRANSCRIPT_PREVIOUS) + " · " else "") + transcriptSummary(t.lines, t.transcribedScenes, LocalUiLanguage.current), Modifier.testTag("transcribe-summary"), color = AutoFlowColors.Green)
    }
    val running = state.runnerJobId == null && state.job?.startsWith("Transcribing") == true
    val confirm = t.confirm
    val job = t.job
    when {
        running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfText(state.jobNotice?.text(LocalUiLanguage.current) ?: state.job.orEmpty(), Modifier.weight(1f).testTag("transcribe-progress"), muted = true, size = 12.sp)
            AfButton(tr(UiText.CANCEL), { model.cancelTranscribe() }, Modifier.testTag("transcribe-cancel"), kind = ButtonKind.Danger)
        }
        confirm != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AfText(
                transcribeConfirmText(confirm.scenes, confirm.requests, LocalUiLanguage.current) + tr(UiText.TRANSCRIBE_AUDIO_DURATION, "audioMs" to (clock(confirm.audioMs))) +
                    tr(UiText.TRANSCRIBE_PRIVACY) +
                    tr(UiText.TRANSCRIBE_USED_TODAY, "toString" to (confirm.usedToday?.toString() ?: "…")),
                Modifier.testTag("transcribe-confirm"), color = AutoFlowColors.Amber,
            )
            WrapRow {
                AfButton(tr(UiText.START), { model.transcribeScenes(confirmed = true) }, Modifier.testTag("transcribe-confirm-start"), kind = ButtonKind.Primary)
                AfButton(tr(UiText.BACK), { model.dismissTranscribe() }, Modifier.testTag("transcribe-confirm-back"))
            }
        }
        job != null && (job.state == AiJobState.FAILED || job.state == AiJobState.PAUSED) -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AfText(
                (job.error ?: tr(UiText.TRANSCRIBE_STOPPED)) + tr(UiText.TRANSCRIBE_REQUESTS_LEFT, "size" to (job.items.size)),
                Modifier.testTag("transcribe-failed"), color = AutoFlowColors.Amber,
            )
            WrapRow {
                AfButton(if (job.state == AiJobState.PAUSED) tr(UiText.V3_RESUME_REMAINING, "n" to job.items.size) else tr(UiText.RETRY), { model.retryTranscribe() }, Modifier.testTag("transcribe-retry"), kind = ButtonKind.Primary, enabled = !state.busy)
                AfButton(tr(UiText.CANCEL_JOB), { model.cancelTranscribe() }, Modifier.testTag("transcribe-cancel"), kind = ButtonKind.Danger)
            }
        }
        job != null -> ProjectAiJobCard(job, model::cancelTranscribe, Modifier.testTag("transcribe-progress"), Modifier.testTag("transcribe-cancel"))
        t.todoScenes > 0 -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AfText(tr(UiText.TRANSCRIBE_OFFER, "todoScenes" to (t.todoScenes)), muted = true, size = 12.sp)
            AfButton(tr(UiText.TRANSCRIBE_SCENES), { model.transcribeScenes() }, Modifier.testTag("transcribe-start"), enabled = !state.busy)
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
            AfButton(tr(UiText.BACK), { editTiming = false })
        } else if (editing) {
            AfTextField(draft, { draft = it }, tr(UiText.NARRATION_FOR_SCENE, "index1" to (index + 1)), Modifier.fillMaxWidth(), lines = 3, mono = false)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AfButton(tr(UiText.SAVE), { if (model.saveSegment(index, draft)) editing = false }, kind = ButtonKind.Primary)
                AfButton(tr(UiText.BACK), { editing = false; draft = seg.textMy })
            }
        } else {
            AfText(seg.textMy.ifBlank { tr(UiText.EMPTY_SCRIPT_EDIT) }, muted = seg.textMy.isBlank())
        }
    }
}

/** Format ms as `hh:mm:ss` for display. */
private fun clock(ms: Long): String {
    val s = ms / 1000; return "${s / 3600}:${(s / 60 % 60).toString().padStart(2,'0')}:${(s % 60).toString().padStart(2,'0')}"
}

/** "Transcribe picked scenes" summary once lines are saved (5B-1, 5B-13 Burmese). */
internal fun transcriptSummary(lines: Int, scenes: Int, language: UiLanguage = UiLanguage.ENGLISH): String = UiText.TRANSCRIPT_SUMMARY.text(language, "scenes" to (scenes), "lines" to (lines))

/** First sentence of the Transcribe confirm card: picked scenes without subtitles and the Gemini requests. */
internal fun transcribeConfirmText(scenes: Int, requests: Int, language: UiLanguage = UiLanguage.ENGLISH): String =
    UiText.TRANSCRIBE_CONFIRM.text(language, "scenes" to (scenes), "requests" to (requests))
