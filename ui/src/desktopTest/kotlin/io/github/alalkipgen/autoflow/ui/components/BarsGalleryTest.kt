package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.dubbing.DubStep
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.icons.TooltipHost
import io.github.alalkipgen.autoflow.ui.projects.label
import io.github.alalkipgen.autoflow.ui.recap.RecapStep
import io.github.alalkipgen.autoflow.ui.recap.recapStepLabels
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import io.github.alalkipgen.autoflow.ui.theme.Tone
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P9-5a gallery (DESIGN.md §9.4): top bar, icon buttons, project top bar and step bars — EN + MY, font 1.0 / 2.0, phone + desktop. */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class BarsGalleryTest(val width: Int, val font: Float, val language: UiLanguage) {
    companion object {
        const val LONG = "Movie recap — a very long project name that cannot fit on one top bar"

        @JvmStatic @Parameterized.Parameters(name = "{0}dp/{1}/{2}")
        fun cases() = listOf(360 to 1f, 360 to 2f, 1440 to 1f, 1440 to 2f).flatMap { (w, f) ->
            UiLanguage.entries.map { arrayOf<Any>(w, f, it) }
        }
    }

    @Composable private fun Gallery() = TooltipHost(Modifier.fillMaxSize().background(AutoFlowColors.Background)) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ProjectTopBar(LONG, 3, tr(UiText.VOICE), {}, options = listOf(StudioAction(tr(UiText.CLASSIC_RECAP), {})),
                steps = recapStepLabels())
            Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AfTopBar(tr(Mode.DUBBING.label()), subtitle = tr(DubStep.SCRIPT.text), status = Tone.Success, onBack = {}) {
                    AfIconButton(AfIcons.Fill.Play, tr(UiText.PROJECT_PAGE_PREVIEW), {})
                    AfIconButton(AfIcons.Regular.More, tr(UiText.V3_MORE_OPTIONS), {}, enabled = false)
                }
                GroupLabel(tr(Mode.MOVIE_RECAP.label()))
                StepBar(recapStepLabels(), 1)
                StepBar(recapStepLabels(), 5)
                GroupLabel(tr(Mode.DUBBING.label()))
                StepBar(DubStep.entries.map { tr(it.text) }, 2)
                StepBar(emptyList(), 4, count = 5)
                Row { AfIconButton(AfIcons.Regular.Back, tr(UiText.BACK), {}); AfIconButton(AfIcons.Regular.Edit, tr(UiText.EDIT), {}, selected = true) }
            }
        }
    }

    private fun recapLabels(language: UiLanguage) = RecapStep.entries.map { My.breakSyllables(it.textKey.text(language)) }

    @Test fun barsFitAndKeep48dpTargets() = runDesktopComposeUiTest(width = width, height = if (font > 1f) 1500 else 900) {
        setContent {
            CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) { Gallery() }
        }
        // Step labels are never cut: one line each, or (large fonts on a phone) none — the subtitle names the step.
        assertEquals(emptyList(), textFitProblems(width, font, strict = true))
        onNodeWithContentDescription(LONG, useUnmergedTree = true).assertExists()
        onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach {
            assertTrue(it.size.width >= 48 && it.size.height >= 48, "48 dp target at $width/$font/$language: ${it.size}")
        }
        onNodeWithTag("project-progress").assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(3f, 0f..5f, 4)))
        repeat(5) { onNode(hasTestTag("progress-segment-$it") and hasAnyAncestor(hasTestTag("project-progress")), useUnmergedTree = true).assertExists() }
        val labels = recapLabels(language).count { onAllNodes(hasText(it) and hasAnyAncestor(hasTestTag("project-progress")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(labels == 0 || labels == 5, "all step names or none at $width/$font/$language: $labels")
        if (font == 1f) assertEquals(5, labels)
        onNodeWithText(My.breakSyllables(UiText.V3_STEP.text(language, "step" to UiText.VOICE.text(language), "n" to 3)), useUnmergedTree = true).assertExists()
        // Desktop at font 1.0 has room for numbered steps: the steps still to do show their number.
        if (width >= 600 && font == 1f) onNode(hasText("4") and hasAnyAncestor(hasTestTag("project-progress")), useUnmergedTree = true).assertExists()
        onNodeWithTag("project-back").assertContentDescriptionEquals(UiText.BACK.text(language))
        val dir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "gallery").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "bars-$width-$font-${language.name.lowercase()}.png"))
        System.getenv("AUTOFLOW_PR_SHOTS_DIR")?.let { if (language == UiLanguage.BURMESE)
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(File(it).apply { mkdirs() }, "bars-${if (width < 600) "phone-$font" else "desktop-$font"}.png")) }
    }
}
