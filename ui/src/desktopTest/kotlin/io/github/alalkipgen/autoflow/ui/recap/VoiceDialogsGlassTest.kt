package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
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
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * Group 2 C 13a–e (PR 7): the five Voice dialogs are glass dialogs — one `glass-dialog` surface with its tile and
 * title (Script · Movie source ranges + its hint · Change voice… · Re-voice / Generate · Voice n remaining), the same
 * tags and buttons as before, an empty range keeps Save disabled with the amber problem, every dialog closes on its
 * Cancel / Discard and nothing is written.
 */
@OptIn(ExperimentalTestApi::class)
class VoiceDialogsGlassTest {
    @Test fun fiveVoiceDialogsAreGlassDialogsWithTheirTitlesTagsAndCloseWithoutWriting() {
        for ((language, font) in listOf(UiLanguage.ENGLISH to 1f, UiLanguage.BURMESE to 2f)) runDesktopComposeUiTest(width = 360, height = 760) {
            val fixture = QaRecapFixture.create(Files.createTempDirectory("g2c-dialogs").toFile())
            val before = File(fixture.dir, "project.json").readBytes()
            val fs = JvmFs(); val current = CurrentProject().apply { set(OpenProject(fixture.dir.path, fixture.project)) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val model = RecapModel(ProjectSession(fs, ProjectStore(fs), ProjectIndex(fs, File(fixture.dir.parentFile, "index.json").path), current), fs, null, null, scope)
            try {
                setContent { CompositionLocalProvider(LocalCompact provides true, LocalDensity provides Density(1f, font),
                    LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) {
                    StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, 360f) {
                        StudioRecapScreen(model, RecapStep.VOICE, 360f, {}, RecapNavigation({}, {}, {}))
                    }
                } }
                fun click(tag: String) { onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performMouseInput { click() }; waitForIdle() }
                fun dialog(title: String, subtitle: String? = null) {
                    onNodeWithTag("glass-dialog").assertExists()
                    onNodeWithTag("glass-dialog-tile", useUnmergedTree = true).assertExists()
                    onNodeWithTag("glass-dialog-title", useUnmergedTree = true).assertTextEquals(title)
                    subtitle?.let { onNodeWithTag("glass-dialog-subtitle", useUnmergedTree = true).assertTextEquals(it) }
                    assertEquals(emptyList(), textFitProblems(360, font), "$title ${language.code}")
                }
                fun pinned(tag: String) { onNodeWithTag(tag).assertIsDisplayed().performMouseInput { click() }; waitForIdle() }
                fun closed() = onNodeWithTag("glass-dialog").assertDoesNotExist()
                fun cancelInDialog() {
                    onNode(hasText(UiText.CANCEL.text(language)) and hasClickAction() and hasAnyAncestor(hasTestTag("glass-dialog")))
                        .performScrollTo().performMouseInput { click() }
                    waitForIdle()
                }

                // 13c Change voice… (the Voice card on the list): Keep old takes · Re-voice · Cancel.
                click("change-voice")
                dialog(UiText.CHANGE_VOICE.text(language))
                onNodeWithTag("voice-keep").performScrollTo().assertIsDisplayed()
                onNodeWithTag("voice-regenerate").performScrollTo().assertIsDisplayed()
                onNodeWithTag("voice-change-impact", useUnmergedTree = true).assertExists()
                cancelInDialog(); closed()

                // 13e Voice n remaining (only when there is something left to voice).
                val remaining = onNodeWithTag("voice-remaining").fetchSemanticsNode()
                if (SemanticsProperties.Disabled !in remaining.config) {
                    click("voice-remaining")
                    onNodeWithTag("voice-confirm").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("glass-dialog-title", useUnmergedTree = true).assertExists()
                    click("voice-confirm-cancel"); closed()
                }

                onNodeWithTag("voice-open-0").performScrollTo().performMouseInput { click() }
                waitForIdle()
                // 13a Script: Cancel | Save (check).
                click("block-script-edit")
                dialog(UiText.V3_SCRIPT.text(language))
                onNodeWithTag("block-script-save").assertIsDisplayed().assertIsEnabled() // pinned footer: in view without a scroll
                pinned("block-script-cancel"); closed()

                // 13b Movie source ranges: the hint under the title; an empty range = Save disabled + the amber problem.
                click("block-clips-edit")
                dialog(UiText.V3_CLIPS.text(language), UiText.V3_CLIPS_HINT.text(language))
                click("clip-add")
                onNodeWithTag("block-clips-save").assertIsDisplayed().assertIsNotEnabled()
                onNodeWithText(UiText.V3_CLIP_INVALID.text(language), useUnmergedTree = true).assertExists()
                pinned("block-clips-discard"); closed()

                // 13d Re-voice / Generate voice for this block: the spoken text quote, Cancel | Start.
                val request = onNodeWithTag("voice-block-request").fetchSemanticsNode()
                if (SemanticsProperties.Disabled !in request.config) {
                    pinned("voice-block-request") // the phone's sticky bar
                    onNodeWithTag("voice-block-confirm-text").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("voice-block-confirm").assertIsDisplayed()
                    pinned("voice-block-cancel"); closed()
                }
                assertContentEquals(before, File(fixture.dir, "project.json").readBytes())
            } finally { scope.cancel() }
        }
    }
}
