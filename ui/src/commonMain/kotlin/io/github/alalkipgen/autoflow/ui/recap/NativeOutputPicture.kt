package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.ui.i18n.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.rememberTextMeasurer
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.media.render.Output
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily

/** The production output-frame surface, also captured by S10 (not a test-only replica). */
@Composable
internal fun NativeOutputPicture(source: ImageBitmap, recap: RecapV2, output: Output, timeMs: Long, cues: List<SrtCue>, images: Map<String, ImageBitmap>, modifier: Modifier = Modifier, overlay: (@Composable () -> Unit)? = null) {
    val measurer = rememberTextMeasurer()
    val family = LocalUiFontFamily.current
    val painter = remember(measurer, family) { NativeFramePainter(measurer, family) }
    val problem = remember(recap, images) { painter.problemNotice(recap, images) }
    if (problem != null) { AfText(problem.text(LocalUiLanguage.current), modifier = modifier); return }
    // Repaint when a cue/layer boundary changes even with the decoder paused at the same source frame.
    val active = recap.layers.filter { PreviewGeometry.isActive(it, timeMs) && (it.type != LayerType.TEXT || it.toMs?.let { end -> timeMs < end } != false) }
    val captions = cues.filter { timeMs >= it.startMs && timeMs < it.endMs }
    val frame = remember(source, recap, output, images, active, captions) { painter.paint(source, recap, output, timeMs, captions, images) }
    if (overlay == null) Image(frame, "Native output-frame proxy", modifier, contentScale = ContentScale.FillBounds)
    else Box(modifier) {
        Image(frame, "Native output-frame proxy", Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        overlay()
    }
}
