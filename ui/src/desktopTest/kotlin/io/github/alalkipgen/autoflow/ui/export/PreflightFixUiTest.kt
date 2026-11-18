package io.github.alalkipgen.autoflow.ui.export

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.recap.PreflightCheck
import io.github.alalkipgen.autoflow.media.render.Quality
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.recap.*
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

private fun preflightTag(items: List<PreflightCheck.Item>, reason: PreflightCheck.Reason): String =
    "preflight-fix-${reason.name}-${items.indexOfFirst { it.reason == reason }}"

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.fixControl(tag: String): SemanticsNodeInteraction {
    val node = onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    val b = node.getUnclippedBoundsInRoot(); val bar = onNodeWithTag("sticky-action-bar").getUnclippedBoundsInRoot()
    assertTrue(b.top.value >= 0 && b.bottom <= bar.top && (b.bottom - b.top).value >= 48f, "$tag must be uncovered and >=48dp: $b/$bar")
    return node
}

@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class PreflightFixUiTest(val width: Int, val font: Float, val language: UiLanguage, val bundled: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}/{1}/{2}/bundled={3}")
        fun cases() = StudioStoryLayoutTest.cases()
    }

    @Test fun allThreeRealCoreWarningsOpenActualNativeVoiceAndKeepReportGatesAndProjectBytes() =
        runDesktopComposeUiTest(width = width, height = if (width == 1440) 900 else 760) {
            ExportHarness().use { h ->
                var nav by mutableStateOf(V2Nav(dest = StudioV2Dest.PROJECTS, projectScreen = "Export", recapStep = RecapStep.STYLE))
                fun export() { nav = nav.copy(projectScreen = "Export") }
                setContent { CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                    LocalUiLanguage provides language, LocalUiFontFamily provides if (bundled) BurmeseTestFont.family else FontFamily.Default) {
                    StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat()) {
                        if (nav.projectScreen == "Export") StudioExportScreen(h.model, h.recap, width.toFloat(),
                            RecapNavigation({}, {}, {}), onPreflightFix = { nav = v2PreflightFix(nav, it) })
                        else StudioRecapScreen(h.recap, nav.recapStep, width.toFloat(), ::export,
                            RecapNavigation({ nav = v2Back(nav, true)!! }, {}, {}))
                    }
                } }
                fun capture(state: String) {
                    assertEquals(emptyList(), textFitProblems(width, font))
                    val dir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "preflight-fix").apply { mkdirs() }
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$state-$width-${language.code}-$font-$bundled.png"))
                }
                for (reason in listOf(PreflightCheck.Reason.UNVOICED, PreflightCheck.Reason.STALE, PreflightCheck.Reason.RUNS_ON)) {
                    runOnIdle { h.preflightFixture(reason); export() }; waitForIdle()
                    val report = h.model.state.value.recap!!.preflight
                    val item = report.first { it.reason == reason }
                    assertEquals(PreflightCheck.Kind.WARNING, item.kind)
                    val bytes = h.bytes(); val canRender = h.model.state.value.canRender
                    val target = fixControl(preflightTag(report, reason)).assertIsEnabled()
                    target.assertTextEquals(UiText.V3_PREFLIGHT_FIX.text(language, "step" to UiText.VOICE.text(language)))
                    if (canRender) onNodeWithTag("export-render").assertIsEnabled() else onNodeWithTag("export-render").assertIsNotEnabled()
                    onAllNodes(hasTestTag("preflight-fix-RIGHTS-${report.indexOfFirst { it.reason == PreflightCheck.Reason.RIGHTS }}")).assertCountEquals(0)
                    capture("${reason.name}-report")
                    target.performMouseInput { moveTo(center); click(); exit() }
                    assertEquals("Recap", nav.projectScreen); assertEquals(RecapStep.VOICE, nav.recapStep)
                    onNodeWithTag("voice-lock").assertExists()
                    onNodeWithTag("preflight-report").assertDoesNotExist()
                    capture("${reason.name}-voice")
                    assertEquals(report, h.model.state.value.recap!!.preflight)
                    assertEquals(canRender, h.model.state.value.canRender)
                    assertEquals(bytes, h.bytes(), "Fix navigation must not approve,save,record or render")
                    assertTrue(h.queued.isEmpty())
                    onNodeWithTag("project-back").assertIsDisplayed().performMouseInput { moveTo(center); click(); exit() }
                    assertEquals("Recap", nav.projectScreen)
                    assertEquals(RecapStep.STORY, nav.recapStep, "Fix retains ordinary previous-step Back, not an export-only shortcut")
                    onNodeWithTag("project-progress").assert(SemanticsMatcher.expectValue(
                        SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(2f, 0f..5f, 4)))
                    assertEquals(bytes, h.bytes())
                }
            }
        }
}

@OptIn(ExperimentalTestApi::class)
class PreflightFixWorkflowTest {
    @Test fun noRouterHasNoInertFixBusyDisablesAndProjectSwitchCannotUseOldReport() =
        runDesktopComposeUiTest(width = 412, height = 760) {
            ExportHarness().use { h ->
                h.preflightFixture(PreflightCheck.Reason.UNVOICED)
                var withRouter by mutableStateOf(false); var routes = 0
                setContent { CompositionLocalProvider(LocalCompact provides true, LocalUiFontFamily provides BurmeseTestFont.family) {
                    StudioExportScreen(h.model, h.recap, 412f, RecapNavigation({}, {}, {}),
                        onPreflightFix = if (withRouter) { { assertEquals(RecapStep.VOICE, it); routes++ } } else null)
                } }
                onNodeWithTag("preflight-report").assertExists()
                val item = h.model.state.value.recap!!.preflight.first { it.reason == PreflightCheck.Reason.UNVOICED }
                val tag = preflightTag(h.model.state.value.recap!!.preflight, item.reason)
                onNodeWithTag(tag).assertDoesNotExist()
                runOnIdle { withRouter = true }; waitForIdle()
                runOnIdle { h.model.render(Quality.PREVIEW) }; waitUntil { h.model.state.value.busy }
                fixControl(tag).assertIsNotEnabled()
                assertFalse(preflightNavigationAllowed(h.model, h.model.projects.value, item))
                assertEquals(0, routes)
                runOnIdle { h.run.value = RenderState.Cancelled }; waitUntil { !h.model.state.value.busy }
                val before = h.bytes()
                fixControl(tag).assertIsEnabled().performMouseInput { moveTo(center); click(); exit() }
                assertEquals(1, routes); assertEquals(before, h.bytes())
                val a = h.model.projects.value
                val b = QaRecapFixture.create(h.root, "QA Preflight B")
                val bBytes = File(b.dir, "project.json").readBytes()
                runOnIdle { h.current.set(OpenProject(b.dir.path, b.project)); h.recap.refresh(); h.model.refresh() }; waitForIdle()
                onNodeWithTag(tag).assertDoesNotExist()
                assertFalse(preflightNavigationAllowed(h.model, a, item))
                assertEquals(1, routes); assertEquals(before, h.bytes())
                assertContentEquals(bBytes, File(b.dir, "project.json").readBytes())
            }
        }
}