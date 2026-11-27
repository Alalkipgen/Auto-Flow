package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.tool.*
import io.github.alalkipgen.autoflow.platform.*
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.*
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class StudioPreviewFallbackTest {
    @Test fun missingVlcExplainsFailureButRealExactFrameUsesSelectedBlockOutputCursor() = scenario(412, UiLanguage.ENGLISH)
    @Test fun wideEnglishSpaceFallbackAndRealExactFrame() = scenario(1440, UiLanguage.ENGLISH)
    @Test fun wideBurmeseSpaceFallbackAndRealExactFrame() = scenario(1440, UiLanguage.BURMESE)

    private fun scenario(width: Int, language: UiLanguage) = runDesktopComposeUiTest(width = width, height = if (width >= 840) 900 else 760) {
        val root = Files.createTempDirectory("uiv3-no-vlc").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val fixture = QaRecapFixture.create(root); val before = File(fixture.dir, "project.json").readBytes()
            val fs = JvmFs()
            val current = CurrentProject().apply { set(OpenProject(fixture.dir.path, fixture.project)) }
            val settings = SettingsStore(fs, File(root, "settings.json").path)
            val font = File(root, "noto.ttf").apply { writeBytes(Base64.getMimeDecoder().decode(StudioPreviewFallbackTest::class.java.getResourceAsStream("/fonts/NotoSansMyanmar-subset.ttf.b64")!!.readBytes())) }
            settings.update { it.copy(vlcPath = File(root, "missing-vlc").path, fontPaths = listOf(font.path)) }
            val tools = ToolRunner { cmd, wd, timeout, keep, onLine ->
                when (val r = ProcessRunner.run(cmd, File(wd), timeout, keep, onLine)) {
                    is ProcessRunner.Exec.Done -> ToolExec.Done(r.exitCode, r.output, r.tail, r.wallMs, r.timedOut)
                    is ProcessRunner.Exec.CannotStart -> ToolExec.CannotStart(r.message)
                }
            }
            val media = MediaTools(settings, DesktopToolLocator(fs), tools)
            val session = ProjectSession(fs, ProjectStore(fs), ProjectIndex(fs, File(root, "index.json").path), current)
            val model = RecapModel(session, fs, media, null, scope, frames = io.github.alalkipgen.autoflow.ui.export.RecapFrames(session, settings, fs, media))
            setContent { CompositionLocalProvider(LocalUiFontFamily provides BurmeseTestFont.family, LocalUiLanguage provides language) {
                StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat()) {
                    StudioRecapScreen(model, RecapStep.VOICE, width.toFloat(), {}, RecapNavigation({}, {}, {}))
                }
            } }
            onNodeWithTag("voice-open-1").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("timeline-play").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("VLC path does not contain", substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("VLC path does not contain", substring = true).performScrollTo().assertIsDisplayed()
            onNodeWithTag("timeline-video").assertDoesNotExist()
            // Space calls the same real preview action, including the existing unavailable-VLC recovery path.
            onNodeWithTag("timeline-time").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("project-shortcuts").assertIsFocused().performKeyInput { pressKey(Key.Spacebar) }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("VLC path does not contain", substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("timeline-video").assertDoesNotExist()
            assertEquals(4_000L, model.previewSource.currentPosition())
            // Group 2 A §1: on a phone the Exact frame is the top card's frame button; the still replaces the picture and
            // the time-row chip names its time (the desktop keeps the Exact frame card).
            val phone = width < 840
            (if (phone) onNodeWithTag("timeline-exact").assert(hasContentDescription(UiText.EXACT_FRAME.text(language)))
                else onNodeWithText(UiText.EXACT_FRAME.text(language))).performScrollTo().assertIsDisplayed().performMouseInput { click() }
            if (phone) {
                // The phone frame button is itself described "Exact frame": wait for the chip, then the still in the picture.
                val chip = hasText(UiText.EXACT_FRAME_AT.text(language, "atMs" to "0:04")) and hasAnyAncestor(hasTestTag("recap-top-preview"))
                waitUntil(timeoutMillis = 30_000) { onAllNodes(chip).fetchSemanticsNodes().isNotEmpty() }
                onNode(chip).performScrollTo().assertIsDisplayed()
                onNode(hasContentDescription(UiText.EXACT_FRAME_DESCRIPTION.text(language)) and hasAnyAncestor(hasTestTag("recap-top-preview")) and !hasClickAction())
                    .assertIsDisplayed()
            } else {
                waitUntil(timeoutMillis = 30_000) { onAllNodesWithContentDescription(UiText.EXACT_FRAME_DESCRIPTION.text(language)).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithContentDescription(UiText.EXACT_FRAME_DESCRIPTION.text(language)).performScrollTo().assertIsDisplayed()
                onNodeWithText("${UiText.EXACT_FRAME.text(language)} · 4.0 s").performScrollTo().assertIsDisplayed()
            }
            assertProjectPanesAndStickyActions(width, "voice-block-request")
            val captures = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "wide-playback").apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(captures, "11-voice-exact-$width-${language.code}.png"))
            assertContentEquals(before, File(fixture.dir, "project.json").readBytes())
        } finally { scope.cancel(); root.deleteRecursively() }
    }
}