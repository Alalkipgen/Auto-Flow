package io.github.alalkipgen.autoflow.ui.export

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
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.alalkipgen.autoflow.core.subtitles.TimeText
import io.github.alalkipgen.autoflow.media.playback.MoviePhase
import io.github.alalkipgen.autoflow.media.playback.MovieRequest
import io.github.alalkipgen.autoflow.media.render.Clip
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.recap.BigPlay
import io.github.alalkipgen.autoflow.ui.recap.PlayCircle
import io.github.alalkipgen.autoflow.ui.recap.PreviewTrack
import io.github.alalkipgen.autoflow.ui.subtitles.BurnedCaption
import io.github.alalkipgen.autoflow.ui.subtitles.SubtitlePlayer
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.GlassColors
import io.github.alalkipgen.autoflow.ui.theme.GlassShapes
import io.github.alalkipgen.autoflow.ui.theme.Tone

/**
 * F-6 (D-95): the Music Video before any render — the render's own graph ([ExportModel.musicPreview]) played on one
 * clock: each shot's accepted clip from its start for the shot's length (muted, the render drops the clips' sound),
 * a pending shot as its "#NN — pending" card, the song from 0:00 and the lyric overlays drawn as they will be burned.
 * Nothing is rendered; transitions, the exact fit of each clip and the encode are the render's.
 *
 * Glass look (Music Video plan G + 🆕-3, samples 12 / 12b–d): one blue-tinted glass card — `monitor-play` tile, the
 * hint folded behind ⓘ; the picture (radius 12, soft shadow) with the time as a glass chip, a pending shot as a dark
 * glass card, the big Play while paused; the glass scrubber with a tick per shot (a tap on a tick goes to that shot);
 * "#n · lyric" + the time; −5 s · Play / Pause · +5 s · Fullscreen. Fullscreen opens as a dialog (the picture moves
 * there; the clock, song and lyrics stay the same). Empty = the dashed box.
 */
@Composable
internal fun MusicVideoPreview(model: ExportModel, player: SubtitlePlayer) {
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.refresh() }
    val preview = remember(state.projectDir, state.shots, state.accepted, state.durationMs, state.songFile, state.font,
        state.lyricShots, state.syncedLines, state.timingOutdated) { model.musicPreview() } ?: return
    val graph = preview.graph
    var hintOpen by rememberSaveable { mutableStateOf(false) }
    val title = tr(UiText.PROJECT_PAGE_PREVIEW)
    GlassCard(Modifier.testTag("mv-preview-card"), tint = GlassColors.ExportBlue, tintAlpha = GlassColors.TintMvPreview, gap = 10.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftTile(AfIcons.Fill.MonitorPlay, GlassColors.ExportBlue, size = 30.dp)
            AfText(My.breakSyllables(title), Modifier.weight(1f), type = GlassSectionType, weight = FontWeight.Bold)
            val fold = tr(if (hintOpen) UiText.V3_EXPANDED else UiText.V3_COLLAPSED)
            GlassIconButton(if (hintOpen) AfIcons.Regular.CaretUp else AfIcons.Regular.Info, title, { hintOpen = !hintOpen },
                Modifier.testTag("mv-preview-hint-toggle").semantics { stateDescription = fold }, size = GlassShapes.IconButtonHeader)
        }
        if (hintOpen) AfText(My.breakSyllables(tr(UiText.MV_PREVIEW_HINT)), Modifier.testTag("mv-preview-hint"), muted = true, size = 12.sp)
        if (graph.clips.isEmpty()) {
            Column(Modifier.fillMaxWidth().glassDashedBox().padding(horizontal = 16.dp, vertical = 18.dp).testTag("mv-preview-empty"),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AfIcon(AfIcons.Duotone.MonitorPlay, tint = GlassColors.ExportBlue, size = 30.dp)
                AfText(My.breakSyllables(tr(UiText.MV_PREVIEW_EMPTY)), muted = true, type = AfType.Caption,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            return@GlassCard
        }
        val starts = remember(graph) { MusicPreviewClock.starts(graph.clips) }
        val duration = graph.durationMs.coerceAtLeast(1)
        var positionMs by remember(graph) { mutableLongStateOf(0L) }
        var playing by remember(graph) { mutableStateOf(false) }
        var seeks by remember(graph) { mutableIntStateOf(0) }
        var failed by remember(graph) { mutableStateOf(emptySet<String>()) }
        var fullscreen by remember(graph) { mutableStateOf(false) }
        LaunchedEffect(graph, playing) {
            if (!playing) return@LaunchedEffect
            var last = withFrameMillis { it }
            while (playing) {
                val now = withFrameMillis { it }
                positionMs = (positionMs + (now - last)).coerceAtMost(duration)
                last = now
                if (positionMs >= duration) playing = false
            }
        }
        val index = MusicPreviewClock.index(starts, positionMs)
        val clip = graph.clips[index]
        val file = clip.artifact?.let { preview.files[it] }
        val video = file != null && file !in failed
        val aspect = graph.output.width.toFloat() / graph.output.height
        val lyric = graph.overlays.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs }?.text?.replace("\n", " ")?.trim()
        val bar = MvBar(positionMs, duration, playing, index, starts,
            seekTo = { positionMs = it.coerceIn(0, duration); seeks++ },
            toggle = {
                if (!playing && positionMs >= duration) { positionMs = 0; seeks++ }
                playing = !playing
            })
        // The picture lives in one place: the card, or the Fullscreen window (a new player there, same clock).
        val picture: @Composable (Modifier) -> Unit = { modifier ->
            Box(modifier, contentAlignment = Alignment.Center) {
                if (video) key(index, seeks) {
                    // The clip starts where the clock is inside its shot; a scrub or the next shot makes a new one.
                    val from = remember { clip.trimInMs + (positionMs - starts[index]).coerceAtLeast(0) }
                    player(file!!, MovieRequest(from, seeks.toLong(), playing = playing, muted = true), Modifier.fillMaxSize()) {
                        if (it.phase == MoviePhase.FAILED) failed = failed + file
                    }
                } else PendingCard(if (file != null) My.breakSyllables(tr(UiText.SUB_PREVIEW_UNAVAILABLE)) else clip.label.ifBlank { "—" })
                BurnedCaption(graph.overlays, positionMs, graph.output.height, "mv-preview-lyric")
                if (!playing && video) Box(Modifier.testTag("mv-preview-big-play")) { BigPlay(bar.toggle) }
            }
        }
        val viewport = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value }.takeIf { it > 0f } ?: 600f
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val width = minOf(maxWidth.value, viewport * (if (aspect > 1f) .45f else .55f) * aspect)
            val shape = RoundedCornerShape(12.dp)
            Box(Modifier.width(width.dp).aspectRatio(aspect).shadow(6.dp, shape).clip(shape).background(Color.Black)
                .border(1.dp, GlassColors.MvFrameEdge, shape).testTag("mv-preview-video"), contentAlignment = Alignment.Center) {
                if (!fullscreen) picture(Modifier.fillMaxSize())
                AfText("#${index + 1} · ${TimeText.show(positionMs)} / ${TimeText.show(duration)}", Modifier.align(Alignment.TopStart).padding(8.dp)
                    .clip(RoundedCornerShape(8.dp)).background(GlassColors.MvTimeChip).padding(horizontal = 8.dp, vertical = 3.dp)
                    .testTag("mv-preview-time"), mono = true, size = 11.5.sp, color = Color.White, weight = FontWeight.Bold)
            }
        }
        preview.song?.let { song -> key(song) { PreviewTrack(song, positionMs, playing, 1f, 1f, loop = false) } }
        Scrubber(bar)
        ShotLine(bar, lyric)
        Controls(bar, Modifier.padding(horizontal = 4.dp)) {
            GlassIconButton(AfIcons.Regular.CornersOut, tr(UiText.PREVIEW_FULLSCREEN), { fullscreen = true },
                Modifier.testTag("mv-preview-fullscreen"), size = GlassShapes.IconButtonHeader)
        }
        if (fullscreen) MvFullscreen(bar, aspect, picture) { fullscreen = false }
    }
}

/** One recomposition's clock for the card and Fullscreen: position, length, play state, the current shot, the shot starts. */
private class MvBar(val positionMs: Long, val durationMs: Long, val playing: Boolean, val index: Int, val starts: List<Long>,
    val seekTo: (Long) -> Unit, val toggle: () -> Unit) {
    val ticks: List<Float> get() = starts.map { it.toFloat() / durationMs }
}

/** A shot with no accepted video (or one that cannot play): a dark glass card centred (Duotone `film-slate` = AfIcons `Film`, + its label). */
@Composable
private fun PendingCard(label: String) {
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(GlassColors.MvPendingGlow, GlassColors.MvPendingDeep))),
        contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(12.dp)
        // Above the centre, so a two-line lyric burned at the bottom stays clear of it.
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp).clip(shape).background(GlassColors.MvPendingFill).border(1.dp, GlassColors.MvPendingBorder, shape)
            .padding(horizontal = 16.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AfIcon(AfIcons.Duotone.Film, tint = AutoFlowColors.Purple, size = 24.dp)
            AfText(label, Modifier.testTag("mv-preview-card-label"), color = Color.White, size = 13.sp, weight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/**
 * The glass scrubber with one tick per shot; a tap within 12 dp of a tick goes to that shot's start, a drag scrubs as
 * before. Each tick is also a "#n" button for TalkBack / tests (`mv-preview-tick-<n>`), with no pointer input of its own.
 */
@Composable
private fun Scrubber(bar: MvBar, modifier: Modifier = Modifier) {
    var trackPx by remember { mutableIntStateOf(0) }
    val near = with(LocalDensity.current) { 12.dp.toPx() }
    val ticks = bar.ticks
    Box(modifier.fillMaxWidth().onSizeChanged { trackPx = it.width }) {
        AfScrubber(bar.positionMs.toFloat() / bar.durationMs, tr(UiText.SUB_PLAYHEAD), { bar.seekTo((it * bar.durationMs).toLong()) },
            Modifier.fillMaxWidth().touchOverflow(horizontal = 12.dp, vertical = 4.dp).testTag("mv-preview-scrubber"), glass = true, ticks = ticks,
            onTap = { f ->
                // touchOverflow widens the scrubber 12 dp past each side, so its track spans this box edge to edge.
                val track = trackPx.coerceAtLeast(1).toFloat()
                val hit = ticks.indices.minByOrNull { kotlin.math.abs(ticks[it] - f) }?.takeIf { kotlin.math.abs(ticks[it] - f) * track <= near }
                bar.seekTo(if (hit != null) bar.starts[hit] else (f * bar.durationMs).toLong())
            })
        TickTargets(bar)
    }
}

/** Zero-input nodes over the ticks (the track spans the box: the scrubber's 12 dp insets sit in its touch overflow). */
@Composable
private fun BoxScope.TickTargets(bar: MvBar) {
    Layout({
        bar.starts.forEachIndexed { i, start ->
            val label = "#${i + 1} · ${TimeText.show(start)}"
            Box(Modifier.size(24.dp, 48.dp).testTag("mv-preview-tick-${i + 1}").semantics {
                role = Role.Button
                contentDescription = label
                onClick(label) { bar.seekTo(start); true }
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

/** "#n · lyric" (the line burned now, if any) left, the mono time right; the time goes under a long line (never a cut word). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShotLine(bar: MvBar, lyric: String?) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically) {
        AfText(My.breakSyllables("#${bar.index + 1}" + (lyric?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")),
            Modifier.padding(end = 8.dp).testTag("mv-preview-shot"), size = 12.5.sp, weight = FontWeight.Bold)
        TimeLine(bar)
    }
}

@Composable
private fun TimeLine(bar: MvBar, modifier: Modifier = Modifier) =
    AfText("${TimeText.show(bar.positionMs)} / ${TimeText.show(bar.durationMs)}", modifier, mono = true, size = 12.sp, color = AutoFlowColors.Muted)

/** −5 s · Play / Pause (48 dp primary circle) · +5 s · [middle] (Fullscreen landscape: scrubber, time, chip) · [last]. */
@Composable
private fun Controls(bar: MvBar, modifier: Modifier = Modifier, middle: (@Composable RowScope.() -> Unit)? = null, last: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassIconButton(AfIcons.Regular.ClockCounterClockwise, tr(UiText.BACK_FIVE_SECONDS), { bar.seekTo(bar.positionMs - 5_000) },
            Modifier.testTag("mv-preview-back"), size = GlassShapes.IconButtonHeader)
        PlayCircle(bar.playing, bar.toggle, "mv-preview-play", tr(if (bar.playing) UiText.SUB_PAUSE else UiText.SUB_PLAY))
        GlassIconButton(AfIcons.Regular.ClockClockwise, tr(UiText.FORWARD_FIVE_SECONDS), { bar.seekTo(bar.positionMs + 5_000) },
            Modifier.testTag("mv-preview-forward"), size = GlassShapes.IconButtonHeader)
        if (middle != null) middle() else Spacer(Modifier.weight(1f))
        last()
    }
}

/**
 * Fullscreen (Group 2 D 25c–d look, samples 12c / 12d): black, the picture centred, nothing over it but the burned lyric.
 * Portrait = picture · scrubber · time + "#n" chip · controls with exit; landscape = the picture fills the height above
 * one bar (controls, scrubber, time, chip, exit).
 */
@Composable
private fun MvFullscreen(bar: MvBar, aspect: Float, picture: @Composable (Modifier) -> Unit, onExit: () -> Unit) {
    Dialog(onDismissRequest = onExit, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        val exit: @Composable () -> Unit = {
            GlassIconButton(AfIcons.Regular.CornersIn, tr(UiText.PREVIEW_EXIT_FULLSCREEN), onExit, Modifier.testTag("mv-preview-fullscreen-exit"),
                size = GlassShapes.IconButtonHeader, selected = true)
        }
        val chip: @Composable () -> Unit = { ToneChip("#${bar.index + 1}", Tone.Primary, Modifier.testTag("mv-preview-fullscreen-shot")) }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).testTag("mv-preview-fullscreen-window")) {
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
                    chip()
                }, last = exit)
                Spacer(Modifier.height(4.dp))
            } else Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.Center) {
                val cap = (high - 200.dp).coerceAtLeast(120.dp)
                Box(Modifier.fillMaxWidth().heightIn(max = cap), contentAlignment = Alignment.Center) {
                    picture(frame.fillMaxWidth().aspectRatio(aspect))
                }
                Spacer(Modifier.height(6.dp))
                Scrubber(bar)
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically) {
                    TimeLine(bar, Modifier.padding(end = 8.dp))
                    chip()
                }
                Spacer(Modifier.height(10.dp))
                Controls(bar, Modifier.padding(horizontal = 4.dp), last = exit)
            }
        }
    }
}

/** Shots are joined with cuts only, so each clip starts where the one before it ends. */
internal object MusicPreviewClock {
    fun starts(clips: List<Clip>): List<Long> = clips.runningFold(0L) { at, c -> at + c.durationMs }.dropLast(1)
    fun index(starts: List<Long>, positionMs: Long): Int = starts.indexOfLast { it <= positionMs }.coerceAtLeast(0)
}
