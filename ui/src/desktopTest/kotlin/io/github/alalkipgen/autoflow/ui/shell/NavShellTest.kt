package io.github.alalkipgen.autoflow.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** P9-2 (DESIGN.md §9.3, §9.8): bottom nav, rail and sidebar fit in Burmese, keep the badge rule and real actions only. */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class NavShellTest(val width: Int, val font: Float) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}dp/{1}")
        fun cases() = listOf(360 to 1f, 360 to 2f, 412 to 2f, 600 to 2f, 840 to 1.3f, 1200 to 2f, 1440 to 1f, 1440 to 2f)
            .map { (w, f) -> arrayOf<Any>(w, f) }
    }

    @Test fun navFitsKeepsTheBadgeRuleAndShowsOnlyRealActions() = runDesktopComposeUiTest(width = width, height = if (width >= 1200) 900 else 760) {
        var badge by mutableStateOf(-3); var selected by mutableStateOf(StudioV2Dest.HOME)
        var created = 0; var settings = 0; var recap = 0
        val language = UiLanguage.BURMESE
        setContent {
            CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) {
                StudioV2Shell(selected, { selected = it }, badge, width.toFloat(), actions = ShellActions(
                    onNewProject = { created++ }, onOpenSettings = { settings++ },
                    create = listOf(ShellShortcut(UiText.MOVIE_RECAP, AfIcons.Duotone.Film, AutoFlowColors.PrimaryFill, "nav-create-recap") { recap++ }),
                )) {
                    // Some screen text, so the fit check sees a whole screen, not only the nav.
                    Column(Modifier.fillMaxSize()) { listOf(UiText.HOME_RECENT, UiText.CREATE, UiText.MOVIE_RECAP, UiText.MUSIC_VIDEO, UiText.V3_SUBTITLES).forEach { AfText(tr(it)) } }
                }
            }
        }
        val style = navStyle(width.toFloat(), editor = false)
        fun tab(dest: StudioV2Dest) = onNode(hasContentDescription(dest.textKey.text(language)) and isSelectable())
        StudioV2Dest.entries.forEach { dest ->
            val b = tab(dest).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            // Sidebar rows are 42 dp (desktop pointer, DESIGN.md §9.8); every other nav target is 48 dp.
            val min = if (style == NavStyle.SIDEBAR) 42f else 48f
            assertTrue(b.height >= min && b.width >= 48f, "$dest is a $min dp target at $width/$font: $b")
            assertTrue(b.left >= -.5f && b.right <= width + .5f, "$dest inside the window: $b")
        }
        onNodeWithTag("jobs-badge", useUnmergedTree = true).assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "${UiText.V3_JOBS_NEEDS_YOU.text(language)} 3"))
        assertEquals(emptyList(), textFitProblems(width, font, strict = true))
        val dir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "nav").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "nav-${style.name.lowercase()}-$width-$font.png"))

        tab(StudioV2Dest.JOBS).performMouseInput { click() }
        assertEquals(StudioV2Dest.JOBS, selected); tab(StudioV2Dest.JOBS).assertIsSelected()
        badge = 12; waitForIdle()
        onNode(hasText("9+") and hasAnyAncestor(hasTestTag("jobs-badge")), useUnmergedTree = true).assertExists()
        badge = 0; waitForIdle()
        onNodeWithTag("jobs-badge", useUnmergedTree = true).assertDoesNotExist()

        when (style) {
            NavStyle.BOTTOM -> listOf("nav-settings", "nav-new-project", "nav-create-recap").forEach { onNodeWithTag(it).assertDoesNotExist() }
            NavStyle.RAIL -> {
                onNodeWithTag("nav-new-project").assertDoesNotExist()
                onNodeWithTag("nav-settings").assertIsDisplayed().performMouseInput { click() }
                assertEquals(1, settings)
                // Icon-only items name themselves in a tooltip while the mouse rests on them; leaving hides it.
                val home = UiText.HOME.text(language)
                tab(StudioV2Dest.HOME).performMouseInput { moveTo(center) }
                onNodeWithText(home).assertIsDisplayed()
                tab(StudioV2Dest.HOME).performMouseInput { moveTo(center + Offset(250f, 300f)) } // into the content, inside the window
                onNodeWithText(home).assertDoesNotExist()
            }
            NavStyle.SIDEBAR -> {
                listOf("nav-new-project" to { created }, "nav-create-recap" to { recap }, "nav-settings" to { settings }).forEach { (tag, count) ->
                    onNodeWithTag(tag).apply { if (tag != "nav-settings") performScrollTo() }.assertIsDisplayed().performMouseInput { click() }
                    assertEquals(1, count(), tag)
                }
            }
        }
    }
}

class NavStyleTest {
    @Test fun widthClassesAndEditorsPickTheNav() {
        assertEquals(NavStyle.BOTTOM, navStyle(599f, editor = true))
        assertEquals(NavStyle.RAIL, navStyle(600f, editor = false))
        assertEquals(NavStyle.RAIL, navStyle(1199f, editor = false))
        assertEquals(NavStyle.SIDEBAR, navStyle(1200f, editor = false))
        assertEquals(NavStyle.RAIL, navStyle(1920f, editor = true))
    }
}
