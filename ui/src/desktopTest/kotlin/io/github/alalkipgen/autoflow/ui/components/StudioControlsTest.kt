package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.recap.QaRecapFixture
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class StudioControlsTest(val width: Int, val font: Float, val language: UiLanguage, val bundled: Boolean) {
    companion object {
        private val fixture by lazy { QaRecapFixture.create(Files.createTempDirectory("uiv3-controls").toFile()) }
        @JvmStatic @Parameterized.Parameters(name = "{0}px/{1}/{2}/bundled={3}")
        fun cases() = listOf(412, 840).flatMap { w -> listOf(1f, 1.3f, 2f).flatMap { f ->
            UiLanguage.entries.flatMap { l -> listOf(true, false).map { b -> arrayOf<Any>(w, f, l, b) } }
        } } + listOf(arrayOf<Any>(360, 2f, UiLanguage.BURMESE, true), arrayOf<Any>(1440, 1f, UiLanguage.ENGLISH, true))
    }
    @Test fun actionsAreVisibleScrollableAndClickable() = runDesktopComposeUiTest(width = width, height = if (width == 1440) 900 else 760) {
        var saved = 0; var classic = 0
        val before = File(fixture.dir, "project.json").readBytes()
        setContent {
            CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                LocalUiLanguage provides language, LocalUiFontFamily provides if (bundled) BurmeseTestFont.family else FontFamily.Default) {
                Column(Modifier.fillMaxSize().background(AutoFlowColors.Background)) {
                    ProjectTopBar(fixture.project.name, 3, tr(UiText.VOICE), {}, options = listOf(
                        StudioAction(tr(UiText.CLASSIC_RECAP), { classic++ }, tag = "classic-action")))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        BlockCard(1, "00:02–00:04", fixture.story.blocks.first().textMy, StudioStatus.FITS, {}, modifier = Modifier.testTag("fixture-block"), onPlay = {})
                        OptionCard(tr(UiText.STYLE_PLAIN), tr(UiText.V3_PLAIN_HINT), true, {}, recommended = true)
                        JobCard(fixture.project.name, StudioStatus.PAUSED, UiText.JOB_PAUSED.text(language))
                        PlannedTile(tr(UiText.VOICE_TAB_CLONE), tr(UiText.V3_PLANNED))
                        SectionCard(tr(UiText.V3_MORE_OPTIONS)) { AfText(tr(UiText.V3_UNSAVED), Modifier.testTag("last-row")) }
                    }
                    StickyActionBar(StudioAction(tr(UiText.SAVE), { saved++ }, tag = "primary-action"),
                        StudioAction(tr(UiText.CANCEL), {}, tag = "secondary-action"))
                }
            }
        }
        repeat(5) { onNodeWithTag("progress-segment-$it", useUnmergedTree = true).assertExists() }
        onNodeWithTag("status-SAVED").assertDoesNotExist()
        onNodeWithTag("primary-action").assertIsDisplayed().performMouseInput { click() }
        assertEquals(1, saved)
        onNodeWithTag("project-overflow").assertIsDisplayed().performMouseInput { click() }
        onNodeWithTag("classic-action").assertIsDisplayed().performMouseInput { click() }
        assertEquals(1, classic)
        onNodeWithTag("last-row").performScrollTo().assertIsDisplayed()
        val last = onNodeWithTag("last-row").fetchSemanticsNode().boundsInRoot
        val bar = onNodeWithTag("sticky-action-bar").fetchSemanticsNode().boundsInRoot
        assertTrue(last.bottom <= bar.top + .5f, "last row hidden by the sticky bar")
        assertTrue(onNodeWithTag("primary-action").fetchSemanticsNode().boundsInRoot.height >= 48)
        assertEquals(emptyList(), textFitProblems(width, font))
        assertContentEquals(before, File(fixture.dir, "project.json").readBytes(), "browsing UI must not save")
        onNodeWithTag("fixture-block").performScrollTo().assertIsDisplayed()
        val dir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "components").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "${if (width == 1440) "11" else "03"}-components-$width-${language.code}-$font-$bundled.png"))
    }
}

class StudioStatusTest {
    @Test fun vocabularyAndTimingAreCentralAndReal() {
        assertEquals("Fits", StudioStatus.FITS.label(UiLanguage.ENGLISH))
        assertEquals("+1.8 s too long", StudioStatus.TIMING.label(UiLanguage.ENGLISH, 1_800))
        assertEquals("−0.6 s short", StudioStatus.TIMING.label(UiLanguage.ENGLISH, -600))
        assertFailsWith<IllegalArgumentException> { StudioStatus.TIMING.label(UiLanguage.ENGLISH) }
        StudioStatus.entries.forEach { s ->
            val value = s.label(UiLanguage.BURMESE, 600)
            assertTrue(value.any { it in '\u1000'..'\u109F' }, "$s: $value")
        }
        assertEquals(AutoFlowColors.Failed, StudioStatus.FAILED.color)
        assertEquals(AutoFlowColors.Amber, StudioStatus.PAUSED.color)
        assertEquals(AutoFlowColors.Cyan, StudioStatus.RUNNING.color)
        fun luminance(c: androidx.compose.ui.graphics.Color): Double = listOf(c.red, c.green, c.blue).map {
            if (it <= .04045f) it / 12.92 else Math.pow((it + .055) / 1.055, 2.4)
        }.let { .2126 * it[0] + .7152 * it[1] + .0722 * it[2] }
        assertTrue(1.05 / (luminance(AutoFlowColors.PrimaryFill) + .05) >= 4.5, "white primary text must meet AA")
    }
}


@OptIn(ExperimentalTestApi::class)
class AfChipStateTest {
    @Test fun busyChoiceIsActuallyDisabledNotAnEnabledNoOp() = runDesktopComposeUiTest {
        var changed = 0
        setContent { AfChip("Fit", false, { changed++ }, Modifier.testTag("choice"), enabled = false) }
        onNodeWithTag("choice").assertIsDisplayed().assertIsNotEnabled().performMouseInput { click() }
        assertEquals(0, changed)
    }
}
