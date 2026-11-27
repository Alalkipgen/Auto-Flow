package io.github.alalkipgen.autoflow.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.ai.*
import io.github.alalkipgen.autoflow.ai.fake.FakeTextProvider
import io.github.alalkipgen.autoflow.ai.fake.FakeTranscribeProvider
import io.github.alalkipgen.autoflow.core.getOrNull
import io.github.alalkipgen.autoflow.core.lyrics.LyricTimings
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ai.registry.aiModule
import io.github.alalkipgen.autoflow.ai.storyboard.StoryboardGenerator
import io.github.alalkipgen.autoflow.core.io.*
import io.github.alalkipgen.autoflow.core.model.*
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.AppDirs
import io.github.alalkipgen.autoflow.ui.di.uiModule
import io.github.alalkipgen.autoflow.ui.fakePlatformModule
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardJobs
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonElement
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class MusicGeminiProgressTest {
    @BeforeTest fun mainDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun resetDispatcher() { Dispatchers.resetMain() }

    @Test fun englishStandard() = projectProgress(412, 1f, UiLanguage.ENGLISH)
    @Test fun englishLarge() = projectProgress(412, 2f, UiLanguage.ENGLISH)
    @Test fun burmeseStandard() = projectProgress(412, 1f, UiLanguage.BURMESE)
    @Test fun burmeseLarge() = projectProgress(412, 2f, UiLanguage.BURMESE)
    @Test fun narrowPhone() = projectProgress(360, 1f, UiLanguage.BURMESE)

    private fun projectProgress(width: Int, font: Float, language: UiLanguage) = runDesktopComposeUiTest(width = width, height = 760) {
        val fs = InMemoryFileSystem()
        val appDirs = AppDirs("/app")
        val dir = "/app/projects/music.autoflow"
        val store = ProjectStore(fs)
        val project = store.create(dir, "Music QA", Mode.MUSIC_VIDEO, ProjectSettings(durationMs = 14_000)).let {
            (it as io.github.alalkipgen.autoflow.core.CoreResult.Ok).value
        }
        fs.writeDurably("$dir/source/lyrics.txt", "Synthetic test lyrics".encodeToByteArray())
        ProjectIndex(fs, appDirs.projectsIndex).record(dir, project)
        val staged = PausingStoryboard()
        val graph = koinApplication { modules(fakePlatformModule(), uiModule, aiModule, module {
            single<FileSystem> { fs }
            single { appDirs }
            single { StoryboardGenerator(staged) }
        }) }
        try {
            graph.koin.get<SettingsStore>().update { it.copy(featureOverrides = mapOf("studioV2" to true), uiLanguage = language.code) }
            val jobs = graph.koin.get<StoryboardJobs>()
            val before = fs.read("$dir/project.json")
            val artifacts = fs.list("$dir/artifacts")
            fun tapText(key: UiText) = onNodeWithText(key.text(language)).performScrollTo().assertIsDisplayed().performMouseInput { click() }
            fun tab(key: UiText) = onNodeWithContentDescription(key.text(language), substring = true).assertIsDisplayed().performMouseInput { click() }
            fun stage(key: UiText, vararg args: Pair<String, Any?>) =
                onNodeWithText(My.breakSyllables(key.text(language, *args)), substring = true).assertIsDisplayed()
            val indeterminate = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)
            val withinProjectRun = hasAnyAncestor(hasTestTag("gemini-project-run"))
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, font)) { KoinIsolatedContext(graph) { App() } }
            }
            onNodeWithTag("recent-Music QA").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            tapText(UiText.GENERATE_STORYBOARD)
            tapText(UiText.GENERATE)
            onNodeWithTag("gemini-project-run").assertIsDisplayed()
            stage(UiText.JOB_STORYBOARD_ANALYZE)
            stage(UiText.JOB_WAITING_GEMINI)
            try { onNode(indeterminate and withinProjectRun, useUnmergedTree = true).assertIsDisplayed() }
            catch (e: AssertionError) {
                val progress = onNode(indeterminate and withinProjectRun, useUnmergedTree = true).fetchSemanticsNode()
                val root = onRoot().fetchSemanticsNode()
                val card = onNodeWithTag("gemini-project-run").fetchSemanticsNode()
                throw AssertionError("${e.message}; bar=${progress.boundsInRoot}; card=${card.boundsInRoot}; root=${root.boundsInRoot}", e)
            }
            val id = assertNotNull(jobs.state.value.gemini).id

            runOnIdle { staged.analyzed.complete(Unit) }
            stage(UiText.JOB_STORYBOARD_BATCH, "n" to 2)
            tab(UiText.HOME)
            onNodeWithTag("home-job-0").performScrollTo().assertIsDisplayed()
            onNodeWithText(My.breakSyllables(UiText.JOB_STORYBOARD_BATCH.text(language, "n" to 2)), substring = true)
                .performScrollTo().assertIsDisplayed()
            tab(UiText.JOBS)
            stage(UiText.JOB_STORYBOARD_BATCH, "n" to 2)
            assertEquals(id, jobs.state.value.gemini?.id)
            onNodeWithTag("job-open-storyboard").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("gemini-project-run").assertIsDisplayed()
            stage(UiText.JOB_STORYBOARD_BATCH, "n" to 2)
            onNode(hasText(UiText.CANCEL.text(language)) and withinProjectRun).assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("gemini-project-run").assertDoesNotExist()
            assertEquals(2, staged.calls)
            assertEquals(StoryboardJobs.CANCELLED, jobs.state.value.last)
            assertContentEquals(before, fs.read("$dir/project.json"))
            assertEquals(artifacts, fs.list("$dir/artifacts"))
        } finally { graph.koin.get<StoryboardJobs>().cancel(); graph.close() }
    }

    @Test fun lyricTimingContinuesAfterLeavingInputAndRefreshesTheReopenedScreen() = runDesktopComposeUiTest(width = 412, height = 760) {
        val fs = InMemoryFileSystem()
        val appDirs = AppDirs("/app")
        val dir = "/app/projects/lyric.autoflow"
        val store = ProjectStore(fs)
        val project = checkNotNull(store.create(dir, "Music QA", Mode.MUSIC_VIDEO, ProjectSettings(durationMs = 30_000)).getOrNull())
        fs.writeDurably("$dir/source/lyrics.txt", "One line\nSecond line".encodeToByteArray())
        fs.writeDurably("$dir/source/song.mp3", byteArrayOf(1, 2, 3))
        ProjectIndex(fs, appDirs.projectsIndex).record(dir, project)
        val waiting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fake = FakeTranscribeProvider()
        val graph = koinApplication { modules(fakePlatformModule(), uiModule, aiModule, module {
            single<FileSystem> { fs }
            single { appDirs }
            single<TranscribeProvider> { object : TranscribeProvider {
                override suspend fun transcribe(audio: ByteArray, lang: String, mime: String, lines: List<String>): AiResult<Transcript> {
                    waiting.complete(Unit)
                    release.await()
                    return fake.transcribe(audio, lang, mime, lines)
                }
            } }
        }) }
        try {
            graph.koin.get<SettingsStore>().update { it.copy(featureOverrides = mapOf("studioV2" to true)) }
            setContent { KoinIsolatedContext(graph) { App() } }
            fun tap(text: String) = onNodeWithText(text).performScrollTo().assertIsDisplayed().performMouseInput { click() }
            tap("Music QA")
            tap(UiText.SUGGEST_TIMINGS.en); tap(UiText.SEND.en)
            waitUntil(timeoutMillis = 5_000) { waiting.isCompleted }
            onNodeWithTag("gemini-project-run").assertIsDisplayed()
            onNodeWithText(UiText.JOB_LYRIC_TIMINGS.en).assertIsDisplayed()
            onNodeWithContentDescription(UiText.JOBS.en, substring = true).performMouseInput { click() }
            onNodeWithText(UiText.JOB_LYRIC_TIMINGS.en, substring = true).assertIsDisplayed()
            onNodeWithTag("job-open-storyboard").performScrollTo().performMouseInput { click() }
            onNodeWithTag("gemini-project-run").assertIsDisplayed()
            runOnIdle { release.complete(Unit) }
            val session = graph.koin.get<ProjectSession>()
            waitUntil(timeoutMillis = 5_000) { session.open?.project?.lyricTiming != null }
            onNodeWithTag("gemini-project-run").assertDoesNotExist()
            onNodeWithText(UiText.TIMINGS_GEMINI.en, substring = true).performScrollTo().assertIsDisplayed()
            assertEquals(1, fake.calls)
            val saved = assertNotNull(session.readLyricTimings())
            assertEquals(LyricTimings.SOURCE_GEMINI, saved.source)
            assertEquals(2, saved.lines.size)
        } finally { graph.koin.get<StoryboardJobs>().cancel(); graph.close() }
    }

    private class PausingStoryboard : TextProvider {
        private val fake = FakeTextProvider()
        val analyzed = CompletableDeferred<Unit>()
        var calls = 0
            private set
        override suspend fun json(prompt: String, schema: JsonSchema, system: String?): AiResult<JsonElement> {
            if (++calls == 1) analyzed.await() else awaitCancellation()
            return fake.json(prompt, schema, system)
        }
    }
}
