package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
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
class BlockClipsLayoutTest(val width: Int, val font: Float, val language: UiLanguage, val bundled: Boolean) {
    companion object {
        private val fixture by lazy { QaRecapFixture.create(Files.createTempDirectory("uiv3-clip-layout").toFile()) }
        @JvmStatic @Parameterized.Parameters(name = "{0}px/{1}/{2}/bundled={3}") fun cases() = StudioVoiceLayoutTest.cases() +
            listOf(arrayOf<Any>(1440, 1f, UiLanguage.BURMESE, true))
    }
    @Test fun realDialogScrollClicksAndBackReachRangeOperationsAndCancelWithoutWrites() = runDesktopComposeUiTest(width = width, height = if (width == 1440) 900 else 760) {
        val before = File(fixture.dir, "project.json").readBytes()
        val fs = JvmFs(); val current = CurrentProject().apply { set(OpenProject(fixture.dir.path, fixture.project)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val model = RecapModel(ProjectSession(fs, ProjectStore(fs), ProjectIndex(fs, File(fixture.dir.parentFile, "index.json").path), current), fs, null, null, scope)
        // Off-screen node keys bypass the desktop host's Escape -> navigation input bridge.
        // Send a platform-style Back event through the real Dialog's registered handler instead.
        val back = object : NavigationEventInput() { fun press() = dispatchOnBackCompleted() }
        try {
            setContent { CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                LocalUiLanguage provides language, LocalUiFontFamily provides if (bundled) BurmeseTestFont.family else FontFamily.Default) {
                val dispatcher = requireNotNull(LocalNavigationEventDispatcherOwner.current).navigationEventDispatcher
                DisposableEffect(dispatcher) { dispatcher.addInput(back); onDispose { dispatcher.removeInput(back) } }
                StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat()) {
                    StudioRecapScreen(model, RecapStep.VOICE, width.toFloat(), {}, RecapNavigation({}, {}, {}))
                }
            } }
            fun click(tag: String) = onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performMouseInput { click() }
            fun options(i: Int) = onNode(hasContentDescription(UiText.V3_MORE_OPTIONS.text(language)) and hasAnyAncestor(hasTestTag("clip-range-$i")))
            fun expand(i: Int) {
                val node = options(i).performScrollTo().assertIsDisplayed()
                if (node.fetchSemanticsNode().config[SemanticsProperties.StateDescription] == UiText.V3_COLLAPSED.text(language))
                    node.performMouseInput { click() }
            }
            fun field(key: UiText, i: Int) = onNode(hasContentDescription(key.text(language)) and hasAnyAncestor(hasTestTag("clip-range-$i")))
            click("voice-open-0"); click("block-clips-edit")
            onNodeWithTag("block-clips-save").assertIsDisplayed().assertIsEnabled()
            click("clip-from-0"); field(UiText.V3_CLIP_FROM, 0).performTextReplacement("2.500")
            click("clip-to-0"); field(UiText.V3_CLIP_TO, 0).performTextReplacement("3.500")
            val dir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "block-clips").apply { mkdirs() }
            fun capture(state: String) = ImageIO.write(onNode(isRoot() and hasAnyDescendant(hasTestTag("block-clips-form"))).captureToImage().toAwtImage(),
                "png", File(dir, "04-cuts-$state-$width-${language.code}-$font-$bundled.png"))
            assertEquals(emptyList(), textFitProblems(width, font)); capture("fields")
            expand(0)
            onNodeWithTag("clip-merge-0").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
            onNodeWithTag("clip-earlier-0").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
            click("clip-split-0")
            onNodeWithTag("clip-range-2").assertExists()
            assertEquals(2, model.blockEditor.state.value.draft!!.block.clips.size) // Captured original, not a premature save.
            expand(0)
            click("clip-merge-0")
            expand(1)
            click("clip-earlier-1")
            expand(0)
            onNodeWithTag("clip-remove-0").performScrollTo().assertIsDisplayed()
            assertEquals(emptyList(), textFitProblems(width, font)); capture("tools")
            click("clip-add"); onNodeWithTag("block-clips-save").assertIsDisplayed().assertIsNotEnabled()
            onNodeWithTag("block-clips-discard").assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("block-clips-form").assertDoesNotExist()
            click("block-clips-edit"); click("clip-from-0")
            field(UiText.V3_CLIP_FROM, 0).performTextReplacement("1.000")
            runOnIdle { back.press() }
            onNodeWithTag("block-clips-form").assertDoesNotExist()
            assertNull(model.blockEditor.state.value.draft)
            click("block-clips-edit"); click("clip-from-0")
            field(UiText.V3_CLIP_FROM, 0).performTextReplacement("1.500")
            onNode(isRoot() and hasAnyDescendant(hasTestTag("block-clips-form"))).performMouseInput { click(Offset.Zero) }
            onNodeWithTag("block-clips-form").assertDoesNotExist()
            assertNull(model.blockEditor.state.value.draft)
            assertContentEquals(before, File(fixture.dir, "project.json").readBytes())
        } finally { scope.cancel() }
    }
}