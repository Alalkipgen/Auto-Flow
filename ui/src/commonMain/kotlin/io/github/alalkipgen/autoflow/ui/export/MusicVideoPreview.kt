package io.github.alalkipgen.autoflow.ui.export

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.subtitles.TimeText
import io.github.alalkipgen.autoflow.media.playback.MoviePhase
import io.github.alalkipgen.autoflow.media.playback.MovieRequest
import io.github.alalkipgen.autoflow.media.render.Clip
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.recap.PreviewTrack
import io.github.alalkipgen.autoflow.ui.subtitles.BurnedCaption
import io.github.alalkipgen.autoflow.ui.subtitles.SubtitlePlayer
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/**
 * F-6 (D-95): the Music Video before any render — the render's own graph ([ExportModel.musicPreview]) played on one
 * clock: each shot's accepted clip from its start for the shot's length (muted, the render drops the clips' sound),
 * a pending shot as its "#NN — pending" card, the song from 0:00 and the lyric overlays drawn as they will be burned.
 * Nothing is rendered; transitions, the exact fit of each clip and the encode are the render's.
 */
@Composable
internal fun MusicVideoPreview(model: ExportModel, player: SubtitlePlayer) {
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.refresh() }
    val preview = remember(state.projectDir, state.shots, state.accepted, state.durationMs, state.songFile, state.font,
        state.lyricShots, state.syncedLines, state.timingOutdated) { model.musicPreview() } ?: return
    val graph = preview.graph
    AfCard(tr(UiText.PROJECT_PAGE_PREVIEW), Modifier.testTag("mv-preview-card")) {
        AfText(My.breakSyllables(tr(UiText.MV_PREVIEW_HINT)), Modifier.testTag("mv-preview-hint"), muted = true, size = 12.sp)
        if (graph.clips.isEmpty()) { AfText(tr(UiText.MV_PREVIEW_EMPTY), Modifier.testTag("mv-preview-empty"), muted = true); return@AfCard }
        val starts = remember(graph) { MusicPreviewClock.starts(graph.clips) }
        val duration = graph.durationMs.coerceAtLeast(1)
        var positionMs by remember(graph) { mutableLongStateOf(0L) }
        var playing by remember(graph) { mutableStateOf(false) }
        var seeks by remember(graph) { mutableIntStateOf(0) }
        var failed by remember(graph) { mutableStateOf(emptySet<String>()) }
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
        val aspect = graph.output.width.toFloat() / graph.output.height
        val viewport = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value }.takeIf { it > 0f } ?: 600f
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val width = minOf(maxWidth.value, viewport * (if (aspect > 1f) .45f else .55f) * aspect)
            Box(Modifier.width(width.dp).aspectRatio(aspect).background(Color.Black).testTag("mv-preview-video"),
                contentAlignment = Alignment.Center) {
                if (file != null && file !in failed) key(index, seeks) {
                    // The clip starts where the clock is inside its shot; a scrub or the next shot makes a new one.
                    val from = remember { clip.trimInMs + (positionMs - starts[index]).coerceAtLeast(0) }
                    player(file, MovieRequest(from, seeks.toLong(), playing = playing, muted = true), Modifier.fillMaxSize()) {
                        if (it.phase == MoviePhase.FAILED) failed = failed + file
                    }
                } else AfText(if (file != null) tr(UiText.SUB_PREVIEW_UNAVAILABLE) else clip.label.ifBlank { "—" },
                    Modifier.padding(12.dp).testTag("mv-preview-card-label"), color = Color.White)
                BurnedCaption(graph.overlays, positionMs, graph.output.height, "mv-preview-lyric")
                AfText("#${index + 1} · ${TimeText.show(positionMs)} / ${TimeText.show(duration)}", Modifier.align(Alignment.TopStart).padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp).testTag("mv-preview-time"), mono = true)
            }
        }
        preview.song?.let { song -> key(song) { PreviewTrack(song, positionMs, playing, 1f, 1f, loop = false) } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfButton(My.breakSyllables(tr(if (playing) UiText.SUB_PAUSE else UiText.SUB_PLAY)), {
                if (!playing && positionMs >= duration) { positionMs = 0; seeks++ }
                playing = !playing
            }, Modifier.testTag("mv-preview-play"))
            AfScrubber(positionMs.toFloat() / duration, tr(UiText.SUB_PLAYHEAD),
                { positionMs = (it * duration).toLong().coerceIn(0, duration); seeks++ }, Modifier.weight(1f).testTag("mv-preview-scrubber"))
        }
    }
}

/** Shots are joined with cuts only, so each clip starts where the one before it ends. */
internal object MusicPreviewClock {
    fun starts(clips: List<Clip>): List<Long> = clips.runningFold(0L) { at, c -> at + c.durationMs }.dropLast(1)
    fun index(starts: List<Long>, positionMs: Long): Int = starts.indexOfLast { it <= positionMs }.coerceAtLeast(0)
}
