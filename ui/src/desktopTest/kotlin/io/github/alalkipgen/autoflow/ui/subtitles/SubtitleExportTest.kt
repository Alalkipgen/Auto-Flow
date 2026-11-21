package io.github.alalkipgen.autoflow.ui.subtitles

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.SubtitlesTool
import io.github.alalkipgen.autoflow.core.recap.Srt
import io.github.alalkipgen.autoflow.core.recap.SrtCue
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleCue
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleExport
import io.github.alalkipgen.autoflow.core.subtitles.TextChoice
import io.github.alalkipgen.autoflow.platform.FileShare
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.DashboardModel
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import io.github.alalkipgen.autoflow.media.playback.MoviePhase
import io.github.alalkipgen.autoflow.media.playback.MovieSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.*

/** S-4a: `.srt` export — written once through `.part`, Share / Save to Downloads on a phone, Save / Open folder on the desktop. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class SubtitleExportTest {
    @BeforeTest fun setupDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun resetDispatcher() { Dispatchers.resetMain() }

    private class Share(override val sharesFiles: Boolean) : FileShare {
        val calls = mutableListOf<String>()
        override fun open(path: String, mime: String) = PlatformResult.Ok(Unit)
        override fun share(path: String, mime: String, title: String): PlatformResult<Unit> { calls += "share $mime $path"; return PlatformResult.Ok(Unit) }
        override suspend fun saveToDownloads(path: String, mime: String, displayName: String): PlatformResult<String> {
            calls += "downloads $displayName"; return PlatformResult.Ok("Download/AutoFlow/$displayName")
        }
        override fun openFolder(path: String): PlatformResult<Unit> { calls += "folder $path"; return PlatformResult.Ok(Unit) }
    }

    private class Fixture(shares: Boolean, cues: List<SubtitleCue>? = null) {
        val fs = InMemoryFileSystem(); val store = ProjectStore(fs); val current = CurrentProject()
        val index = ProjectIndex(fs, "/app/projects.json")
        val dir = "/p/My film.autoflow"
        val session = ProjectSession(fs, store, index, current)
        val share = Share(shares)
        val doc = SubtitleDoc(source = SubtitleDoc.SOURCE_SRT, nextId = 3, cues = cues ?: listOf(
            SubtitleCue("c1", 1_000, 3_000, "We have to leave tonight.", textMy = "ဒီည ထွက်သွားရမယ်။"),
            SubtitleCue("c2", 4_000, 6_000, "Not yet translated")))
        val version: io.github.alalkipgen.autoflow.core.model.ArtifactId
        init {
            val created = (store.create(dir, "My film", Mode.SUBTITLES) as CoreResult.Ok).value
            version = (ArtifactStore(fs, dir).put(CoreJson.encodeToString(SubtitleDoc.serializer(), doc).encodeToByteArray(),
                ArtifactKind.SUBTITLES, "json") as CoreResult.Ok).value.id
            val project = created.copy(subtitles = SubtitlesTool(video = SourceVideo("source/movie.mp4", 9, 60_000, 1920, 1080, 25.0), current = version))
            current.set(OpenProject(dir, (store.save(dir, project) as CoreResult.Ok).value))
        }
        val model = SubtitlesModel(session, fs, DashboardModel(fs, store, index, SettingsStore(fs, "/app/s.json"), FakePicker(), current),
            null, MainScope(), probe = { null })
        val editor = SubtitleEditorModel(session, fs, MainScope())
        val exporter = SubtitleExportModel(session, fs, share, MainScope())
        fun file(choice: TextChoice) = "$dir/exports/" + SubtitleExport.fileName("My film", choice, version.value)
    }

    private val noDecoder: SubtitlePlayer = { _, _, m, report ->
        LaunchedEffect(Unit) { report(MovieSnapshot(phase = MoviePhase.FAILED, problem = "no decoder")) }
        Box(m)
    }

    private fun ComposeUiTest.show(f: Fixture, font: Float = 1f, language: UiLanguage = UiLanguage.ENGLISH) = setContent {
        CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalUiLanguage provides language,
            LocalUiFontFamily provides BurmeseTestFont.family) { SubtitlesScreen(f.model, f.session, f.editor, player = noDecoder, exporter = f.exporter) }
    }

    @Test fun writesOnceThroughPartAndBlocksWithoutLines() {
        val f = Fixture(shares = true)
        val path = f.exporter.write(f.doc, f.version, TextChoice.BURMESE)
        assertEquals(f.file(TextChoice.BURMESE), path)
        assertFalse(f.fs.exists("$path.part"))
        assertEquals(listOf(SrtCue(1_000, 3_000, "ဒီည ထွက်သွားရမယ်။"), SrtCue(4_000, 6_000, "Not yet translated")),
            Srt.parse(f.fs.read(path!!).decodeToString()))
        f.fs.writeDurably(path, "kept".encodeToByteArray()) // an existing file of this version is never overwritten
        assertEquals(path, f.exporter.write(f.doc, f.version, TextChoice.BURMESE))
        assertEquals("kept", f.fs.read(path).decodeToString())

        assertNull(f.exporter.write(f.doc.copy(cues = emptyList()), f.version, TextChoice.ORIGINAL))
        assertEquals(UiText.SUB_EXPORT_NO_LINES, f.exporter.state.value.notice?.key)
        assertNull(f.exporter.write(f.doc.copy(cues = listOf(SubtitleCue("c1", 3_000, 3_000, "x"))), f.version, TextChoice.ORIGINAL))
        assertEquals(UiText.SUB_EXPORT_BAD_TIMES, f.exporter.state.value.notice?.key)
        assertFalse(f.fs.exists(f.file(TextChoice.ORIGINAL)))
    }

    @Test fun phoneSharesAndSavesToDownloads() = runDesktopComposeUiTest(width = 412, height = 900) {
        val f = Fixture(shares = true)
        show(f)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-export-open").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("subtitles-export-open").performMouseInput { click() }
        onNodeWithTag("subtitles-export-BURMESE").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "selected"))
        onNodeWithTag("subtitles-export-video-planned").assertExists()
        onNodeWithTag("subtitles-export-save").assertDoesNotExist()
        onNodeWithTag("subtitles-export-ORIGINAL").performMouseInput { click() }
        onNodeWithTag("subtitles-export-share").performMouseInput { click() }
        onNodeWithTag("subtitles-export-downloads").performMouseInput { click() }
        val orig = f.file(TextChoice.ORIGINAL)
        assertEquals(listOf("share ${SubtitleExport.SRT_MIME} $orig", "downloads ${orig.substringAfterLast('/')}"), f.share.calls)
        onNodeWithTag("subtitles-export-notice").assertTextEquals(UiText.SUB_EXPORT_DOWNLOADED.text(UiLanguage.ENGLISH,
            "path" to "Download/AutoFlow/${orig.substringAfterLast('/')}"))
        onNodeWithTag("subtitles-export-close").performMouseInput { click() }
        onNodeWithTag("subtitles-export").assertDoesNotExist()
    }

    @Test fun desktopSavesAndOpensTheFolder() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val f = Fixture(shares = false)
        show(f)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-export-open").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("subtitles-export-open").performMouseInput { click() }
        onNodeWithTag("subtitles-export-share").assertDoesNotExist()
        onNodeWithTag("subtitles-export-save").performMouseInput { click() }
        val my = f.file(TextChoice.BURMESE)
        onNodeWithTag("subtitles-export-notice").assertTextEquals(UiText.SUB_EXPORT_SAVED.text(UiLanguage.ENGLISH, "path" to my))
        onNodeWithTag("subtitles-export-folder").performMouseInput { click() }
        assertEquals(listOf("folder ${f.dir}/exports"), f.share.calls)
    }

    @Test fun exportHidesWhileThereAreUnsavedChangesAndFits() {
        for (width in listOf(360, 1440)) for (font in listOf(1f, 2f)) for (language in UiLanguage.entries) {
            runDesktopComposeUiTest(width = width, height = 900) {
                val f = Fixture(shares = width < 840)
                show(f, font, language)
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-export-open").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("subtitles-export-open").performMouseInput { click() }
                onNodeWithTag("subtitles-export-srt").assertExists()
                // P9-12d: the .srt card's icon, and the desktop's Save / Open folder and Close with theirs (Share has none).
                assertCardIcon("subtitles-export-srt", My.breakSyllables(UiText.SUB_EXPORT_SRT.text(language)))
                if (width >= 840) {
                    assertIconButton("subtitles-export-save", UiText.SUB_SAVE_SRT, language)
                    assertIconButton("subtitles-export-folder", UiText.OPEN_FOLDER, language)
                }
                assertIconButton("subtitles-export-close", UiText.CLOSE, language)
                assertEquals(emptyList(), textFitProblems(width, font, strict = true), "$width × $font × $language")
                onNodeWithTag("subtitles-export-close").performScrollTo().performMouseInput { click() }
                f.editor.shift(500)
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-save").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("subtitles-export-open").assertDoesNotExist()
            }
        }
    }
}
