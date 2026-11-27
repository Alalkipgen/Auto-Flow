package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.geometry.Offset
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.ai.fake.FakeTranscribeProvider
import io.github.alalkipgen.autoflow.ai.usage.UsageCounter
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.recap.RecapScene
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.*
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
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.*

@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class StudioSourceLayoutTest(val width: Int, val font: Float, val language: UiLanguage, val bundled: Boolean) {
    companion object {
        private val fixture by lazy {
            val f = QaRecapFixture.create(Files.createTempDirectory("uiv3-source").toFile())
            // Same synthetic QA fixture, with real AAC sound for the Source transcription confirmation.
            val movie = File(f.dir, "source/movie.mp4"); val audio = File(f.dir.parentFile, "source-audio.mp4")
            val p = ProcessBuilder(System.getenv("AUTOFLOW_FFMPEG") ?: "ffmpeg", "-v", "error", "-nostdin", "-y",
                "-i", movie.path, "-f", "lavfi", "-i", "sine=frequency=330:sample_rate=16000", "-shortest",
                "-c:v", "copy", "-c:a", "aac", audio.path).redirectErrorStream(true).start()
            val log = p.inputStream.bufferedReader().readText(); check(p.waitFor() == 0) { log }
            audio.copyTo(movie, overwrite = true)
            f.copy(project = (ProjectStore(JvmFs()).save(f.dir.path, f.project.copy(recap = f.project.recap!!.copy(
                movie = f.project.recap!!.movie!!.copy(sizeBytes = movie.length(), hasAudio = true),
                scenes = listOf(RecapScene(2_000, 4_000, true), RecapScene(6_000, 8_000, true))))) as CoreResult.Ok).value)
        }
        @JvmStatic @Parameterized.Parameters(name = "{0}px/{1}/{2}/bundled={3}")
        fun cases() = StudioStoryLayoutTest.cases()
    }
    @Test fun sourceControlsAreVisibleHonestAndBrowseCancelPreservesAllFiles() =
        runDesktopComposeUiTest(width = width, height = if (width == 1440) 900 else 760) {
            fun bytes() = fixture.dir.walkTopDown().filter { it.isFile }.associate {
                it.relativeTo(fixture.dir).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
            }
            val before = bytes(); val fs = JvmFs(); val store = ProjectStore(fs)
            val current = CurrentProject().apply { set(OpenProject(fixture.dir.path, fixture.project)) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val provider = FakeTranscribeProvider(); var pickerCalls = 0
            val picker = object : FilePicker {
                override suspend fun pickFolder(title: String, startDir: String?) = PlatformResult.Ok(null)
                override suspend fun pickFiles(title: String, extensions: Set<String>, multiple: Boolean, startDir: String?): PlatformResult<List<String>> {
                    pickerCalls++; return PlatformResult.Ok(emptyList())
                }
            }
            val model = RecapModel(ProjectSession(fs, store, ProjectIndex(fs, File(fixture.dir.parentFile, "index.json").path), current),
                fs, null, picker, scope, transcriber = provider, usage = UsageCounter(fs, File(fixture.dir.parentFile, "usage.json").path),
                sceneAudio = SceneAudioCutter { _, _, _, _ -> error("cancelled confirmation must never cut sound") })
            var back = 0; var next = 0; var classic = 0
            val platformBack = object : NavigationEventInput() { fun press() = dispatchOnBackCompleted() }
            try {
                setContent { CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                    LocalUiLanguage provides language, LocalUiFontFamily provides if (bundled) BurmeseTestFont.family else FontFamily.Default) {
                    val dispatcher = requireNotNull(LocalNavigationEventDispatcherOwner.current).navigationEventDispatcher
                    DisposableEffect(dispatcher) { dispatcher.addInput(platformBack); onDispose { dispatcher.removeInput(platformBack) } }
                    StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat()) {
                        StudioRecapScreen(model, RecapStep.SOURCE, width.toFloat(), {}, RecapNavigation({ back++ }, { next++ }, { classic++ }))
                    }
                } }
                assertProjectPanesAndStickyActions(width, "recap-next")
                fun click(tag: String) = onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performMouseInput { click() }
                val dir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "source").apply { mkdirs() }
                fun capture(state: String) {
                    assertEquals(emptyList(), textFitProblems(width, font))
                    val action = onNodeWithTag("recap-next").assertIsDisplayed().assertIsEnabled().getUnclippedBoundsInRoot()
                    val bar = onNodeWithTag("sticky-action-bar").getUnclippedBoundsInRoot()
                    assertTrue((action.bottom - action.top).value >= 48f && action.bottom <= bar.bottom && action.top >= bar.top)
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "02-source-$state-$width-${language.code}-$font-$bundled.png"))
                }
                repeat(5) { onNodeWithTag("progress-segment-$it").assertExists() }
                onNodeWithTag("source-movie-name").assertTextEquals("movie.mp4")
                onNodeWithText(UiText.V3_AUDIO_FOUND.text(language)).assertExists()
                capture("movie")
                click("source-density-16"); onNodeWithTag("source-density-16").assertIsSelected()
                click("source-density-6"); onNodeWithTag("source-density-6").assertIsSelected()
                click("source-density-10"); onNodeWithTag("source-density-10").assertIsSelected()
                assertEquals(before, bytes(), "choosing density must not scan or save")
                capture("split")
                onNodeWithText(UiText.IMPORT_SUBTITLES.text(language)).performScrollTo().assertIsDisplayed().performMouseInput { click() }
                assertEquals(1, pickerCalls)
                onNodeWithTag("source-summary").performScrollTo().assertIsDisplayed()
                capture("inputs")
                click("transcribe-start")
                onNodeWithTag("transcribe-confirm").performScrollTo().assertIsDisplayed()
                assertEquals(2, model.state.value.transcribe.confirm!!.scenes)
                assertEquals(1, model.state.value.transcribe.confirm!!.requests)
                capture("transcribe")
                onNodeWithTag("transcribe-confirm-back").performScrollTo().assertIsDisplayed()
                onNodeWithTag("transcribe-confirm-start").assertIsDisplayed().assertIsEnabled()
                capture("transcribe-actions")
                onNodeWithTag("transcribe-confirm-back").performMouseInput { click() }
                assertNull(model.state.value.transcribe.confirm)
                click("transcribe-start")
                onNodeWithTag("transcribe-confirm").performScrollTo().assertIsDisplayed()
                onNodeWithTag("project-back").assertIsDisplayed().performMouseInput { click() }
                assertNull(model.state.value.transcribe.confirm); assertEquals(0, back)
                click("source-picked-only"); onNodeWithTag("scene-pick-0").performScrollTo().assertIsDisplayed()
                onNodeWithTag("scene-pick-1").performScrollTo().assertIsDisplayed()
                capture("picks")
                click("source-replace")
                onNodeWithTag("source-replace-warning").performScrollTo().assertIsDisplayed()
                onNodeWithTag("source-replace-confirm").assertIsDisplayed()
                assertEquals(emptyList(), textFitProblems(width, font))
                ImageIO.write(onNode(isRoot() and hasAnyDescendant(hasTestTag("source-replace-warning"))).captureToImage().toAwtImage(), "png", File(dir, "02-source-replace-$width-${language.code}-$font-$bundled.png"))
                onNodeWithTag("source-replace-cancel").assertIsDisplayed().performMouseInput { click() }
                assertEquals(1, pickerCalls, "cancel must not open the movie picker")
                click("source-replace"); runOnIdle { platformBack.press() }
                onNodeWithTag("source-replace-confirm").assertDoesNotExist()
                click("source-replace")
                onNode(isRoot() and hasAnyDescendant(hasTestTag("source-replace-warning"))).performMouseInput { click(Offset.Zero) }
                onNodeWithTag("source-replace-confirm").assertDoesNotExist()
                assertEquals(1, pickerCalls, "platform Back and outside dismissal must not open the picker")
                onNodeWithTag("recap-next").assertIsDisplayed().performMouseInput { click() }; assertEquals(1, next)
                onNodeWithTag("project-back").assertIsDisplayed().performMouseInput { click() }; assertEquals(1, back)
                onNodeWithTag("project-overflow").assertIsDisplayed().performMouseInput { click() }
                onNodeWithTag("classic-recap").assertIsDisplayed().performMouseInput { click() }; assertEquals(1, classic)
                assertEquals(0, provider.calls); assertEquals(before, bytes())
            } finally { scope.cancel() }
        }
}
