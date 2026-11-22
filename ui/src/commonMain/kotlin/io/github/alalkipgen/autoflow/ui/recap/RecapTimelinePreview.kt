package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.i18n.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
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

/**
 * Real native-v2 timeline playback, decoded once underneath the output-frame Compose compositor. The movie picture is
 * muted; the voice takes and the music play beside it on the same clock (F-5, [RecapPreviewSound]).
 */
@Composable
internal fun RecapTimelinePreview(model: RecapModel, blockId: String? = null, studio: Boolean = false, editLayers: Boolean = false,
    autoShow: Boolean = io.github.alalkipgen.autoflow.ui.components.previewAtOnce,
    top: Boolean = false, diagnostics: MutableState<Boolean>? = null, exactEnabled: Boolean = true,
) {
    val state by model.state.collectAsState()
    val open by model.previewSource.projects.collectAsState()
    val source = remember(state, open) { model.previewSource.resolve(state) } ?: return
    val recap = state.recap ?: return
    val movie = recap.movie ?: return
    val edit by model.layerEditor.state.collectAsState()
    val draftRecap = remember(recap, edit, editLayers) { if (editLayers) model.layerEditor.preview(recap) else recap }
    val imageLayers = draftRecap.layers.filter { it.type == io.github.alalkipgen.autoflow.core.recap.LayerType.IMAGE ||
        it.type == io.github.alalkipgen.autoflow.core.recap.LayerType.BACKGROUND }.map { Triple(it.type, it.params.mode, it.params.image) }
    val output = RenderPresets.preview(model.outputRatio(recap))
    val startMs = if (blockId == null) 0L else source.timeline.clips.firstOrNull { it.blockId == blockId }?.outputFromMs
    if (startMs == null) { AfText(tr(UiText.V3_BLOCK_PREVIEW_UNAVAILABLE), muted = true); return }
    // A layer-only Save does not change the timeline. Keep the paused output time for its Exact-frame check,
    // but still reset on another project/movie/timeline; Source continues guarding every read by revision.
    val playbackKey: Any = if (editLayers) source.projectDir to open?.project?.id else source.key
    // The phone top card (A §1): collapsed or open is remembered per project, across timeline changes.
    val collapsed = rememberSaveable(source.projectDir) { mutableStateOf(false) }
    // An Exact frame (made or in flight) belongs to this project revision, never the next one (as ExactFrameCard).
    val exactKey = listOf(open?.dir, open?.project?.id, open?.project?.updatedAt)
    key(playbackKey, source.path, source.timeline, blockId) {
        val clock = remember { RecapPlaybackClock(source.timeline).apply { if (startMs > 0) seek(startMs) } }
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
        // The existing desktop decoder prepares, but does not decode, a never-played paused movie.
        // Keep the sole OUTPUT clock frozen. Do not pause on the first PLAYING/bitmap pair: both can
        // precede the queued source seek. Warm the decoder, confirm Pause, then seek the exact target again.
        // Only a subsequent paused-seek frame may become the editable output; warming pixels stay hidden.
        var editFramePrime by remember { mutableStateOf<LayerEditFramePrime?>(null) }
        var images by remember { mutableStateOf<Map<String, ImageBitmap>?>(null) }
        LaunchedEffect(source.key, imageLayers) {
            images = null
            images = withContext(Dispatchers.Default) { model.previewSource.images(source, draftRecap) }
        }
        LaunchedEffect(if (editLayers) edit.draft?.token else null, editLayers && model.layerEditor.value() != null) {
            if (editLayers && model.layerEditor.value() != null) {
                clock.setPlaying(false)
                clock.seek(model.layerEditor.value()!!.fromMs)
                frame = null
                editFramePrime = LayerEditFramePrime(clock.request)
                enabled = true
                refresh++
            }
            // Save/Discard during preparation must not expose an old priming frame either.
            // The same handshake may finish with no draft; project/movie/timeline changes dispose it.
        }
        // F-6: open on the paused first frame (same warm/pause/seek handshake as a layer edit) so the preview is
        // there without pressing Play.
        LaunchedEffect(Unit) {
            if (autoShow && !enabled && !editLayers) {
                frame = null
                editFramePrime = LayerEditFramePrime(clock.request)
                enabled = true
                refresh++
            }
        }
        LaunchedEffect(source.key, outputMs) { model.previewSource.position(source, outputMs) }
        var exact by remember(exactKey) { mutableStateOf<io.github.alalkipgen.autoflow.ui.export.RecapFrames.Result?>(null) }
        var exactBusy by remember(exactKey) { mutableStateOf(false) }
        // Space (studio project screens only) runs exactly this Play/Pause button action.
        val togglePlay: () -> Unit = { exact = null; editFramePrime = null; enabled = true; if (clock.problem != null) clock.seek(clock.positionMs); clock.setPlaying(!clock.playing); refresh++ }
        if (studio) PlayPauseShortcut(togglePlay)
        val previewLabel = tr(UiText.V3_LIVE_PREVIEW)
        // The one decoder + output-frame compositor, shared by the desktop card and the phone top card / Fullscreen.
        val player: @Composable (Modifier) -> Unit = { modifier ->
            Box(modifier.background(AutoFlowColors.Background).testTag("timeline-video").semantics {
                contentDescription = previewLabel
                stateDescription = captions.joinToString("\n") { it.text }.ifBlank { "—" }
            }) {
                val decoderCommand = if (editFramePrime?.warming == true) command.copy(playing = true) else command
                PreviewMoviePlayback(source.path, decoderCommand, Modifier.fillMaxSize(), source.vlcDir,
                    frameSize = IntSize(movie.width, movie.height), onFrame = {
                        frame = it
                        val prime = editFramePrime?.delivered(command)
                        editFramePrime = prime
                        if (prime?.acceptsFrame(command) == true) {
                            if (prime.warming) {
                                frame = null
                                editFramePrime = prime.pausing()
                            } else editFramePrime = null
                        }
                    }) { snapshot ->
                    val now = kotlin.time.TimeSource.Monotonic.markNow()
                    val elapsed = lastPoll?.elapsedNow()?.inWholeMilliseconds ?: 0
                    lastPoll = now
                    clock.observe(snapshot, elapsed)
                    val prime = editFramePrime
                    editFramePrime = when {
                        prime == null -> null
                        prime.acceptsEndedFrame(snapshot, frame != null) -> null
                        prime.isPaused(snapshot) -> {
                            // Seek only AFTER the native PAUSED event, not concurrently with first Play.
                            clock.seek(clock.positionMs)
                            frame = null
                            prime.seeking(clock.request)
                        }
                        else -> prime.observe(snapshot, command).let { observed ->
                            if (observed.stage == LayerEditFramePrime.Stage.SEEKING && observed.acceptsFrame(command)) null else observed
                        }
                    }
                    refresh++
                }
                // Opaque cover prevents a transient raw/pre-Looks frame from masquerading as output.
                Box(Modifier.fillMaxSize().background(AutoFlowColors.Background)) {
                    val raw = frame
                    val assets = images
                    if (editFramePrime == null && raw != null && assets != null) {
                        if (editLayers) NativeLayerPreview(raw, recap, output, outputMs, captions, assets, model.layerEditor,
                            !playing && !state.busy && state.recording == null, Modifier.fillMaxSize())
                        else NativeOutputPicture(raw, recap, output, outputMs, captions, assets, Modifier.fillMaxSize())
                    }
                    // Collapsed: the decoder keeps running in a 1 px slot; the idle line would only be clipped there.
                    else if (!(top && collapsed.value)) AfText(tr(UiText.PREPARING_FRAME), muted = true)
                }
            }
        }
        if (top) {
            TopPreview(model, source, recap, output, clock, outputMs, captions, playing, problem, enabled, editLayers && edit.draft != null,
                exactEnabled, state.story?.blocks?.map { it.id }, collapsed, diagnostics, exact, exactBusy, { exact = it }, { exactBusy = it },
                togglePlay, seekTo = { clock.seek(it); exact = null; refresh++ },
                reopen = {
                    // Fullscreen moves the picture into its own window: a new decoder at the same output time.
                    frame = null
                    if (enabled) {
                        val wasPlaying = clock.playing
                        clock.seek(clock.positionMs)
                        if (!wasPlaying) editFramePrime = LayerEditFramePrime(clock.request)
                    }
                    refresh++
                }, player = player)
        } else AfCard(tr(UiText.TIMELINE_CARD)) {
            AfText(tr(if (studio) UiText.V3_TIMELINE_SCOPE else UiText.TIMELINE_HINT), muted = true, size = 12.sp)
            if (enabled && problem == null) {
                player(Modifier.widthIn(max = 420.dp * (output.width.toFloat() / output.height)).fillMaxWidth().aspectRatio(output.width.toFloat() / output.height))
                RecapPreviewSound(source.timeline, source.music, outputMs, playing)
            }
            problem?.let { AfText(it, color = AutoFlowColors.Amber) }
            val readouts: @Composable () -> Unit = {
                AfText("${outputMs} / ${source.timeline.durationMs} ms · ${clock.clip?.blockId ?: tr(UiText.FINISHED)}", modifier = Modifier.testTag("timeline-position"))
                val activeLayers = recap.layers.filter { io.github.alalkipgen.autoflow.core.recap.PreviewGeometry.isActive(it, outputMs) }
                AfText(tr(UiText.ACTIVE_LAYER_IDS) + activeLayers.joinToString { it.id }, muted = true, size = 12.sp)
                AfTextField(seekInput, { seekInput = it }, tr(UiText.OUTPUT_MS), modifier = Modifier.testTag("timeline-seek-input"))
                AfButton(tr(UiText.SEEK_TO_TIME), { seekInput.toLongOrNull()?.takeIf { it >= 0 }?.let { clock.seek(it); refresh++ } }, Modifier.testTag("timeline-seek"), enabled = seekInput.toLongOrNull()?.let { it >= 0 } == true)
                AfText(captions.joinToString("\n") { it.text }.ifBlank { "—" }, modifier = Modifier.testTag("timeline-caption"))
            }
            if (studio) {
                if (!enabled) AfText(tr(UiText.V3_PREVIEW_IDLE), muted = true)
                AfText(tr(UiText.V3_OUTPUT_POSITION, "position" to RecapModel.clock(outputMs),
                    "duration" to RecapModel.clock(source.timeline.durationMs)), Modifier.testTag("timeline-time"))
                AfScrubber(outputMs.toFloat() / (source.timeline.durationMs - 1).coerceAtLeast(1),
                    tr(UiText.V3_SCRUB_OUTPUT), { clock.seek((it * (source.timeline.durationMs - 1)).toLong()); refresh++ },
                    Modifier.testTag("timeline-scrubber"))
            } else readouts()
            WrapRow {
                AfButton(if (playing) tr(UiText.PAUSE) else tr(UiText.PLAY), togglePlay, Modifier.testTag("timeline-play"),
                    icon = if (playing) AfIcons.Fill.Pause else AfIcons.Fill.Play)
                AfButton(tr(UiText.BACK_FIVE_SECONDS), { clock.seek(clock.positionMs - 5_000); refresh++ }, Modifier.testTag("timeline-back"))
                AfButton(tr(UiText.FORWARD_FIVE_SECONDS), { clock.seek(clock.positionMs + 5_000); refresh++ }, Modifier.testTag("timeline-forward"))
                AfButton(tr(UiText.CLOSE_PLAYER), { enabled = false; editFramePrime = null; frame = null; clock.setPlaying(false); refresh++ }, Modifier.testTag("timeline-close"), enabled = enabled,
                    icon = AfIcons.Regular.Close)
            }
            // Below the controls, so the picture, scrubber and Play keep their place.
            if (source.timeline.voice.isNotEmpty() || source.music != null) {
                AfText(My.breakSyllables(tr(UiText.PREVIEW_SOUND_HINT)), Modifier.testTag("timeline-sound-hint"), muted = true, size = 12.sp)
            }
            if (studio) SectionCard(tr(UiText.V3_DIAGNOSTICS), Modifier.testTag("timeline-diagnostics"), initiallyExpanded = false) {
                AfText(tr(UiText.TIMELINE_HINT), muted = true, size = 12.sp)
                readouts()
            }
        }
    }
}
