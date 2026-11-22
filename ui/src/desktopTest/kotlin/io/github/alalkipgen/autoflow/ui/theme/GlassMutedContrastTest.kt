package io.github.alalkipgen.autoflow.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.GlassScenes
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.GlassCard
import io.github.alalkipgen.autoflow.ui.components.glassColour
import io.github.alalkipgen.autoflow.ui.glassScenePng
import io.github.alalkipgen.autoflow.ui.mutedTextContrast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Owner decision for #286: Muted = `#A9B2CB` on every screen. Muted captions on glass cards, measured on the real
 * pixels (360 dp phone, density 2, font 1.0 and 2.0) with the caption where the screens put it — beside the leading
 * tile or thumbnail, under the title. AA = 4.5:1; at font 2.0 a 12.5 sp caption is 25 px, WCAG "large text" (3:1).
 */
@OptIn(ExperimentalTestApi::class)
class GlassMutedContrastTest {
    /** Every card tint at its strongest alpha: the mode colours (Projects .25; tiles, Library, Lexicon are weaker) and the job attention tones (.22). */
    private val tints: List<Pair<Color, Float>> = Mode.entries.map { it.glassColour to GlassColors.TintProjects } +
        listOf(Tone.Warning, Tone.Error, Tone.Info, Tone.Running, Tone.Success, Tone.Primary).map { it.fill to GlassColors.TintJobs }

    /** A card like a Projects row: leading box (40 dp tile or 100 × 62 thumbnail), title, chip-row gap, Muted caption. */
    @Composable
    private fun CaptionCard(font: Float, lead: Boolean, card: @Composable (@Composable () -> Unit) -> Unit) =
        CompositionLocalProvider(LocalDensity provides Density(2f, font), LocalUiFontFamily provides BurmeseTestFont.family) {
            Box(Modifier.fillMaxSize().background(GlassColors.Base).padding(12.dp)) {
                card {
                    val text: @Composable () -> Unit = {
                        Column {
                            AfText("Bagan Vlog", type = AfType.CardTitle)
                            Spacer(Modifier.height(36.dp))
                            AfText("5 h ago · 16:9", color = AutoFlowColors.Muted, type = AfType.Caption)
                        }
                    }
                    if (font > 1f) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Box(Modifier.size(100.dp, if (lead) 62.dp else 40.dp)); text() }
                    else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Box(Modifier.size(if (lead) 100.dp else 40.dp, if (lead) 62.dp else 40.dp)); text() }
                }
            }
        }

    private fun measure(font: Float, lead: Boolean, card: @Composable (@Composable () -> Unit) -> Unit): Double {
        var ratio = 0.0
        runDesktopComposeUiTest(width = 720, height = 640) {
            setContent { CaptionCard(font, lead, card) }
            waitForIdle()
            val found = mutedTextContrast(onAllNodes(isRoot())[0].captureToImage().toAwtImage())
            assertEquals(1, found.size, "one Muted caption")
            ratio = found.single().first
        }
        return ratio
    }

    private fun scene(name: String): ImageBitmap = org.jetbrains.skia.Image.makeFromEncoded(glassScenePng(name)).toComposeImageBitmap()

    @Test fun mutedCaptionIsAaOnPlainAndEveryTintedGlassCard() = listOf(1f, 2f).forEach { font ->
        val plain = measure(font, lead = false) { content -> GlassCard { content() } }
        assertTrue(plain >= 4.5, "plain glass, font $font: ${"%.2f".format(plain)}:1")
        val flat = measure(font, lead = false) { content -> GlassCard(flat = true) { content() } }
        assertTrue(flat >= 4.5, "flat glass, font $font: ${"%.2f".format(flat)}:1")
        tints.forEach { (colour, alpha) ->
            val r = measure(font, lead = false) { content -> GlassCard(tint = colour, tintAlpha = alpha) { content() } }
            assertTrue(r >= 4.5, "tint $colour at $alpha, font $font: ${"%.2f".format(r)}:1")
        }
    }

    /**
     * Thumbnail-tinted cards (the card's own frame, blurred, at .42 — kept as the owner asked): the five demo frames.
     * Large text (font 2.0) must keep 3:1; normal text is reported against 4.5:1 (bright frames stay below — owner
     * decision pending in #286).
     */
    @Test fun mutedCaptionOnThumbnailTintedCards() {
        val rows = GlassScenes.flatMap { name ->
            val art = scene(name)
            listOf(1f, 2f).map { font -> Triple(name, font, measure(font, lead = true) { content -> GlassCard(thumbnail = art) { content() } }) }
        }
        println(rows.joinToString("\n") { (n, f, r) -> "thumbnail $n font $f: ${"%.2f".format(r)}:1" })
        rows.filter { it.second > 1f }.forEach { (n, _, r) -> assertTrue(r >= 3.0, "thumbnail $n, font 2.0 (large text): ${"%.2f".format(r)}:1") }
    }
}
