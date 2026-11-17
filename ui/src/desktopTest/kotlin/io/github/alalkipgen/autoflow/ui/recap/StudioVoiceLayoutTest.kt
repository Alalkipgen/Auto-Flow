package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.*
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class StudioVoiceLayoutTest(val width: Int, val font: Float, val language: UiLanguage, val bundled: Boolean) {
    companion object {
        private val fixture by lazy { QaRecapFixture.create(Files.createTempDirectory("uiv3-voice").toFile()) }
        @JvmStatic @Parameterized.Parameters(name = "{0}px/{1}/{2}/bundled={3}")
        fun cases() = listOf(412, 840).flatMap { w -> listOf(1f, 1.3f, 2f).flatMap { f ->
            UiLanguage.entries.flatMap { l -> listOf(true, false).map { b -> arrayOf<Any>(w, f, l, b) } }
        } } + listOf(arrayOf<Any>(360, 2f, UiLanguage.BURMESE, true), arrayOf<Any>(1440, 1f, UiLanguage.ENGLISH, true), arrayOf<Any>(1440, 1f, UiLanguage.BURMESE, true))
    }
    @Test fun voiceListRealFiltersScrollingDetailBackAndPrimaryActions() = runDesktopComposeUiTest(width = width, height = if (width == 1440) 900 else 760) {
        val before = File(fixture.dir, "project.json").readBytes()
        val fs = JvmFs(); val current = CurrentProject().apply { set(OpenProject(fixture.dir.path, fixture.project)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val model = RecapModel(ProjectSession(fs, ProjectStore(fs), ProjectIndex(fs,
            File(fixture.dir.parentFile, "index-$width-$font-${language.code}-$bundled.json").path), current), fs, null, null, scope)
        var back = 0; var next = 0; var classic = 0
        val navigation = RecapNavigation({ back++ }, { next++ }, { classic++ })
        try {
            setContent {
                CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                    LocalUiLanguage provides language, LocalUiFontFamily provides if (bundled) BurmeseTestFont.family else FontFamily.Default) {
                    StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat()) {
                        StudioRecapScreen(model, RecapStep.VOICE, width.toFloat(), {}, navigation)
                    }
                }
            }
            assertProjectPanesAndStickyActions(width, "voice-remaining", "recap-next")
            onNodeWithTag("voice-remaining").assertIsDisplayed().assertIsNotEnabled()
            onNodeWithText(UiText.V3_ATTENTION_COUNT.text(language, "n" to 1)).performScrollTo().assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("block-select-0").assertExists()
            onNodeWithTag("block-select-1").assertDoesNotExist()
            onNodeWithText(UiText.V3_VOICED_COUNT.text(language, "n" to 2)).performScrollTo().assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("block-select-0").performScrollTo().assertIsDisplayed()
            assertEquals(emptyList(), textFitProblems(width, font))
            onNodeWithTag("change-voice").performScrollTo().assertIsDisplayed()
            onNodeWithTag("voice-content").performMouseInput { scroll(-20_000f) }
            waitForIdle()
            val dir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "voice").apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "03-voice-$width-${language.code}-$font-$bundled.png"))
            onNodeWithTag("voice-fit-0").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            onNodeWithText(UiText.FIT_SPEED.text(language)).performScrollTo().assertIsDisplayed()
            onNodeWithTag("project-back").assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("voice-open-0").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("project-back").assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("voice-remaining").assertIsDisplayed()
            assertEquals(0, back, "detail Back returns to the list before the previous step")
            onNodeWithTag("recap-next").assertIsDisplayed().performMouseInput { click() }; assertEquals(1, next)
            onNodeWithTag("project-back").assertIsDisplayed().performMouseInput { click() }; assertEquals(1, back)
            onNodeWithTag("project-overflow").assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("classic-recap").assertIsDisplayed().performMouseInput { click() }; assertEquals(1, classic)
            assertContentEquals(before, File(fixture.dir, "project.json").readBytes())
        } finally { scope.cancel() }
    }
}