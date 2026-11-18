package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.core.recap.CaptionSize
import io.github.alalkipgen.autoflow.core.recap.CaptionStyle
import io.github.alalkipgen.autoflow.ui.i18n.UiText

/** UI conveniences over existing persisted fields, not new renderer capabilities or preset IDs. */
internal enum class CaptionLookPreset(val label: UiText, val style: CaptionStyle) {
    CLEAN(UiText.V3_CAPTION_CLEAN, CaptionStyle()), // Renderer default auto outline, not outline-off.
    BOXED(UiText.V3_CAPTION_BOXED, CaptionStyle(box = true)),
    OUTLINE(UiText.V3_CAPTION_OUTLINE, CaptionStyle(strokeWidth = 3)),
}

internal fun matchingCaptionPreset(style: CaptionStyle): CaptionLookPreset? =
    CaptionLookPreset.entries.firstOrNull { it.style == style }

internal const val CAPTION_XL_FACTOR = 1.4f

/** L supplies the existing largest line-chunk setting; only the existing custom factor supplies XL pixels. */
internal fun captionExtraLarge(style: CaptionStyle): CaptionStyle =
    style.copy(size = CaptionSize.L, customSizeFactor = CAPTION_XL_FACTOR)
