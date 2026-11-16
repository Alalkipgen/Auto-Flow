package io.github.alalkipgen.autoflow.ui.recap

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

internal fun LayerType.labelMy(): String = when (this) {
    LayerType.BLUR -> "မှုန်ဝါး"
    LayerType.IMAGE -> "ပုံ"
    LayerType.TEXT -> "စာသား"
    LayerType.BORDER -> "ဘေးဘောင်"
    LayerType.BACKGROUND -> "နောက်ခံ"
}

@Composable
internal fun RecapLayerControls(recap: RecapV2, idle: Boolean, model: RecapModel) {
    val editor = model.layerEditor
    val state by editor.state.collectAsState()
    val project by model.state.collectAsState()
    DisposableEffect(project.projectDir) { onDispose { editor.cancel() } }
    AfCard("ထုတ်ယူမည့် frame ပေါ်က Layer များ", Modifier.testTag("native-layers")) {
        AfText("နောက်ဆုံး Layer က အပေါ်ဆုံးမှာ ဆွဲတယ်။ တကယ့်စာတန်းက Layer အားလုံးရဲ့ အပေါ်မှာ ရှိတယ်။", muted = true)
        if (state.draft == null) WrapRow {
            LayerType.entries.forEach { type ->
                AfButton("+ ${type.labelMy()}", { editor.add(type) }, Modifier.testTag("layer-add-${type.name}"), enabled = idle)
            }
        }
        recap.layers.forEachIndexed { index, layer ->
            AfButton("${index + 1} · ${layer.type.labelMy()} · ${layer.id}", { editor.edit(layer.id) },
                Modifier.fillMaxWidth().testTag("layer-edit-${layer.id}"), enabled = idle && state.draft == null)
        }
        state.problem?.let { AfText(it, color = AutoFlowColors.Amber) }
        val d = state.draft
        if (d != null) {
            var form by remember(d.token) { mutableStateOf(LayerForm.of(d.layer)) }
            val value = form.read(d.layer)
            val problem = if (value == null) "ဂဏန်းများကို မှန်ကန်စွာ ထည့်ပါ။" else LayerEdits.problem(value)
            // No persisted convenience toggle, no conversion to recap.visual.masks.
            AfText("${d.layer.type.labelMy()} · ${d.layer.id}", bold = true)
            @Composable fun input(key: String, label: String, lines: Int = 1) {
                AfText(label, muted = true)
                AfTextField(form.fields.getValue(key), { form = form.set(key, it) }, label,
                    Modifier.fillMaxWidth().testTag("layer-field-$key"), lines = lines, mono = key != "text", enabled = idle && !state.importing)
            }
            input("from", "စတင်ချိန် (output ms)")
            input("to", "ဆုံးချိန် (output ms၊ အဆုံးအထိဆို အလွတ်ထားပါ)")
            if (LayerEdits.usesRect(d.layer.type)) {
                AfText("OUTPUT-FRAME ဘောင် (0–1) — ရုပ်ရှင်ပုံထဲက mask မဟုတ်ပါ။", muted = true)
                input("x", "ဘယ်ဘက် x"); input("y", "အပေါ် y")
                input("w", "အကျယ် w"); input("h", "အမြင့် h")
                AfText("ဘောင်တည်နေရာသာ ပြသည်၊ render ပုံ မဟုတ်ပါ။ ဆွဲရွှေ့၊ လက်နှစ်ချောင်း / mouse wheel ဖြင့် ချုံ့ချဲ့ပါ။", muted = true)
                val r = value?.takeIf { LayerEdits.problem(it) == null }?.rect ?: d.layer.rect
                LayerRectEditor(r, RenderPresets.preview(model.outputRatio(recap)).let { it.width.toFloat() / it.height }, d.token,
                    enabled = idle && !state.importing, onRect = { form = form.rect(it) })
            } else AfText("ဒီ Layer က frame တစ်ခုလုံးကို သုံးတယ်။ rect ကို မသုံး၊ မပြောင်းပါ။", muted = true)
            when (d.layer.type) {
                LayerType.BLUR -> { input("sigma", "မှုန်ဝါးအား sigma"); input("feather", "အစွန်းပျော့ feather (0–0.05)") }
                LayerType.IMAGE -> input("opacity", "အလင်းပိတ်အား opacity (0–1)")
                LayerType.TEXT -> { input("text", "Layer စာသား", 3); input("style", "Font family (အလွတ် = စာတန်း font)") }
                LayerType.BORDER -> { input("px", "ဘေးဘောင် အထူ px"); input("colour", "အရောင် #RRGGBB") }
                LayerType.BACKGROUND -> {
                    WrapRow { BackgroundMode.entries.forEach { mode -> AfChip(mode.name, form.fields["mode"] == mode.name, { form = form.set("mode", mode.name) }) } }
                    if (form.fields["mode"] == BackgroundMode.COLOUR.name) input("colour", "အရောင် #RRGGBB")
                }
            }
            if (d.layer.type == LayerType.IMAGE || d.layer.type == LayerType.BACKGROUND && form.fields["mode"] == BackgroundMode.IMAGE.name) {
                AfText(d.layer.params.image ?: "ပုံ မရွေးရသေးပါ။", muted = true)
                AfButton(if (state.importing) "ပုံ ထည့်နေတယ်…" else "ပုံ ရွေးမယ်…", { editor.importImage() },
                    Modifier.testTag("layer-image"), enabled = idle && !state.importing)
            }
            AfText("ဆွဲစဉ် ${d.index + 1} (နောက်မှဆွဲ = အပေါ်ထပ်)", muted = true)
            WrapRow {
                AfButton("← အစောဆွဲ", { editor.move(-1) }, Modifier.testTag("layer-earlier"), enabled = idle && d.index > 0)
                AfButton("နောက်မှဆွဲ →", { editor.move(1) }, Modifier.testTag("layer-later"), enabled = idle && d.index < recap.layers.size - if (d.isNew) 0 else 1)
            }
            if (problem != null) AfText(problem, color = AutoFlowColors.Amber)
            WrapRow {
                AfButton("သိမ်းမယ်", { value?.let(editor::save) }, Modifier.testTag("layer-save"), enabled = idle && !state.importing && problem == null)
                AfButton("မသိမ်းဘဲ ပယ်မယ်", editor::cancel, Modifier.testTag("layer-cancel"))
                if (!d.isNew) AfButton("Layer ဖယ်မယ်", { editor.remove() }, Modifier.testTag("layer-remove"), enabled = idle && !state.importing, kind = ButtonKind.Danger)
            }
        }
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
