package io.github.alalkipgen.autoflow.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.TextLayoutResult
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import java.awt.image.BufferedImage

/** The owner's demo frame [name] (Exact UI v4 `lib.js` scene, kept as SVG text) drawn 320 × 200 by Skia into a PNG. */
internal fun glassScenePng(name: String): ByteArray {
    val svg = org.jetbrains.skia.svg.SVGDOM(org.jetbrains.skia.Data.makeFromBytes(
        GlassShotsTest::class.java.getResourceAsStream("/glass-scenes/$name.svg")!!.readBytes()))
    svg.setContainerSize(320f, 200f)
    val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(320, 200)
    svg.render(surface.canvas)
    return surface.makeImageSnapshot().encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes
}

/** The owner's five demo frames. */
internal val GlassScenes = listOf("dune", "kfp", "inter", "lesson", "bagan")

/**
 * Every Muted text on screen against the real pixels around it: a 3 px ring just outside the text's bounds, whose
 * 95th-percentile luminance stands for the lightest background behind the text (WCAG). (ratio, text) per Muted text.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.mutedTextContrast(image: BufferedImage): List<Pair<Double, String>> =
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        .fetchSemanticsNodes().mapNotNull { node ->
            val layout = mutableListOf<TextLayoutResult>().also { node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(it) }
                .firstOrNull() ?: return@mapNotNull null
            if (layout.layoutInput.style.color != AutoFlowColors.Muted) return@mapNotNull null
            val b = node.boundsInRoot
            val x0 = b.left.toInt(); val x1 = b.right.toInt() - 1; val y0 = b.top.toInt(); val y1 = b.bottom.toInt() - 1
            val ring = buildList {
                for (d in 1..3) {
                    for (x in x0..x1) { add(x to y0 - d); add(x to y1 + d) }
                    for (y in y0..y1) { add(x0 - d to y); add(x1 + d to y) }
                }
            }.filter { (x, y) -> x in 0 until image.width && y in 0 until image.height }
                .map { (x, y) -> Color(image.getRGB(x, y)).luminance() }.sorted()
            if (ring.isEmpty()) return@mapNotNull null
            val background = ring[(ring.size * .95).toInt().coerceAtMost(ring.size - 1)]
            (AutoFlowColors.Muted.luminance() + .05) / (background + .05) to layout.layoutInput.text.text
        }
