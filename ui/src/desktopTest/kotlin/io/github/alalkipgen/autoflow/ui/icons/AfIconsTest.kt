package io.github.alalkipgen.autoflow.ui.icons

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P9-2a (DESIGN.md §9.3): the generated Phosphor vectors build, tiles draw them, tooltips follow the mouse.
 *  P9-11b adds Search, Volume, Scissors and Image to the generated set; the glass PR adds Box (Phosphor `package`); the
 *  Projects dialogs PR adds Trash, Drive (`hard-drives`), Archive, Info and FolderMinus (`folder-minus`); Settings (P9-17a)
 *  adds Shield, CheckCircle, Share, Globe, Key, CaretDown, Cpu, Wrench and Flask; Group 2 PR 1 adds MonitorPlay, CaretUp, ClockCounterClockwise, ClockClockwise, CornersOut, CornersIn, Swap and Crosshair; PR 3 adds FilmStrip. */
@OptIn(ExperimentalTestApi::class)
class AfIconsTest {
    @Test fun everyGeneratedIconBuildsInAllThreeWeights() {
        val all = with(AfIcons.Regular) { listOf(Home, Folder, Book, Jobs, Gear, Spark, Plus, Film, Music, Dub, Wave, Subtitles, Search, Volume, Scissors, Image, Box, Trash, Drive, Archive, Info, FolderMinus, Shield, CheckCircle, Share, Globe, Key, CaretDown, Cpu, Wrench, Flask, MonitorPlay, CaretUp, ClockCounterClockwise, ClockClockwise, CornersOut, CornersIn, Swap, Crosshair, FilmStrip) } +
            with(AfIcons.Fill) { listOf(Home, Folder, Book, Jobs, Gear, Spark, Plus, Film, Music, Dub, Wave, Subtitles, Search, Volume, Scissors, Image, Box, Trash, Drive, Archive, Info, FolderMinus, Shield, CheckCircle, Share, Globe, Key, CaretDown, Cpu, Wrench, Flask, MonitorPlay, CaretUp, ClockCounterClockwise, ClockClockwise, CornersOut, CornersIn, Swap, Crosshair, FilmStrip) } +
            with(AfIcons.Duotone) { listOf(Home, Folder, Book, Jobs, Gear, Spark, Plus, Film, Music, Dub, Wave, Subtitles, Search, Volume, Scissors, Image, Box, Trash, Drive, Archive, Info, FolderMinus, Shield, CheckCircle, Share, Globe, Key, CaretDown, Cpu, Wrench, Flask, MonitorPlay, CaretUp, ClockCounterClockwise, ClockClockwise, CornersOut, CornersIn, Swap, Crosshair, FilmStrip) }
        all.forEach { assertEquals(256f, it.viewportWidth, it.name); assertTrue(it.root.size > 0, it.name) }
        assertEquals(all.size, all.map { it.name }.toSet().size)
    }

    @Test fun tooltipShowsOnHoverAndHidesOnExitAndPress() = runDesktopComposeUiTest(width = 400, height = 300) {
        setContent {
            TooltipHost(Modifier.fillMaxSize()) {
                Row {
                    Box(Modifier.size(48.dp).testTag("a").afTooltip("Home tip")) { AfIcon(AfIcons.Regular.Home, contentDescription = "Home") }
                    IconTile(AfIcons.Duotone.Film, AutoFlowColors.PrimaryFill, Modifier.testTag("tile"))
                }
            }
        }
        onNodeWithText("Home tip").assertDoesNotExist()
        onNodeWithTag("a").performMouseInput { moveTo(center) }
        val tip = onNodeWithText("Home tip").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val anchor = onNodeWithTag("a").fetchSemanticsNode().boundsInRoot
        assertTrue(tip.left >= anchor.right, "the bubble sits right of its anchor: $tip / $anchor")
        onNodeWithTag("a").performMouseInput { moveTo(center + Offset(200f, 150f)) }
        onNodeWithText("Home tip").assertDoesNotExist()
        onNodeWithTag("a").performMouseInput { moveTo(center); press(); release() }
        onNodeWithText("Home tip").assertDoesNotExist()
        onNodeWithContentDescription("Home").assertExists()
        assertEquals(44f, onNodeWithTag("tile").fetchSemanticsNode().size.width.toFloat())
    }

    @Test fun withoutAHostTheModifierDoesNothing() = runDesktopComposeUiTest(width = 200, height = 200) {
        setContent { Box(Modifier.size(48.dp).testTag("a").afTooltip("tip")) }
        onNodeWithTag("a").performMouseInput { moveTo(center) }
        onNodeWithText("tip").assertDoesNotExist()
    }
}
