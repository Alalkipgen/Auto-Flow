package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.ui.test.*
import kotlin.test.assertTrue

/**
 * Assert actual production geometry in every existing matrix cell, without larger windows or click overrides.
 * [editorInMiddle] = the Music Video shot sheet's order (list · sheet · media pane, Group 3 E).
 */
internal fun SemanticsNodeInteractionsProvider.assertProjectPanesAndStickyActions(width: Int, vararg actions: String, editorInMiddle: Boolean = false) {
    val bar = onNodeWithTag("sticky-action-bar").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    for (tag in actions) {
        val rect = onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(rect.width >= 48f && rect.height >= 48f && rect.left >= 0 && rect.right <= width + .5f,
            "$tag must retain a visible 48 dp target inside the window")
        assertTrue(rect.top >= bar.top && rect.bottom <= bar.bottom, "$tag must fit inside its sticky bar")
    }
    if (width < 840) return
    val order = if (editorInMiddle) listOf("project-list", "project-editor", "project-preview") else listOf("project-list", "project-preview", "project-editor")
    val panes = order.map {
        onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    }
    assertTrue(panes[0].right <= panes[1].left && panes[1].right <= panes[2].left,
        "spec order is ${order.joinToString()}; panes cannot overlap")
    panes.forEach { assertTrue(it.left >= 0 && it.right <= width + .5f && it.bottom <= bar.top + .5f,
        "content must reserve sticky-bar space, including the last scrollable row") }
}
