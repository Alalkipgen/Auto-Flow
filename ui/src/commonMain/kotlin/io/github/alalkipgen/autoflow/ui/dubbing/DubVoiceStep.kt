package io.github.alalkipgen.autoflow.ui.dubbing

import io.github.alalkipgen.autoflow.ui.icons.AfIcons

import io.github.alalkipgen.autoflow.ui.jobs.ToolAiJobCard
import io.github.alalkipgen.autoflow.ui.jobs.ToolAiRequestCard
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.GlassColors
import io.github.alalkipgen.autoflow.ui.voice.voiceLabel
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
    shorten: DubShortenModel? = null, script: DubScriptModel? = null, toScript: () -> Unit = {}, preview: @Composable () -> Unit = {}) {
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
            // F-6: the preview scrolls with the step, so a phone with a large font keeps room for the controls.
            item(key = "preview") { preview() }
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
        s.confirm?.takeIf { idle }?.let { SpeakDialog(model, it, open) }
    }
}

/**
 * The voice card (Group 4 F 1–2, 🆕-4; samples 03 / 04): a summary row — the 40 dp Pink → Purple avatar, "Nilar · Edge",
 * the Locked pill, "Calm · Original sound: Lower · 15%" and a caret — that opens the same controls as before, each group
 * under a small label: models (or the amber callout when none), Gemini voices, Edge voices, Tone, and the original sound as a
 * two-option segmented control with its hint. Closed at first when a voice is chosen and models exist; while Speak runs
 * the caret is off and DUB_VOICE_EDIT_PAUSED shows.
 */
@Composable
private fun Settings(model: DubVoiceModel, open: OpenProject?, idle: Boolean) {
    val d = open?.project?.dubbing
    val lock = d?.lock
    val edge = model.edgeVoices()
    val noModels = model.models.isEmpty()
    var expanded by remember(open?.dir) { mutableStateOf(lock == null || noModels) }
    val mode = d?.original?.mode ?: DubOriginal.LOWER
    val percent = kotlin.math.round((d?.original?.takeIf { it.mode != DubOriginal.MUTE }?.volume ?: model.lowerVolume) * 100).toInt()
    val original = if (mode == DubOriginal.MUTE) tr(UiText.V3_DUB_MUTE) else tr(UiText.V3_DUB_LOWER, "percent" to percent)
    val tone = TONES.firstOrNull { it.first == d?.tone }?.second?.let { tr(it) }
    val state = tr(if (expanded && idle) UiText.V3_EXPANDED else UiText.V3_COLLAPSED)
    val voiceTitle = tr(UiText.VA_VOICE)
    GlassCard(Modifier.testTag("dub-voice-settings"), tint = GlassColors.RecapPink, tintAlpha = .10f, gap = 10.dp) {
        Row(Modifier.fillMaxWidth().testTag("dub-voice-summary"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Brush.linearGradient(listOf(GlassColors.RecapPink, AutoFlowColors.Purple))),
                contentAlignment = Alignment.Center) { AfIcon(AfIcons.Fill.UserSound, tint = Color.White, size = 20.dp) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                WrapRow(spacing = 6.dp) {
                    AfText(My.breakSyllables(lock?.let { voiceLabel(it, edge) } ?: tr(UiText.VA_NO_VOICE)), size = 15.sp, bold = true)
                    if (lock != null) LockedPill()
                }
                if (lock != null) AfText(My.breakSyllables(if (tone != null) tr(UiText.V3_DUB_VOICE_SUMMARY, "tone" to tone, "original" to original)
                    else "${tr(UiText.DUB_ORIGINAL)}: $original"), Modifier.testTag("dub-voice-summary-text"), muted = true, size = 12.sp)
            }
            GlassIconButton(if (expanded && idle) AfIcons.Regular.CaretUp else AfIcons.Regular.CaretDown, voiceTitle, { expanded = !expanded },
                Modifier.testTag("dub-voice-expand").semantics { stateDescription = state }, enabled = idle)
        }
        if (!idle) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AfIcon(AfIcons.Regular.Info, tint = AutoFlowColors.Muted, size = 15.dp)
            AfText(My.breakSyllables(tr(UiText.DUB_VOICE_EDIT_PAUSED)), Modifier.weight(1f), muted = true, size = 12.sp)
        }
        // While Speak runs the card stays closed (sample 04 row 4): the voice cannot change mid-run.
        if (expanded && idle) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = .08f)))
            Label(tr(UiText.BURMESE_TTS_MODELS))
            if (noModels) GlassCallout(AfIcons.Fill.Warn, My.breakSyllables(tr(UiText.BURMESE_MODELS_MISSING)), AutoFlowColors.Amber)
            if (!noModels) WrapRow {
                model.models.forEach { id ->
                    GlassFilterChip(id, lock?.model == id, { model.setLock(VoiceLock("gemini", id, lock?.voice ?: "Charon", "my")) },
                        Modifier.testTag("dub-model-$id"), enabled = idle)
                }
            }
            Label(tr(UiText.V3_GEMINI_VOICES))
            WrapRow {
                RecapModel.TTS_VOICES.forEach { (name, _) ->
                    val modelId = io.github.alalkipgen.autoflow.ui.voice.EdgeVoices.geminiModel(lock, model.models)
                    GlassFilterChip(My.breakSyllables("$name · ${ttsVoiceKind(name)}"),
                        lock?.voice == name && lock.provider != io.github.alalkipgen.autoflow.ui.voice.EdgeVoices.PROVIDER,
                        { modelId?.let { model.setLock(VoiceLock("gemini", it, name, "my")) } },
                        Modifier.testTag("dub-voice-$name"), enabled = idle && modelId != null)
                }
            }
            edge?.let { io.github.alalkipgen.autoflow.ui.voice.EdgeVoiceChips(it, lock, idle, "dub", glass = true) { l -> model.setLock(l) } }
            Label(tr(UiText.VA_TONE))
            WrapRow {
                TONES.forEach { (id, label) ->
                    GlassFilterChip(My.breakSyllables(tr(label)), d?.tone == id, { model.setTone(id) }, Modifier.testTag("dub-tone-$id"), enabled = idle)
                }
            }
            Label(tr(UiText.DUB_ORIGINAL))
            GlassSegmented(listOf(
                GlassSegment(tr(UiText.V3_DUB_LOWER, "percent" to percent), "dub-original-lower", idle, AfIcons.Regular.SpeakerLow),
                GlassSegment(tr(UiText.V3_DUB_MUTE), "dub-original-mute", idle, AfIcons.Regular.SpeakerMuted),
            ), if (mode == DubOriginal.MUTE) 1 else 0, { model.setOriginal(if (it == 1) DubOriginal.MUTE else DubOriginal.LOWER) })
            AfText(My.breakSyllables(tr(if (mode == DubOriginal.MUTE) UiText.V3_DUB_MUTE_HINT else UiText.V3_DUB_LOWER_HINT)),
                Modifier.testTag("dub-original-hint"), muted = true, size = 12.sp)
        }
    }
}

/** A group's small 12 sp muted label (Group 1 `GroupLabel` size; not bold, Group 3 rule). */
@Composable
private fun Label(text: String) = AfText(My.breakSyllables(text), muted = true, size = 12.sp)

/** "Locked" pill (`lock-simple`, muted): the voice every take is made with. */
@Composable
private fun LockedPill() {
    val shape = RoundedCornerShape(50)
    Row(Modifier.clip(shape).background(Color.White.copy(alpha = .06f)).border(1.dp, Color.White.copy(alpha = .12f), shape)
        .padding(horizontal = 8.dp, vertical = 2.dp).testTag("dub-voice-locked"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        AfIcon(AfIcons.Fill.Lock, tint = AutoFlowColors.Muted, size = 12.dp)
        AfText(My.breakSyllables(tr(UiText.V3_VOICE_LOCKED)), muted = true, type = AfType.Micro)
    }
}

/**
 * Speak (Group 4 F 3, 5): the glass job card (`microphone-stage` in Purple: progress, Resume, Cancel), or how the last
 * Speak ended as a green / amber callout. The confirm is [SpeakDialog].
 */
@Composable
private fun Speak(model: DubVoiceModel, s: DubVoiceUi, job: AiJob?) {
    val c = s.confirm
    if (job != null) ToolAiJobCard(job, Modifier.testTag("dub-speak-job"), tile = CardTile(AfIcons.Fill.Dub, AutoFlowColors.Purple)) {
        val n = maxOf(job.initialItemCount ?: 0, job.items.size)
        val done = n - job.items.size
        val text = when (job.state) {
            AiJobState.PAUSED -> tr(UiText.DUB_SPEAK_PAUSED, "done" to done, "n" to n) + (job.error?.let { " $it" } ?: "")
            AiJobState.FAILED -> tr(UiText.DUB_SPEAK_STOPPED, "detail" to (job.error ?: ""), "done" to done, "n" to n)
            else -> tr(UiText.DUB_SPEAK_PROGRESS, "k" to minOf(done + 1, n), "n" to n)
        }
        if (job.state == AiJobState.FAILED) GlassCallout(AfIcons.Fill.XCircle, My.breakSyllables(text), AutoFlowColors.Failed,
            textModifier = Modifier.testTag("dub-speak-progress"))
        else AfText(My.breakSyllables(text), Modifier.testTag("dub-speak-progress"), size = 15.sp)
        WrapRow {
            if (job.state == AiJobState.PAUSED || job.state == AiJobState.FAILED) GlassButton(My.breakSyllables(tr(UiText.V3_RESUME_REMAINING,
                "n" to job.items.size)), { model.resume(job) }, Modifier.testTag("dub-speak-resume"), kind = ButtonKind.Primary, icon = AfIcons.Fill.Play)
            GlassButton(My.breakSyllables(tr(UiText.CANCEL)), { model.cancel(job) }, Modifier.testTag("dub-speak-cancel"), kind = ButtonKind.Danger,
                icon = AfIcons.Regular.Close)
        }
    }
    else if (c == null) s.notice?.let {
        GlassCallout(if (s.ok) AfIcons.Fill.CheckCircle else AfIcons.Fill.Warn, My.breakSyllables(it.text(LocalUiLanguage.current)),
            if (s.ok) AutoFlowColors.Green else AutoFlowColors.Amber, textModifier = Modifier.testTag("dub-speak-notice"))
    }
}

/** 🆕-3: the Speak confirm as a glass dialog (sample 06a) — outside the list, so it stays while the list scrolls. */
@Composable
private fun SpeakDialog(model: DubVoiceModel, c: DubSpeakRequest, open: OpenProject?) {
    val edge = model.edgeVoices()
    val label = voiceLabel(c.lock, edge)
    val minutes = model.minutes(c.lines.size, c.lock)
    // §8.4: Edge says Edge (and free); both name the voice as people say it, not the raw id and model.
    val facts = if (c.lock.provider == io.github.alalkipgen.autoflow.ui.voice.EdgeVoices.PROVIDER)
        tr(UiText.V3_DUB_EDGE_CONFIRM, "n" to c.lines.size, "voice" to label, "minutes" to minutes)
    else tr(UiText.VA_SPEAK_CONFIRM, "n" to c.lines.size, "voice" to label, "minutes" to minutes)
    val d = open?.project?.dubbing
    val mode = d?.original?.mode ?: DubOriginal.LOWER
    val original = if (mode == DubOriginal.MUTE) tr(UiText.V3_DUB_MUTE)
        else tr(UiText.V3_DUB_LOWER, "percent" to kotlin.math.round((d?.original?.volume ?: 0.15) * 100).toInt())
    val tone = TONES.firstOrNull { it.first == (c.tone ?: d?.tone) }?.second?.let { tr(it) }
    GlassDialog({ model.dismiss() }, AfIcons.Fill.Dub, GlassColors.RecapPink, My.breakSyllables(tr(UiText.V3_DUB_SPEAK_TITLE, "n" to c.lines.size)),
        Modifier.testTag("dub-speak-confirm")) {
        AfText(My.breakSyllables(facts), Modifier.testTag("dub-speak-facts"), bold = true)
        // The voice and its tone, as the summary row says them.
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .04f))
            .border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(12.dp)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(Brush.linearGradient(listOf(GlassColors.RecapPink, AutoFlowColors.Purple))),
                contentAlignment = Alignment.Center) { AfIcon(AfIcons.Fill.UserSound, tint = Color.White, size = 16.dp) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AfText(My.breakSyllables(label), bold = true)
                AfText(My.breakSyllables(if (tone != null) tr(UiText.V3_DUB_VOICE_SUMMARY, "tone" to tone, "original" to original)
                    else "${tr(UiText.DUB_ORIGINAL)}: $original"), muted = true, size = 12.sp)
            }
        }
        GlassDialogButtons(GlassDialogAction(My.breakSyllables(tr(UiText.BACK)), { model.dismiss() }, icon = AfIcons.Regular.Back, tag = "dub-speak-back"),
            GlassDialogAction(My.breakSyllables(tr(UiText.START)), { model.start() }, ButtonKind.Primary, AfIcons.Fill.Play, tag = "dub-speak-start"))
    }
}

/** Shorten (D-2c): offered for TOO_LONG lines; Gemini's lines next to the current ones, chosen per line, then Use n lines. */
@Composable
private fun Shorten(model: DubShortenModel, script: DubScriptModel, dir: String, rows: List<DubVoiceRow>, doc: SubtitleDoc, idle: Boolean,
    toScript: () -> Unit) {
    val all by model.state.collectAsState()
    val s = all.takeIf { it.dir == dir } ?: DubShortenUi()
    val long = remember(rows, doc) { model.lines(rows, doc).size }
    if (long == 0 && s.rows.isEmpty() && s.notice == null && !s.busy) return
    if (s.busy) {
        ToolAiRequestCard(My.breakSyllables(tr(UiText.DUB_SHORTEN)), Modifier.testTag("dub-shorten-card")) {
            AfText(My.breakSyllables(tr(UiText.DUB_SHORTEN_WORKING)), Modifier.testTag("dub-shorten-working"), muted = true)
        }
        return
    }
    AfCard(My.breakSyllables(tr(UiText.DUB_SHORTEN)), Modifier.testTag("dub-shorten-card")) {
        when {
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
                    AfButton(My.breakSyllables(tr(UiText.BACK)), { model.dismiss() }, Modifier.testTag("dub-shorten-back"), icon = AfIcons.Regular.Back)
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
