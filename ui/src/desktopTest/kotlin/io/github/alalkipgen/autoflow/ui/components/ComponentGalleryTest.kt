package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.projects.label
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** P9-3 gallery (DESIGN.md §9.4): every button kind and chip in every state, EN + MY, font 1.0 / 2.0, phone + desktop. */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class ComponentGalleryTest(val width: Int, val font: Float, val language: UiLanguage) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}dp/{1}/{2}")
        fun cases() = listOf(360 to 1f, 360 to 2f, 1440 to 1f, 1440 to 2f).flatMap { (w, f) ->
            UiLanguage.entries.map { arrayOf<Any>(w, f, it) }
        }
    }

    @Composable private fun Gallery() = Column(Modifier.fillMaxSize().background(AutoFlowColors.Background).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val kinds = listOf(ButtonKind.Primary to UiText.V3_PROJECT_CREATE, ButtonKind.Secondary to UiText.OPEN,
            ButtonKind.Text to UiText.CANCEL, ButtonKind.Flow to UiText.EXPORT, ButtonKind.Danger to UiText.DELETE)
        kinds.forEach { (kind, text) ->
            WrapRow {
                AfButton(tr(text), {}, Modifier.testTag("btn-$kind"), kind = kind)
                AfButton(tr(text), {}, kind = kind, icon = if (kind == ButtonKind.Danger) AfIcons.Regular.Close else AfIcons.Regular.Plus)
                AfButton(tr(text), {}, kind = kind, small = true)
                AfButton(tr(text), {}, kind = kind, enabled = false)
            }
        }
        FilterChipRow(Mode.entries.mapIndexed { i, m -> FilterChipItem(tr(m.label()), i * 3 + 1) }, 0, {})
        WrapRow {
            listOf("9:16", "16:9", "1:1").forEachIndexed { i, r -> AfChip(r, i == 1, {}) }
            AfChip(tr(UiText.RETRY), false, {}, enabled = false, icon = AfIcons.Regular.Redo)
        }
        WrapRow {
            StudioStatus.entries.forEach { StatusChip(it, deltaMs = if (it == StudioStatus.TIMING) 1500L else null) }
        }
        WrapRow { Mode.entries.forEach { ModeChip(it) } }
    }

    @Test fun everyStateFitsAndKeeps48dpTargets() = runDesktopComposeUiTest(width = width, height = if (width >= 600) 900 else if (font > 1f) 2600 else 1500) {
        setContent {
            CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) { Gallery() }
        }
        assertEquals(emptyList(), textFitProblems(width, font, strict = true))
        // Full size, not the shown part: the filter row scrolls, so its last chips are partly hidden at the edge.
        onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach {
            assertTrue(it.size.width >= 48 && it.size.height >= 48, "48 dp target at $width/$font/$language: ${it.size}")
        }
        val dir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "gallery").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "gallery-$width-$font-${language.name.lowercase()}.png"))
        System.getenv("AUTOFLOW_PR_SHOTS_DIR")?.let { if (language == UiLanguage.BURMESE)
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(File(it).apply { mkdirs() }, "gallery-${if (width < 600) "phone-$font" else "desktop-$font"}.png")) }
    }
}

/** Hover, press and keyboard focus change the drawn button; disabled cannot be clicked (DESIGN.md §9.4). */
@OptIn(ExperimentalTestApi::class)
class ButtonStatesTest {
    @Test fun hoverAndFocusDrawAndDisabledDoesNotClick() = runDesktopComposeUiTest(width = 300, height = 200) {
        var clicks = 0
        setContent {
            Column(Modifier.background(AutoFlowColors.Background).padding(20.dp)) {
                AfButton("Open", { clicks++ }, Modifier.testTag("b"))
                AfButton("Off", { clicks++ }, Modifier.testTag("off"), enabled = false)
                Box(Modifier.size(20.dp).testTag("away"))
            }
        }
        fun pixels() = onNodeWithTag("b").captureToImage().toAwtImage().let { img -> IntArray(img.width) { img.getRGB(it, img.height / 2) } }
        val idle = pixels()
        onNodeWithTag("b").performMouseInput { moveTo(center) }
        val hovered = pixels()
        assertNotEquals(idle.toList(), hovered.toList(), "hover lightens the button")
        onNodeWithTag("away").performMouseInput { moveTo(center) }
        assertEquals(idle.toList(), pixels().toList())
        onNodeWithTag("b").performSemanticsAction(SemanticsActions.RequestFocus)
        assertNotEquals(idle.toList(), pixels().toList(), "keyboard focus draws a ring")
        onNodeWithTag("off").assertIsNotEnabled().performMouseInput { click() }
        onNodeWithTag("b").performMouseInput { click() }
        assertEquals(1, clicks)
    }
}
