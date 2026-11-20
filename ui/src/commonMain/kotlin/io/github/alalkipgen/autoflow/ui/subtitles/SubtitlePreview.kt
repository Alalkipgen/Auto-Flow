package io.github.alalkipgen.autoflow.ui.subtitles

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
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleCue
import io.github.alalkipgen.autoflow.core.subtitles.TextChoice
import io.github.alalkipgen.autoflow.core.subtitles.TimeText
import io.github.alalkipgen.autoflow.media.playback.MoviePhase
import io.github.alalkipgen.autoflow.media.playback.MovieRequest
import io.github.alalkipgen.autoflow.media.playback.MovieSnapshot
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

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
 * The video with the line under the playhead drawn over it (the chosen view), Play/Pause and a scrubber. Kept to a
 * third of the window height so the lines stay in sight on a phone. Without a decoder it says so; editing goes on.
 */
@Composable
internal fun SubtitlePreview(path: String, video: SourceVideo, cues: List<SubtitleCue>, view: TextChoice, playhead: Playhead,
    player: SubtitlePlayer) {
    val aspect = if (video.width > 0 && video.height > 0) video.width.toFloat() / video.height else 16f / 9
    val viewport = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value }.takeIf { it > 0f } ?: 600f
    var failed by remember(path) { mutableStateOf(false) }
    val duration = video.durationMs.coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().testTag("subtitles-preview-card"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (failed) WrapRow { // nothing to show: no black box, just why, and where the playhead is
            AfText(My.breakSyllables(tr(UiText.SUB_PREVIEW_UNAVAILABLE)), Modifier.testTag("subtitles-preview-unavailable"), muted = true)
            AfText(TimeText.show(playhead.positionMs), Modifier.testTag("subtitles-playhead"), mono = true)
        } else BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val width = minOf(maxWidth.value, viewport * 0.34f * aspect)
            Box(Modifier.width(width.dp).aspectRatio(aspect).background(AutoFlowColors.Panel).testTag("subtitles-preview"),
                contentAlignment = Alignment.BottomCenter) {
                player(path, MovieRequest(playhead.seekMs, playhead.revision, playing = playhead.playing, muted = false),
                    Modifier.fillMaxSize()) { if (!playhead.report(it)) failed = true }
                AfText(TimeText.show(playhead.positionMs), Modifier.align(Alignment.TopStart).padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp).testTag("subtitles-playhead"), mono = true)
                cues.firstOrNull { playhead.positionMs >= it.startMs && playhead.positionMs < it.endMs }?.let { c ->
                    val text = when (view) {
                        TextChoice.ORIGINAL -> c.text
                        TextChoice.BURMESE -> c.textMy?.takeIf { it.isNotBlank() } ?: c.text
                        TextChoice.BOTH -> listOfNotNull(c.text, c.textMy?.takeIf { it.isNotBlank() }).joinToString("\n") // as the list and export
                    }
                    AfText(My.breakSyllables(text), Modifier.padding(8.dp).background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 8.dp, vertical = 2.dp).testTag("subtitles-preview-line"), color = Color.White)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfButton(My.breakSyllables(tr(if (playhead.playing) UiText.SUB_PAUSE else UiText.SUB_PLAY)), playhead::toggle,
                Modifier.testTag("subtitles-play"), enabled = !failed)
            AfScrubber(playhead.positionMs.toFloat() / duration, tr(UiText.SUB_PLAYHEAD),
                { playhead.seek((it * duration).toLong()) }, Modifier.weight(1f).testTag("subtitles-scrubber"))
        }
    }
}
