package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.tool.*
import io.github.alalkipgen.autoflow.platform.*
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.*
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class StudioPreviewFallbackTest {
    @Test fun missingVlcExplainsFailureButRealExactFrameUsesSelectedBlockOutputCursor() = runDesktopComposeUiTest(width = 412, height = 760) {
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
            setContent { CompositionLocalProvider(LocalUiFontFamily provides BurmeseTestFont.family) {
                StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, 412f) {
                    StudioRecapScreen(model, RecapStep.VOICE, 412f, {}, RecapNavigation({}, {}, {}))
                }
            } }
            onNodeWithTag("voice-open-1").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("timeline-play").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("VLC path does not contain", substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("VLC path does not contain", substring = true).performScrollTo().assertIsDisplayed()
            onNodeWithTag("timeline-video").assertDoesNotExist()
            assertEquals(4_000L, model.previewSource.currentPosition())
            onNodeWithText(UiText.EXACT_FRAME.en).performScrollTo().assertIsDisplayed().performMouseInput { click() }
            waitUntil(timeoutMillis = 30_000) { onAllNodesWithContentDescription(UiText.EXACT_FRAME_DESCRIPTION.en).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription(UiText.EXACT_FRAME_DESCRIPTION.en).performScrollTo().assertIsDisplayed()
            onNodeWithText("${UiText.EXACT_FRAME.en} · 4.0 s").performScrollTo().assertIsDisplayed()
            assertContentEquals(before, File(fixture.dir, "project.json").readBytes())
        } finally { scope.cancel(); root.deleteRecursively() }
    }
}