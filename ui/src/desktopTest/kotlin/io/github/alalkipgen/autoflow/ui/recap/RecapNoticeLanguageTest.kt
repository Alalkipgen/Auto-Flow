package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.recap.RecapMovie
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.*
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.*
import kotlin.test.*

/** A real displayed action, in-flight job and completed result in both shipped Recap surfaces. */
@OptIn(ExperimentalTestApi::class)
class RecapNoticeLanguageTest {
    @Test fun classicNoticeAndInFlightJobRetranslateWithoutRestart() = exercise(false)
    @Test fun studioNoticeAndInFlightJobRetranslateWithoutRestart() = exercise(true)

    private fun exercise(studio: Boolean) = runDesktopComposeUiTest(width = if (studio) 840 else 412, height = 760) {
        val fs = InMemoryFileSystem()
        val projects = ProjectStore(fs)
        val created = (projects.create("/p", "မူလရုပ်ရှင်", Mode.MOVIE_RECAP) as CoreResult.Ok).value
        val saved = (projects.save("/p", created.copy(recap = RecapV2(rightsConfirmed = true,
            movie = RecapMovie("source/movie.mp4", 3, 60000, 1920, 1080)))) as CoreResult.Ok).value
        fs.writeDurably("/p/source/movie.mp4", byteArrayOf(1, 2, 3))
        val current = CurrentProject().also { it.set(OpenProject("/p", saved)) }
        val session = ProjectSession(fs, projects, ProjectIndex(fs, "/app/projects.json"), current)
        val settings = SettingsStore(fs, "/app/settings.json")
        val release = CompletableDeferred<Unit>()
        val runner = ToolRunner { _, _, _, _, onLine ->
            release.await()
            onLine("[scdet] lavfi.scd.score: 30, lavfi.scd.time: 10")
            onLine("[scdet] lavfi.scd.score: 30, lavfi.scd.time: 30.5")
            ToolExec.Done(0, "", emptyList(), 1, false)
        }
        val locator = object : ToolLocator {
            override fun findFfmpeg() = "/bin/ffmpeg"
            override fun findVlc(): String? = null
            override fun isVlcDir(dir: String) = false
        }
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val model = RecapModel(session, fs, MediaTools(settings, locator, runner), null, scope)
            setContent { CompositionLocalProvider(LocalUiFontFamily provides BurmeseTestFont.family) {
                AppLanguage(settings.state) {
                    if (studio) StudioRecapScreen(model, RecapStep.SOURCE, 840f, {}) else RecapScreen(model)
                }
            } }
            onNodeWithText(UiText.FIND_SCENES.en).performScrollTo().assertIsDisplayed().performMouseInput { click() }
            waitUntil { model.state.value.busy }
            assertEquals(UiText.SCENES_FINDING.en, model.state.value.job)
            settings.update { it.copy(uiLanguage = "my") }
            waitForIdle()
            // Studio: the busy strip (no "⏳ ", Group 2 D 19 · 1–3); Classic keeps its line.
            onNodeWithText((if (studio) "" else "⏳ ") + UiText.SCENES_FINDING.text(UiLanguage.BURMESE), substring = true).performScrollTo().assertIsDisplayed()
            onNodeWithText(UiText.SCENES_FINDING.en, substring = true).assertDoesNotExist()
            assertEquals(UiText.SCENES_FINDING.en, model.state.value.job, "business identity stays English while the label changes")
            runOnIdle { release.complete(Unit) }
            waitUntil { !model.state.value.busy && model.state.value.recap!!.scenes.isNotEmpty() }
            val count = model.state.value.recap!!.scenes.size
            onNodeWithText(UiText.SCENES_FOUND.text(UiLanguage.BURMESE, "n" to count)).performScrollTo().assertIsDisplayed()
            val projectBytes = fs.read("/p/project.json")
            settings.update { it.copy(uiLanguage = "en") }
            waitForIdle()
            onNodeWithText(UiText.SCENES_FOUND.text(UiLanguage.ENGLISH, "n" to count)).performScrollTo().assertIsDisplayed()
            assertContentEquals(projectBytes, fs.read("/p/project.json"))
            model.busyNotice(UiNotice(UiText.SCRIPT_WRITING))
            assertEquals(RecapModel.WRITING, model.state.value.job, "legacy writing/cancellation checks remain valid")
            model.done(null)
            model.say("owner diagnostic မြန်မာ {n}")
            assertTrue(model.state.value.notice!!.text(UiLanguage.BURMESE).endsWith("owner diagnostic မြန်မာ {n}"))
            model.dismissMessage()
            assertNull(model.state.value.notice)
        } finally { scope.cancel() }
    }
}
