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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import io.github.alalkipgen.autoflow.ui.theme.GlassShapes
import kotlinx.coroutines.launch
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
import io.github.alalkipgen.autoflow.ui.subtitles.Playhead

private val TONES = listOf("calm" to UiText.VA_TONE_CALM, "lively" to UiText.VA_TONE_LIVELY, "news" to UiText.VA_TONE_NEWS,
    "story" to UiText.VA_TONE_STORY)

internal fun sec(ms: Long) = ((ms + 99) / 100) / 10.0

/** The Voice step's line filters (Group 4 F 7): To fix = too long; No voice = no take or an old one; Done = the rest. */
internal enum class DubLineFilter(val tag: String) {
    ALL("all"), FIX("fix"), NONE("none"), DONE("done");

    fun has(r: DubVoiceRow): Boolean = when (this) {
        ALL -> true
        FIX -> r.fit?.kind == DubFitKind.TOO_LONG
        NONE -> r.take == null
        DONE -> r.take != null && r.fit?.kind != DubFitKind.TOO_LONG
    }
}

/**
 * The Voice step (D-2b; Group 4 F 6–9): the preview, the voice card, Speak, Shorten, the line filters with counts and every
 * line as a glass card with its fit pill; the sticky Speak with the "33 of 40 lines spoken" caption, or Go to Export.
 * [playhead] / [export] give the preview's line (🆕-2: the playing outline, a head tap seeks there); [editLine] opens Dub
 * script at a too-long line with its field focused (🆕-5).
 */
@Composable
internal fun VoiceStep(model: DubVoiceModel, open: OpenProject?, doc: SubtitleDoc, toExport: () -> Unit,
    shorten: DubShortenModel? = null, script: DubScriptModel? = null, toScript: () -> Unit = {}, playhead: Playhead? = null,
    export: DubExportModel? = null, editLine: (String) -> Unit = {}, wide: Boolean = false, preview: @Composable () -> Unit = {}) {
    val all by model.state.collectAsState()
    val jobs by model.jobs.collectAsState()
    val dir = open?.dir
    val job = model.jobOf(dir, jobs)
    val idle = job == null
    val s = all.takeIf { it.dir == dir } ?: DubVoiceUi()
    val rows = remember(open?.project, doc) { model.rows(open, doc) }
    val missing = rows.count { it.take == null && it.spoken.isNotEmpty() }
    var playing by remember { mutableStateOf<String?>(null) }
    var filter by remember(dir) { mutableStateOf(DubLineFilter.ALL) }
    val shown = rows.filter { filter.has(it) }
    val ticks = remember(dir, open?.project, doc) { export?.previewTicks(open, doc) }
    val position = playhead?.positionMs
    val playingN = position?.let { pos -> ticks?.let { DubPreviewClock.line(it, pos) } ?: rows.lastOrNull { pos >= it.startMs && pos < it.endMs }?.n }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val shortenOn = shorten != null && script != null && dir != null
    // Desktop (Group 4 H): the selected line opens in place and its waveform shows on the right; at first the first too-long line.
    var selected by remember(dir) { mutableStateOf<String?>(null) }
    val chosen = if (!wide) null else selected?.let { id -> rows.firstOrNull { it.id == id } }
        ?: rows.firstOrNull { it.fit?.kind == DubFitKind.TOO_LONG } ?: rows.firstOrNull()
    val lines = @Composable { modifier: Modifier -> LazyColumn(modifier.testTag("dub-voice-rows"), state = list, contentPadding = PaddingValues(screenPadding),
            verticalArrangement = Arrangement.spacedBy(if (wide) 8.dp else 12.dp)) {
            // F-6: the preview scrolls with the step, so a phone with a large font keeps room for the controls (desktop: on the right).
            if (!wide) {
                item(key = "preview") { preview() }
                item(key = "settings") { Settings(model, open, idle) }
                item(key = "speak") { Speak(model, s, job) }
            }
            if (shortenOn) item(key = "shorten") { Shorten(shorten!!, script!!, dir!!, rows, doc, idle, toScript) }
            item(key = "filters") {
                val items = DubLineFilter.entries.map { f ->
                    val n = rows.count { f.has(it) }
                    FilterChipItem(My.breakSyllables(tr(when (f) {
                        DubLineFilter.ALL -> UiText.V3_ALL_COUNT
                        DubLineFilter.FIX -> UiText.V3_DUB_FILTER_FIX
                        DubLineFilter.NONE -> UiText.V3_DUB_FILTER_NO_VOICE
                        DubLineFilter.DONE -> UiText.V3_DUB_FILTER_DONE
                    }, "n" to n)), tag = "dub-filter-${f.tag}")
                }
                FilterChipRow(items, filter.ordinal, { filter = DubLineFilter.entries[it] }, Modifier.testTag("dub-filters"), glass = true)
            }
            if (shown.isEmpty()) item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(vertical = 32.dp).testTag("dub-filter-empty"), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AfIcon(AfIcons.Regular.Funnel, tint = AutoFlowColors.Muted, size = 32.dp)
                    AfText(My.breakSyllables(tr(UiText.V3_DUB_FILTER_EMPTY)), muted = true, size = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            items(shown, key = { it.id }) { r ->
                val ask: (() -> Unit)? = if (shortenOn) ({
                    shorten!!.ask(dir!!, rows, doc)
                    scope.launch { list.animateScrollToItem(if (wide) 0 else 3) } // (preview, settings, speak,) shorten
                    Unit
                }) else null
                if (wide) DeskLineRow(model, r, r.id == chosen?.id, { selected = r.id; playhead?.seek(r.startMs) }, idle, playing == r.id,
                    { playing = if (playing == r.id) null else r.id }, r.n == playingN, { editLine(r.id) }, ask, shortenOn && shorten!!.available)
                else LineRow(model, r, idle, playing == r.id, { playing = if (playing == r.id) null else r.id }, r.n == playingN,
                    playhead?.let { p -> { p.seek(r.startMs) } }, { editLine(r.id) }, ask, shortenOn && shorten!!.available)
            }
        }
    }
    Column(if (wide) Modifier.fillMaxSize() else Modifier.widthIn(max = 900.dp).fillMaxSize()) {
        if (wide) DubPanes({ lines(Modifier.fillMaxSize()) }, {
            preview()
            chosen?.let { r -> LineWaveCard(r, remember(r.take?.au) { r.take?.let { model.takePeaks(it.au) } }) }
            Settings(model, open, idle)
            Speak(model, s, job)
        }, Modifier.weight(1f))
        else lines(Modifier.fillMaxWidth().weight(1f))
        val n = rows.count { it.spoken.isNotEmpty() }
        if (missing > 0 || !idle) StickyActionBar(StudioAction(My.breakSyllables(tr(UiText.DUB_SPEAK_LINES, "n" to missing)), { model.ask() },
            enabled = idle && missing > 0 && s.confirm == null, tag = "dub-speak", icon = AfIcons.Regular.Dub),
            caption = My.breakSyllables(tr(UiText.V3_DUB_SPEAK_CAPTION, "done" to (n - missing), "n" to n,
                "minutes" to model.minutes(missing, open?.project?.dubbing?.lock))), glass = true, captionTag = "dub-speak-caption")
        else StickyActionBar(StudioAction(My.breakSyllables(tr(UiText.DUB_GO_TO, "step" to tr(UiText.DUB_STEP_EXPORT))), toExport, tag = "dub-go-export",
            icon = AfIcons.Regular.Export, trailingIcon = AfIcons.Regular.ArrowRight),
            caption = My.breakSyllables(tr(UiText.DUB_ALL_VOICED)), glass = true, captionIcon = AfIcons.Regular.CheckCircle,
            captionColour = AutoFlowColors.Green, captionTag = "dub-speak-caption")
        s.confirm?.takeIf { idle }?.let { SpeakDialog(model, it, open) }
    }
}

/**
 * One line (Group 4 F 8, samples 05 / 06): a glass card — the head "#n" + times (a tap seeks the preview, 🆕-2) and the
 * fit pill; the spoken line with the 40 dp Redo and Play buttons; the room and take as facts. A too-long line gets the
 * amber bar and outline and its two fixes: Edit line (🆕-5) and Shorten. [playingLine] = the preview's line (Cyan outline).
 */
@Composable
private fun LineRow(model: DubVoiceModel, r: DubVoiceRow, idle: Boolean, playing: Boolean, toggle: () -> Unit, playingLine: Boolean,
    seek: (() -> Unit)?, editLine: () -> Unit, shorten: (() -> Unit)?, canShorten: Boolean) {
    val take = r.take
    val fit = r.fit
    val long = fit?.kind == DubFitKind.TOO_LONG
    GlassCard(Modifier.testTag("dub-voice-row-${r.id}"), padding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp,
        top = if (seek == null) 12.dp else 0.dp), gap = 6.dp, attention = if (long) io.github.alalkipgen.autoflow.ui.theme.Tone.Warning else null,
        outline = when {
            playingLine -> GlassColors.PlayingOutline
            long -> AutoFlowColors.Amber.copy(alpha = .45f)
            else -> null
        }) {
        val head = Modifier.testTag("dub-voice-head-${r.id}").fillMaxWidth().then(if (seek == null) Modifier else Modifier
            .heightIn(min = io.github.alalkipgen.autoflow.ui.theme.AfSize.Touch)
            .pointerInput(seek) { detectTapGestures { seek() } }
            .semantics { role = Role.Button; onClick { seek(); true } })
        Box(head, contentAlignment = Alignment.CenterStart) {
            WrapRow(Modifier.fillMaxWidth().padding(vertical = if (seek == null) 0.dp else 6.dp)) {
                AfText("#${r.n}", size = 14.sp, bold = true)
                AfText("${TimeText.show(r.startMs)} → ${TimeText.show(r.endMs)}", muted = true, mono = true, size = 13.sp)
                Spacer(Modifier.weight(1f))
                FitPill(r, Modifier.testTag("dub-fit-${r.id}"))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AfText(My.breakSyllables(r.spoken), Modifier.weight(1f), size = 15.sp)
            if (take != null || r.old) GlassIconButton(AfIcons.Regular.Redo, tr(UiText.DUB_REDO_LINE), { model.ask(setOf(r.id)) },
                Modifier.testTag("dub-redo-${r.id}"), size = GlassShapes.IconButtonHeader, enabled = idle)
            take?.let { t -> model.takePath(t.au)?.let { AudioPlayButton(it, playing, toggle, glass = true, glassIcon = AfIcons.Fill.Play) } }
        }
        WrapRow(spacing = 12.dp) {
            Fact(AfIcons.Regular.Timer, tr(UiText.DUB_SLOT, "s" to (r.slotMs / 100) / 10.0), Modifier.testTag("dub-voice-slot-${r.id}"))
            take?.let { Fact(AfIcons.Regular.Wave, tr(UiText.DUB_TAKE, "s" to sec(it.ms)), Modifier.testTag("dub-take-${r.id}")) }
        }
        if (long) WrapRow(Modifier.padding(top = 4.dp)) {
            GlassButton(My.breakSyllables(tr(UiText.SUB_EDIT_LINE)), editLine, Modifier.testTag("dub-edit-line-${r.id}"), icon = AfIcons.Regular.Edit,
                small = true)
            shorten?.let { GlassButton(My.breakSyllables(tr(UiText.DUB_SHORTEN)), it, Modifier.testTag("dub-row-shorten-${r.id}"),
                icon = AfIcons.Regular.Spark, small = true, enabled = idle && canShorten) }
        }
    }
}

/**
 * One line on the desktop (Group 4 H, sample 12): a table row — "#n", the start time (mono), the spoken line with the source
 * line under it (muted 12 sp), the take's length, the fit pill and Play; the head selects the line and seeks the preview
 * there. The [selected] line opens in place: its spoken line (read-only here; Edit line opens Dub script), the room, and
 * Edit line · Shorten (too long) · Speak this line again.
 */
@Composable
private fun DeskLineRow(model: DubVoiceModel, r: DubVoiceRow, selected: Boolean, select: () -> Unit, idle: Boolean, playing: Boolean,
    toggle: () -> Unit, playingLine: Boolean, editLine: () -> Unit, shorten: (() -> Unit)?, canShorten: Boolean) {
    val take = r.take
    val long = r.fit?.kind == DubFitKind.TOO_LONG
    GlassCard(Modifier.testTag("dub-voice-row-${r.id}"), padding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), gap = 8.dp,
        selected = selected && !long, attention = if (long) io.github.alalkipgen.autoflow.ui.theme.Tone.Warning else null,
        outline = when {
            long && selected -> AutoFlowColors.Amber
            playingLine -> GlassColors.PlayingOutline
            long -> AutoFlowColors.Amber.copy(alpha = .45f)
            else -> null
        }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.weight(1f).heightIn(min = io.github.alalkipgen.autoflow.ui.theme.AfSize.Touch).testTag("dub-voice-head-${r.id}")
                .pointerInput(select) { detectTapGestures { select() } }.semantics { role = Role.Button; onClick { select(); true } }
                .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AfText("#${r.n}", Modifier.widthIn(min = 40.dp), size = 14.sp, bold = true)
                AfText(TimeText.show(r.startMs), Modifier.widthIn(min = 56.dp), muted = true, mono = true, size = 13.sp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AfText(My.breakSyllables(r.spoken), size = 15.sp)
                    if (r.source.isNotBlank()) AfText(r.source, muted = true, size = 12.sp)
                }
            }
            take?.let { AfText(My.breakSyllables(tr(UiText.DUB_TAKE, "s" to sec(it.ms))), Modifier.testTag("dub-take-${r.id}"), muted = true, size = 12.sp) }
            FitPill(r, Modifier.testTag("dub-fit-${r.id}"))
            Box(Modifier.size(io.github.alalkipgen.autoflow.ui.theme.AfSize.Touch), contentAlignment = Alignment.Center) {
                take?.let { t -> model.takePath(t.au)?.let { AudioPlayButton(it, playing, toggle, glass = true, glassIcon = AfIcons.Fill.Play) } }
            }
        }
        if (selected) {
            val box = RoundedCornerShape(12.dp)
            Box(Modifier.fillMaxWidth().clip(box).background(Color.White.copy(alpha = .04f)).border(1.dp, Color.White.copy(alpha = .08f), box)
                .padding(horizontal = 14.dp, vertical = 10.dp)) { AfText(My.breakSyllables(r.spoken), size = 15.sp) }
            WrapRow(spacing = 12.dp) {
                Fact(AfIcons.Regular.Timer, tr(UiText.DUB_SLOT, "s" to (r.slotMs / 100) / 10.0), Modifier.testTag("dub-voice-slot-${r.id}"))
            }
            WrapRow(Modifier.padding(bottom = 8.dp)) {
                if (long) GlassButton(My.breakSyllables(tr(UiText.SUB_EDIT_LINE)), editLine, Modifier.testTag("dub-edit-line-${r.id}"),
                    icon = AfIcons.Regular.Edit, small = true)
                if (long) shorten?.let { GlassButton(My.breakSyllables(tr(UiText.DUB_SHORTEN)), it, Modifier.testTag("dub-row-shorten-${r.id}"),
                    icon = AfIcons.Regular.Spark, small = true, enabled = idle && canShorten) }
                if (take != null || r.old) GlassButton(My.breakSyllables(tr(UiText.DUB_REDO_LINE)), { model.ask(setOf(r.id)) }, Modifier.testTag("dub-redo-${r.id}"),
                    icon = AfIcons.Regular.Redo, small = true, enabled = idle)
            }
        }
    }
}

@Composable
private fun Fact(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, modifier: Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        AfIcon(icon, tint = AutoFlowColors.Muted, size = 14.dp)
        AfText(My.breakSyllables(text), modifier, muted = true, size = 12.sp)
    }
}

/**
 * The fit pill (Group 3's pill look): Fits Green · Uses the pause Cyan timer · Faster Cyan gauge · Too long Amber warning ·
 * No voice / Script changed Muted. One node for tests and screen readers (the text is the pill's).
 */
@Composable
internal fun FitPill(r: DubVoiceRow, modifier: Modifier) {
    val fit = r.fit
    val (icon, tone, text) = when {
        r.take == null || fit == null -> if (r.old) Triple(AfIcons.Regular.NotePencil, AutoFlowColors.Muted, tr(UiText.V3_SCRIPT_CHANGED))
            else Triple(AfIcons.Regular.CircleDashed, AutoFlowColors.Muted, tr(UiText.V3_NO_VOICE))
        fit.kind == DubFitKind.FITS -> Triple(AfIcons.Regular.CheckCircle, AutoFlowColors.Green, tr(UiText.V3_FITS))
        fit.kind == DubFitKind.USES_PAUSE -> Triple(AfIcons.Regular.Timer, AutoFlowColors.Cyan, tr(UiText.DUB_USES_PAUSE))
        fit.kind == DubFitKind.FASTER -> Triple(AfIcons.Regular.Gauge, AutoFlowColors.Cyan, tr(UiText.DUB_FASTER, "x" to fit.speed))
        else -> Triple(AfIcons.Fill.Warn, AutoFlowColors.Amber, tr(UiText.V3_TOO_LONG, "seconds" to sec(fit.overMs)))
    }
    io.github.alalkipgen.autoflow.ui.shots.TonePill(tone, icon, text, modifier.semantics(mergeDescendants = true) {})
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

/**
 * Shorten (D-2c; Group 4 F 6): offered for TOO_LONG lines in a Purple sparkle tile card; Gemini's lines next to the current
 * ones (old struck through), chosen per line with a glass Use chip, then Use n lines. While Gemini works: the job-card look.
 */
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
    TileCard(My.breakSyllables(tr(UiText.DUB_SHORTEN)), CardTile(AfIcons.Regular.Spark, AutoFlowColors.Purple), Modifier.testTag("dub-shorten-card")) {
        when {
            s.rows.isNotEmpty() -> {
                AfText(My.breakSyllables(tr(UiText.DUB_SHORTEN_TITLE)), bold = true, size = 14.sp)
                val box = RoundedCornerShape(12.dp)
                s.rows.forEach { r ->
                    Column(Modifier.fillMaxWidth().clip(box).background(Color.White.copy(alpha = .04f)).border(1.dp, Color.White.copy(alpha = .08f), box)
                        .padding(horizontal = 12.dp, vertical = 10.dp).testTag("dub-shorten-row-${r.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AfText("#${r.n}", muted = true, mono = true, size = 12.sp)
                        AfText(My.breakSyllables(r.old), muted = true, size = 13.sp,
                            decoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
                        AfText(My.breakSyllables(r.new), Modifier.testTag("dub-shorten-new-${r.id}"), bold = true, size = 15.sp)
                        val over = r.new.length > r.maxChars
                        AfText(My.breakSyllables(tr(UiText.DUB_SHORTEN_NEW, "len" to r.new.length, "max" to r.maxChars)), size = 12.sp,
                            color = if (over) AutoFlowColors.Amber else null, muted = !over)
                        val tick: (@Composable (Color) -> Unit)? = if (!r.use) null else ({ ink -> AfIcon(AfIcons.Regular.Check, tint = ink, size = 14.dp) })
                        GlassFilterChip(My.breakSyllables(tr(UiText.DUB_USE)), r.use, { model.toggle(r.id) }, Modifier.testTag("dub-shorten-use-${r.id}"),
                            leading = tick)
                    }
                }
                val n = s.rows.count { it.use }
                WrapRow {
                    GlassButton(My.breakSyllables(tr(UiText.DUB_USE_LINES, "n" to n)), { if (model.use(script)) toScript() },
                        Modifier.testTag("dub-shorten-apply"), kind = ButtonKind.Primary, enabled = n > 0 && idle, icon = AfIcons.Regular.Check)
                    GlassButton(My.breakSyllables(tr(UiText.BACK)), { model.dismiss() }, Modifier.testTag("dub-shorten-back"), icon = AfIcons.Regular.Back)
                }
            }
            else -> {
                s.notice?.let { GlassCallout(AfIcons.Fill.Warn, My.breakSyllables(it.text(LocalUiLanguage.current)), AutoFlowColors.Amber,
                    textModifier = Modifier.testTag("dub-shorten-notice")) }
                if (long > 0) {
                    AfText(My.breakSyllables(tr(UiText.DUB_SHORTEN_N, "n" to long)), size = 14.sp)
                    GlassButton(My.breakSyllables(tr(UiText.DUB_SHORTEN)), { model.ask(dir, rows, doc) }, Modifier.testTag("dub-shorten"),
                        enabled = idle && model.available, icon = AfIcons.Regular.Spark)
                }
            }
        }
    }
}
