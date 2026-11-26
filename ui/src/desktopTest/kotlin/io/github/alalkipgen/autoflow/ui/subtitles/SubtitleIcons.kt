package io.github.alalkipgen.autoflow.ui.subtitles

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import kotlin.test.*

/** The labels whose leading glyph P9-12d replaced with an icon ("📄 Import subtitles", "+ Add a name"). */
internal val SUBTITLE_ICON_LABELS = listOf(UiText.IMPORT_SUBTITLES, UiText.SUB_NAME_ADD)

class SubtitleIconLabelsTest {
    @Test fun iconLabelsStartWithAWordInBothLanguages() = UiLanguage.entries.forEach { language ->
        SUBTITLE_ICON_LABELS.forEach { key ->
            val text = key.text(language)
            assertTrue(text.first().isLetterOrDigit() && text == text.trim(), "$key/${language.code} starts with a glyph: $text")
        }
    }
}

/**
 * P9-12d: the button tagged [tag] shows [key]'s label only (no glyph) with one 18 dp icon before it, in a ≥ 48 dp
 * target ([iconDp] 16 for a small glass button). [scroll] = false for the sticky bar, which does not scroll.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.assertIconButton(tag: String, key: UiText, language: UiLanguage, scroll: Boolean = true, iconDp: Float = 18f) {
    val label = My.breakSyllables(key.text(language))
    val node = onNodeWithTag(tag).apply { if (scroll) performScrollTo() }.assertIsDisplayed().fetchSemanticsNode()
    assertEquals(listOf(label), node.config[SemanticsProperties.Text].map { it.text }, "$tag: the label only")
    val b = node.boundsInRoot
    assertTrue(b.width >= 48f && b.height >= 48f, "$tag: 48 dp target $b")
    val icons = onAllNodes(hasTestTag("button-icon") and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).fetchSemanticsNodes()
    assertEquals(1, icons.size, "$tag: one icon")
    val icon = icons.single().boundsInRoot
    assertEquals(iconDp, icon.width, .5f, "$tag: $iconDp dp icon")
    val text = onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    assertTrue(icon.right <= text.left, "$tag: the icon before the label")
}

/** P9-12d: the icon-only button tagged [tag] — no text, [key]'s label as its description (and tooltip), a 48 dp target. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.assertIconOnly(tag: String, key: UiText, language: UiLanguage) {
    val node = onNodeWithTag(tag).performScrollTo().assertIsDisplayed().fetchSemanticsNode()
    assertEquals(listOf(key.text(language)), node.config[SemanticsProperties.ContentDescription], tag)
    assertFalse(SemanticsProperties.Text in node.config, "$tag: no text glyph")
    assertTrue(node.boundsInRoot.width >= 48f && node.boundsInRoot.height >= 48f, "$tag: 48 dp target ${node.boundsInRoot}")
}

/** P9-12d: the card tagged [tag] has one 18 dp icon (or [iconDp] tile, Group 5) right before its [title]. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.assertCardIcon(tag: String, title: String, iconDp: Float = 18f) {
    val titles = onAllNodes(hasText(title) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
    runCatching { titles[0].performScrollTo() } // a lazy list item can be composed ahead, not yet placed
    val t = titles.fetchSemanticsNodes().map { it.boundsInRoot }.first { it.width > 0f }
    val icons = onAllNodes(hasTestTag("card-icon") and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).fetchSemanticsNodes()
        .map { it.boundsInRoot }.filter { it.right <= t.left && t.left - it.right <= 12f && it.top < t.bottom && t.top < it.bottom }
    assertEquals(1, icons.size, "$tag: one icon before the title $t")
    assertEquals(iconDp, icons.single().width, .5f, "$tag: $iconDp dp icon")
}
