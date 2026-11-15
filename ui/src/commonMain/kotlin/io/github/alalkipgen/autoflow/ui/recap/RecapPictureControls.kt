package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.testTag
import io.github.alalkipgen.autoflow.core.recap.PreviewGeometry
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.recap.FrameBackground
import io.github.alalkipgen.autoflow.core.recap.Look
import io.github.alalkipgen.autoflow.core.recap.MaskPreset
import io.github.alalkipgen.autoflow.core.recap.MaskStrength
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.CaptionStyle
import io.github.alalkipgen.autoflow.core.recap.Recap
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.core.recap.RecapVisuals
import io.github.alalkipgen.autoflow.core.recap.WatermarkAnchor
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfChip
import io.github.alalkipgen.autoflow.ui.components.AfCard
import io.github.alalkipgen.autoflow.ui.components.AfH2
import io.github.alalkipgen.autoflow.ui.components.AfH3
import io.github.alalkipgen.autoflow.ui.components.AfSwitch
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.AfTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.components.decodeImage
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes

const val HIDE_ON_MOVIE = "Hide things on the movie (its own subtitles, logos)"
const val CAPTIONS_ON_BOX = "Put my captions on the bottom box"

/**
 * Picture (5B-2) in card 5: blur boxes over the movie with a frame preview, the space around the movie when its shape
 * differs from the video's, and captions on the bottom box. Applied when the video is rendered.
 */
@Composable
internal fun RecapPictureControls(recap: RecapV2, idle: Boolean, model: RecapModel) {
    val v = recap.visual
    // ── 🎬 Output ──────────────────────────────────────────────────────────────
    AfCard("🎬 Output") {
        AfH3("Video ratio")
        WrapRow {
            AfChip("9:16 (default)", v.outputRatio == null, { if (idle) model.setOutputRatio(null) })
            Ratio.entries.forEach { r ->
                AfChip(r.label, v.outputRatio == r, { if (idle) model.setOutputRatio(r) })
            }
        }
    }

    // ── 🙈 Hide things ─────────────────────────────────────────────────────────
    AfCard("🙈 Hide things on the movie") {
        AfText("Blur boxes over the movie's subtitles, logos etc.", muted = true, size = 12.sp)
        WrapRow {
            MaskPreset.entries.forEach { p ->
                AfChip(p.label, v.masks.any { it.preset == p }, { if (idle) model.setVisual { RecapVisuals.toggle(it, p) } })
            }
        }
        if (v.masks.isNotEmpty()) {
            MaskPreview(recap, model)
            WrapRow {
                AfH3("Blur strength")
                MaskStrength.entries.forEach { s ->
                    AfChip(s.label, v.masks.all { it.strength == s }, { if (idle) model.setVisual { RecapVisuals.strength(it, s) } })
                }
            }
            v.masks.forEach { m ->
                WrapRow {
                    AfText(m.preset.label, size = 12.sp)
                    AfButton("▲", { if (idle) model.setVisual { RecapVisuals.move(it, m.preset, -RecapVisuals.STEP) } })
                    AfButton("▼", { if (idle) model.setVisual { RecapVisuals.move(it, m.preset, RecapVisuals.STEP) } })
                    AfButton("Taller", { if (idle) model.setVisual { RecapVisuals.resize(it, m.preset, 2 * RecapVisuals.STEP) } })
                    AfButton("Shorter", { if (idle) model.setVisual { RecapVisuals.resize(it, m.preset, -2 * RecapVisuals.STEP) } })
                    if (!m.preset.isStrip) {
                        AfButton("◀", { if (idle) model.setVisual { RecapVisuals.moveX(it, m.preset, -RecapVisuals.STEP) } })
                        AfButton("▶", { if (idle) model.setVisual { RecapVisuals.moveX(it, m.preset, RecapVisuals.STEP) } })
                        AfButton("Wider", { if (idle) model.setVisual { RecapVisuals.resizeW(it, m.preset, 2 * RecapVisuals.STEP) } })
                        AfButton("Narrower", { if (idle) model.setVisual { RecapVisuals.resizeW(it, m.preset, -2 * RecapVisuals.STEP) } })
                    }
                }
            }
            if (v.masks.any { it.centerY >= 0.5f }) {
                AfSwitch(CAPTIONS_ON_BOX, v.captionsOnMask, { on -> if (idle) model.setVisual { it.copy(captionsOnMask = on) } })
            }
        }
    }

    ExactFrameCard(model)

    // ── 🖼 Background ──────────────────────────────────────────────────────────
    if (Look.FILL !in recap.looks) {
    AfCard("🖼 Background (space around the movie)") {
        AfText("Fill the space when the movie shape differs from the output ratio.", muted = true, size = 12.sp)
        WrapRow {
            FrameBackground.entries.forEach { b ->
                AfChip(b.label, v.background == b, { if (idle) model.setVisual { it.copy(background = b) } })
            }
        }
        if (v.background == FrameBackground.COLOUR) {
            WrapRow {
                RecapVisuals.COLOURS.forEach { (name, hex) ->
                    AfChip(name, v.backgroundColour.equals(hex, ignoreCase = true), { if (idle) model.setVisual { it.copy(backgroundColour = hex) } })
                }
            }
        }
        if (v.background == FrameBackground.IMAGE) {
            val imgFile = v.backgroundImageFile?.substringAfterLast('/') ?: "not set"
            WrapRow {
                AfText("Image: $imgFile", size = 12.sp)
                AfButton("Change…", { if (idle) model.importBackgroundImage() })
                if (v.backgroundImageFile != null) {
                    AfButton("Remove", { if (idle) model.setVisual { it.copy(background = FrameBackground.BARS, backgroundImageFile = null) } })
                }
            }
        }
    }
    }

    // ── 💧 Watermark ───────────────────────────────────────────────────────────
    AfCard("💧 Watermark") {
        AfText("Logo / watermark image overlaid on every frame.", muted = true, size = 12.sp)
        WrapRow {
            if (v.watermarkFile != null) {
                AfText("🖼 ${v.watermarkFile!!.substringAfterLast('/')}", size = 12.sp)
                AfButton("Change…", { if (idle) model.importWatermark() })
                AfButton("Remove", { if (idle) model.removeWatermark() })
            } else {
                AfButton("Set watermark…", { if (idle) model.importWatermark() })
            }
        }
        if (v.watermarkFile != null) {
            AfH3("Position")
            WrapRow {
                WatermarkAnchor.entries.forEach { anchor ->
                    AfChip(anchor.label, v.watermarkAnchor == anchor, { if (idle) model.setWatermarkAnchor(anchor) })
                }
            }
        }
    }

    // ── ✍️ Captions ────────────────────────────────────────────────────────────
    AfCard("✍️ Captions") {
        // Colour presets
        AfH3("Colour")
        WrapRow {
            CaptionStyle.COLOURS.forEach { (name, hex) ->
                AfChip(
                    name,
                    recap.captionStyle.customColour == null && recap.captionStyle.colour.equals(hex, ignoreCase = true),
                    { if (idle) { model.setCaptionColour(hex); model.setCaptionCustomColour(null) } }
                )
            }
        }
        // Custom hex override
        AfH3("Custom colour (#RRGGBB)")
        var colourInput by remember { mutableStateOf(recap.captionStyle.customColour ?: "") }
        WrapRow {
            AfTextField(colourInput, { colourInput = it }, "#RRGGBB")
            AfButton("Apply", {
                if (idle) { model.setCaptionCustomColour(colourInput.ifBlank { null }); colourInput = recap.captionStyle.customColour ?: "" }
            })
            if (recap.captionStyle.customColour != null) {
                AfButton("Clear", { if (idle) { model.setCaptionCustomColour(null); colourInput = "" } })
            }
        }
        // Size presets + custom factor
        AfH3("Size")
        WrapRow {
            io.github.alalkipgen.autoflow.core.recap.CaptionSize.entries.forEach { sz ->
                AfChip(sz.label, recap.captionStyle.customSizeFactor == null && recap.captionStyle.size == sz, {
                    if (idle) model.update { it.copy(captionStyle = it.captionStyle.copy(size = sz, customSizeFactor = null)) }
                })
            }
        }
        AfH3("Custom size factor (0.5–3.0)")
        var sizeInput by remember { mutableStateOf(recap.captionStyle.customSizeFactor?.toString() ?: "") }
        WrapRow {
            AfTextField(sizeInput, { sizeInput = it }, "e.g. 1.5")
            AfButton("Apply", {
                if (idle) { model.setCaptionCustomSize(sizeInput.toFloatOrNull()); sizeInput = recap.captionStyle.customSizeFactor?.toString() ?: "" }
            })
            if (recap.captionStyle.customSizeFactor != null) {
                AfButton("Clear", { if (idle) { model.setCaptionCustomSize(null); sizeInput = "" } })
            }
        }
        // Stroke
        AfH3("Outline / Stroke width")
        WrapRow {
            listOf("Off" to 0, "Thin" to 1, "Medium" to 3, "Thick" to 6).forEach { (label, px) ->
                AfChip(label, recap.captionStyle.strokeWidth == px, { if (idle) model.setCaptionStrokeWidth(px) })
            }
        }
        if (recap.captionStyle.strokeWidth > 0) {
            AfH3("Outline colour")
            WrapRow {
                CaptionStyle.STROKE_COLOURS.forEach { (name, hex) ->
                    AfChip(name, recap.captionStyle.strokeColour.equals(hex, ignoreCase = true), { if (idle) model.setCaptionStrokeColour(hex) })
                }
            }
        }
        // Dark box toggle
        AfSwitch("Dark box behind captions", recap.captionStyle.box, { on -> if (idle) model.update { it.copy(captionStyle = it.captionStyle.copy(box = on)) } })
    }
}

/** Test tag of the live mask preview (drag / pinch target). */
internal const val MASK_PREVIEW_TAG = "mask-preview"

/**
 * Live preview (5A-8, D-42): a frame of the first picked scene with the blur boxes drawn on it (what the render will
 * hide). Touch a box to pick it, drag to move it, pinch (or the mouse wheel on a computer) to resize it around the
 * fingers; the box follows the fingers and is saved when they lift. Rects are 0–1 of the movie picture (D-50), the
 * same numbers the export compiler uses.
 */
@Composable
private fun MaskPreview(recap: RecapV2, model: RecapModel) {
    val scene = recap.picked.firstOrNull() ?: recap.scenes.firstOrNull()
    val image by produceState<ImageBitmap?>(null, scene?.startMs, scene?.endMs) { value = scene?.let { model.thumbnail(it) }?.let(::decodeImage) }
    val movie = recap.movie
    val ratio = if (movie != null && movie.width > 0 && movie.height > 0) movie.width.toFloat() / movie.height else 16f / 9f
    val masks = recap.visual.masks
    var selected by remember { mutableStateOf<MaskPreset?>(null) }
    // The box under the fingers while a gesture runs; saved (and cleared) when they lift.
    var draft by remember { mutableStateOf<Pair<MaskPreset, List<Float>>?>(null) }
    val current by rememberUpdatedState(masks)
    Box(
        Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(ratio).clip(AutoFlowShapes.Card).background(AutoFlowColors.Background)
            .testTag(MASK_PREVIEW_TAG)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    val list = current
                    val hit = PreviewGeometry.hit(list.map { it.rect }, down.position.x / w, down.position.y / h, slop = 0.02f)
                    if (hit == null) {
                        selected = null
                        return@awaitEachGesture
                    }
                    val preset = list[hit].preset
                    selected = preset
                    var rect = list[hit].rect
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (zoom != 1f || pan != Offset.Zero) {
                            val c = event.calculateCentroid(useCurrent = false).takeIf { it.isSpecified }
                                ?: Offset((rect[0] + rect[2] / 2) * w, (rect[1] + rect[3] / 2) * h)
                            rect = PreviewGeometry.transform(rect, pan.x / w, pan.y / h, zoom, c.x / w, c.y / h, RecapVisuals.MIN_SIZE)
                            draft = preset to rect
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (draft != null) model.setMaskRect(preset, rect)
                    draft = null
                }
            }
            .pointerInput(Unit) {
                // Mouse wheel = pinch on a computer: resizes the picked box around its centre, saved at once.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val preset = selected ?: continue
                        val rect = current.firstOrNull { it.preset == preset }?.rect ?: continue
                        val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (dy == 0f) continue
                        val zoom = if (dy < 0) 1.1f else 1f / 1.1f
                        model.setMaskRect(preset, PreviewGeometry.transform(rect, 0f, 0f, zoom, rect[0] + rect[2] / 2, rect[1] + rect[3] / 2, RecapVisuals.MIN_SIZE))
                        event.changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        image?.let { Image(it, contentDescription = "Movie frame", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        Canvas(Modifier.fillMaxSize()) {
            masks.forEach { m ->
                val r = draft?.takeIf { it.first == m.preset }?.second ?: m.rect
                val topLeft = Offset(r[0] * size.width, r[1] * size.height)
                val box = Size(r[2] * size.width, r[3] * size.height)
                val picked = m.preset == selected
                drawRect(Color.White.copy(alpha = if (picked) 0.4f else 0.55f), topLeft, box)
                drawRect(if (picked) AutoFlowColors.Cyan else AutoFlowColors.Purple, topLeft, box, style = Stroke(width = (if (picked) 3 else 2).dp.toPx()))
            }
        }
    }
    AfText(
        selected?.let { "${it.label}: drag to move · pinch or mouse wheel to resize" } ?: "Touch a box to move or resize it.",
        muted = true, size = 12.sp,
    )
}

/**
 * "Exact frame" (D-42): the export compiler renders one still of the recap (blur boxes, background, looks,
 * watermark, burned-in captions with the real font) — the preview above is close, this is exact.
 */
@Composable
internal fun ExactFrameCard(model: RecapModel) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<io.github.alalkipgen.autoflow.ui.export.RecapFrames.Result?>(null) }
    var working by remember { mutableStateOf(false) }
    AfCard("🎯 Exact frame") {
        AfText("One frame made by the export itself — what the video will really show.", muted = true, size = 12.sp)
        AfButton(if (working) "Rendering…" else "Exact frame", {
            if (!working) {
                working = true
                scope.launch { result = model.exactFrame(); working = false }
            }
        }, enabled = !working)
        when (val r = result) {
            is io.github.alalkipgen.autoflow.ui.export.RecapFrames.Result.Ok -> {
                val bmp = remember(r.frame) { decodeImage(r.frame.bytes) }
                bmp?.let {
                    Image(it, contentDescription = "Exact frame", Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(it.width.toFloat() / it.height).clip(AutoFlowShapes.Card))
                }
                AfText("Exact frame · ${RecapModel.seconds(r.frame.atMs)}", muted = true, size = 12.sp)
            }
            is io.github.alalkipgen.autoflow.ui.export.RecapFrames.Result.Failed -> AfText(r.reason, color = AutoFlowColors.Amber, size = 12.sp)
            null -> {}
        }
    }
}
