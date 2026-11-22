package io.github.alalkipgen.autoflow.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * WCAG AA (4.5:1) for text on the glass surfaces (Exact UI v4 token table): the composite fills over the base
 * background, without the ambient blobs (they move under the cards while a screen scrolls).
 */
class GlassContrastTest {
    private fun ratio(text: Color, background: Color): Double {
        val a = text.compositeOver(background).luminance() + .05; val b = background.luminance() + .05
        return (maxOf(a, b) / minOf(a, b)).toDouble()
    }
    private fun aa(what: String, text: Color, background: Color) =
        ratio(text, background).let { assertTrue(it >= 4.5, "$what: ${"%.2f".format(it)}:1") }

    private val card = GlassColors.CardTop.compositeOver(GlassColors.Base)
    private val modes = listOf(GlassColors.MovieRecap, GlassColors.MusicVideo, GlassColors.Subtitles, GlassColors.Voice, GlassColors.Dubbing)

    @Test fun primaryButtonsKeepWhiteTextAa() {
        aa("white on primary start", Color.White, GlassColors.PrimaryStart)
        aa("white on primary end", Color.White, GlassColors.PrimaryEnd)
    }

    @Test fun textStaysAaOnTheLightestTint() = modes.forEach { mode ->
        val tinted = GlassColors.CardTop.compositeOver(mode.copy(alpha = GlassColors.TintProjects).compositeOver(GlassColors.Base))
        aa("Text on $mode .25", AutoFlowColors.Text, tinted)
        aa("PrimaryText on $mode .25", AutoFlowColors.PrimaryText, card)
    }

    @Test fun mutedAndToneTextStayAaOnPlainGlass() {
        aa("Muted on a glass card", AutoFlowColors.Muted, card)
        aa("Muted on the flat row", AutoFlowColors.Muted, GlassColors.FlatFill.compositeOver(GlassColors.Base))
        aa("Failed on the danger button", AutoFlowColors.Failed, GlassColors.DangerFill.compositeOver(card))
        aa("Text on a secondary button", AutoFlowColors.Text, GlassColors.SecondaryFill.compositeOver(card))
        aa("SuccessText on a glass card", AutoFlowColors.SuccessText, card)
    }
}
