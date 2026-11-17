package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.ui.i18n.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.media.render.RenderPresets
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/** All numeric text stays transient until Save; invalid text must never silently save the previous number. */
internal data class LayerForm(val fields: Map<String, String>) {
    fun set(name: String, text: String) = copy(fields = fields + (name to text))
    fun rect(rect: List<Float>) = copy(fields = fields + listOf("x", "y", "w", "h").zip(rect.map { it.toString() }))
    fun read(base: Layer): Layer? = runCatching {
        fun float(key: String) = fields.getValue(key).toFloat()
        val p = base.params
        val params = when (base.type) {
            LayerType.BLUR -> p.copy(sigma = float("sigma"), feather = float("feather"))
            LayerType.IMAGE -> p.copy(opacity = float("opacity"))
            LayerType.TEXT -> p.copy(textMy = fields.getValue("text"), style = fields.getValue("style"))
            LayerType.BORDER -> p.copy(px = fields.getValue("px").toInt(), colour = fields.getValue("colour"))
            LayerType.BACKGROUND -> p.copy(mode = BackgroundMode.valueOf(fields.getValue("mode")), colour = fields.getValue("colour"))
        }
        base.copy(rect = if (LayerEdits.usesRect(base.type)) listOf("x", "y", "w", "h").map(::float) else base.rect,
            fromMs = fields.getValue("from").toLong(), toMs = fields.getValue("to").takeIf { it.isNotBlank() }?.toLong(), params = params)
    }.getOrNull()

    companion object {
        fun of(l: Layer): LayerForm = LayerForm(mapOf(
            "x" to l.rect.getOrElse(0) { 0f }.toString(), "y" to l.rect.getOrElse(1) { 0f }.toString(),
            "w" to l.rect.getOrElse(2) { 1f }.toString(), "h" to l.rect.getOrElse(3) { 1f }.toString(),
            "from" to l.fromMs.toString(), "to" to (l.toMs?.toString() ?: ""),
            "sigma" to l.params.sigma.toString(), "feather" to l.params.feather.toString(),
            "opacity" to l.params.opacity.toString(), "text" to l.params.textMy, "style" to l.params.style,
            "px" to l.params.px.toString(), "colour" to l.params.colour, "mode" to l.params.mode.name,
        ))
    }
}

internal fun LayerType.labelMy(language: UiLanguage = UiLanguage.ENGLISH): String = when (this) {
    LayerType.BLUR -> UiText.LAYER_BLUR.text(language)
    LayerType.IMAGE -> UiText.LAYER_IMAGE.text(language)
    LayerType.TEXT -> UiText.LAYER_TEXT.text(language)
    LayerType.BORDER -> UiText.LAYER_BORDER.text(language)
    LayerType.BACKGROUND -> UiText.LAYER_BACKGROUND.text(language)
}

@Composable
internal fun RecapLayerControls(recap: RecapV2, idle: Boolean, model: RecapModel, studio: Boolean = false) {
    val editor = model.layerEditor
    val state by editor.state.collectAsState()
    val project by model.state.collectAsState()
    DisposableEffect(project.projectDir) { onDispose { editor.cancel() } }
    AfCard(tr(UiText.NATIVE_LAYERS_CARD), Modifier.testTag("native-layers")) {
        AfText(tr(UiText.LAYER_ORDER_HINT), muted = true)
        if (state.draft == null) WrapRow {
            LayerType.entries.forEach { type ->
                AfButton("+ ${type.labelMy(LocalUiLanguage.current)}", { editor.add(type) }, Modifier.testTag("layer-add-${type.name}"), enabled = idle)
            }
        }
        recap.layers.forEachIndexed { index, layer ->
            AfButton("${index + 1} · ${layer.type.labelMy(LocalUiLanguage.current)}${if (studio) "" else " · ${layer.id}"}", { editor.edit(layer.id) },
                Modifier.fillMaxWidth().testTag("layer-edit-${layer.id}"), enabled = idle && state.draft == null)
        }
        state.problem?.let { AfText(state.notice?.text(LocalUiLanguage.current) ?: it, color = AutoFlowColors.Amber) }
        val d = state.draft
        if (d != null) {
            var form by remember(d.token) { mutableStateOf(LayerForm.of(d.layer)) }
            val value = form.read(d.layer)
            val problem = if (value == null) tr(UiText.NUMBERS_INVALID) else LayerEdits.problem(value)?.let { tr(UiText.RESULT_DETAIL, "detail" to it) }
            // No persisted convenience toggle, no conversion to recap.visual.masks.
            AfText(d.layer.type.labelMy(LocalUiLanguage.current) + if (studio) "" else " · ${d.layer.id}", bold = true)
            @Composable fun input(key: String, label: String, lines: Int = 1) {
                AfText(label, muted = true)
                AfTextField(form.fields.getValue(key), { form = form.set(key, it) }, label,
                    Modifier.fillMaxWidth().testTag("layer-field-$key"), lines = lines, mono = key != "text", enabled = idle && !state.importing)
            }
            val r = value?.takeIf { LayerEdits.problem(it) == null }?.rect ?: d.layer.rect
            if (studio && LayerEdits.usesRect(d.layer.type)) {
                listOf(UiText.RECT_X, UiText.RECT_Y, UiText.RECT_W, UiText.RECT_H).forEachIndexed { index, label ->
                    AfText("${tr(label)} · ${kotlin.math.round(r[index] * 1000) / 10}%")
                    val maximum = if (index < 2) 1f - r[index + 2] else 1f - r[index - 2]
                    AfScrubber(if (maximum > 0) r[index] / maximum else 0f, tr(label),
                        { form = form.rect(layerPercentRect(r, index, it)) },
                        Modifier.testTag("layer-percent-$index"), enabled = idle && !state.importing && maximum > 0)
                }
            }
            val exact: @Composable () -> Unit = {
                input("from", tr(UiText.LAYER_FROM))
                input("to", tr(UiText.LAYER_TO))
                if (LayerEdits.usesRect(d.layer.type)) {
                    AfText(tr(UiText.LAYER_RECT_HINT), muted = true)
                    input("x", tr(UiText.RECT_X)); input("y", tr(UiText.RECT_Y))
                    input("w", tr(UiText.RECT_W)); input("h", tr(UiText.RECT_H))
                    AfText(tr(UiText.LAYER_RECT_GESTURES), muted = true)
                    LayerRectEditor(r, RenderPresets.preview(model.outputRatio(recap)).let { it.width.toFloat() / it.height }, d.token,
                        enabled = idle && !state.importing, onRect = { form = form.rect(it) })
                } else AfText(tr(UiText.LAYER_FULL_FRAME), muted = true)
            }
            if (studio) SectionCard(tr(UiText.V3_MORE_OPTIONS), Modifier.testTag("layer-exact-options"), initiallyExpanded = false) {
                AfText(d.layer.id, muted = true)
                exact()
            } else exact()
            when (d.layer.type) {
                LayerType.BLUR -> { input("sigma", tr(UiText.LAYER_SIGMA)); input("feather", tr(UiText.LAYER_FEATHER)) }
                LayerType.IMAGE -> input("opacity", tr(UiText.LAYER_OPACITY))
                LayerType.TEXT -> { input("text", tr(UiText.LAYER_TEXT_FIELD), 3); input("style", tr(UiText.LAYER_FONT)) }
                LayerType.BORDER -> { input("px", tr(UiText.LAYER_BORDER_PX)); input("colour", tr(UiText.COLOUR_HEX)) }
                LayerType.BACKGROUND -> {
                    WrapRow { BackgroundMode.entries.forEach { mode -> AfChip(mode.uiLabel(), form.fields["mode"] == mode.name, { form = form.set("mode", mode.name) }) } }
                    if (form.fields["mode"] == BackgroundMode.COLOUR.name) input("colour", tr(UiText.COLOUR_HEX))
                }
            }
            if (d.layer.type == LayerType.IMAGE || d.layer.type == LayerType.BACKGROUND && form.fields["mode"] == BackgroundMode.IMAGE.name) {
                AfText(d.layer.params.image ?: tr(UiText.LAYER_IMAGE_EMPTY), muted = true)
                AfButton(if (state.importing) tr(UiText.LAYER_IMAGE_IMPORTING) else tr(UiText.CHOOSE_IMAGE), { editor.importImage() },
                    Modifier.testTag("layer-image"), enabled = idle && !state.importing)
            }
            AfText(tr(UiText.LAYER_DRAW_ORDER, "index1" to (d.index + 1)), muted = true)
            WrapRow {
                AfButton(tr(UiText.LAYER_EARLIER), { editor.move(-1) }, Modifier.testTag("layer-earlier"), enabled = idle && d.index > 0)
                AfButton(tr(UiText.LAYER_LATER), { editor.move(1) }, Modifier.testTag("layer-later"), enabled = idle && d.index < recap.layers.size - if (d.isNew) 0 else 1)
            }
            if (problem != null) AfText(problem, color = AutoFlowColors.Amber)
            WrapRow {
                AfButton(tr(UiText.SAVE), { value?.let(editor::save) }, Modifier.testTag("layer-save"), enabled = idle && !state.importing && problem == null,
                    kind = if (studio) ButtonKind.Primary else ButtonKind.Secondary)
                AfButton(tr(UiText.DISCARD), editor::cancel, Modifier.testTag("layer-cancel"))
                if (!d.isNew) AfButton(tr(UiText.REMOVE_LAYER), { editor.remove() }, Modifier.testTag("layer-remove"), enabled = idle && !state.importing, kind = ButtonKind.Danger)
            }
        }
    }
}

/** Slider convenience only: the saved numbers still describe the existing normalized output rect. */
internal fun layerPercentRect(rect: List<Float>, index: Int, fraction: Float): List<Float> = rect.toMutableList().apply {
    val maximum = 1f - rect[if (index < 2) index + 2 else index - 2]
    this[index] = (maximum * fraction.coerceIn(0f, 1f)).let {
        if (index < 2) it else it.coerceAtLeast(minOf(0.001f, maximum))
    }
}

/** Output-frame gesture surface; drafts are persisted only by the explicit Save action. */
@Composable
internal fun LayerRectEditor(rect: List<Float>, ratio: Float, token: Long, enabled: Boolean, onRect: (List<Float>) -> Unit) {
    val current by rememberUpdatedState(rect)
    val change by rememberUpdatedState(onRect)
    Box(Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(ratio).background(Color.Black).testTag("native-layer-rect")
        .pointerInput(token, enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val w = size.width.toFloat().coerceAtLeast(1f); val h = size.height.toFloat().coerceAtLeast(1f)
                if (PreviewGeometry.hit(listOf(current), down.position.x / w, down.position.y / h, 0.02f) == null) return@awaitEachGesture
                var r = current
                down.consume()
                do {
                    val event = awaitPointerEvent()
                    val pan = event.calculatePan(); val zoom = event.calculateZoom()
                    if (pan != Offset.Zero || zoom != 1f) {
                        val c = event.calculateCentroid(useCurrent = false).takeIf { it.isSpecified } ?: down.position
                        r = PreviewGeometry.transform(r, pan.x / w, pan.y / h, zoom, c.x / w, c.y / h)
                        change(r)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }.pointerInput(token, enabled) {
            if (!enabled) return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type != PointerEventType.Scroll) continue
                    val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: continue
                    if (dy == 0f) continue
                    val r = current
                    change(PreviewGeometry.transform(r, 0f, 0f, if (dy < 0) 1.1f else 1 / 1.1f, r[0] + r[2] / 2, r[1] + r[3] / 2))
                    event.changes.forEach { it.consume() }
                }
            }
        }) {
        Canvas(Modifier.fillMaxSize()) {
            val r = current
            drawRect(Color.Cyan.copy(alpha = 0.18f), Offset(r[0] * size.width, r[1] * size.height), Size(r[2] * size.width, r[3] * size.height))
            drawRect(Color.Cyan, Offset(r[0] * size.width, r[1] * size.height), Size(r[2] * size.width, r[3] * size.height), style = Stroke(2.dp.toPx()))
        }
    }
}
