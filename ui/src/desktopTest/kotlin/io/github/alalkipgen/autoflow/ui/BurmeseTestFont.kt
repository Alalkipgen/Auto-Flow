package io.github.alalkipgen.autoflow.ui

import androidx.compose.ui.text.font.FontFamily
import io.github.alalkipgen.autoflow.ui.theme.UiFont
import java.io.File
import java.util.Base64

/**
 * The app's built-in UI font (D-49: Pyidaungsu regular + bold from the repo's `fonts/`) for layout tests, so Burmese
 * labels are measured with real glyphs instead of the headless fallback's boxes (5B-13 large-font pass).
 */
object BurmeseTestFont {
    val family: FontFamily by lazy {
        val dir = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "fonts") }
            .first { File(it, "Pyidaungsu-Regular.ttf.b64").isFile }
        val bytes = listOf("Pyidaungsu-Regular.ttf", "Pyidaungsu-Bold.ttf").associate { name ->
            "/fonts/$name" to Base64.getMimeDecoder().decode(File(dir, "$name.b64").readBytes())
        }
        UiFont.load(bytes.keys.toList()) { bytes[it] } ?: error("Pyidaungsu did not load from $dir")
    }
}

/**
 * DESIGN.md §7 Accessibility: Burmese text wraps, never cut inside a syllable. A line may not start with a dependent
 * mark (vowel sign, medial, tone, asat), right after a stacking virama, or with a final consonant (its marks carry
 * asat or a virama). Returns the offsets of the bad line starts.
 */
fun syllableCuts(layout: androidx.compose.ui.text.TextLayoutResult): List<Int> {
    val text = layout.layoutInput.text.text
    fun mark(c: Char) = c in '\u1000'..'\u109F' && (c.category == CharCategory.NON_SPACING_MARK || c.category == CharCategory.COMBINING_SPACING_MARK)
    fun finalConsonant(o: Int): Boolean {
        if (text[o] !in '\u1000'..'\u102A') return false
        var j = o + 1
        while (j < text.length && mark(text[j])) {
            if (text[j] == '\u103A' || text[j] == '\u1039') return true
            if (text[j] in '\u102B'..'\u1035') return false // a vowel: the asat is part of it (ပေါ်)
            j++
        }
        return false
    }
    return (1 until layout.lineCount).map { layout.getLineStart(it) }.filter { o ->
        o in 1 until text.length && (mark(text[o]) || text[o - 1] == '\u1039' || finalConsonant(o))
    }
}

/**
 * Every laid-out text on screen: inside the window, not truncated, no Burmese line break inside a syllable. With
 * [strict] also not clipped by its own box or a parent (Phase 9 no-clipping rule, D-100). Strict is opt-in: each
 * Phase 9 PR turns it on for the screens it polishes (older screens still draw emoji glyphs wider than their box).
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
fun androidx.compose.ui.test.ComposeUiTest.textFitProblems(width: Int, font: Float, strict: Boolean = false): List<String> {
    val nodes = onAllNodes(
        androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult),
        useUnmergedTree = true,
    ).fetchSemanticsNodes()
    check(nodes.size > 5) { "found only ${nodes.size} text nodes" }
    return nodes.mapNotNull { node ->
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.firstOrNull() ?: return@mapNotNull null
        val text = layout.layoutInput.text.text
        val b = node.boundsInRoot
        when {
            syllableCuts(layout).isNotEmpty() -> "cut inside a syllable at ${syllableCuts(layout)}: $text"
            layout.multiParagraph.didExceedMaxLines -> "truncated: $text"
            b.width > 0f && (b.left < -0.5f || b.right > width + 0.5f) -> "outside the window ($b): $text"
            strict -> clipProblem(node, layout)
            else -> null
        }?.let { "$width/$font $it" }
    }
}

/**
 * DESIGN.md §9.6: a text drawn bigger than its own box (`hasVisualOverflow`), or cut by a parent's clip — a fixed
 * height, a clipped card. Only a scroll viewport or the window edge may hide part of a text.
 */
private fun clipProblem(node: androidx.compose.ui.semantics.SemanticsNode, layout: androidx.compose.ui.text.TextLayoutResult): String? {
    val text = layout.layoutInput.text.text
    // An empty field measures with the fallback font's metrics, not the field's; there is no ink to cut.
    if (text.isBlank()) return null
    // hasVisualOverflow also flags sub-pixel rounding and a layout width wider than the drawn lines; measure the ink.
    val paragraph = layout.multiParagraph
    val left = (0 until layout.lineCount).minOfOrNull { layout.getLineLeft(it) } ?: 0f
    val right = (0 until layout.lineCount).maxOfOrNull { layout.getLineRight(it) } ?: 0f
    if (!paragraph.didExceedMaxLines && (paragraph.height - layout.size.height > 1f || left < -1f || right - layout.size.width > 1f))
        return "overflows its box (lines $left..$right x ${paragraph.height} in ${layout.size}): $text"
    val own = androidx.compose.ui.geometry.Rect(node.positionInRoot, androidx.compose.ui.geometry.Size(node.size.width.toFloat(), node.size.height.toFloat()))
    val shown = node.boundsInRoot
    if (shown.width <= 0f || shown.height <= 0f) return null // scrolled out of view
    val ancestors = generateSequence(node.parent) { it.parent }.toList()
    val root = (ancestors.lastOrNull() ?: node).boundsInRoot
    fun viewports(key: androidx.compose.ui.semantics.SemanticsPropertyKey<*>) =
        ancestors.filter { key in it.config }.map { it.boundsInRoot } + root
    val vertical = viewports(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)
    val horizontal = viewports(androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange)
    fun near(a: Float, b: Float) = kotlin.math.abs(a - b) < 0.5f
    val cut = listOfNotNull(
        "top".takeIf { shown.top > own.top + .5f && vertical.none { near(it.top, shown.top) } },
        "bottom".takeIf { shown.bottom < own.bottom - .5f && vertical.none { near(it.bottom, shown.bottom) } },
        "left".takeIf { shown.left > own.left + .5f && horizontal.none { near(it.left, shown.left) } },
        "right".takeIf { shown.right < own.right - .5f && horizontal.none { near(it.right, shown.right) } },
    )
    return if (cut.isEmpty()) null else "clipped by a parent at the $cut ($shown of $own): $text"
}
