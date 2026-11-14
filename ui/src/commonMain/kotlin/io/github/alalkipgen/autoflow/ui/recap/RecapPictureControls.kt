package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.Canvas
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
import io.github.alalkipgen.autoflow.core.recap.Recap
import io.github.alalkipgen.autoflow.core.recap.RecapVisuals
import io.github.alalkipgen.autoflow.core.recap.WatermarkAnchor
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfChip
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
internal fun RecapPictureControls(recap: Recap, idle: Boolean, model: RecapModel) {
    val v = recap.visual
    AfText(HIDE_ON_MOVIE, muted = true, size = 12.sp)
    WrapRow {
        MaskPreset.entries.forEach { p ->
            AfChip(p.label, v.masks.any { it.preset == p }, { if (idle) model.setVisual { RecapVisuals.toggle(it, p) } })
        }
    }
    if (v.masks.isNotEmpty()) {
        MaskPreview(recap, model)
        WrapRow {
            AfText("Blur", muted = true, size = 12.sp)
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
            }
        }
        if (v.masks.any { it.centerY >= 0.5f }) {
            AfSwitch(CAPTIONS_ON_BOX, v.captionsOnMask, { on -> if (idle) model.setVisual { it.copy(captionsOnMask = on) } })
        }
    }
    if (Look.FILL !in recap.looks) {
        AfText("Space around the movie (when its shape differs from the video's)", muted = true, size = 12.sp)
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
        } else if (v.background != FrameBackground.IMAGE) {
            // Show IMAGE option only briefly as a text note (the chip above already includes it)
        }
    }
    // Watermark (Brand Kit, D-54/D-56): logo image overlaid on every frame.
    AfText("Watermark / Logo (overlay on every frame)", muted = true, size = 12.sp)
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
        AfText("Position", muted = true, size = 12.sp)
        WrapRow {
            WatermarkAnchor.entries.forEach { anchor ->
                AfChip(anchor.label, v.watermarkAnchor == anchor, { if (idle) model.setWatermarkAnchor(anchor) })
            }
        }
    }

    // Output video ratio (D-56): overrides the project-level ratio for Movie Recap.
    AfText("Output video ratio", muted = true, size = 12.sp)
    WrapRow {
        AfChip("Default (9:16)", v.outputRatio == null, { if (idle) model.setOutputRatio(null) })
        Ratio.entries.forEach { r ->
            AfChip(r.label, v.outputRatio == r, { if (idle) model.setOutputRatio(r) })
        }
    }

    // Caption custom colour + size (D-56)
    AfText("Caption colour (custom hex, e.g. #FF5500)", muted = true, size = 12.sp)
    var colourInput by remember { mutableStateOf(recap.captionStyle.customColour ?: "") }
    WrapRow {
        AfTextField(colourInput, { colourInput = it }, "#RRGGBB")
        AfButton("Apply", {
            if (idle) {
                model.setCaptionCustomColour(colourInput.ifBlank { null })
                colourInput = recap.captionStyle.customColour ?: ""
            }
        })
        if (recap.captionStyle.customColour != null) {
            AfButton("Clear", { if (idle) { model.setCaptionCustomColour(null); colourInput = "" } })
        }
    }
    AfText("Caption size (custom factor 0.5–3.0; blank = use preset)", muted = true, size = 12.sp)
    var sizeInput by remember { mutableStateOf(recap.captionStyle.customSizeFactor?.toString() ?: "") }
    WrapRow {
        AfTextField(sizeInput, { sizeInput = it }, "e.g. 1.5")
        AfButton("Apply", {
            if (idle) {
                model.setCaptionCustomSize(sizeInput.toFloatOrNull())
                sizeInput = recap.captionStyle.customSizeFactor?.toString() ?: ""
            }
        })
        if (recap.captionStyle.customSizeFactor != null) {
            AfButton("Clear", { if (idle) { model.setCaptionCustomSize(null); sizeInput = "" } })
        }
    }
}

/** A frame of the first picked scene with the blur boxes drawn on it (what the render will hide). */
@Composable
private fun MaskPreview(recap: Recap, model: RecapModel) {
    val scene = recap.picked.firstOrNull() ?: recap.scenes.firstOrNull() ?: return
    val image by produceState<ImageBitmap?>(null, scene.startMs, scene.endMs) { value = model.thumbnail(scene)?.let(::decodeImage) }
    val movie = recap.movie
    val ratio = if (movie != null && movie.width > 0 && movie.height > 0) movie.width.toFloat() / movie.height else 16f / 9f
    Box(Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(ratio).clip(AutoFlowShapes.Card).background(AutoFlowColors.Background)) {
        image?.let { Image(it, contentDescription = "Movie frame", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        Canvas(Modifier.fillMaxSize()) {
            recap.visual.masks.forEach { m ->
                val r = m.rect
                val topLeft = Offset(r[0] * size.width, r[1] * size.height)
                val box = Size(r[2] * size.width, r[3] * size.height)
                drawRect(Color.White.copy(alpha = 0.55f), topLeft, box)
                drawRect(AutoFlowColors.Purple, topLeft, box, style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
}
