package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.i18n.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import io.github.alalkipgen.autoflow.core.recap.RecapStory
import io.github.alalkipgen.autoflow.media.render.CaptionChunks
import io.github.alalkipgen.autoflow.media.render.Output
import io.github.alalkipgen.autoflow.media.render.RecapRender
import io.github.alalkipgen.autoflow.media.render.RenderPresets
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
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
import io.github.alalkipgen.autoflow.core.recap.WatermarkSize
import io.github.alalkipgen.autoflow.ui.components.AfScrubber
import io.github.alalkipgen.autoflow.ui.components.OptionChips
import kotlin.math.roundToInt
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfIconButton
import androidx.compose.ui.graphics.graphicsLayer
import io.github.alalkipgen.autoflow.ui.components.MoviePlayback
import io.github.alalkipgen.autoflow.ui.components.moviePlaybackSupported
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
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector

internal enum class PictureSection { LAYERS, CAPTIONS }

const val HIDE_ON_MOVIE = "ရုပ်ရှင်ပေါ်က အရာတွေ ဖုံးမယ် (သူ့စာတန်း၊ logo)"
const val CAPTIONS_ON_BOX = "ကျွန်ုပ်ရဲ့ စာတန်းကို အောက်ဘက် ဘောင်ပေါ် တင်မယ်"

/**
 * Picture (5B-2) in card 5: blur boxes over the movie with a frame preview, the space around the movie when its shape
 * differs from the video's, and captions on the bottom box. Applied when the video is rendered.
 */
@Composable
internal fun RecapPictureControls(recap: RecapV2, idle: Boolean, model: RecapModel, section: PictureSection? = null) {
    val v = recap.visual
    // Studio Style (a [section], Group 2 C 15 / 16): each group = an inner glass card with a soft tile; glass chips,
    // buttons, switches and fields. Classic (no section) keeps its cards; only the emoji literals became icons.
    val glass = section != null
    val language = LocalUiLanguage.current
    @Composable fun card(title: String, tile: CardTile, icon: ImageVector, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
        if (glass) InnerGlassCard(title, tile, modifier, content = content) else AfCard(title, modifier, icon = icon, content = content)
    @Composable fun chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
        if (glass) RecapChip(text, selected, onClick, modifier, enabled) else AfChip(text, selected, onClick, modifier, enabled)
    @Composable fun colourChip(text: String, hex: String, selected: Boolean, onClick: () -> Unit) =
        if (glass) RecapSwatchChip(text, hexColour(hex), selected, onClick) else AfChip(text, selected, onClick)
    @Composable fun button(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: ButtonKind = ButtonKind.Secondary,
        icon: ImageVector? = null) =
        if (glass) GlassButton(text, onClick, modifier, kind, icon = icon, small = true)
        else AfButton(text, onClick, modifier)
    @Composable fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        if (glass) RecapSwitch(label, checked, onChange) else AfSwitch(label, checked, onChange)
    @Composable fun hint(text: String) = if (glass) AfText(text, muted = true, type = AfType.Caption) else AfText(text, muted = true, size = 12.sp)
    // Glass: a heading and its controls 6 dp apart; Classic: straight into the card's column, as before.
    @Composable fun group(content: @Composable () -> Unit) =
        if (glass) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() } else content()
    if (section != PictureSection.CAPTIONS) {
    // ── Output ───────────────────────────────────────────────────────────────
    card(tr(UiText.OUTPUT_CARD), RecapTiles.Output, AfIcons.Regular.Film) {
        AfH3(tr(UiText.VIDEO_RATIO))
        WrapRow {
            chip(tr(UiText.DEFAULT_RATIO), v.outputRatio == null, { if (idle) model.setOutputRatio(null) })
            Ratio.entries.forEach { r ->
                chip(r.label, v.outputRatio == r, { if (idle) model.setOutputRatio(r) }, Modifier.testTag("style-ratio-${r.name}"))
            }
        }
    }

    // ── Hide things ──────────────────────────────────────────────────────────
    card(tr(UiText.HIDE_THINGS_CARD), RecapTiles.HideThings, AfIcons.Regular.Square) {
        hint(tr(UiText.HIDE_THINGS_HINT))
        WrapRow {
            MaskPreset.entries.forEach { p ->
                chip(maskLabel(p, language), v.masks.any { it.preset == p }, { if (idle) model.setVisual { RecapVisuals.toggle(it, p) } })
            }
        }
        if (v.masks.isNotEmpty()) {
            MaskPreview(recap, model, glass)
            val strengths: @Composable () -> Unit = {
                MaskStrength.entries.forEach { s ->
                    chip(strengthLabel(s, language), v.masks.all { it.strength == s }, { if (idle) model.setVisual { RecapVisuals.strength(it, s) } })
                }
            }
            if (glass) group { AfH3(tr(UiText.BLUR_STRENGTH)); WrapRow { strengths() } }
            else WrapRow { AfH3(tr(UiText.BLUR_STRENGTH)); strengths() }
            v.masks.forEach { m ->
                val moves: @Composable () -> Unit = {
                    if (glass) {
                        // Group 2 C 15: 36 dp glass caret buttons, small glass buttons; one darker box per blur box.
                        GlassIconButton(AfIcons.Regular.CaretUp, tr(UiText.MOVE_UP), { if (idle) model.setVisual { RecapVisuals.move(it, m.preset, -RecapVisuals.STEP) } })
                        GlassIconButton(AfIcons.Regular.CaretDown, tr(UiText.MOVE_DOWN), { if (idle) model.setVisual { RecapVisuals.move(it, m.preset, RecapVisuals.STEP) } })
                    } else {
                        // Nudges = the chevron turned to its direction (the icon set has one caret).
                        AfIconButton(AfIcons.Regular.Chevron, tr(UiText.MOVE_UP), { if (idle) model.setVisual { RecapVisuals.move(it, m.preset, -RecapVisuals.STEP) } },
                            Modifier.graphicsLayer(rotationZ = -90f))
                        AfIconButton(AfIcons.Regular.Chevron, tr(UiText.MOVE_DOWN), { if (idle) model.setVisual { RecapVisuals.move(it, m.preset, RecapVisuals.STEP) } },
                            Modifier.graphicsLayer(rotationZ = 90f))
                    }
                    button(tr(UiText.TALLER), { if (idle) model.setVisual { RecapVisuals.resize(it, m.preset, 2 * RecapVisuals.STEP) } })
                    button(tr(UiText.SHORTER), { if (idle) model.setVisual { RecapVisuals.resize(it, m.preset, -2 * RecapVisuals.STEP) } })
                    if (!m.preset.isStrip) {
                        if (glass) {
                            GlassIconButton(AfIcons.Regular.CaretLeft, tr(UiText.MOVE_LEFT), { if (idle) model.setVisual { RecapVisuals.moveX(it, m.preset, -RecapVisuals.STEP) } })
                            GlassIconButton(AfIcons.Regular.Chevron, tr(UiText.MOVE_RIGHT), { if (idle) model.setVisual { RecapVisuals.moveX(it, m.preset, RecapVisuals.STEP) } })
                        } else {
                            AfIconButton(AfIcons.Regular.Chevron, tr(UiText.MOVE_LEFT), { if (idle) model.setVisual { RecapVisuals.moveX(it, m.preset, -RecapVisuals.STEP) } },
                                Modifier.graphicsLayer(scaleX = -1f))
                            AfIconButton(AfIcons.Regular.Chevron, tr(UiText.MOVE_RIGHT), { if (idle) model.setVisual { RecapVisuals.moveX(it, m.preset, RecapVisuals.STEP) } })
                        }
                        button(tr(UiText.WIDER), { if (idle) model.setVisual { RecapVisuals.resizeW(it, m.preset, 2 * RecapVisuals.STEP) } })
                        button(tr(UiText.NARROWER), { if (idle) model.setVisual { RecapVisuals.resizeW(it, m.preset, -2 * RecapVisuals.STEP) } })
                    }
                }
                if (glass) InsetGlassBox {
                    AfText(maskLabel(m.preset, language), type = AfType.Caption)
                    WrapRow(spacing = 6.dp) { moves() }
                } else WrapRow {
                    AfText(maskLabel(m.preset, language), size = 12.sp)
                    moves()
                }
            }
            if (v.masks.any { it.centerY >= 0.5f }) {
                switch(tr(UiText.CAPTIONS_ON_BOX), v.captionsOnMask, { on -> if (idle) model.setVisual { it.copy(captionsOnMask = on) } })
            }
        }
    }

    if (section == null) { RecapTimelinePreview(model); ExactFrameCard(model) }

    // ── Background ───────────────────────────────────────────────────────────
    if (Look.FILL !in recap.looks) {
    card(tr(UiText.BACKGROUND_CARD), RecapTiles.FrameBackground, AfIcons.Regular.Image) {
        hint(tr(UiText.BACKGROUND_HINT))
        WrapRow {
            FrameBackground.entries.forEach { b ->
                chip(b.uiLabel(), v.background == b, { if (idle) model.setVisual { it.copy(background = b) } })
            }
        }
        if (v.background == FrameBackground.COLOUR) {
            WrapRow {
                RecapVisuals.COLOURS.forEach { (name, hex) ->
                    colourChip(colourPresetLabel(name), hex, v.backgroundColour.equals(hex, ignoreCase = true), { if (idle) model.setVisual { it.copy(backgroundColour = hex) } })
                }
            }
        }
        if (v.background == FrameBackground.IMAGE) {
            val imgFile = v.backgroundImageFile?.substringAfterLast('/') ?: tr(UiText.NOT_SET)
            val remove = { if (idle) model.setVisual { it.copy(background = FrameBackground.BARS, backgroundImageFile = null) } }
            if (glass) {
                GlassFact(AfIcons.Regular.Image, tr(UiText.BACKGROUND_IMAGE_NAME, "imgFile" to (imgFile)))
                WrapRow {
                    button(tr(UiText.CHANGE), { if (idle) model.importBackgroundImage() }, icon = AfIcons.Regular.ArrowsClockwise)
                    if (v.backgroundImageFile != null) button(tr(UiText.REMOVE), remove, kind = ButtonKind.Danger, icon = AfIcons.Regular.Close)
                }
            } else WrapRow {
                AfText(tr(UiText.BACKGROUND_IMAGE_NAME, "imgFile" to (imgFile)), size = 12.sp)
                AfButton(tr(UiText.CHANGE), { if (idle) model.importBackgroundImage() })
                if (v.backgroundImageFile != null) AfButton(tr(UiText.REMOVE), remove)
            }
        }
    }
    }

    if (section == null) RecapLayerControls(recap, idle, model)

    // ── Watermark ────────────────────────────────────────────────────────────
    card(tr(UiText.WATERMARK_CARD), RecapTiles.Watermark, AfIcons.Regular.Image) {
        hint(tr(UiText.WATERMARK_HINT))
        val file = v.watermarkFile?.substringAfterLast('/')
        if (glass) {
            // Group 2 C 15: the file = an image icon + its name (one line; the full name stays readable); Change… / Remove (danger) under it.
            if (file != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AfIcon(AfIcons.Regular.Image, tint = AutoFlowColors.Muted, size = 17.dp)
                    AfText(file, Modifier.weight(1f).semantics { contentDescription = file }, type = AfType.Body, maxLines = 1, ellipsis = true)
                }
                WrapRow {
                    button(tr(UiText.CHANGE), { if (idle) model.importWatermark() }, icon = AfIcons.Regular.ArrowsClockwise)
                    button(tr(UiText.REMOVE), { if (idle) model.removeWatermark() }, kind = ButtonKind.Danger, icon = AfIcons.Regular.Close)
                }
            } else WrapRow { button(tr(UiText.SET_WATERMARK), { if (idle) model.importWatermark() }, icon = AfIcons.Regular.Upload) }
        } else WrapRow {
            if (file != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AfIcon(AfIcons.Regular.Image, tint = AutoFlowColors.Muted, size = 16.dp)
                    AfText(file, size = 12.sp)
                }
                AfButton(tr(UiText.CHANGE), { if (idle) model.importWatermark() })
                AfButton(tr(UiText.REMOVE), { if (idle) model.removeWatermark() })
            } else {
                AfButton(tr(UiText.SET_WATERMARK), { if (idle) model.importWatermark() })
            }
        }
        if (v.watermarkFile != null) {
            WatermarkWidth(v.watermarkPercent, idle, glass, model::setWatermarkWidth)
            AfH3(tr(UiText.POSITION))
            WrapRow {
                WatermarkAnchor.entries.forEach { anchor ->
                    val on = v.watermarkAnchor == anchor
                    if (glass) RecapIconChip(anchor.uiLabel(), anchor.icon(), on, { if (idle) model.setWatermarkAnchor(anchor) })
                    else AfChip(anchor.uiLabel(), on, { if (idle) model.setWatermarkAnchor(anchor) }, icon = anchor.icon())
                }
            }
        }
    }

    }
    // ── Captions ─────────────────────────────────────────────────────────────
    if (section != PictureSection.LAYERS) {
        val body: @Composable ColumnScope.() -> Unit = {
        val currentState by model.state.collectAsState()
        val open by model.previewSource.projects.collectAsState()
        val projectDir = currentState.projectDir
        val capturedProject = open?.project
        var manualStyle by remember(model, projectDir) { mutableStateOf<CaptionStyle?>(null) }
        var colourInput by remember(model, projectDir) { mutableStateOf(recap.captionStyle.customColour ?: "") }
        var sizeInput by remember(model, projectDir) { mutableStateOf(recap.captionStyle.customSizeFactor?.toString() ?: "") }
        fun currentProject() = capturedProject != null && open?.dir == projectDir &&
            model.state.value.projectDir == projectDir && model.project() == capturedProject
        fun manualChange(change: () -> Unit) {
            if (!idle) return
            val before = model.project()?.recap?.captionStyle
            change()
            val saved = model.project()?.recap?.captionStyle
            if (saved != null && saved != before) manualStyle = saved
        }
        /** A value field with its Apply / Clear: glass = the field beside its small buttons while it fits (C 16). */
        @Composable fun valueField(value: String, onValue: (String) -> Unit, placeholder: String, tag: String, actions: @Composable () -> Unit) {
            if (glass) GlassFieldRow({ AfTextField(value, onValue, placeholder, Modifier.fillMaxWidth().testTag(tag), glass = true) }, actions)
            else WrapRow { AfTextField(value, onValue, placeholder, Modifier.testTag(tag)); actions() }
        }
        val preset = if (manualStyle == recap.captionStyle) null else matchingCaptionPreset(recap.captionStyle)
        group {
            AfH3(tr(UiText.V3_CAPTION_PRESETS))
            AfText(tr(UiText.V3_CAPTION_PRESET_STATUS, "name" to tr(preset?.label ?: UiText.V3_CAPTION_CUSTOM)),
                modifier = Modifier.testTag("caption-preset-status"))
            WrapRow {
                CaptionLookPreset.entries.forEach { choice ->
                    chip(tr(choice.label), preset == choice, {
                        if (idle && currentProject()) {
                            if (model.project()?.recap?.captionStyle != choice.style) model.setCaptionStyle { choice.style }
                            if (model.project()?.recap?.captionStyle == choice.style) {
                                manualStyle = null; colourInput = ""; sizeInput = ""
                            }
                        }
                    }, Modifier.testTag("caption-preset-${choice.name}"), enabled = idle)
                }
            }
        }
        // Colour presets (glass: a swatch dot on each chip)
        group {
            AfH3(tr(UiText.COLOUR))
            WrapRow {
                CaptionStyle.COLOURS.forEach { (name, hex) ->
                    colourChip(
                        colourPresetLabel(name),
                        hex,
                        recap.captionStyle.customColour == null && recap.captionStyle.colour.equals(hex, ignoreCase = true),
                        { manualChange { model.setCaptionColour(hex); model.setCaptionCustomColour(null) } }
                    )
                }
            }
        }
        // Custom hex override
        group {
            AfH3(tr(UiText.CUSTOM_COLOUR))
            valueField(colourInput, { colourInput = it }, "#RRGGBB", "caption-custom-colour") {
                button(tr(UiText.APPLY), {
                    manualChange { model.setCaptionCustomColour(colourInput.ifBlank { null }); colourInput = model.project()?.recap?.captionStyle?.customColour ?: "" }
                }, Modifier.testTag("caption-colour-apply"))
                if (recap.captionStyle.customColour != null) {
                    button(tr(UiText.CLEAR), { manualChange { model.setCaptionCustomColour(null); colourInput = "" } }, Modifier.testTag("caption-colour-clear"))
                }
            }
        }
        // Size presets + custom factor
        group {
            AfH3(tr(UiText.SIZE))
            WrapRow {
                io.github.alalkipgen.autoflow.core.recap.CaptionSize.entries.forEach { sz ->
                    chip(sz.uiLabel(), recap.captionStyle.customSizeFactor == null && recap.captionStyle.size == sz, {
                        manualChange { model.update { it.copy(captionStyle = it.captionStyle.copy(size = sz, customSizeFactor = null)) } }
                    }, Modifier.testTag("caption-size-${sz.name}"))
                }
                chip(tr(UiText.V3_CAPTION_XL), recap.captionStyle.size == io.github.alalkipgen.autoflow.core.recap.CaptionSize.L &&
                    recap.captionStyle.customSizeFactor == CAPTION_XL_FACTOR, {
                    if (currentProject()) manualChange {
                        model.setCaptionStyle(::captionExtraLarge)
                        sizeInput = model.project()?.recap?.captionStyle?.customSizeFactor?.toString() ?: ""
                    }
                }, Modifier.testTag("caption-size-XL"), enabled = idle)
            }
        }
        group {
            AfH3(tr(UiText.CUSTOM_SIZE_FACTOR))
            valueField(sizeInput, { sizeInput = it }, tr(UiText.SIZE_FACTOR_EXAMPLE), "caption-custom-size") {
                button(tr(UiText.APPLY), {
                    manualChange { model.setCaptionCustomSize(sizeInput.toFloatOrNull()); sizeInput = model.project()?.recap?.captionStyle?.customSizeFactor?.toString() ?: "" }
                }, Modifier.testTag("caption-factor-apply"))
                if (recap.captionStyle.customSizeFactor != null) {
                    button(tr(UiText.CLEAR), { manualChange { model.setCaptionCustomSize(null); sizeInput = "" } }, Modifier.testTag("caption-factor-clear"))
                }
            }
        }
        // Stroke
        group {
            AfH3(tr(UiText.OUTLINE_WIDTH))
            WrapRow {
                listOf(tr(UiText.OFF) to 0, tr(UiText.THIN) to 1, tr(UiText.MEDIUM) to 3, tr(UiText.THICK) to 6).forEach { (label, px) ->
                    chip(label, recap.captionStyle.strokeWidth == px, { manualChange { model.setCaptionStrokeWidth(px) } })
                }
            }
        }
        if (recap.captionStyle.strokeWidth > 0) {
            group {
                AfH3(tr(UiText.OUTLINE_COLOUR))
                WrapRow {
                    CaptionStyle.STROKE_COLOURS.forEach { (name, hex) ->
                        colourChip(colourPresetLabel(name), hex, recap.captionStyle.strokeColour.equals(hex, ignoreCase = true), { manualChange { model.setCaptionStrokeColour(hex) } })
                    }
                }
            }
        }
        // Dark box toggle
        switch(tr(UiText.DARK_CAPTION_BOX), recap.captionStyle.box, { on -> manualChange { model.update { it.copy(captionStyle = it.captionStyle.copy(box = on)) } } })
        }
        // Group 2 C 16: the Captions card = a glass card with the blue subtitles tile.
        if (glass) TileCard(tr(UiText.CAPTIONS_CARD), RecapTiles.Captions, Modifier.testTag("caption-controls"), content = body)
        else AfCard(tr(UiText.CAPTIONS_CARD), Modifier.testTag("caption-controls"), icon = AfIcons.Regular.Subtitles, content = body)
    }
}

/** The icon a watermark position chip shows before its name (Group 2 C 15; the names lost their arrows). */
internal fun WatermarkAnchor.icon(): ImageVector = when (this) {
    WatermarkAnchor.TOP_LEFT -> AfIcons.Regular.ArrowUpLeft
    WatermarkAnchor.TOP_RIGHT -> AfIcons.Regular.ArrowUpRight
    WatermarkAnchor.BOTTOM_LEFT -> AfIcons.Regular.ArrowDownLeft
    WatermarkAnchor.BOTTOM_RIGHT -> AfIcons.Regular.ArrowDownRight
    WatermarkAnchor.CENTER -> AfIcons.Regular.Crosshair
}

/** Test tag of the live mask preview (drag / pinch target). */
internal const val MASK_PREVIEW_TAG = "mask-preview"

// Blur-box controls in Burmese (5B-13); presets and strengths keep their saved English enum names.
internal const val TALLER = "ပိုမြင့်"
internal const val SHORTER = "ပိုနိမ့်"
internal const val WIDER = "ပိုကျယ်"
internal const val NARROWER = "ပိုကျဉ်း"
internal const val EXACT_FRAME = "အတိအကျ ပုံ"
internal const val PLAY_PLAYBACK = "▶ ရုပ်ရှင် ဖွင့်ကြည့်မယ်"
internal const val STOP_PLAYBACK = "⏹ ပုံရိပ်ပဲ ပြမယ်"
internal const val PLAYBACK_TAG = "preview-playback"

/** Burmese name of a blur-box preset (5B-13). */
internal fun maskLabel(p: MaskPreset, language: UiLanguage = UiLanguage.ENGLISH): String = when (p) {
    MaskPreset.BOTTOM -> UiText.MASK_BOTTOM.text(language)
    MaskPreset.TOP -> UiText.MASK_TOP.text(language)
    MaskPreset.LOGO_TL -> UiText.MASK_LOGO_TL.text(language)
    MaskPreset.LOGO_TR -> UiText.MASK_LOGO_TR.text(language)
    MaskPreset.CUSTOM -> UiText.MASK_CUSTOM.text(language)
}

internal fun strengthLabel(s: MaskStrength, language: UiLanguage = UiLanguage.ENGLISH): String = when (s) {
    MaskStrength.LIGHT -> UiText.BLUR_LIGHT.text(language)
    MaskStrength.STRONG -> UiText.BLUR_STRONG.text(language)
}

/** Hint under the live preview for the touched box. */
internal fun boxHint(p: MaskPreset, language: UiLanguage = UiLanguage.ENGLISH): String = UiText.MASK_GESTURE_HINT.text(language, "maskLabelp" to (maskLabel(p, language)))

/**
 * Live preview (5A-8, D-42): a frame of the first picked scene with the blur boxes drawn on it (what the render will
 * hide). Touch a box to pick it, drag to move it, pinch (or the mouse wheel on a computer) to resize it around the
 * fingers; the box follows the fingers and is saved when they lift. Rects are 0–1 of the movie picture (D-50), the
 * same numbers the export compiler uses.
 */
@Composable
private fun MaskPreview(recap: RecapV2, model: RecapModel, glass: Boolean = false) {
    val scene = recap.picked.firstOrNull() ?: recap.scenes.firstOrNull()
    val image by produceState<ImageBitmap?>(null, scene?.startMs, scene?.endMs) { value = scene?.let { model.thumbnail(it) }?.let(::decodeImage) }
    val movie = recap.movie
    val ratio = if (movie != null && movie.width > 0 && movie.height > 0) movie.width.toFloat() / movie.height else 16f / 9f
    val masks = recap.visual.masks
    val output = RenderPresets.preview(model.outputRatio(recap))
    val story by model.state.collectAsState()
    val caption = previewCaption(recap, story.story, output)
    var selected by remember { mutableStateOf<MaskPreset?>(null) }
    if (glass) AfText(tr(UiText.MASK_PLACEMENT_HINT), muted = true, type = AfType.Caption)
    else AfText(tr(UiText.MASK_PLACEMENT_HINT), muted = true, size = 12.sp)
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
        RecapPreviewPicture(
            recap, output, image, caption, Modifier.fillMaxSize(), selected, draft,
        )
    }
    val touch = selected?.let { boxHint(it, LocalUiLanguage.current) } ?: tr(UiText.MASK_TOUCH_HINT)
    // Glass (Group 2 C 15): the gesture hint = a muted hand-tap row.
    if (glass) GlassFact(AfIcons.Regular.HandTap, touch, colour = AutoFlowColors.Muted, caption = true)
    else AfText(touch, muted = true, size = 12.sp)
}

/** The first caption the export would burn (first story block's first chunk), or null with captions off / no text. */
internal fun previewCaption(recap: RecapV2, story: RecapStory?, output: Output): String? {
    if (!recap.burnCaptions) return null
    val text = story?.blocks?.firstOrNull { it.textMy.isNotBlank() }?.textMy?.trim() ?: return null
    return CaptionChunks.chunks(text, RecapRender.lineChars(output, recap.captionStyle)).firstOrNull()
}

/**
 * The Recap preview picture (5A-8, D-42; S10): the movie frame, the blur boxes where the render puts them, and the
 * caption where the export burns it ([RecapRender.captionSpot]: on the caption box, centred on it, or the usual
 * bottom place) at the export's size relative to the picture, with its colour and outline or box. The export draws
 * the same numbers with ffmpeg / libass; `PreviewExportParityTest` (S10) measures both.
 */
@Composable
internal fun RecapPreviewPicture(
    recap: RecapV2,
    output: Output,
    image: ImageBitmap?,
    caption: String?,
    modifier: Modifier = Modifier,
    selected: MaskPreset? = null,
    draft: Pair<MaskPreset, List<Float>>? = null,
    background: (@Composable () -> Unit)? = null,
) {
    val measurer = rememberTextMeasurer()
    val family = LocalUiFontFamily.current
    val spot = remember(recap, output) { RecapRender.captionSpot(recap, output) }
    val style = recap.captionStyle
    Box(modifier) {
        if (background != null) background()
        else image?.let { Image(it, contentDescription = tr(UiText.MOVIE_FRAME), Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        Canvas(Modifier.fillMaxSize()) {
            recap.visual.masks.forEach { m ->
                val r = draft?.takeIf { it.first == m.preset }?.second ?: m.rect
                val topLeft = Offset(r[0] * size.width, r[1] * size.height)
                val box = Size(r[2] * size.width, r[3] * size.height)
                val picked = m.preset == selected
                drawRect(Color.White.copy(alpha = if (picked) 0.4f else 0.55f), topLeft, box)
                drawRect(if (picked) AutoFlowColors.Cyan else AutoFlowColors.Purple, topLeft, box, style = Stroke(width = (if (picked) 3 else 2).dp.toPx()))
            }
            if (caption != null && spot != null) {
                val emPx = spot.emOfPicture * size.height
                // libass draws in output pixels: outline = strokeWidth or max(2, size / 24); box padding max(4, size / 6).
                val outPx = RecapRender.captionSizePx(output, style).coerceAtLeast(1)
                val perOutPx = emPx / outPx
                val text = TextStyle(fontFamily = family, fontSize = (emPx / fontScale / density).sp, textAlign = TextAlign.Center)
                val layout = measurer.measure(caption, text, softWrap = false)
                val w = layout.size.width.toFloat()
                val h = layout.size.height.toFloat()
                val topLeft = Offset(spot.x * size.width - w / 2, if (spot.bottom) spot.y * size.height - h else spot.y * size.height - h / 2)
                val colour = hexColour(style.effectiveColour)
                if (style.box) {
                    val pad = maxOf(4, outPx / 6) * perOutPx
                    drawRect(Color.Black.copy(alpha = 0.6f), Offset(topLeft.x - pad, topLeft.y - pad), Size(w + 2 * pad, h + 2 * pad))
                } else {
                    val outline = (if (style.strokeWidth > 0) style.strokeWidth else maxOf(2, outPx / 24)) * perOutPx
                    val edge = if (style.strokeWidth > 0) hexColour(style.strokeColour) else Color.Black
                    drawText(layout, edge, topLeft, drawStyle = Stroke(width = 2 * outline, join = StrokeJoin.Round))
                }
                drawText(layout, colour, topLeft)
            }
        }
    }
}

/** `#RRGGBB` → a colour; anything else is white (as [io.github.alalkipgen.autoflow.media.render.AssWriter.assColor]). */
private fun hexColour(hex: String): Color =
    hex.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: Color.White

/**
 * "Exact frame" (D-42): the export compiler renders one still of the recap (blur boxes, background, looks,
 * watermark, burned-in captions with the real font) — the preview above is close, this is exact.
 */
@Composable
internal fun ExactFrameCard(model: RecapModel, enabled: Boolean = true) {
    val open by model.previewSource.projects.collectAsState()
    // A completed/in-flight still belongs to this project revision, never the next project on screen.
    androidx.compose.runtime.key(open?.dir, open?.project?.id, open?.project?.updatedAt) { ExactFrameContent(model, enabled) }
}

@Composable
private fun ExactFrameContent(model: RecapModel, enabled: Boolean) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<io.github.alalkipgen.autoflow.ui.export.RecapFrames.Result?>(null) }
    var working by remember { mutableStateOf(false) }
    // The button names the card (a title would repeat "Exact frame"); its image icon replaces the old 🎯 title.
    AfCard {
        AfText(tr(UiText.EXACT_FRAME_HINT), muted = true, size = 12.sp)
        AfButton(if (working) tr(UiText.RENDERING) else tr(UiText.EXACT_FRAME), {
            if (!working) {
                working = true
                val at = model.previewSource.currentPosition()
                scope.launch { try { result = model.exactFrame(at) } finally { working = false } }
            }
        }, enabled = !working && enabled, icon = AfIcons.Regular.Image)
        if (!enabled) AfText(tr(UiText.V3_LAYER_DRAFT_PREVIEW), muted = true)
        when (val r = result.takeIf { enabled }) {
            is io.github.alalkipgen.autoflow.ui.export.RecapFrames.Result.Ok -> {
                val bmp = remember(r.frame) { decodeImage(r.frame.bytes) }
                bmp?.let {
                    Image(it, contentDescription = tr(UiText.EXACT_FRAME_DESCRIPTION), Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(it.width.toFloat() / it.height).clip(AutoFlowShapes.Card))
                }
                AfText("${tr(UiText.EXACT_FRAME)} · ${RecapModel.seconds(r.frame.atMs)}", muted = true, size = 12.sp)
            }
            is io.github.alalkipgen.autoflow.ui.export.RecapFrames.Result.Failed -> AfText(r.reason, color = AutoFlowColors.Amber, size = 12.sp)
            null -> {}
        }
    }
}

@Composable
internal fun WatermarkWidth(value: Int, enabled: Boolean, glass: Boolean = false, onSize: (Int) -> Unit) {
    var editingCustom by remember { mutableStateOf(false) }
    val sizes = listOf(WatermarkSize.SMALL, WatermarkSize.MEDIUM, WatermarkSize.LARGE)
    val width = My.breakSyllables(tr(UiText.WATERMARK_WIDTH))
    val labels = listOf(tr(UiText.SMALL), tr(UiText.MEDIUM), tr(UiText.LARGE), tr(UiText.WATERMARK_CUSTOM)).map(My::breakSyllables)
    val selection = sizes.indexOf(value).takeIf { it >= 0 && !editingCustom } ?: labels.lastIndex
    AfH3(width)
    val choose: (String) -> Unit = { label ->
        if (enabled) {
            val preset = sizes.getOrNull(labels.indexOf(label))
            editingCustom = preset == null
            preset?.let(onSize)
        }
    }
    // Glass (Group 2 C 15): glass chips and a caption for the value.
    if (glass) WrapRow(Modifier.testTag("watermark-size")) { labels.forEach { RecapChip(it, it == labels[selection], { choose(it) }) } }
    else OptionChips(labels, labels[selection], choose, Modifier.testTag("watermark-size"))
    val percent = My.breakSyllables(tr(UiText.WATERMARK_WIDTH_VALUE, "n" to value))
    if (glass) AfText(percent, Modifier.testTag("watermark-width-value"), muted = true, type = AfType.Caption)
    else AfText(percent, Modifier.testTag("watermark-width-value"), muted = true)
    if (selection == labels.lastIndex) {
        val span = WatermarkSize.MAX - WatermarkSize.MIN
        AfScrubber((value - WatermarkSize.MIN).toFloat() / span, "$width · $percent",
            { onSize((it * span).roundToInt() + WatermarkSize.MIN) }, Modifier.testTag("watermark-width-slider"), enabled)
    }
}
