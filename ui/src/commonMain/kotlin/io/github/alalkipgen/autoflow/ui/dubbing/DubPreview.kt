package io.github.alalkipgen.autoflow.ui.dubbing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.core.subtitles.TimeText
import io.github.alalkipgen.autoflow.media.audio.DubCue
import io.github.alalkipgen.autoflow.media.playback.MovieRequest
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.recap.BigPlay
import io.github.alalkipgen.autoflow.ui.recap.PlayCircle
import io.github.alalkipgen.autoflow.ui.recap.PreviewTrack
import io.github.alalkipgen.autoflow.ui.subtitles.BurnedCaption
import io.github.alalkipgen.autoflow.ui.subtitles.Playhead
import io.github.alalkipgen.autoflow.ui.subtitles.SubtitlePlayer
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.GlassColors
import io.github.alalkipgen.autoflow.ui.theme.GlassShapes
import io.github.alalkipgen.autoflow.ui.theme.Tone

/**
 * F-6 (D-95): the dubbed video before Export, on Dub script, Voice and Export — the video with the movie's sound at
 * its mix level (muted when Mute), every spoken line's take where the mix puts it, and the Burmese subtitles as the burn
 * draws them ([captions] off on Export when Burmese subtitles are off). Nothing is rendered; the mix's exact levels,
 * the stems and the burn itself are Export's.
 *
 * Glass look (Group 4 D + 🆕-1, samples 11 / 11b / 11c; the Group 2 / 3 preview card): a cyan-tinted glass card —
 * `monitor-play` tile, PROJECT_PAGE_PREVIEW over the one-line scope (V3_DUB_PREVIEW_SCOPE), DUB_PREVIEW_HINT folded behind
 * ⓘ; the picture (radius 12, soft shadow, the big Play while paused); the glass scrubber with a tick per line (amber = too
 * long, faint = no voice); the mono time + "#n" chip of the line under the playhead; −5 s · Play / Pause · +5 s ·
 * Fullscreen. Fullscreen opens as a dialog on the same [playhead] (the picture moves there). No decoder = a dark glass box.
 */
@Composable
internal fun DubPreview(export: DubExportModel, open: OpenProject?, doc: SubtitleDoc, playhead: Playhead, player: SubtitlePlayer,
    captions: Boolean = true) {
    val video = open?.project?.subtitles?.video ?: return
    val ex by export.state.collectAsState()
    val voice = remember(open.dir, open.project, doc) { export.previewVoice(open, doc) }
    val ticks = remember(open.dir, open.project, doc) { export.previewTicks(open, doc) }
    val burned = remember(open.dir, open.project, doc, ex.size, ex.fit, captions) {
        if (captions) export.previewCaptions(open, doc) else emptyList()
    }
    val path = joinPath(open.dir, video.file)
    var hintOpen by rememberSaveable { mutableStateOf(false) }
    var failed by remember(path) { mutableStateOf(false) }
    var fullscreen by remember(path) { mutableStateOf(false) }
    val duration = video.durationMs.coerceAtLeast(1)
    val aspect = if (video.width > 0 && video.height > 0) video.width.toFloat() / video.height else 16f / 9
    val title = tr(UiText.PROJECT_PAGE_PREVIEW)
    val bar = DubBar(playhead.positionMs, duration, playhead.playing, DubPreviewClock.line(ticks, playhead.positionMs), ticks,
        seekTo = { playhead.seek(it.coerceIn(0, duration)) },
        toggle = {
            if (!playhead.playing && playhead.positionMs >= duration) playhead.seek(0)
            playhead.toggle()
        })
    // The picture lives in one place: the card, or the Fullscreen window (a new player there, from where the clock is).
    val picture: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier, contentAlignment = Alignment.BottomCenter) {
            player(path, MovieRequest(playhead.seekMs, playhead.revision, playing = playhead.playing, muted = export.previewOriginal(open) <= 0f,
                volume = export.previewOriginal(open).coerceIn(0f, 1f)), Modifier.fillMaxSize()) { if (!playhead.report(it)) failed = true }
            BurnedCaption(burned, playhead.positionMs, video.height, "dub-burned-line")
            if (!playhead.playing) Box(Modifier.align(Alignment.Center).testTag("dub-preview-big-play")) { BigPlay(bar.toggle) }
        }
    }
    val moveTo: (Boolean) -> Unit = { full -> fullscreen = full; playhead.seek(playhead.positionMs) }
    GlassCard(Modifier.fillMaxWidth().testTag("dub-preview-box"), tint = AutoFlowColors.Cyan, tintAlpha = .14f, gap = 10.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftTile(AfIcons.Fill.MonitorPlay, AutoFlowColors.Cyan, size = 30.dp)
            Column(Modifier.weight(1f)) {
                AfText(My.breakSyllables(title), type = GlassSectionType, weight = FontWeight.Bold)
                AfText(My.breakSyllables(tr(UiText.V3_DUB_PREVIEW_SCOPE, "n" to voice.size)), Modifier.testTag("dub-preview-scope"),
                    muted = true, type = AfType.Caption)
            }
            val fold = tr(if (hintOpen) UiText.V3_EXPANDED else UiText.V3_COLLAPSED)
            GlassIconButton(AfIcons.Regular.Info, title, { hintOpen = !hintOpen },
                Modifier.testTag("dub-preview-info").semantics { stateDescription = fold }, size = GlassShapes.IconButtonHeader, selected = hintOpen)
        }
        if (hintOpen) AfText(My.breakSyllables(tr(UiText.DUB_PREVIEW_HINT, "n" to voice.size)), Modifier.testTag("dub-preview-hint"),
            muted = true, size = 12.sp)
        Column(Modifier.fillMaxWidth().testTag("dub-preview-card"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val viewport = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value }.takeIf { it > 0f } ?: 600f
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                // A third of the window high at most (the lines stay in sight); a portrait video at most 46 % of the width.
                val width = minOf(maxWidth.value * (if (aspect < 1f) .46f else 1f), viewport * .3f * aspect)
                val shape = RoundedCornerShape(12.dp)
                if (failed) Column(Modifier.fillMaxWidth().heightIn(min = minOf(maxWidth.value * 9f / 16f, viewport * .3f).dp).clip(shape).background(GlassColors.MvTimeChip)
                    .border(1.dp, GlassColors.MvFrameEdge, shape).padding(16.dp).testTag("dub-preview-unavailable"),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
                    AfIcon(AfIcons.Duotone.VideoCameraSlash, tint = AutoFlowColors.Muted, size = 30.dp)
                    AfText(My.breakSyllables(tr(UiText.SUB_PREVIEW_UNAVAILABLE)), muted = true, type = AfType.Caption, textAlign = TextAlign.Center)
                } else Box(Modifier.width(width.dp).aspectRatio(aspect).shadow(6.dp, shape).clip(shape).background(Color.Black)
                    .border(1.dp, GlassColors.MvFrameEdge, shape).testTag("dub-preview"), contentAlignment = Alignment.Center) {
                    if (!fullscreen) picture(Modifier.fillMaxSize())
                }
            }
            if (!failed) {
                DubVoiceSound(voice, playhead.positionMs, playhead.playing)
                Scrubber(bar)
                TimeRow(bar)
                Controls(bar, Modifier.padding(horizontal = 4.dp)) {
                    GlassIconButton(AfIcons.Regular.CornersOut, tr(UiText.PREVIEW_FULLSCREEN), { moveTo(true) },
                        Modifier.testTag("dub-preview-fullscreen"), size = GlassShapes.IconButtonHeader)
                }
            }
        }
    }
    if (fullscreen && !failed) DubFullscreen(bar, aspect, picture) { moveTo(false) }
}

/** One recomposition's clock for the card and Fullscreen: position, length, play state, the line under the playhead, the ticks. */
private class DubBar(val positionMs: Long, val durationMs: Long, val playing: Boolean, val line: Int?, val lines: List<DubTick>,
    val seekTo: (Long) -> Unit, val toggle: () -> Unit) {
    val ticks: List<Float> get() = lines.map { it.startMs.toFloat() / durationMs }
    val colours: List<Color> get() = lines.map {
        when (it.kind) {
            DubTickKind.TOO_LONG -> AutoFlowColors.Amber
            DubTickKind.NO_VOICE -> Color.White.copy(alpha = .15f)
            DubTickKind.VOICED -> Color.White.copy(alpha = .35f)
        }
    }
}

/**
 * The glass scrubber with one tick per line; a tap within 12 dp of a tick goes to that line's start, a drag scrubs as
 * before. Each tick is also a "#n" button for TalkBack / tests (`dub-preview-tick-<n>`), with no pointer input of its own.
 */
@Composable
private fun Scrubber(bar: DubBar, modifier: Modifier = Modifier) {
    var trackPx by remember { mutableIntStateOf(0) }
    val near = with(LocalDensity.current) { 12.dp.toPx() }
    val ticks = bar.ticks
    Box(modifier.fillMaxWidth().onSizeChanged { trackPx = it.width }) {
        AfScrubber(bar.positionMs.toFloat() / bar.durationMs, tr(UiText.SUB_PLAYHEAD), { bar.seekTo((it * bar.durationMs).toLong()) },
            Modifier.fillMaxWidth().touchOverflow(horizontal = 12.dp, vertical = 4.dp).testTag("dub-scrubber"), glass = true, ticks = ticks,
            tickColors = bar.colours, onTap = { f ->
                // touchOverflow widens the scrubber 12 dp past each side, so its track spans this box edge to edge.
                val track = trackPx.coerceAtLeast(1).toFloat()
                val hit = ticks.indices.minByOrNull { kotlin.math.abs(ticks[it] - f) }?.takeIf { kotlin.math.abs(ticks[it] - f) * track <= near }
                bar.seekTo(if (hit != null) bar.lines[hit].startMs else (f * bar.durationMs).toLong())
            })
        TickTargets(bar)
    }
}

/** Zero-input nodes over the ticks (the track spans the box: the scrubber's 12 dp insets sit in its touch overflow). */
@Composable
private fun BoxScope.TickTargets(bar: DubBar) {
    Layout({
        bar.lines.forEach { l ->
            val label = "#${l.n} · ${TimeText.show(l.startMs)}"
            Box(Modifier.size(24.dp, 48.dp).testTag("dub-preview-tick-${l.n}").semantics {
                role = Role.Button
                contentDescription = label
                onClick(label) { bar.seekTo(l.startMs); true }
            })
        }
    }, Modifier.matchParentSize()) { measurables, constraints ->
        val track = constraints.maxWidth.coerceAtLeast(1)
        val placeables = measurables.map { it.measure(Constraints()) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { i, p ->
                val x = (bar.ticks[i] * track).toInt() - p.width / 2
                p.place(x.coerceIn(0, (constraints.maxWidth - p.width).coerceAtLeast(0)), (constraints.maxHeight - p.height) / 2)
            }
        }
    }
}

@Composable
private fun TimeLine(bar: DubBar, modifier: Modifier = Modifier) =
    AfText("${TimeText.show(bar.positionMs)} / ${TimeText.show(bar.durationMs)}", modifier.testTag("dub-playhead"), mono = true, size = 12.5.sp)

/** "#n" of the line under the playhead (none between lines). */
@Composable
private fun LineChip(bar: DubBar) {
    bar.line?.let { ToneChip("#$it", Tone.Primary, Modifier.semantics(mergeDescendants = true) {}.testTag("dub-preview-line-chip")) }
}

/** Time left, the line chip right; when both do not fit (large fonts) the chip goes under the time, never a cut word. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeRow(bar: DubBar) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically) {
        TimeLine(bar, Modifier.padding(end = 8.dp))
        LineChip(bar)
    }
}

/** −5 s · Play / Pause (48 dp primary circle) · +5 s · [middle] (Fullscreen landscape: scrubber, time, chip) · [last]. */
@Composable
private fun Controls(bar: DubBar, modifier: Modifier = Modifier, middle: (@Composable RowScope.() -> Unit)? = null, last: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassIconButton(AfIcons.Regular.ClockCounterClockwise, tr(UiText.BACK_FIVE_SECONDS), { bar.seekTo(bar.positionMs - 5_000) },
            Modifier.testTag("dub-preview-back"), size = GlassShapes.IconButtonHeader)
        PlayCircle(bar.playing, bar.toggle, "dub-play", tr(if (bar.playing) UiText.SUB_PAUSE else UiText.SUB_PLAY))
        GlassIconButton(AfIcons.Regular.ClockClockwise, tr(UiText.FORWARD_FIVE_SECONDS), { bar.seekTo(bar.positionMs + 5_000) },
            Modifier.testTag("dub-preview-forward"), size = GlassShapes.IconButtonHeader)
        if (middle != null) middle() else Spacer(Modifier.weight(1f))
        last()
    }
}

/**
 * Fullscreen (Group 2 D 25c–d look, samples 11b / 11c): black, the picture centred, nothing over it but the burned line.
 * Portrait = picture · scrubber · time + "#n" chip · controls with exit; landscape = the picture fills the height above
 * one bar (controls, scrubber, time, chip, exit). Back (dismiss) also exits.
 */
@Composable
private fun DubFullscreen(bar: DubBar, aspect: Float, picture: @Composable (Modifier) -> Unit, onExit: () -> Unit) {
    Dialog(onDismissRequest = onExit, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        val exit: @Composable () -> Unit = {
            GlassIconButton(AfIcons.Regular.CornersIn, tr(UiText.PREVIEW_EXIT_FULLSCREEN), onExit, Modifier.testTag("dub-preview-fullscreen-exit"),
                size = GlassShapes.IconButtonHeader, selected = true)
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).testTag("dub-preview-fullscreen-window")) {
            val high = maxHeight
            val frame = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black)
            if (maxWidth > maxHeight) Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    picture(frame.fillMaxHeight().aspectRatio(aspect, matchHeightConstraintsFirst = true))
                }
                Spacer(Modifier.height(6.dp))
                Controls(bar, Modifier.padding(horizontal = 4.dp), middle = {
                    Scrubber(bar, Modifier.weight(1f))
                    TimeLine(bar)
                    LineChip(bar)
                }, last = exit)
                Spacer(Modifier.height(4.dp))
            } else Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.Center) {
                val cap = (high - 200.dp).coerceAtLeast(120.dp)
                Box(Modifier.fillMaxWidth().heightIn(max = cap), contentAlignment = Alignment.Center) {
                    picture(frame.fillMaxWidth().aspectRatio(aspect))
                }
                Spacer(Modifier.height(6.dp))
                Scrubber(bar)
                TimeRow(bar)
                Spacer(Modifier.height(10.dp))
                Controls(bar, Modifier.padding(horizontal = 4.dp), last = exit)
            }
        }
    }
}

/** The take under the playhead, kept on the preview clock ([PreviewTrack]); silent between lines. */
@Composable
internal fun DubVoiceSound(cues: List<DubCue>, positionMs: Long, playing: Boolean) {
    val cue = DubPreviewClock.at(cues, positionMs) ?: return
    key(cue.file, cue.atMs) {
        PreviewTrack(cue.file, DubPreviewClock.offset(cue, positionMs), playing, cue.speed.toFloat(), 1f, loop = false)
    }
}

/** Where the mix plays a take: from [DubCue.atMs] for [DubCue.maxMs] of output, its lead skipped, at its speed. */
internal object DubPreviewClock {
    fun at(cues: List<DubCue>, positionMs: Long): DubCue? = cues.lastOrNull { positionMs >= it.atMs && positionMs < it.atMs + it.maxMs }
    fun offset(cue: DubCue, positionMs: Long): Long = cue.leadMs + ((positionMs - cue.atMs).coerceAtLeast(0) * cue.speed).toLong()

    /**
     * The "#n" of the line under the playhead (🆕-1 chip): the line whose voice speaks now ([DubTick.voiceEndMs]) or whose
     * subtitle time holds [positionMs]; null between lines.
     */
    fun line(lines: List<DubTick>, positionMs: Long): Int? =
        lines.lastOrNull { positionMs >= it.startMs && positionMs < maxOf(it.endMs, it.voiceEndMs) }?.n
}
