package io.github.alalkipgen.autoflow.ui.dubbing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.dubbing.DubFitKind
import io.github.alalkipgen.autoflow.core.model.DubOriginal
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.core.subtitles.TimeText
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.recap.RecapModel
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.voice.VoiceSpeakJob

private val TONES = listOf("calm" to UiText.VA_TONE_CALM, "lively" to UiText.VA_TONE_LIVELY, "news" to UiText.VA_TONE_NEWS,
    "story" to UiText.VA_TONE_STORY)

private fun sec(ms: Long) = ((ms + 99) / 100) / 10.0

/** The Voice step (D-2b): voice, tone and original sound, the Speak card, and every line with its take and fit. */
@Composable
internal fun VoiceStep(model: DubVoiceModel, open: OpenProject?, doc: SubtitleDoc, toExport: () -> Unit,
    shorten: DubShortenModel? = null, script: DubScriptModel? = null, toScript: () -> Unit = {}) {
    val all by model.state.collectAsState()
    val jobs by model.jobs.collectAsState()
    val dir = open?.dir
    val job = model.jobOf(dir, jobs)
    val idle = job == null
    val s = all.takeIf { it.dir == dir } ?: DubVoiceUi()
    val rows = remember(open?.project, doc) { model.rows(open, doc) }
    val missing = rows.count { it.take == null && it.spoken.isNotEmpty() }
    var playing by remember { mutableStateOf<String?>(null) }
    Column(Modifier.widthIn(max = 900.dp).fillMaxSize()) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("dub-voice-rows"), contentPadding = PaddingValues(screenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "settings") { Settings(model, open, idle) }
            item(key = "speak") { Speak(model, s, job) }
            if (shorten != null && script != null && dir != null) item(key = "shorten") { Shorten(shorten, script, dir, rows, doc, idle, toScript) }
            items(rows, key = { it.id }) { r ->
                AfCard("#${r.n} · ${TimeText.show(r.startMs)} → ${TimeText.show(r.endMs)}", Modifier.testTag("dub-voice-row-${r.id}")) {
                    AfText(My.breakSyllables(r.spoken))
                    AfText(My.breakSyllables(tr(UiText.DUB_SLOT, "s" to (r.slotMs / 100) / 10.0)), muted = true)
                    val take = r.take
                    val fit = r.fit
                    if (take == null || fit == null) AfText(My.breakSyllables(tr(if (r.old) UiText.V3_SCRIPT_CHANGED else UiText.V3_NO_VOICE)),
                        Modifier.testTag("dub-fit-${r.id}"), muted = true)
                    else {
                        AfText(My.breakSyllables(tr(UiText.DUB_TAKE, "s" to sec(take.ms))), Modifier.testTag("dub-take-${r.id}"))
                        val (label, color) = when (fit.kind) {
                            DubFitKind.FITS -> tr(UiText.V3_FITS) to AutoFlowColors.Green
                            DubFitKind.USES_PAUSE -> tr(UiText.DUB_USES_PAUSE) to AutoFlowColors.Green
                            DubFitKind.FASTER -> tr(UiText.DUB_FASTER, "x" to fit.speed) to AutoFlowColors.Amber
                            DubFitKind.TOO_LONG -> tr(UiText.V3_TOO_LONG, "seconds" to sec(fit.overMs)) to AutoFlowColors.Failed
                        }
                        AfText(My.breakSyllables(label), Modifier.testTag("dub-fit-${r.id}"), color = color, bold = true)
                    }
                    WrapRow {
                        take?.let { t -> model.takePath(t.au)?.let { AudioPlayButton(it, playing == r.id, { playing = if (playing == r.id) null else r.id }) } }
                        if (take != null || r.old) AfButton(My.breakSyllables("↻ " + tr(UiText.DUB_REDO_LINE)), { model.ask(setOf(r.id)) },
                            Modifier.testTag("dub-redo-${r.id}"), enabled = idle)
                    }
                }
            }
        }
        if (missing > 0 || !idle) StickyActionBar(StudioAction(My.breakSyllables(tr(UiText.DUB_SPEAK_LINES, "n" to missing)), { model.ask() },
            enabled = idle && missing > 0 && s.confirm == null, tag = "dub-speak"))
        else StickyActionBar(StudioAction(My.breakSyllables(tr(UiText.DUB_GO_TO, "step" to tr(UiText.DUB_STEP_EXPORT))), toExport, tag = "dub-go-export"))
    }
}

@Composable
private fun Settings(model: DubVoiceModel, open: OpenProject?, idle: Boolean) {
    val d = open?.project?.dubbing
    val lock = d?.lock
    AfCard(My.breakSyllables(tr(UiText.VA_VOICE)), Modifier.testTag("dub-voice-settings")) {
        AfText(My.breakSyllables(tr(UiText.BURMESE_TTS_MODELS)), muted = true)
        if (model.models.isEmpty()) AfText(My.breakSyllables(tr(UiText.BURMESE_MODELS_MISSING)), color = AutoFlowColors.Amber)
        WrapRow {
            model.models.forEach { id ->
                AfChip(id, lock?.model == id, { model.setLock(VoiceLock("gemini", id, lock?.voice ?: "Charon", "my")) }, Modifier.testTag("dub-model-$id"), enabled = idle)
            }
        }
        WrapRow {
            RecapModel.TTS_VOICES.forEach { (name, _) ->
                val modelId = io.github.alalkipgen.autoflow.ui.voice.EdgeVoices.geminiModel(lock, model.models)
                AfChip(My.breakSyllables("$name · ${ttsVoiceKind(name)}"), lock?.voice == name, { modelId?.let { model.setLock(VoiceLock("gemini", it, name, "my")) } },
                    Modifier.testTag("dub-voice-$name"), enabled = idle && modelId != null)
            }
        }
        model.edgeVoices()?.let { edge ->
            io.github.alalkipgen.autoflow.ui.voice.EdgeVoiceChips(edge, lock, idle, "dub") { model.setLock(it) }
        }
        AfText(tr(UiText.VA_TONE), bold = true)
        WrapRow { TONES.forEach { (id, label) -> AfChip(My.breakSyllables(tr(label)), d?.tone == id, { model.setTone(id) }, Modifier.testTag("dub-tone-$id"), enabled = idle) } }
        AfText(My.breakSyllables(tr(UiText.DUB_ORIGINAL)), bold = true)
        val mode = d?.original?.mode ?: DubOriginal.LOWER
        WrapRow {
            AfChip(My.breakSyllables(tr(UiText.DUB_LOWER)), mode == DubOriginal.LOWER, { model.setOriginal(DubOriginal.LOWER) }, Modifier.testTag("dub-original-lower"), enabled = idle)
            AfChip(My.breakSyllables(tr(UiText.DUB_MUTE)), mode == DubOriginal.MUTE, { model.setOriginal(DubOriginal.MUTE) }, Modifier.testTag("dub-original-mute"), enabled = idle)
        }
        if (!idle) AfText(My.breakSyllables(tr(UiText.DUB_VOICE_EDIT_PAUSED)), muted = true)
    }
}

/** The confirm card, the job (progress · Resume · Cancel), or how the last Speak ended. */
@Composable
private fun Speak(model: DubVoiceModel, s: DubVoiceUi, job: AiJob?) {
    val c = s.confirm
    when {
        job != null -> AfCard(My.breakSyllables(tr(UiText.JOB_DUB_TTS)), Modifier.testTag("dub-speak-job")) {
            val n = maxOf(job.initialItemCount ?: 0, job.items.size)
            val done = n - job.items.size
            val text = when (job.state) {
                AiJobState.PAUSED -> tr(UiText.DUB_SPEAK_PAUSED, "done" to done, "n" to n) + (job.error?.let { " $it" } ?: "")
                AiJobState.FAILED -> tr(UiText.DUB_SPEAK_STOPPED, "detail" to (job.error ?: ""), "done" to done, "n" to n)
                else -> tr(UiText.DUB_SPEAK_PROGRESS, "k" to minOf(done + 1, n), "n" to n)
            }
            AfText(My.breakSyllables(text), Modifier.testTag("dub-speak-progress"),
                color = if (job.state == AiJobState.FAILED) AutoFlowColors.Failed else AutoFlowColors.Text)
            WrapRow {
                if (job.state == AiJobState.PAUSED || job.state == AiJobState.FAILED) AfButton(My.breakSyllables(tr(UiText.V3_RESUME_REMAINING,
                    "n" to job.items.size)), { model.resume(job) }, Modifier.testTag("dub-speak-resume"), kind = ButtonKind.Primary)
                AfButton(My.breakSyllables(tr(UiText.CANCEL)), { model.cancel(job) }, Modifier.testTag("dub-speak-cancel"))
            }
        }
        c != null -> AfCard(My.breakSyllables(tr(UiText.DUB_SPEAK_LINES, "n" to c.lines.size)), Modifier.testTag("dub-speak-confirm")) {
            AfText(My.breakSyllables(tr(UiText.VA_SPEAK_CONFIRM, "n" to c.lines.size, "voice" to "${c.lock.voice} · ${c.lock.model}",
                "minutes" to (c.lines.size + VoiceSpeakJob.RPM - 1) / VoiceSpeakJob.RPM)), Modifier.testTag("dub-speak-facts"), bold = true)
            WrapRow {
                AfButton(My.breakSyllables(tr(UiText.START)), { model.start() }, Modifier.testTag("dub-speak-start"), kind = ButtonKind.Primary)
                AfButton(My.breakSyllables(tr(UiText.BACK)), { model.dismiss() }, Modifier.testTag("dub-speak-back"))
            }
        }
        else -> s.notice?.let {
            AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.testTag("dub-speak-notice"),
                color = if (s.ok) AutoFlowColors.Green else AutoFlowColors.Amber)
        }
    }
}

/** Shorten (D-2c): offered for TOO_LONG lines; Gemini's lines next to the current ones, chosen per line, then Use n lines. */
@Composable
private fun Shorten(model: DubShortenModel, script: DubScriptModel, dir: String, rows: List<DubVoiceRow>, doc: SubtitleDoc, idle: Boolean,
    toScript: () -> Unit) {
    val all by model.state.collectAsState()
    val s = all.takeIf { it.dir == dir } ?: DubShortenUi()
    val long = remember(rows, doc) { model.lines(rows, doc).size }
    if (long == 0 && s.rows.isEmpty() && s.notice == null) return
    AfCard(My.breakSyllables(tr(UiText.DUB_SHORTEN)), Modifier.testTag("dub-shorten-card")) {
        when {
            s.busy -> AfText(My.breakSyllables(tr(UiText.DUB_SHORTEN_WORKING)), Modifier.testTag("dub-shorten-working"), muted = true)
            s.rows.isNotEmpty() -> {
                AfText(My.breakSyllables(tr(UiText.DUB_SHORTEN_TITLE)), bold = true)
                s.rows.forEach { r ->
                    Column(Modifier.fillMaxWidth().testTag("dub-shorten-row-${r.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AfText("#${r.n}", muted = true)
                        AfText(My.breakSyllables(r.old), muted = true)
                        AfText(My.breakSyllables(r.new), Modifier.testTag("dub-shorten-new-${r.id}"), bold = true)
                        AfText(My.breakSyllables(tr(UiText.DUB_SHORTEN_NEW, "len" to r.new.length, "max" to r.maxChars)),
                            color = if (r.new.length > r.maxChars) AutoFlowColors.Amber else null, muted = r.new.length <= r.maxChars)
                        AfChip(My.breakSyllables(tr(UiText.DUB_USE)), r.use, { model.toggle(r.id) }, Modifier.testTag("dub-shorten-use-${r.id}"))
                    }
                }
                val n = s.rows.count { it.use }
                WrapRow {
                    AfButton(My.breakSyllables(tr(UiText.DUB_USE_LINES, "n" to n)), { if (model.use(script)) toScript() },
                        Modifier.testTag("dub-shorten-apply"), kind = ButtonKind.Primary, enabled = n > 0 && idle)
                    AfButton(My.breakSyllables(tr(UiText.BACK)), { model.dismiss() }, Modifier.testTag("dub-shorten-back"))
                }
            }
            else -> {
                s.notice?.let { AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.testTag("dub-shorten-notice"), color = AutoFlowColors.Amber) }
                if (long > 0) {
                    AfText(My.breakSyllables(tr(UiText.DUB_SHORTEN_N, "n" to long)))
                    AfButton(My.breakSyllables(tr(UiText.DUB_SHORTEN)), { model.ask(dir, rows, doc) }, Modifier.testTag("dub-shorten"),
                        enabled = idle && model.available)
                }
            }
        }
    }
}
