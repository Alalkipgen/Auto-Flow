package io.github.alalkipgen.autoflow.ui.dubbing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.recap.CaptionSize
import io.github.alalkipgen.autoflow.core.recap.CaptionStyle
import io.github.alalkipgen.autoflow.media.render.BurnSize
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.recap.CAPTION_XL_FACTOR
import io.github.alalkipgen.autoflow.ui.recap.CaptionLookPreset
import io.github.alalkipgen.autoflow.ui.recap.RecapSwatchChip
import io.github.alalkipgen.autoflow.ui.recap.captionExtraLarge
import io.github.alalkipgen.autoflow.ui.recap.matchingCaptionPreset
import io.github.alalkipgen.autoflow.ui.subtitles.overlayColour
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/**
 * The caption style of the burned Burmese subtitles on Style (D-106): Movie Recap's caption choices on the Dubbing
 * project's own [CaptionStyle] (never RecapModel) — Clean / Boxed / Outline, the colour (swatches and a free `#RRGGBB`),
 * the outline width and colour (without the box), the dark box; then the size (Small / Medium / Large / XL and a free
 * factor). Every change is saved in the project and shown on the preview at once; the export burns the same overlays.
 */
@Composable
internal fun DubCaptionLook(model: DubExportModel, style: CaptionStyle, idle: Boolean, projectKey: String?) {
    var colourInput by remember(projectKey) { mutableStateOf(style.customColour ?: "") }
    // The look ignores the size (it has its own chips): Boxed at Large is still Boxed.
    val preset = matchingCaptionPreset(style.copy(size = CaptionSize.M, customSizeFactor = null))
    InsetOptions(Modifier.testTag("dub-caption-look")) {
        Label(tr(UiText.V3_CAPTION_PRESETS))
        AfText(My.breakSyllables(tr(UiText.V3_CAPTION_PRESET_STATUS, "name" to tr(preset?.label ?: UiText.V3_CAPTION_CUSTOM))),
            Modifier.testTag("dub-caption-preset-status"), type = AfType.Caption)
        WrapRow {
            CaptionLookPreset.entries.forEach { p ->
                Chip(tr(p.label), preset == p, {
                    // A look keeps the chosen size (the size has its own chips below).
                    model.setCaptionStyle { p.style.copy(size = it.size, customSizeFactor = it.customSizeFactor) }; colourInput = ""
                }, "dub-caption-preset-${p.name}", idle)
            }
        }
        Label(tr(UiText.COLOUR))
        WrapRow {
            CaptionStyle.COLOURS.forEach { (name, hex) ->
                RecapSwatchChip(colourPresetLabel(name), overlayColour(hex), style.customColour == null && style.colour.equals(hex, ignoreCase = true),
                    { model.setCaptionStyle { it.copy(colour = hex, customColour = null) }; colourInput = "" },
                    Modifier.testTag("dub-caption-colour-$name"), enabled = idle)
            }
        }
        Label(tr(UiText.CUSTOM_COLOUR))
        GlassFieldRow({ AfTextField(colourInput, { colourInput = it }, "#RRGGBB", Modifier.fillMaxWidth().testTag("dub-caption-custom-colour"),
                enabled = idle, glass = true) }, {
            GlassButton(tr(UiText.APPLY), { model.setCustomColour(colourInput.ifBlank { null }); colourInput = model.style().customColour ?: "" },
                Modifier.testTag("dub-caption-colour-apply"), enabled = idle, small = true)
            if (style.customColour != null) GlassButton(tr(UiText.CLEAR), { model.setCustomColour(null); colourInput = "" },
                Modifier.testTag("dub-caption-colour-clear"), enabled = idle, small = true)
        })
        if (!style.box) {
            Label(tr(UiText.OUTLINE_WIDTH))
            WrapRow {
                listOf(UiText.OFF to 0, UiText.THIN to 1, UiText.MEDIUM to 3, UiText.THICK to 6).forEach { (label, px) ->
                    Chip(tr(label), style.strokeWidth == px, { model.setCaptionStyle { it.copy(strokeWidth = px) } }, "dub-caption-outline-$px", idle)
                }
            }
            if (style.strokeWidth > 0) {
                Label(tr(UiText.OUTLINE_COLOUR))
                WrapRow {
                    CaptionStyle.STROKE_COLOURS.forEach { (name, hex) ->
                        RecapSwatchChip(colourPresetLabel(name), overlayColour(hex), style.strokeColour.equals(hex, ignoreCase = true),
                            { model.setCaptionStyle { it.copy(strokeColour = hex) } }, Modifier.testTag("dub-caption-outline-colour-$name"), enabled = idle)
                    }
                }
            }
        }
        GlassSwitchRow(My.breakSyllables(tr(UiText.DARK_CAPTION_BOX)), style.box, { on -> model.setCaptionStyle { it.copy(box = on) } },
            Modifier.testTag("dub-caption-box"), enabled = idle)
    }
}

/** The size of the burned subtitles (D-106): Small / Medium / Large as before, XL, and a free factor (0.5–3.0 × Medium). */
@Composable
internal fun DubCaptionSize(model: DubExportModel, style: CaptionStyle, idle: Boolean, projectKey: String?) {
    var sizeInput by remember(projectKey) { mutableStateOf(style.customSizeFactor?.toString() ?: "") }
    Label(tr(UiText.SIZE))
    WrapRow {
        listOf(BurnSize.SMALL to CaptionSize.S, BurnSize.MEDIUM to CaptionSize.M, BurnSize.LARGE to CaptionSize.L).forEach { (burn, size) ->
            Chip(size.uiLabel(), style.customSizeFactor == null && style.size == size, { model.setSize(burn); sizeInput = "" },
                "dub-burn-size-${burn.name}", idle)
        }
        Chip(tr(UiText.V3_CAPTION_XL), style.customSizeFactor == CAPTION_XL_FACTOR, {
            model.setCaptionStyle(::captionExtraLarge); sizeInput = CAPTION_XL_FACTOR.toString()
        }, "dub-burn-size-XL", idle)
    }
    Label(tr(UiText.CUSTOM_SIZE_FACTOR))
    GlassFieldRow({ AfTextField(sizeInput, { sizeInput = it }, tr(UiText.SIZE_FACTOR_EXAMPLE), Modifier.fillMaxWidth().testTag("dub-caption-custom-size"),
            enabled = idle, glass = true) }, {
        GlassButton(tr(UiText.APPLY), { model.setCustomSize(sizeInput.toFloatOrNull()); sizeInput = model.style().customSizeFactor?.toString() ?: "" },
            Modifier.testTag("dub-caption-size-apply"), enabled = idle, small = true)
        if (style.customSizeFactor != null) GlassButton(tr(UiText.CLEAR), { model.setCustomSize(null); sizeInput = "" },
            Modifier.testTag("dub-caption-size-clear"), enabled = idle, small = true)
    })
}

/**
 * Save caption style… (floppy disk, D-106): a name, then Save to Library — the same Library caption preset Movie Recap
 * saves and applies. The Library's answer shows under it.
 */
@Composable
internal fun DubSaveCaptionStyle(model: DubExportModel, style: CaptionStyle, idle: Boolean, projectKey: String?, notice: UiNotice?, ok: Boolean) {
    var name by remember(projectKey) { mutableStateOf("") }
    if (name.isEmpty()) WrapRow {
        GlassButton(My.breakSyllables(tr(UiText.SAVE_CAPTION_PRESET)), {
            name = "Caption ${style.size.label} ${if (style.box) "box" else "outline"}"
        }, Modifier.testTag("dub-caption-save"), enabled = idle, icon = AfIcons.Regular.FloppyDisk)
    } else InsetOptions(Modifier.testTag("dub-caption-save-form")) {
        Label(tr(UiText.CAPTION_PRESET_NAME))
        AfTextField(name, { name = it }, tr(UiText.CAPTION_PRESET_NAME), Modifier.fillMaxWidth().testTag("dub-caption-save-name"), mono = false, glass = true)
        WrapRow {
            GlassButton(My.breakSyllables(tr(UiText.SAVE_TO_LIBRARY)), { if (name.isNotBlank()) { model.saveCaptionStyle(name.trim()); name = "" } },
                Modifier.testTag("dub-caption-save-confirm"), kind = ButtonKind.Primary, enabled = idle && name.isNotBlank(), icon = AfIcons.Regular.Book)
            GlassButton(tr(UiText.CANCEL), { name = "" }, Modifier.testTag("dub-caption-save-cancel"))
        }
    }
    notice?.let {
        val tone = if (ok) AutoFlowColors.Green else AutoFlowColors.Amber
        GlassCallout(if (ok) AfIcons.Fill.CheckCircle else AfIcons.Fill.Warn, My.breakSyllables(it.text(LocalUiLanguage.current)), tone,
            textModifier = Modifier.testTag("dub-caption-save-notice"))
    }
}

/** A darker inner box of options (radius 12, White .04 fill, 1 dp White .08 line, padding 12, gap 8), as the burn's choices. */
@Composable
internal fun InsetOptions(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val box = RoundedCornerShape(12.dp)
    Column(modifier.fillMaxWidth().clip(box).background(Color.White.copy(alpha = .04f)).border(1.dp, Color.White.copy(alpha = .08f), box)
        .padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** A group's label: Muted 12 sp (as the burn's size / lines labels). */
@Composable
private fun Label(text: String) = AfText(My.breakSyllables(text), muted = true, size = 12.sp)

/** The glass filter chip with `check` when chosen (as the burn's chips). */
@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit, tag: String, enabled: Boolean) =
    GlassFilterChip(My.breakSyllables(text), selected, onClick, Modifier.testTag(tag), enabled = enabled,
        leading = if (!selected) null else ({ ink -> AfIcon(AfIcons.Regular.Check, tint = ink, size = 14.dp) }))
