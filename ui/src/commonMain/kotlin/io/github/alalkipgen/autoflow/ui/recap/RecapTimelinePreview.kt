package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.ui.i18n.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.ImageBitmap
import io.github.alalkipgen.autoflow.media.render.RenderPresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.media.playback.RecapPlaybackClock
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/** Real muted native-v2 timeline playback, decoded once underneath the output-frame Compose compositor. */
@Composable
internal fun RecapTimelinePreview(model: RecapModel) {
    val state by model.state.collectAsState()
    val open by model.previewSource.projects.collectAsState()
    val source = remember(state, open) { model.previewSource.resolve(state) } ?: return
    val recap = state.recap ?: return
    val movie = recap.movie ?: return
    val output = RenderPresets.preview(model.outputRatio(recap))
    key(source.key, source.path, source.timeline) {
        val clock = remember { RecapPlaybackClock(source.timeline) }
        var refresh by remember { mutableLongStateOf(0) }
        var enabled by remember { mutableStateOf(false) }
        var lastPoll by remember { mutableStateOf<kotlin.time.TimeMark?>(null) }
        var seekInput by remember { mutableStateOf("0") }
        val command = remember(refresh) { clock.request }
        // Clock is a non-Compose controller. Capture immutable render inputs HERE on every refresh; a nested
        // skippable Box/WrapRow must not capture only the same mutable clock identity after a paused seek.
        val outputMs = clock.positionMs
        val captions = clock.captions
        val playing = clock.playing
        val problem = clock.problem
        var frame by remember { mutableStateOf<ImageBitmap?>(null) }
        var images by remember { mutableStateOf<Map<String, ImageBitmap>?>(null) }
        LaunchedEffect(source.key) { images = withContext(Dispatchers.Default) { model.previewSource.images(source, recap) } }
        LaunchedEffect(outputMs) { model.previewSource.position(source, outputMs) }
        AfCard(tr(UiText.TIMELINE_CARD)) {
            AfText(tr(UiText.TIMELINE_HINT), muted = true, size = 12.sp)
            if (enabled && problem == null) {
                Box(Modifier.widthIn(max = 420.dp * (output.width.toFloat() / output.height)).fillMaxWidth().aspectRatio(output.width.toFloat() / output.height).background(AutoFlowColors.Background).testTag("timeline-video")) {
                    MoviePlayback(source.path, command, Modifier.fillMaxSize(), source.vlcDir,
                        frameSize = IntSize(movie.width, movie.height), onFrame = { frame = it }) { snapshot ->
                        val now = kotlin.time.TimeSource.Monotonic.markNow()
                        val elapsed = lastPoll?.elapsedNow()?.inWholeMilliseconds ?: 0
                        lastPoll = now
                        clock.observe(snapshot, elapsed)
                        refresh++
                    }
                    // Opaque cover prevents a transient raw/pre-Looks frame from masquerading as output.
                    Box(Modifier.fillMaxSize().background(AutoFlowColors.Background)) {
                        val raw = frame
                        val assets = images
                        if (raw != null && assets != null) NativeOutputPicture(raw, recap, output, outputMs, captions, assets, Modifier.fillMaxSize())
                        else AfText(tr(UiText.PREPARING_FRAME), muted = true)
                    }
                }
            }
            problem?.let { AfText(it, color = AutoFlowColors.Amber) }
            AfText("${outputMs} / ${source.timeline.durationMs} ms · ${clock.clip?.blockId ?: tr(UiText.FINISHED)}", modifier = Modifier.testTag("timeline-position"))
            val activeLayers = state.recap!!.layers.filter { io.github.alalkipgen.autoflow.core.recap.PreviewGeometry.isActive(it, outputMs) }
            AfText(tr(UiText.ACTIVE_LAYER_IDS) + activeLayers.joinToString { it.id }, muted = true, size = 12.sp)
            AfTextField(seekInput, { seekInput = it }, tr(UiText.OUTPUT_MS), modifier = Modifier.testTag("timeline-seek-input"))
            AfButton(tr(UiText.SEEK_TO_TIME), { seekInput.toLongOrNull()?.takeIf { it >= 0 }?.let { clock.seek(it); refresh++ } }, Modifier.testTag("timeline-seek"), enabled = seekInput.toLongOrNull()?.let { it >= 0 } == true)
            // Accessibility/readout uses the identical output-clock cues as the rasterized output frame.
            AfText(captions.joinToString("\n") { it.text }.ifBlank { "—" }, modifier = Modifier.testTag("timeline-caption"))
            WrapRow {
                AfButton(if (playing) tr(UiText.PAUSE) else tr(UiText.PLAY), { enabled = true; if (clock.problem != null) clock.seek(clock.positionMs); clock.setPlaying(!clock.playing); refresh++ }, Modifier.testTag("timeline-play"))
                AfButton(tr(UiText.BACK_FIVE_SECONDS), { clock.seek(clock.positionMs - 5_000); refresh++ }, Modifier.testTag("timeline-back"))
                AfButton(tr(UiText.FORWARD_FIVE_SECONDS), { clock.seek(clock.positionMs + 5_000); refresh++ }, Modifier.testTag("timeline-forward"))
                AfButton(tr(UiText.CLOSE_PLAYER), { enabled = false; frame = null; clock.setPlaying(false); refresh++ }, Modifier.testTag("timeline-close"))
            }
        }
    }
}
