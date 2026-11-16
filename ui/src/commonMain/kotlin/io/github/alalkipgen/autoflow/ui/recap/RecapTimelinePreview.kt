package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.media.playback.RecapPlaybackClock
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/** Real embedded timeline playback. This picture-only mode is NOT the still Exact frame / full rendered output. */
@Composable
internal fun RecapTimelinePreview(model: RecapModel) {
    val state by model.state.collectAsState()
    val open by model.previewSource.projects.collectAsState()
    val source = remember(state, open) { model.previewSource.resolve(state) } ?: return
    val movie = state.recap?.movie ?: return
    key(source.key, source.path, source.timeline) {
        val clock = remember { RecapPlaybackClock(source.timeline) }
        var refresh by remember { mutableLongStateOf(0) }
        var enabled by remember { mutableStateOf(false) }
        var lastPoll by remember { mutableStateOf<kotlin.time.TimeMark?>(null) }
        var seekInput by remember { mutableStateOf("0") }
        val command = remember(refresh) { clock.request }
        LaunchedEffect(clock.positionMs) { model.previewSource.position(source, clock.positionMs) }
        AfCard("ရုပ်ရှင် အချိန်လိုင်း · အသံပိတ်") {
            AfText("ဇာတ်လမ်း clip အားလုံး · အသံပိတ် · output အချိန်အတိုင်း။ မူရင်းပုံပေါ်တွင် ဖုံးမည့်ဘောင်နှင့် အချိန်ကိုက် caption နေရာကိုသာ ပြသည်။ Looks၊ နောက်ခံနှင့် native layer များပါဝင်သော နောက်ဆုံး output မဟုတ်ပါ။ အတိအကျ ပုံဖြင့် စစ်ပါ။", muted = true, size = 12.sp)
            if (enabled && clock.problem == null) {
                Box(Modifier.fillMaxWidth().aspectRatio(movie.width.toFloat() / movie.height.coerceAtLeast(1)).background(AutoFlowColors.Background).testTag("timeline-video")) {
                    RecapPreviewPicture(state.recap!!, io.github.alalkipgen.autoflow.media.render.RenderPresets.preview(model.outputRatio(state.recap!!)),
                        null, clock.captions.joinToString("\n") { it.text }.takeIf { it.isNotBlank() }, Modifier.fillMaxSize(),
                        background = {
                            MoviePlayback(source.path, command, Modifier.fillMaxSize(), source.vlcDir) { snapshot ->
                                val now = kotlin.time.TimeSource.Monotonic.markNow()
                                val elapsed = lastPoll?.elapsedNow()?.inWholeMilliseconds ?: 0
                                lastPoll = now
                                clock.observe(snapshot, elapsed)
                                refresh++
                            }
                        },
                    )
                }
            }
            clock.problem?.let { AfText(it, color = AutoFlowColors.Amber) }
            AfText("${clock.positionMs} / ${source.timeline.durationMs} ms · ${clock.clip?.blockId ?: "ပြီးပါပြီ"}", modifier = Modifier.testTag("timeline-position"))
            val activeLayers = state.recap!!.layers.filter { io.github.alalkipgen.autoflow.core.recap.PreviewGeometry.isActive(it, clock.positionMs) }
            AfText("အချိန်ကိုက် native layer ID များ (ဤနေရာတွင် မဆွဲသေး): " + activeLayers.joinToString { it.id }, muted = true, size = 12.sp)
            AfTextField(seekInput, { seekInput = it }, "Output ms", modifier = Modifier.testTag("timeline-seek-input"))
            AfButton("အချိန်သို့ ရွှေ့", { seekInput.toLongOrNull()?.takeIf { it >= 0 }?.let { clock.seek(it); refresh++ } }, Modifier.testTag("timeline-seek"), enabled = seekInput.toLongOrNull()?.let { it >= 0 } == true)
            // Timed cue readout is honest: this is NOT yet a caption rasterization/placement parity claim.
            AfText(clock.captions.joinToString("\n") { it.text }.ifBlank { "—" }, modifier = Modifier.testTag("timeline-caption"))
            WrapRow {
                AfButton(if (clock.playing) "⏸ ခဏရပ်" else "▶ ဖွင့်", { enabled = true; if (clock.problem != null) clock.seek(clock.positionMs); clock.setPlaying(!clock.playing); refresh++ }, Modifier.testTag("timeline-play"))
                AfButton("− ၅ စက္ကန့်", { clock.seek(clock.positionMs - 5_000); refresh++ }, Modifier.testTag("timeline-back"))
                AfButton("+ ၅ စက္ကန့်", { clock.seek(clock.positionMs + 5_000); refresh++ }, Modifier.testTag("timeline-forward"))
                AfButton("⏹ ပိတ်", { enabled = false; clock.setPlaying(false); refresh++ }, Modifier.testTag("timeline-close"))
            }
        }
    }
}
