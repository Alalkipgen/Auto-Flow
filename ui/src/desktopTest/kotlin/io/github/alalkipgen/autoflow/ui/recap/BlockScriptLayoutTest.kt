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
class BlockScriptLayoutTest(val width: Int, val font: Float, val language: UiLanguage, val bundled: Boolean) {
    companion object {
        private val fixture by lazy { QaRecapFixture.create(Files.createTempDirectory("uiv3-script-layout").toFile()) }
        @JvmStatic @Parameterized.Parameters(name = "{0}px/{1}/{2}/bundled={3}")
        fun cases() = StudioVoiceLayoutTest.cases()
    }
    @Test fun nativeScriptDialogScrollsToBothFieldsAndVisibleSaveCancelWithoutWriting() = runDesktopComposeUiTest(width = width, height = if (width == 1440) 900 else 760) {
        val before = File(fixture.dir, "project.json").readBytes()
        val fs = JvmFs(); val current = CurrentProject().apply { set(OpenProject(fixture.dir.path, fixture.project)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val model = RecapModel(ProjectSession(fs, ProjectStore(fs), ProjectIndex(fs, File(fixture.dir.parentFile, "index.json").path), current), fs, null, null, scope)
        try {
            setContent { CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                LocalUiLanguage provides language, LocalUiFontFamily provides if (bundled) BurmeseTestFont.family else FontFamily.Default) {
                StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat()) {
                    StudioRecapScreen(model, RecapStep.VOICE, width.toFloat(), {}, RecapNavigation({}, {}, {}))
                }
            } }
            onNodeWithTag("voice-open-0").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("block-script-edit").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("block-script-save").assertIsDisplayed().assertIsEnabled()
            onNodeWithTag("block-script-input").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            onNodeWithContentDescription(UiText.V3_CAPTION_TEXT.text(language)).performTextReplacement("ပြင်ထားသော်လည်း မသိမ်းပါ")
            onNodeWithTag("block-spoken-input").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            onNodeWithContentDescription(UiText.V3_SPOKEN_OVERRIDE.text(language)).performTextReplacement("အသံထွက် မူကြမ်း")
            onNode(hasContentDescription(UiText.V3_MORE_OPTIONS.text(language)) and hasAnyAncestor(hasTestTag("block-script-form"))).performScrollTo().assertIsDisplayed().performMouseInput { click() }
            onNodeWithText(UiText.V3_SPOKEN_HINT.text(language)).performScrollTo().assertIsDisplayed()
            onNode(hasContentDescription(UiText.V3_MORE_OPTIONS.text(language)) and hasAnyAncestor(hasTestTag("block-script-form"))).performScrollTo().performMouseInput { click() }
            onNodeWithContentDescription(UiText.V3_SPOKEN_OVERRIDE.text(language)).performScrollTo().assertIsDisplayed()
            assertEquals(emptyList(), textFitProblems(width, font))
            val dir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "block-script").apply { mkdirs() }
            ImageIO.write(onNode(isRoot() and hasAnyDescendant(hasTestTag("block-script-form"))).captureToImage().toAwtImage(), "png", File(dir, "04-script-$width-${language.code}-$font-$bundled.png"))
            onNodeWithTag("block-script-cancel").assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("block-script-input").assertDoesNotExist()
            onNodeWithTag("block-script-text").assertTextEquals("FIRST")
            assertContentEquals(before, File(fixture.dir, "project.json").readBytes())
        } finally { scope.cancel() }
    }
}
