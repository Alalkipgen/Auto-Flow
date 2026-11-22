package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.alalkipgen.autoflow.core.recap.PublishKitText
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.core.recap.SrtCue
import io.github.alalkipgen.autoflow.media.playback.RecapPlaybackClock
import io.github.alalkipgen.autoflow.media.render.Output
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.export.RecapFrames
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.icons.afTooltip
import io.github.alalkipgen.autoflow.ui.theme.*
import kotlinx.coroutines.launch

/**
 * The phone / narrow-window top preview (Group 2 A §1, D 25a–d): the presentation of `RecapTimelinePreview(top = true)` on the
 * same clock, decoder and output-frame compositor as the desktop card. One glass card (Cyan tint): header with collapse,
 * the output picture (radius 12, at most 45 % of the window high), the scrubber with block ticks, time + block chip,
 * −5 s · Play/Pause · +5 s · Exact frame · Fullscreen. Diagnostics (from ⋮) and Fullscreen open as dialogs.
 */
@Composable
internal fun TopPreview(
    model: RecapModel, source: RecapPreviewSource.Source, recap: RecapV2, output: Output, clock: RecapPlaybackClock,
    outputMs: Long, captions: List<SrtCue>, playing: Boolean, problem: String?, enabled: Boolean, editing: Boolean, exactEnabled: Boolean,
    storyBlocks: List<String>?, collapsed: MutableState<Boolean>, diagnostics: MutableState<Boolean>?,
    exact: RecapFrames.Result?, exactBusy: Boolean, setExact: (RecapFrames.Result?) -> Unit, setExactBusy: (Boolean) -> Unit,
    togglePlay: () -> Unit, seekTo: (Long) -> Unit, reopen: () -> Unit, player: @Composable (Modifier) -> Unit,
) {
    val aspect = output.width.toFloat() / output.height
    val duration = source.timeline.durationMs
    val scope = rememberCoroutineScope()
    var fullscreen by remember { mutableStateOf(false) }
    if (enabled && problem == null) RecapPreviewSound(source.timeline, source.music, outputMs, playing)
    val ticks = remember(source.timeline) {
        val starts = linkedMapOf<String, Long>()
        source.timeline.clips.forEach { starts.putIfAbsent(it.blockId, it.outputFromMs) }
        starts.values.map { it.toFloat() / (duration - 1).coerceAtLeast(1) }
    }
    val blockId = clock.clip?.blockId
    val blockNumber = blockId?.let { id ->
        storyBlocks?.indexOf(id)?.takeIf { it >= 0 }?.plus(1)
            ?: source.timeline.clips.map { it.blockId }.distinct().indexOf(id).takeIf { it >= 0 }?.plus(1)
    }
    val shown = exact.takeIf { exactEnabled }
    val made = shown as? RecapFrames.Result.Ok
    val still = made?.let { r -> remember(r.frame) { decodeImage(r.frame.bytes) } }
    val onExact: () -> Unit = {
        if (shown != null) setExact(null)
        else if (!exactBusy) {
            if (clock.playing) togglePlay()
            setExactBusy(true)
            val at = model.previewSource.currentPosition()
            scope.launch { try { setExact(model.exactFrame(at)) } finally { setExactBusy(false) } }
        }
    }
    val ui = TopPreviewUi(aspect, outputMs, duration, playing, enabled, problem, blockNumber, ticks, made?.frame?.atMs,
        (shown as? RecapFrames.Result.Failed)?.reason, exactBusy, exactEnabled, !playing && made == null && !editing,
        togglePlay, { seekTo(it) }, onExact)
    // The picture lives in exactly one place: the card, or the Fullscreen window (a new decoder there, same output time).
    // [live] = the decoder runs (Play pressed once, or opened at once, and no playback problem).
    val picture: @Composable (Modifier, Boolean) -> Unit = { modifier, live ->
        Box(modifier) {
            if (live) player(Modifier.fillMaxSize())
            still?.let { Image(it, tr(UiText.EXACT_FRAME_DESCRIPTION), Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
    }
    val title = tr(UiText.PROJECT_PAGE_PREVIEW)
    val folded = collapsed.value
    GlassCard(Modifier.testTag("recap-top-preview"), tint = AutoFlowColors.Cyan, tintAlpha = .14f, gap = 0.dp,
        padding = if (folded) PaddingValues(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)
        else PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp)) {
        val foldState = if (folded) tr(UiText.V3_COLLAPSED) else tr(UiText.V3_EXPANDED)
        val collapse = Modifier.testTag("recap-top-preview-collapse").semantics { stateDescription = foldState }
        if (folded) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftTile(AfIcons.Fill.MonitorPlay, AutoFlowColors.Cyan, size = 30.dp)
            Column(Modifier.weight(1f)) {
                AfText(title, type = GlassSectionType, weight = FontWeight.Bold)
                TimeText(ui, Modifier.testTag("timeline-time"), size = 12.sp, colour = AutoFlowColors.Muted)
            }
            GlassIconButton(if (playing) AfIcons.Fill.Pause else AfIcons.Fill.Play, tr(if (playing) UiText.PAUSE else UiText.PLAY), togglePlay,
                Modifier.testTag("timeline-play"), size = GlassShapes.IconButtonHeader)
            GlassIconButton(AfIcons.Regular.CaretDown, title, { collapsed.value = false }, collapse)
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftTile(AfIcons.Fill.MonitorPlay, AutoFlowColors.Cyan, size = 30.dp)
            Column(Modifier.weight(1f)) {
                AfText(title, type = GlassSectionType, weight = FontWeight.Bold)
                AfText(My.breakSyllables(tr(UiText.V3_PREVIEW_SCOPE)), muted = true, type = AfType.Caption)
            }
            GlassIconButton(AfIcons.Regular.CaretUp, title, { collapsed.value = true }, collapse)
        }
        if (!folded) Spacer(Modifier.height(8.dp))
        // One call site in both states: collapsed keeps the decoder (and so the clock, voice and music) running unseen.
        PictureSlot(ui, folded, !fullscreen, picture)
        if (!folded) {
            ui.exactFailed?.let { AfText(it, Modifier.padding(top = 6.dp), color = AutoFlowColors.Amber, size = 12.sp) }
            problem?.let { AfText(it, Modifier.padding(top = 6.dp), color = AutoFlowColors.Amber, size = 12.sp) }
            Spacer(Modifier.height(2.dp))
            Scrubber(ui)
            TimeRow(ui, Modifier.testTag("timeline-time"))
            Spacer(Modifier.height(8.dp))
            Controls(ui, Modifier.padding(horizontal = 4.dp)) {
                GlassIconButton(AfIcons.Regular.CornersOut, tr(UiText.PREVIEW_FULLSCREEN), { fullscreen = true; reopen() },
                    Modifier.testTag("timeline-fullscreen"), size = GlassShapes.IconButtonHeader)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
    if (fullscreen) FullscreenPreview(ui, { fullscreen = false; reopen() }, picture)
    if (diagnostics?.value == true) DiagnosticsDialog(recap, source, clock, outputMs, captions, seekTo) { diagnostics.value = false }
}

/** Immutable render inputs of one recomposition (the clock itself is a non-Compose controller). */
private class TopPreviewUi(
    val aspect: Float, val outputMs: Long, val durationMs: Long, val playing: Boolean, val enabled: Boolean, val problem: String?,
    val block: Int?, val ticks: List<Float>, val exactAtMs: Long?, val exactFailed: String?, val exactBusy: Boolean,
    val exactEnabled: Boolean, val bigPlay: Boolean, val togglePlay: () -> Unit, val seekTo: (Long) -> Unit, val onExact: () -> Unit,
)

/** Collapsed: a 0 × 0 slot that still composes the 1 px decoder surface; else the picture at the output ratio. */
private val Hidden = Modifier.clipToBounds().layout { measurable, _ ->
    val placeable = measurable.measure(Constraints.fixed(1, 1))
    layout(0, 0) { placeable.place(0, 0) }
}

@Composable
private fun PictureSlot(ui: TopPreviewUi, folded: Boolean, here: Boolean, picture: @Composable (Modifier, Boolean) -> Unit) {
    val viewport = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value }.takeIf { it > 0f } ?: 760f
    BoxWithConstraints(if (folded) Hidden else Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val cap = viewport * .45f
        val capped = !folded && maxWidth.value / ui.aspect > cap
        val width = if (capped) (cap * ui.aspect).dp else maxWidth
        Box(if (capped) Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = .28f)).padding(vertical = 4.dp)
            else Modifier, contentAlignment = Alignment.Center) {
            PictureFrame(ui, if (folded) Modifier.size(1.dp) else Modifier.width(width).aspectRatio(ui.aspect), !folded, here, picture)
        }
    }
}

/** Radius 12, black, 1 dp White .08 edge; idle text, the big paused Play; nothing else over the picture. */
@Composable
private fun PictureFrame(ui: TopPreviewUi, modifier: Modifier, overlays: Boolean, here: Boolean, picture: @Composable (Modifier, Boolean) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.then(if (overlays) Modifier.shadow(6.dp, shape) else Modifier).clip(shape).background(Color.Black)
        .then(if (overlays) Modifier.border(1.dp, Color.White.copy(alpha = .08f), shape) else Modifier), contentAlignment = Alignment.Center) {
        if (here) picture(Modifier.fillMaxSize(), ui.enabled && ui.problem == null)
        if (overlays) {
            if (!ui.enabled) AfText(My.breakSyllables(tr(UiText.V3_PREVIEW_IDLE)), Modifier.align(Alignment.BottomCenter).padding(10.dp),
                muted = true, type = AfType.Caption, textAlign = TextAlign.Center)
            if (ui.bigPlay && here) BigPlay(ui.togglePlay)
        }
    }
}

/** 54 dp dark glass circle, 1.5 dp White .55 ring; the Play button below is the accessible control. */
@Composable
private fun BigPlay(onPlay: () -> Unit) {
    val play by rememberUpdatedState(onPlay)
    Box(Modifier.size(54.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(AutoFlowColors.Background.copy(alpha = .55f))
        .border(1.5.dp, Color.White.copy(alpha = .55f), CircleShape).pointerInput(Unit) { detectTapGestures { play() } },
        contentAlignment = Alignment.Center) {
        AfIcon(AfIcons.Fill.Play, tint = Color.White, size = 24.dp)
    }
}

@Composable
private fun Scrubber(ui: TopPreviewUi, modifier: Modifier = Modifier) {
    val last = (ui.durationMs - 1).coerceAtLeast(1)
    // The track runs edge to edge of the card content; the 48 dp target reaches 12 dp into the card padding.
    AfScrubber(ui.outputMs.toFloat() / last, tr(UiText.V3_SCRUB_OUTPUT), { ui.seekTo((it * last).toLong()) },
        modifier.touchOverflow(horizontal = 12.dp, vertical = 4.dp).testTag("timeline-scrubber"), glass = true, ticks = ui.ticks)
}

@Composable
private fun TimeText(ui: TopPreviewUi, modifier: Modifier = Modifier, short: Boolean = false, size: androidx.compose.ui.unit.TextUnit = 12.5.sp,
    colour: Color = AutoFlowColors.Text) {
    val position = RecapModel.clock(ui.outputMs)
    val total = RecapModel.clock(ui.durationMs)
    AfText(if (short) "$position / $total" else tr(UiText.V3_OUTPUT_POSITION, "position" to position, "duration" to total),
        modifier, mono = true, size = size, color = colour)
}

/** Time left, chip right; when both do not fit (large fonts, a long chip) the chip goes under the time, never a cut word. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeRow(ui: TopPreviewUi, time: Modifier = Modifier) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically) {
        TimeText(ui, time.padding(end = 8.dp))
        StateChip(ui)
    }
}

/** The current block (primary), or the shown Exact frame's time (info, image icon), or Rendering…. */
@Composable
private fun StateChip(ui: TopPreviewUi) {
    when {
        ui.exactBusy -> ToneChip(tr(UiText.RENDERING), Tone.Running, Modifier.testTag("timeline-chip"))
        ui.exactAtMs != null -> ToneChip(tr(UiText.EXACT_FRAME_AT, "atMs" to PublishKitText.timestamp(ui.exactAtMs)), Tone.Info,
            Modifier.testTag("timeline-chip"), AfIcons.Fill.Image)
        ui.block != null -> ToneChip(tr(UiText.V3_BLOCK, "n" to ui.block), Tone.Primary, Modifier.testTag("timeline-chip"))
    }
}

/** −5 s · Play/Pause (48 dp primary circle) · +5 s · spacer · Exact frame (x = back to live) · [last] (Fullscreen / exit). */
@Composable
private fun Controls(ui: TopPreviewUi, modifier: Modifier = Modifier, middle: (@Composable RowScope.() -> Unit)? = null,
    last: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassIconButton(AfIcons.Regular.ClockCounterClockwise, tr(UiText.BACK_FIVE_SECONDS), { ui.seekTo(ui.outputMs - 5_000) },
            Modifier.testTag("timeline-back"), size = GlassShapes.IconButtonHeader)
        PlayCircle(ui.playing, ui.togglePlay)
        GlassIconButton(AfIcons.Regular.ClockClockwise, tr(UiText.FORWARD_FIVE_SECONDS), { ui.seekTo(ui.outputMs + 5_000) },
            Modifier.testTag("timeline-forward"), size = GlassShapes.IconButtonHeader)
        if (middle != null) middle() else Spacer(Modifier.weight(1f))
        val live = ui.exactAtMs != null || ui.exactFailed != null
        GlassIconButton(if (live) AfIcons.Regular.Close else AfIcons.Regular.Image, tr(if (live) UiText.CLOSE else UiText.EXACT_FRAME), ui.onExact,
            Modifier.testTag("timeline-exact"), size = GlassShapes.IconButtonHeader, enabled = live || (ui.exactEnabled && !ui.exactBusy))
        last()
    }
}

/** 48 dp primary-gradient circle with a coloured shadow and top highlight; Fill play / pause 22 dp. */
@Composable
private fun PlayCircle(playing: Boolean, onClick: () -> Unit) {
    val label = tr(if (playing) UiText.PAUSE else UiText.PLAY)
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.testTag("timeline-play").afTooltip(label).size(48.dp)
        .shadow(10.dp, CircleShape, ambientColor = GlassColors.PrimaryGlow, spotColor = GlassColors.PrimaryGlow).clip(CircleShape)
        .drawBehind { drawRect(primaryBrush()) }
        .border(1.dp, Color.White.copy(alpha = .18f), CircleShape)
        .clickable(interaction, null, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        AfIcon(if (playing) AfIcons.Fill.Pause else AfIcons.Fill.Play, tint = Color.White, size = 22.dp)
    }
}

/**
 * Fullscreen (D 25c–d): black, the picture centred. Portrait = picture · scrubber · time + chip · controls with exit;
 * landscape = the picture fills the height above one control bar. Nothing is drawn over the picture.
 */
@Composable
private fun FullscreenPreview(ui: TopPreviewUi, onExit: () -> Unit, picture: @Composable (Modifier, Boolean) -> Unit) {
    Dialog(onDismissRequest = onExit, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        val exit: @Composable () -> Unit = {
            GlassIconButton(AfIcons.Regular.CornersIn, tr(UiText.PREVIEW_EXIT_FULLSCREEN), onExit, Modifier.testTag("timeline-fullscreen-exit"),
                size = GlassShapes.IconButtonHeader, selected = true)
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).testTag("timeline-fullscreen-window")) {
            val high = maxHeight
            val shape = RoundedCornerShape(12.dp)
            val frame = Modifier.clip(shape).background(Color.Black)
            if (maxWidth > maxHeight) Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    FullPicture(ui, frame.fillMaxHeight().aspectRatio(ui.aspect, matchHeightConstraintsFirst = true), picture)
                }
                Spacer(Modifier.height(6.dp))
                Controls(ui, Modifier.padding(horizontal = 4.dp), middle = {
                    Scrubber(ui, Modifier.weight(1f))
                    TimeText(ui, short = true)
                    StateChip(ui)
                }, last = exit)
                Spacer(Modifier.height(4.dp))
            } else Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.Center) {
                val cap = (high - 200.dp).coerceAtLeast(120.dp)
                Box(Modifier.fillMaxWidth().heightIn(max = cap), contentAlignment = Alignment.Center) {
                    FullPicture(ui, frame.fillMaxWidth().aspectRatio(ui.aspect), picture)
                }
                Spacer(Modifier.height(6.dp))
                Scrubber(ui)
                TimeRow(ui)
                Spacer(Modifier.height(10.dp))
                Controls(ui, Modifier.padding(horizontal = 4.dp), last = exit)
            }
        }
    }
}

@Composable
private fun FullPicture(ui: TopPreviewUi, modifier: Modifier, picture: @Composable (Modifier, Boolean) -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        picture(Modifier.fillMaxSize(), ui.enabled && ui.problem == null)
        if (!ui.enabled) AfText(My.breakSyllables(tr(UiText.V3_PREVIEW_IDLE)), Modifier.align(Alignment.BottomCenter).padding(10.dp),
            muted = true, type = AfType.Caption, textAlign = TextAlign.Center)
        if (ui.bigPlay) BigPlay(ui.togglePlay)
    }
}

/**
 * Diagnostics (D 25b, from ⋮ on phone steps 2–5): the desktop card's readouts in a glass dialog — hint, position (mono box),
 * active native layer ids, output ms + Seek to time, the caption now; full-width Close.
 */
@Composable
private fun DiagnosticsDialog(recap: RecapV2, source: RecapPreviewSource.Source, clock: RecapPlaybackClock, outputMs: Long,
    captions: List<SrtCue>, seekTo: (Long) -> Unit, onClose: () -> Unit) {
    var seekInput by remember { mutableStateOf("0") }
    GlassDialog(onClose, AfIcons.Fill.Shield, AutoFlowColors.Cyan, tr(UiText.V3_DIAGNOSTICS), Modifier.testTag("timeline-diagnostics")) {
        AfText(tr(UiText.TIMELINE_HINT), muted = true, type = AfType.Caption)
        // A mono quote box (as GlassMonoBox) whose text node keeps the plain readout for checks.
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(GlassColors.MonoFill)
            .border(1.dp, GlassColors.MonoBorder, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 9.dp)) {
            AfText("$outputMs / ${source.timeline.durationMs} ms · ${clock.clip?.blockId ?: tr(UiText.FINISHED)}",
                Modifier.testTag("timeline-position"), mono = true, size = 12.5.sp)
        }
        val active = recap.layers.filter { io.github.alalkipgen.autoflow.core.recap.PreviewGeometry.isActive(it, outputMs) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AfText(tr(UiText.ACTIVE_LAYER_IDS).trimEnd(), Modifier.weight(1f, fill = false), muted = true, type = AfType.Caption)
            Box(Modifier.weight(1f)) { MonoText(active.joinToString { it.id }.ifEmpty { "—" }, 12.5.sp, AutoFlowColors.Muted) }
        }
        val target = seekInput.toLongOrNull()?.takeIf { it >= 0 }
        GlassFieldRow(field = {
            GlassTextField(seekInput, { seekInput = it }, tr(UiText.OUTPUT_MS),
                Modifier.fillMaxWidth().testTag("timeline-seek-input").semantics(mergeDescendants = true) {})
        }, actions = {
            GlassButton(tr(UiText.SEEK_TO_TIME), { target?.let(seekTo) }, Modifier.testTag("timeline-seek"), enabled = target != null,
                icon = AfIcons.Regular.Crosshair)
        })
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(GlassColors.MonoFill)
            .border(1.dp, GlassColors.MonoBorder, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
            AfText(captions.joinToString("\n") { it.text }.ifBlank { "—" }, Modifier.testTag("timeline-caption"), type = AfType.Body)
        }
        GlassDialogButtons(GlassDialogAction(tr(UiText.CLOSE), onClose, tag = "recap-diagnostics-close"))
    }
}

/**
 * The phone / narrow-window (< 840 dp) top preview: the first item of every Recap step's scrolling content, seeking to the
 * open block ([blockId]) as the old inline preview did. [diagnostics] = the ⋮ Diagnostics dialog ([diagnosticsAction]).
 */
@Composable
internal fun RecapTopPreview(model: RecapModel, diagnostics: MutableState<Boolean>?, blockId: String? = null,
    editLayers: Boolean = false, exactEnabled: Boolean = true) =
    RecapTimelinePreview(model, blockId, studio = true, editLayers = editLayers, top = true, diagnostics = diagnostics,
        exactEnabled = exactEnabled)

/** ⋮ Diagnostics (D §2.2, phone steps 2–5): opens the top preview's readouts; off while there is no timeline to read. */
@Composable
internal fun diagnosticsAction(model: RecapModel, state: RecapUiState, open: MutableState<Boolean>): StudioAction {
    val projects by model.previewSource.projects.collectAsState()
    val available = remember(state, projects) { model.previewSource.resolve(state) != null }
    return StudioAction(tr(UiText.V3_DIAGNOSTICS), { open.value = true }, available, "recap-diagnostics", AfIcons.Fill.Shield)
}
