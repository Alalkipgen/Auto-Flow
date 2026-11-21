package io.github.alalkipgen.autoflow.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Phase 9 type scale (docs/DESIGN.md §9.2), sizes in sp. Line height stays the font's own: Pyidaungsu's is 1.72 × the
 * size and Noto Sans Myanmar's 2.18 ×, both above every bundle value (1.3–1.65); a fixed lower one would let stacked
 * marks (ျ ြ ွ ှ ု ူ ့ ်) overlap the next line or be clipped (D-100).
 */
enum class AfType(val size: TextUnit, val weight: FontWeight, val numbers: Boolean = false) {
    ScreenTitle(22.sp, FontWeight.Bold),
    TopBarTitle(17.sp, FontWeight.SemiBold),
    CardTitle(17.sp, FontWeight.SemiBold),
    RowTitle(14.5.sp, FontWeight.SemiBold),
    TileTitle(13.5.sp, FontWeight.SemiBold),
    Body(14.5.sp, FontWeight.Normal),
    Button(14.sp, FontWeight.SemiBold),
    ButtonSmall(13.sp, FontWeight.SemiBold),
    Caption(12.5.sp, FontWeight.Normal),
    Micro(11.5.sp, FontWeight.SemiBold),

    /** Times and counts: caption with tabular figures. */
    Numbers(12.5.sp, FontWeight.Normal, numbers = true),
}

/** UI text style; the Burmese-safe parts (centred, untrimmed line height; Android font padding) apply to all text. */
fun afTextStyle(color: Color, size: TextUnit, weight: FontWeight, family: FontFamily, numbers: Boolean = false) = TextStyle(
    color = color,
    fontSize = size,
    fontWeight = weight,
    fontFamily = family,
    fontFeatureSettings = if (numbers) "tnum" else null,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    platformStyle = burmeseSafePlatformStyle,
)

/** Android: `includeFontPadding = true`, so tall Burmese stacks keep their room; desktop: none needed. */
internal expect val burmeseSafePlatformStyle: PlatformTextStyle?
