package io.github.alalkipgen.autoflow.ui.subtitles

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.ai.registry.aiModule
import io.github.alalkipgen.autoflow.core.flags.FeatureFlags
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.platform.AppDirs
import io.github.alalkipgen.autoflow.platform.FilePicker
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.di.uiModule
import io.github.alalkipgen.autoflow.ui.fakePlatformModule
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.shell.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.SubtitlesTool
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.recap.SrtCue
import io.github.alalkipgen.autoflow.core.recap.SubtitleTrack
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleOps
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.projects.DashboardModel
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily

/** S-1b in the real app graph: flag → Home tile → picker → project → Subtitles screen → Home Continue. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class SubtitlesRouteTest {
    @BeforeTest fun setupDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun resetDispatcher() { Dispatchers.resetMain() }

    @Test fun tileCreatesAProjectThatOpensAndContinuesFromHome() = runDesktopComposeUiTest(width = 412, height = 760) {
        val root = Files.createTempDirectory("p7-subtitles-route").toFile()
        try {
            val fs = JvmFs()
            val home = File(root, "projects").apply { mkdirs() }.invariantSeparatorsPath
            val appDirs = AppDirs(File(root, "app").invariantSeparatorsPath, home)
            val picked = File(root, "Download/Rain clip.mp4").apply { parentFile.mkdirs(); writeText("not decodable here") }
            val app = koinApplication {
                modules(fakePlatformModule(), module {
                    single<FileSystem> { fs }; single { appDirs }
                    single<FilePicker> { FakePicker(files = listOf(picked.invariantSeparatorsPath)) }
                }, uiModule, aiModule)
            }
            try {
                app.koin.get<SettingsStore>().update {
                    it.copy(featureOverrides = mapOf(FeatureFlags.STUDIO_V2 to true, FeatureFlags.TOOLS_SUBTITLES to true))
                }
                setContent { KoinIsolatedContext(app) { App() } }
                onNodeWithTag("home-subtitles-tool").performScrollTo().assertIsEnabled().performMouseInput { click() }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-notice").fetchSemanticsNodes().isNotEmpty() }
                val open = assertNotNull(app.koin.get<CurrentProject>().state.value)
                assertEquals(Mode.SUBTITLES, open.project.mode)
                assertTrue(open.project.name.startsWith("Rain clip · "), open.project.name)
                assertEquals("not decodable here", File(open.dir, "source/movie.mp4").readText())
                // No ffprobe in the test graph: the project stays, says why, and invents no facts.
                onNodeWithTag("subtitles-notice").assertTextEquals(UiText.MOVIE_UNREADABLE.en)
                onNodeWithTag("subtitles-no-video").assertIsDisplayed()
                onNodeWithTag("subtitles-source").assertDoesNotExist() // no source without a readable video
                val shots = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "subtitles").apply { mkdirs() }
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(shots, "01-subtitles-412-unreadable.png"))
                assertEquals(listOf(Mode.SUBTITLES), app.koin.get<ProjectIndex>().entries.value.map { it.mode })

                onNodeWithContentDescription(UiText.HOME.en).assertIsDisplayed().performMouseInput { click() }
                onNodeWithTag("home-mode-SUBTITLES").performScrollTo().assertIsDisplayed()
                onNodeWithTag("home-continue-step").performScrollTo().assertTextEquals(UiText.V3_SUBTITLES.en)
                onNodeWithTag("recent-${open.project.name}").performScrollTo().performMouseInput { click() }
                onNodeWithTag("subtitles-no-video").assertIsDisplayed()
            } finally { app.close() }
        } finally { root.deleteRecursively() }
    }

    /**
     * The facts card with the source chooser (text + picture track) or with a saved `st`, at the size extremes
     * (D-22 one layout), English and Burmese, with the bundled Burmese font.
     */
    @Test fun videoFactsFitAtSmallAndWideSizesInBothLanguages() {
        for ((width, font) in listOf(360 to 2f, 1440 to 1f)) for (language in UiLanguage.entries) for (withDoc in listOf(false, true)) {
            runDesktopComposeUiTest(width = width, height = 760) {
                val fs = InMemoryFileSystem(); val store = ProjectStore(fs); val current = CurrentProject()
                val index = ProjectIndex(fs, "/app/projects.json")
                val dir = "/p/Clip.autoflow"
                val created = (store.create(dir, "Clip", Mode.SUBTITLES) as CoreResult.Ok).value
                val project = (store.save(dir, created.copy(subtitles = SubtitlesTool(video =
                    SourceVideo("source/movie.mp4", 9, 3_725_000, 1920, 1080, 29.97, hasAudio = false)))) as CoreResult.Ok).value
                val tracks = listOf(SubtitleTrack(2, "subrip", "eng", "English SDH"), SubtitleTrack(3, "hdmv_pgs_subtitle", "bur"))
                val shown = if (!withDoc) project else {
                    val doc = SubtitleOps.fromCues(listOf(SrtCue(1_000, 3_000, "ဒီည ထွက်သွားရမယ်။ We have to leave tonight, before the rain starts."),
                        SrtCue(4_000, 6_000, "Second line")), SubtitleDoc.trackSource(2), lang = "eng")
                    val st = (ArtifactStore(fs, dir).put(CoreJson.encodeToString(SubtitleDoc.serializer(), doc).encodeToByteArray(),
                        ArtifactKind.SUBTITLES, "json") as CoreResult.Ok).value.id
                    (store.save(dir, project.copy(subtitles = project.subtitles!!.copy(current = st))) as CoreResult.Ok).value
                }
                current.set(OpenProject(dir, shown))
                val session = ProjectSession(fs, store, index, current)
                val model = SubtitlesModel(session, fs, DashboardModel(fs, store, index, SettingsStore(fs, "/app/s.json"), FakePicker(), current),
                    null, kotlinx.coroutines.MainScope(), probe = { io.github.alalkipgen.autoflow.media.probe.MediaInfo(durationMs = 3_725_000,
                        width = 1920, height = 1080, fps = 25.0, videoCodec = "h264", audioCodec = "aac", subtitleTracks = tracks) })
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalUiLanguage provides language,
                        LocalUiFontFamily provides BurmeseTestFont.family) { SubtitlesScreen(model, session, SubtitleEditorModel(session, fs, kotlinx.coroutines.MainScope()), player = { _, _, m, _ -> androidx.compose.foundation.layout.Box(m) }) }
                }
                val facts = UiText.SUBTITLES_VIDEO_FACTS.text(language, "duration" to "1:02:05", "width" to 1920, "height" to 1080,
                    "fps" to "29.97", "sound" to UiText.SUBTITLES_SOUND_NO.text(language))
                onNodeWithTag("subtitles-video-facts").assertIsDisplayed().assertTextEquals(My.breakSyllables(facts))
                onNodeWithTag("subtitles-no-video").assertDoesNotExist()
                if (withDoc) {
                    waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-doc-facts").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("subtitles-doc-facts").assertTextEquals(My.breakSyllables(UiText.SUB_DOC_FACTS.text(language,
                        "n" to 2, "from" to UiText.SUB_FROM_TRACK.text(language, "index" to "2"))))
                    onNodeWithTag("subtitles-view-ORIGINAL").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "selected"))
                    onNodeWithTag("subtitles-cues").assertIsDisplayed().performScrollToKey("c1")
                    onNodeWithTag("subtitles-cue-c1").assertIsDisplayed()
                    onNodeWithTag("subtitles-save").assertDoesNotExist() // nothing to save before an edit
                } else {
                    waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-track-2").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("subtitles-track-2").performScrollTo().assertIsEnabled().assertIsSelected() // the only text track
                    onNodeWithTag("subtitles-track-3").assertDoesNotExist() // picture track: one line, not an option
                    onNodeWithTag("subtitles-picture-tracks").assertTextEquals(My.breakSyllables(UiText.SUB_PICTURE_TRACK.text(language,
                        "n" to 1, "codec" to "hdmv_pgs_subtitle")))
                    onNodeWithTag("subtitles-use-track").performScrollTo().assertIsEnabled()
                    onNodeWithTag("subtitles-transcribe-planned").performScrollTo().assertIsNotEnabled()
                }
                assertEquals(emptyList(), textFitProblems(width, font))
                val shots = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "subtitles").apply { mkdirs() }
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png",
                    File(shots, "02-${if (withDoc) "doc" else "source"}-$width-${language.name.lowercase()}.png"))
            }
        }
    }
}
