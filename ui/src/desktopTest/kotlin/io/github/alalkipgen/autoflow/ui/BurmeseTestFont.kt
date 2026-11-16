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

/** Every laid-out text on screen: inside the window, not truncated, no Burmese line break inside a syllable. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
fun androidx.compose.ui.test.ComposeUiTest.textFitProblems(width: Int, font: Float): List<String> {
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
            else -> null
        }?.let { "$width/$font $it" }
    }
}
