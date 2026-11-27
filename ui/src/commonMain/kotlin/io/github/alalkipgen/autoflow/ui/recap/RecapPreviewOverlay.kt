package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.media.render.Output
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import kotlin.math.*

/** IMAGE is scaled by width with its aspect kept by BOTH existing renderers; stored rect height is unused. */
internal fun outputLayerRect(layer: Layer, imageAspect: Float?): List<Float> =
    if (layer.type == LayerType.IMAGE && imageAspect != null) layer.rect.toMutableList().apply { this[3] = this[2] * imageAspect }
    else layer.rect

/** Normalized OUTPUT space, including bars: never inverse-transform through movie Mirror/Zoom/Fill. */
internal fun outputLayerChange(layer: Layer, dx: Float, dy: Float, zoom: Float, cx: Float, cy: Float,
    resize: Boolean, imageAspect: Float?): List<Float> {
    val r = layer.rect
    if (listOf(dx, dy, zoom, cx, cy).any { !it.isFinite() } ||
        !LayerEdits.usesRect(layer.type) || LayerEdits.problem(layer) != null) return r
    val aspect = imageAspect?.takeIf { layer.type == LayerType.IMAGE && it.isFinite() && it > 0 }
    if (resize) {
        val maxW = minOf(1f - r[0], if (aspect == null) 1f else (1f - r[1]) / aspect)
        val delta = if (aspect != null && abs(dy / aspect) > abs(dx)) dy / aspect else dx
        val w = (r[2] + delta).coerceIn(minOf(.001f, maxW), maxW)
        val h = if (aspect != null) r[3] else (r[3] + dy).coerceIn(minOf(.001f, 1f - r[1]), 1f - r[1])
        return listOf(r[0], r[1], w, h)
    }
    if (aspect == null) return PreviewGeometry.transform(r, dx, dy, zoom, cx, cy, minSize = .001f)
    val z = zoom.takeIf { it > 0 } ?: 1f
    val maxW = minOf(1f, 1f / aspect)
    val w = (r[2] * z).coerceIn(minOf(.001f, maxW), maxW)
    val scale = w / r[2]
    val x = (cx - (cx - r[0]) * scale + dx).coerceIn(0f, 1f - w)
    val y = (cy - (cy - r[1]) * scale + dy).coerceIn(0f, minOf(1f - r[3], (1f - w * aspect).coerceAtLeast(0f)))
    return listOf(x, y, w, r[3])
}

/** Production picture + editor. The overlay slot is invoked only after the native painter's asset/validation guards. */
@Composable
internal fun NativeLayerPreview(source: ImageBitmap, saved: RecapV2, output: Output, timeMs: Long, cues: List<SrtCue>,
    images: Map<String, ImageBitmap>, editor: RecapLayerEditor, idle: Boolean, modifier: Modifier = Modifier) {
    val state by editor.state.collectAsState()
    val recap = remember(saved, state) { editor.preview(saved) }
    NativeOutputPicture(source, recap, output, timeMs, cues, images, modifier.clipToBounds(), overlay = {
        PreviewOverlay(recap, output, timeMs, images, editor, idle && !state.importing)
    })
}

@Composable
private fun PreviewOverlay(recap: RecapV2, output: Output, timeMs: Long, images: Map<String, ImageBitmap>,
    editor: RecapLayerEditor, enabled: Boolean) {
    val state by editor.state.collectAsState()
    val selected = editor.value()?.takeIf { LayerEdits.usesRect(it.type) && activeOnVideo(it, timeMs) }
    fun aspect(layer: Layer): Float? = layer.params.image?.let(images::get)?.let {
        it.height.toFloat() / it.width * output.width / output.height
    }
    val language = LocalUiLanguage.current
    val layers = recap.layers.filter { LayerEdits.usesRect(it.type) && activeOnVideo(it, timeMs) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("output-layer-overlay")) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
        if (w <= 0 || h <= 0) return@BoxWithConstraints
        if (state.draft == null && enabled) Box(Modifier.fillMaxSize().pointerInput(layers, w, h, images) {
            detectTapGestures { p ->
                PreviewGeometry.hit(layers.map { outputLayerRect(it, aspect(it)) }, p.x / w, p.y / h)?.let { editor.edit(layers[it].id) }
            }
        })
        Canvas(Modifier.fillMaxSize()) {
            listOfNotNull(selected).forEach { layer ->
                val r = outputLayerRect(layer, aspect(layer))
                drawRect(AutoFlowColors.Purple, Offset(r[0] * size.width, r[1] * size.height),
                    Size(r[2] * size.width, r[3] * size.height), style = Stroke(2.dp.toPx()))
            }
        }
        val draft = state.draft
        if (selected != null && draft != null && enabled) {
            val r = outputLayerRect(selected, aspect(selected))
            val target = with(density) { 48.dp.toPx() }
            val left = (r[0] * w).coerceIn(0f, (w - target).coerceAtLeast(0f))
            val top = (r[1] * h).coerceIn(0f, (h - target).coerceAtLeast(0f))
            val mw = (r[2] * w).coerceIn(minOf(target, w), w - left)
            val mh = (r[3] * h).coerceIn(minOf(target, h), h - top)
            val rx = ((r[0] + r[2]) * w - target / 2).coerceIn(0f, (w - target).coerceAtLeast(0f))
            val ry = ((r[1] + r[3]) * h - target / 2).coerceIn(0f, (h - target).coerceAtLeast(0f))
            val change: (List<Float>) -> Unit = { editor.rect(draft.token, it) }
            Box(Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .size(with(density) { mw.toDp() }, with(density) { mh.toDp() })
                .testTag("layer-move-handle").semantics {
                    contentDescription = UiText.V3_LAYER_MOVE.text(language)
                    stateDescription = selected.rect.joinToString(",")
                }.layerGesture(selected, draft.token, w, h, Offset(left, top), false, aspect(selected), change)) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = center
                    drawCircle(AutoFlowColors.Card, 13.dp.toPx(), c)
                    drawLine(Color.White, c - Offset(8.dp.toPx(), 0f), c + Offset(8.dp.toPx(), 0f), 2.dp.toPx())
                    drawLine(Color.White, c - Offset(0f, 8.dp.toPx()), c + Offset(0f, 8.dp.toPx()), 2.dp.toPx())
                }
            }
            Box(Modifier.offset { IntOffset(rx.roundToInt(), ry.roundToInt()) }.size(48.dp)
                .testTag("layer-resize-handle").semantics { contentDescription = UiText.V3_LAYER_RESIZE.text(language) }
                .layerGesture(selected, draft.token, w, h, Offset(rx, ry), true, aspect(selected), change)) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(AutoFlowColors.Purple, center - Offset(7.dp.toPx(), 7.dp.toPx()), Size(14.dp.toPx(), 14.dp.toPx()))
                    drawRect(Color.White, center - Offset(7.dp.toPx(), 7.dp.toPx()), Size(14.dp.toPx(), 14.dp.toPx()), style = Stroke(2.dp.toPx()))
                }
            }
            // Never stack two 48dp controls. The original reference-only Remove remains available in the form.
            if (!draft.isNew && (rx + target <= w - target || ry >= target)) {
                val removeLabel = tr(UiText.REMOVE_LAYER)
                Canvas(Modifier.offset { IntOffset((w - target).roundToInt(), 0) }.size(48.dp)
                    .testTag("layer-video-remove").semantics { contentDescription = removeLabel }
                    .clickable(role = Role.Button, onClickLabel = removeLabel) { editor.remove() }) {
                    drawCircle(AutoFlowColors.Card, 16.dp.toPx())
                    val d = Offset(6.dp.toPx(), 6.dp.toPx())
                    drawLine(Color.White, center - d, center + d, 2.dp.toPx())
                    drawLine(Color.White, center + Offset(-d.x, d.y), center + Offset(d.x, -d.y), 2.dp.toPx())
                }
            }
        }
    }
}

private fun activeOnVideo(layer: Layer, time: Long) = PreviewGeometry.isActive(layer, time) &&
    (layer.type != LayerType.TEXT || layer.toMs?.let { time < it } != false)

@Composable
private fun Modifier.layerGesture(layer: Layer, token: Long, width: Float, height: Float, origin: Offset, resize: Boolean,
    aspect: Float?, onRect: (List<Float>) -> Unit): Modifier {
    val latest by rememberUpdatedState(layer)
    val changed by rememberUpdatedState(onRect)
    val targetOrigin by rememberUpdatedState(origin)
    return pointerInput(token, resize, width, height, aspect) {
        awaitEachGesture {
            val down = awaitFirstDown()
            var value = latest
            down.consume()
            do {
                val event = awaitPointerEvent()
                val pan = event.calculatePan(); val zoom = event.calculateZoom()
                if (pan != Offset.Zero || zoom != 1f) {
                    val centre = event.calculateCentroid(useCurrent = false).takeIf { it.isSpecified } ?: down.position
                    val outputCentre = centre + targetOrigin
                    val next = outputLayerChange(value, pan.x / width, pan.y / height, zoom,
                        outputCentre.x / width, outputCentre.y / height, resize, aspect)
                    value = value.copy(rect = next)
                    changed(next)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}
