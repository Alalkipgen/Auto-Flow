package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P9-6 (DESIGN.md §9.8): every width 360 → 1920 at font 1.0 / 2.0 — the right nav, content beside it, nothing cut. */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class AdaptiveShellTest(val width: Int, val font: Float) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}dp/{1}")
        fun cases() = listOf(360, 600, 760, 840, 1024, 1199, 1200, 1440, 1920).flatMap { w -> listOf(1f, 2f).map { arrayOf<Any>(w, it) } }
    }

    @Test fun navAndContentNeverOverlapOrClip() = runDesktopComposeUiTest(width = width, height = 760) {
        val language = UiLanguage.BURMESE
        setContent {
            CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) {
                StudioV2Shell(StudioV2Dest.PROJECTS, {}, 2, width.toFloat()) {
                    Column(Modifier.fillMaxSize().paneScroll(rememberScrollState()).testTag("g-content")) {
                        repeat(6) { listOf(UiText.HOME_RECENT, UiText.CREATE, UiText.MOVIE_RECAP, UiText.MUSIC_VIDEO, UiText.V3_SUBTITLES).forEach { AfText(tr(it)) } }
                    }
                }
            }
        }
        assertEquals(emptyList(), textFitProblems(width, font, strict = true))
        val content = onNodeWithTag("g-content").fetchSemanticsNode().boundsInRoot
        val tab = onNode(hasContentDescription(StudioV2Dest.HOME.textKey.text(language)) and isSelectable()).fetchSemanticsNode().boundsInRoot
        when (navStyle(width.toFloat(), editor = false)) {
            NavStyle.BOTTOM -> assertTrue(content.bottom <= tab.top + .5f && content.width == width.toFloat(), "$content above $tab")
            else -> assertTrue(content.left >= tab.right - .5f && content.right <= width + .5f, "$content right of $tab")
        }
        assertEquals(width < 1200 && width >= 600, navStyle(width.toFloat(), false) == NavStyle.RAIL)
        // Medium: the phone layout, at most 640 dp wide, centred in the room right of the rail.
        if (widthClass(width.toFloat()) == WidthClass.MEDIUM) {
            val room = width - 72f
            assertTrue(content.width <= 640.5f && kotlin.math.abs(content.left - 72f - (room - content.width) / 2) <= 1f, "$content centred")
        } else assertEquals(0, onAllNodesWithTag("medium-content").fetchSemanticsNodes().size)
    }
}

/** P9-6 panes, scrollbar and right-click (DESIGN.md §9.8). */
@OptIn(ExperimentalTestApi::class)
class AdaptivePanesTest {
    @Test fun listDetailAndEditorPanesFollowTheWidthClass() {
        for (width in listOf(360, 840, 1440)) runDesktopComposeUiTest(width = width, height = 700) {
            var detail by mutableStateOf(false)
            setContent {
                Column {
                    Box(Modifier.height(300.dp)) { ListDetailPane(width.toFloat(), detail, { AfText("LIST") }, { AfText("DETAIL") }) }
                    Box(Modifier.height(300.dp)) { EditorPanes(width.toFloat(), { AfText("MAIN") }, { AfText("SIDE") }) }
                }
            }
            if (width >= 840) {
                val list = onNodeWithTag("list-detail-list").fetchSemanticsNode().boundsInRoot
                val pane = onNodeWithTag("list-detail-detail").fetchSemanticsNode().boundsInRoot
                assertTrue(list.width in 420f..480f && list.right + 18f <= pane.left + .5f, "list $list, detail $pane at $width")
                val main = onNodeWithTag("editor-main").fetchSemanticsNode().boundsInRoot
                val side = onNodeWithTag("editor-side").fetchSemanticsNode().boundsInRoot
                assertTrue(main.right <= side.left && side.width <= 460.5f && side.width <= width * .45f + .5f, "main $main, side $side at $width")
            } else {
                onNodeWithText("LIST").assertExists(); onNodeWithText("DETAIL").assertDoesNotExist()
                detail = true; waitForIdle()
                onNodeWithText("DETAIL").assertExists(); onNodeWithText("LIST").assertDoesNotExist()
                val main = onNodeWithTag("editor-main").fetchSemanticsNode().boundsInRoot
                val side = onNodeWithTag("editor-side").fetchSemanticsNode().boundsInRoot
                assertTrue(main.bottom <= side.top && side.width == width.toFloat(), "stacked at $width")
            }
        }
    }

    @Test fun dragTheScrollbarThumbScrollsAndAClickBesideItReachesTheContent() = runDesktopComposeUiTest(width = 400, height = 400) {
        val state = ScrollStateHolder()
        var clicks = 0
        setContent {
            val scroll = rememberScrollState().also { state.value = it }
            Column(Modifier.size(400.dp).paneScroll(scroll).testTag("pane")) {
                AfButton("TOP", { clicks++ }, Modifier.fillMaxWidth().testTag("top"))
                repeat(40) { AfText("row $it") }
            }
        }
        onNodeWithTag("top").performMouseInput { click() }; assertEquals(1, clicks)
        onNodeWithTag("pane").performMouseInput { moveTo(androidx.compose.ui.geometry.Offset(395f, 10f)); press(); moveBy(androidx.compose.ui.geometry.Offset(0f, 120f)); release() }
        waitForIdle()
        assertTrue(state.value!!.value > 0, "dragging the thumb scrolls")
    }

    @Test fun rightClickOnTheProjectTopBarOpensTheOverflowOptions() = runDesktopComposeUiTest(width = 900, height = 400) {
        var classic = 0
        setContent { ProjectTopBar("Film", 2, "Story", {}, options = listOf(StudioAction("Classic", { classic++ }, tag = "opt-classic"))) }
        onNodeWithTag("opt-classic").assertDoesNotExist()
        onNodeWithText("Film").performMouseInput { rightClick() }
        waitForIdle()
        onNodeWithTag("opt-classic").assertExists().performMouseInput { click() }
        assertEquals(1, classic)
    }

    private class ScrollStateHolder { var value: androidx.compose.foundation.ScrollState? = null }
}
