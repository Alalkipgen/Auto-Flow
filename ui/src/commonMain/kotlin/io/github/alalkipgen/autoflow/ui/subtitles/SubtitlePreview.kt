package io.github.alalkipgen.autoflow.ui.subtitles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleCue
import io.github.alalkipgen.autoflow.core.subtitles.TextChoice
import io.github.alalkipgen.autoflow.core.subtitles.TimeText
import io.github.alalkipgen.autoflow.media.playback.MoviePhase
import io.github.alalkipgen.autoflow.media.playback.MovieRequest
import io.github.alalkipgen.autoflow.media.playback.MovieSnapshot
import io.github.alalkipgen.autoflow.media.render.Overlay
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.recap.BigPlay
import io.github.alalkipgen.autoflow.ui.recap.PlayCircle
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.GlassColors
import io.github.alalkipgen.autoflow.ui.theme.GlassShapes
import io.github.alalkipgen.autoflow.ui.theme.Tone

/** The editor's playhead: where the video is, whether it plays, and a seek counter (one seek per change). */
@Stable
internal class Playhead {
    var positionMs by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)
    var seekMs by mutableLongStateOf(0L); private set
    var revision by mutableLongStateOf(0L); private set
    /** Play line: pause at this time. */
    var stopAtMs by mutableStateOf<Long?>(null)

    fun seek(ms: Long) { seekMs = ms.coerceAtLeast(0); positionMs = seekMs; revision++ }
    fun playLine(c: SubtitleCue) { seek(c.startMs); stopAtMs = c.endMs; playing = true }
    fun toggle() { stopAtMs = null; playing = !playing }
    fun inside(c: SubtitleCue) = positionMs > c.startMs && positionMs < c.endMs

    /** A snapshot of the platform player; while playing it moves the playhead. */
    fun report(now: MovieSnapshot): Boolean {
        when (now.phase) {
            MoviePhase.FAILED -> { playing = false; return false }
            MoviePhase.ENDED -> playing = false
            else -> if (playing) positionMs = now.positionMs
        }
        stopAtMs?.let { if (positionMs >= it) { playing = false; stopAtMs = null } }
        return true
    }
}

/** The platform movie surface, swappable in tests (no decoder there). */
typealias SubtitlePlayer = @Composable (path: String, request: MovieRequest, modifier: Modifier, onSnapshot: (MovieSnapshot) -> Unit) -> Unit

internal fun moviePlayer(vlcDir: String?): SubtitlePlayer = { path, request, modifier, report ->
    MoviePlayback(path, request, modifier, vlcDir = vlcDir, onSnapshot = report)
}

/**
 * A seek bar for [SubtitlePreview]: the editor's cue strip (🆕-2). [fullscreen] = the copy in the Fullscreen window
 * (its own tags); [enabled] is false while the video cannot play — the strip still seeks then (plan G 4).
 */
internal typealias PreviewSeekBar = @Composable (modifier: Modifier, fullscreen: Boolean) -> Unit

/**
 * The video with the line under the playhead drawn over it (the chosen view), kept to a share of the window height
 * ([heightShare]) so the lines stay in sight on a phone. Group 5 G (🆕-1, the Group 4 pieces; samples 01 / 11 / 11b /
 * 11c): in a cyan-tinted glass card ([card]; the burn preview sits in its page's card) — the picture (radius 12, soft
 * shadow, the big Play while paused) with the time chip "0:42.3 / 12:40.0" on it ([timeOnVideo]; else the time is in the
 * controls row, the desktop), then [seekBar] (the editor's cue strip; else the glass scrubber `$tag-scrubber`), then
 * −5 s · Play / Pause · +5 s · the "#n" chip of the line under the playhead · Fullscreen (a dark window on the same
 * [playhead]; the picture moves there). Without a decoder: a dark glass box that says so; the seek bar still sets the time.
 * F-6 (D-95), the preview before a render: [burned] = the burn's own overlays, drawn as the video will show them instead
 * of the plain line (empty = no subtitles on the video); the movie's sound at [volume] (0 = muted) and [sound] for
 * what plays beside it (the dub's voices); [tag] prefixes the test tags so two previews never share them.
 */
@Composable
internal fun SubtitlePreview(path: String, video: SourceVideo, cues: List<SubtitleCue>, view: TextChoice, playhead: Playhead,
    player: SubtitlePlayer, heightShare: Float = 0.34f, tag: String = "subtitles", burned: List<Overlay>? = null, volume: Float = 1f,
    sound: @Composable (positionMs: Long, playing: Boolean) -> Unit = { _, _ -> }, timeOnVideo: Boolean = true, card: Boolean = true,
    seekBar: PreviewSeekBar? = null) {
    val aspect = if (video.width > 0 && video.height > 0) video.width.toFloat() / video.height else 16f / 9
    val viewport = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value }.takeIf { it > 0f } ?: 600f
    var failed by remember(path) { mutableStateOf(false) }
    var fullscreen by remember(path) { mutableStateOf(false) }
    val duration = video.durationMs.coerceAtLeast(1)
    val bar = PreviewBar(tag, playhead, duration, cues, failed, seekBar)
    // The picture lives in one place: the card, or the Fullscreen window (a new player there, from where the clock is).
    val picture: @Composable (Modifier, Boolean) -> Unit = { modifier, chip ->
        Box(modifier, contentAlignment = Alignment.BottomCenter) {
            player(path, MovieRequest(playhead.seekMs, playhead.revision, playing = playhead.playing, muted = volume <= 0f,
                volume = volume.coerceIn(0f, 1f)), Modifier.fillMaxSize()) { if (!playhead.report(it)) failed = true }
            if (chip) TimeLabel(bar, Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 7.dp, vertical = 2.dp), size = 11.sp, colour = Color.White)
            if (burned != null) BurnedCaption(burned, playhead.positionMs, video.height, "$tag-burned-line")
            else cues.firstOrNull { playhead.positionMs >= it.startMs && playhead.positionMs < it.endMs }?.let { c ->
                val text = when (view) {
                    TextChoice.ORIGINAL -> c.text
                    TextChoice.BURMESE -> c.textMy?.takeIf { it.isNotBlank() } ?: c.text
                    TextChoice.BOTH -> listOfNotNull(c.text, c.textMy?.takeIf { it.isNotBlank() }).joinToString("\n") // as the list and export
                }
                AfText(My.breakSyllables(text), Modifier.padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 2.dp).testTag("$tag-preview-line"), color = Color.White, textAlign = TextAlign.Center)
            }
            if (!playhead.playing) Box(Modifier.align(Alignment.Center).testTag("$tag-preview-big-play")) { BigPlay(bar.toggle) }
        }
    }
    val moveTo: (Boolean) -> Unit = { full -> fullscreen = full; playhead.seek(playhead.positionMs) }
    val body: @Composable ColumnScope.() -> Unit = {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // A share of the window high at most (the lines stay in sight); a portrait video at most 46 % of the width.
            val width = minOf(maxWidth.value * (if (aspect < 1f) .46f else 1f), viewport * heightShare * aspect)
            val shape = RoundedCornerShape(12.dp)
            if (failed) Column(Modifier.fillMaxWidth().heightIn(min = minOf(maxWidth.value * 9f / 16f, viewport * heightShare).dp).clip(shape)
                .background(GlassColors.MvTimeChip).border(1.dp, GlassColors.MvFrameEdge, shape).padding(16.dp).testTag("$tag-preview-unavailable"),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
                AfIcon(AfIcons.Duotone.VideoCameraSlash, tint = AutoFlowColors.Muted, size = 30.dp)
                AfText(My.breakSyllables(tr(UiText.SUB_PREVIEW_UNAVAILABLE)), muted = true, type = AfType.Caption, textAlign = TextAlign.Center)
            } else Box(Modifier.width(width.dp).aspectRatio(aspect).shadow(6.dp, shape).clip(shape).background(Color.Black)
                .border(1.dp, GlassColors.MvFrameEdge, shape).testTag("$tag-preview"), contentAlignment = Alignment.Center) {
                if (!fullscreen) picture(Modifier.fillMaxSize(), timeOnVideo)
            }
        }
        if (!failed) sound(playhead.positionMs, playhead.playing)
        SeekBar(bar, Modifier.fillMaxWidth(), fullscreen = false)
        Controls(bar, time = failed || !timeOnVideo) {
            if (!failed) GlassIconButton(AfIcons.Regular.CornersOut, tr(UiText.PREVIEW_FULLSCREEN), { moveTo(true) },
                Modifier.testTag("$tag-preview-fullscreen"), size = GlassShapes.IconButtonHeader)
        }
    }
    if (card) GlassCard(Modifier.fillMaxWidth().testTag("$tag-preview-card"), tint = AutoFlowColors.Cyan, tintAlpha = .12f,
        padding = PaddingValues(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 4.dp), gap = 8.dp, content = body)
    else Column(Modifier.fillMaxWidth().testTag("$tag-preview-card"), verticalArrangement = Arrangement.spacedBy(8.dp), content = body)
    if (fullscreen && !failed) PreviewFullscreen(bar, aspect, picture) { moveTo(false) }
}

/** One recomposition's clock for the card and Fullscreen: the tags, the time, the line under the playhead, the seek bar. */
private class PreviewBar(val tag: String, val playhead: Playhead, val durationMs: Long, cues: List<SubtitleCue>, val failed: Boolean,
    val seekBar: PreviewSeekBar?) {
    val positionMs = playhead.positionMs
    /** "#n" of the line under the playhead (none between lines). */
    val line: Int? = cues.indexOfFirst { positionMs >= it.startMs && positionMs < it.endMs }.takeIf { it >= 0 }?.plus(1)
    fun seekTo(ms: Long) = playhead.seek(ms.coerceIn(0, durationMs))
    val toggle: () -> Unit = {
        if (!playhead.playing && playhead.positionMs >= durationMs) playhead.seek(0)
        playhead.toggle()
    }
}

/** "0:42.3 / 12:40.0" in mono (white on the video, muted in the controls row): the now text is `$tag-playhead`, the length `$tag-strip-length`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeLabel(bar: PreviewBar, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.TextUnit = 12.5.sp,
    colour: Color = AutoFlowColors.Muted) {
    // Three whole pieces: on a narrow row the length goes under the time, never a cut number.
    FlowRow(modifier, itemVerticalAlignment = Alignment.CenterVertically) {
        AfText(TimeText.show(bar.positionMs), Modifier.testTag("${bar.tag}-playhead"), mono = true, size = size, color = colour)
        AfText(" / ", mono = true, size = size, color = colour)
        AfText(TimeText.show(bar.durationMs), Modifier.testTag("${bar.tag}-strip-length"), mono = true, size = size, color = colour)
    }
}

/** The editor's cue strip, else the glass scrubber (the Group 2 one: cyan → purple fill, 16 dp knob, no blocks). */
@Composable
private fun SeekBar(bar: PreviewBar, modifier: Modifier, fullscreen: Boolean) {
    val custom = bar.seekBar
    if (custom != null) custom(modifier, fullscreen)
    else AfScrubber(bar.positionMs.toFloat() / bar.durationMs, tr(UiText.SUB_PLAYHEAD), { bar.seekTo((it * bar.durationMs).toLong()) },
        modifier.touchOverflow(horizontal = 12.dp, vertical = 4.dp).testTag(if (fullscreen) "${bar.tag}-fullscreen-scrubber" else "${bar.tag}-scrubber"),
        glass = true)
}

/** "#n" of the line under the playhead (purple tone chip, mono; hidden between lines). */
@Composable
private fun LineChip(bar: PreviewBar) {
    bar.line?.let { ToneChip("#$it", Tone.Primary, Modifier.semantics(mergeDescendants = true) {}.testTag("${bar.tag}-preview-line-chip")) }
}

/**
 * −5 s · Play / Pause (48 dp primary circle) · +5 s · [time] ("now / length" here instead of on the video) · [middle]
 * (Fullscreen landscape: the seek bar) · "#n" chip · [last]. Off (α .38) while the video cannot play.
 */
@Composable
private fun Controls(bar: PreviewBar, modifier: Modifier = Modifier, time: Boolean, middle: (@Composable RowScope.() -> Unit)? = null,
    last: @Composable () -> Unit) {
    val on = !bar.failed
    // 8 dp gaps + 11 sp: "0:42.3 / 12:40.0" stays on one line on a 360 dp phone at font 1.0 (plan G 4); larger fonts wrap at " / ".
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassIconButton(AfIcons.Regular.ClockCounterClockwise, tr(UiText.BACK_FIVE_SECONDS), { bar.seekTo(bar.positionMs - 5_000) },
            Modifier.testTag("${bar.tag}-preview-back"), size = GlassShapes.IconButtonHeader, enabled = on)
        PlayCircle(bar.playhead.playing, bar.toggle, "${bar.tag}-play", tr(if (bar.playhead.playing) UiText.SUB_PAUSE else UiText.SUB_PLAY), enabled = on)
        GlassIconButton(AfIcons.Regular.ClockClockwise, tr(UiText.FORWARD_FIVE_SECONDS), { bar.seekTo(bar.positionMs + 5_000) },
            Modifier.testTag("${bar.tag}-preview-forward"), size = GlassShapes.IconButtonHeader, enabled = on)
        when {
            middle != null -> middle()
            time -> Box(Modifier.weight(1f)) { TimeLabel(bar, size = 11.sp) }
            else -> Spacer(Modifier.weight(1f))
        }
        if (middle != null && time) TimeLabel(bar, size = 11.sp)
        LineChip(bar)
        last()
    }
}

/**
 * Fullscreen (Group 2 D 25c–d look, samples 11b / 11c): black, the picture centred with only the line (and, portrait,
 * the time chip) over it. Portrait = picture · seek bar · controls with exit; landscape = the picture fills the height
 * above one bar (−5 · Play · +5 · seek bar · time · #n · exit). Back (dismiss) also exits.
 */
@Composable
private fun PreviewFullscreen(bar: PreviewBar, aspect: Float, picture: @Composable (Modifier, Boolean) -> Unit, onExit: () -> Unit) {
    Dialog(onDismissRequest = onExit, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        val exit: @Composable () -> Unit = {
            GlassIconButton(AfIcons.Regular.CornersIn, tr(UiText.PREVIEW_EXIT_FULLSCREEN), onExit, Modifier.testTag("${bar.tag}-preview-fullscreen-exit"),
                size = GlassShapes.IconButtonHeader, selected = true)
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).testTag("${bar.tag}-preview-fullscreen-window")) {
            val high = maxHeight
            val frame = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black)
            if (maxWidth > maxHeight) Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    picture(frame.fillMaxHeight().aspectRatio(aspect, matchHeightConstraintsFirst = true), false)
                }
                Spacer(Modifier.height(6.dp))
                Controls(bar, Modifier.padding(horizontal = 4.dp), time = true, middle = { SeekBar(bar, Modifier.weight(1f), fullscreen = true) },
                    last = exit)
                Spacer(Modifier.height(4.dp))
            } else Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.Center) {
                val cap = (high - 160.dp).coerceAtLeast(120.dp)
                Box(Modifier.fillMaxWidth().heightIn(max = cap), contentAlignment = Alignment.Center) {
                    picture(frame.fillMaxWidth().aspectRatio(aspect), true)
                }
                Spacer(Modifier.height(8.dp))
                SeekBar(bar, Modifier.fillMaxWidth(), fullscreen = true)
                Spacer(Modifier.height(4.dp))
                Controls(bar, Modifier.padding(horizontal = 4.dp), time = false, last = exit)
            }
        }
    }
}
