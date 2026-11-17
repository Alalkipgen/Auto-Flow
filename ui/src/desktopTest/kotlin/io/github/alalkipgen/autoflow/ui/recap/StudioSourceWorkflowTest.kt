package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.toAwtImage
import javax.imageio.ImageIO
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.tool.*
import io.github.alalkipgen.autoflow.platform.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.*
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class StudioSourceWorkflowTest {
    private class Picker(var files: List<String>) : FilePicker {
        var calls = 0
        override suspend fun pickFolder(title: String, startDir: String?) = PlatformResult.Ok(null)
        override suspend fun pickFiles(title: String, extensions: Set<String>, multiple: Boolean, startDir: String?): PlatformResult<List<String>> {
            calls++; return PlatformResult.Ok(files)
        }
    }
    @Test fun sourceEditsReopenWithoutLosingStoryVoiceHistoryOrDuplicatingStoryInputs() = runDesktopComposeUiTest(width = 412, height = 760) {
        val fixture = QaRecapFixture.create(Files.createTempDirectory("source-save").toFile())
        val fs = JvmFs(); val store = ProjectStore(fs)
        val original = fixture.project.recap!!.copy(scenes = listOf(RecapScene(0, 2_000, true), RecapScene(2_000, 4_000)))
        val saved = (store.save(fixture.dir.path, fixture.project.copy(recap = original)) as CoreResult.Ok).value
        val current = CurrentProject().apply { set(OpenProject(fixture.dir.path, saved)) }
        val immutable = File(fixture.dir, "artifacts").walkTopDown().filter { it.isFile }.associate { it.path to it.readBytes().toList() }
        val srt = File(fixture.dir.parentFile, "picked.srt").apply { writeText("1\n00:00:00,000 --> 00:00:01,000\nSource subtitle\n") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val model = RecapModel(ProjectSession(fs, store, ProjectIndex(fs, File(fixture.dir.parentFile, "index.json").path), current), fs, null, Picker(listOf(srt.path)), scope)
        var step by mutableStateOf(RecapStep.SOURCE)
        try {
            setContent { StudioRecapScreen(model, step, 412f, {}, RecapNavigation({}, { step = step.next() ?: step }, {})) }
            fun click(tag: String) = onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performMouseInput { click() }
            click("scene-pick-1"); onNodeWithTag("scene-pick-1").assertIsOn()
            click("source-target-60000")
            onNodeWithTag("source-summary").performScrollTo().assertIsDisplayed()
            onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("source-summary"))).performTextReplacement("Owner summary မြန်မာ")
            onNodeWithTag("recap-next").assertIsDisplayed()
            onNodeWithText(UiText.IMPORT_SUBTITLES.en).performScrollTo().assertIsDisplayed().performMouseInput { click() }
            waitUntil { model.state.value.recap?.sources?.subtitles != null }
            val reopened = (store.open(fixture.dir.path) as CoreResult.Ok).value
            assertEquals(original.copy(targetMs = 60_000, scenes = original.scenes.map { it.copy(picked = true) },
                sources = original.sources.copy(subtitles = "source/subtitles.srt")), reopened.recap)
            assertEquals(immutable, File(fixture.dir, "artifacts").walkTopDown().filter { it.isFile }.associate { it.path to it.readBytes().toList() })
            runOnIdle { current.set(OpenProject(fixture.dir.path, reopened)); model.refresh() }
            assertEquals("Owner summary မြန်မာ", model.state.value.summary)
            assertEquals(srt.readText(), File(fixture.dir, "source/subtitles.srt").readText())
            onNodeWithTag("recap-next").assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("source-summary").assertDoesNotExist()
            onNodeWithContentDescription(My.breakSyllables(UiText.V3_STORY_OPTIONS.en)).performScrollTo().assertIsDisplayed().performMouseInput { click() }
            onNodeWithText(UiText.IMPORT_SUBTITLES.en).assertDoesNotExist()
            onNodeWithText(UiText.REGISTER_WRITTEN.en).performScrollTo().assertIsDisplayed()
            onNodeWithTag("block-select-0").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("block-script-edit").performScrollTo().assertIsDisplayed()
            onNodeWithTag("block-clips-edit").performScrollTo().assertIsDisplayed()
        } finally { scope.cancel() }
    }

    @Test fun importTracksExtractionScanFailureRetryAndDisabledBusyControlsUseExistingPaths() = runDesktopComposeUiTest(width = 412, height = 760) {
        val fs = InMemoryFileSystem(); val store = ProjectStore(fs); val dir = "/source.autoflow"
        val created = (store.create(dir, "Source actions", Mode.MOVIE_RECAP) as CoreResult.Ok).value
        val saved = (store.save(dir, created.copy(recap = RecapV2(rightsConfirmed = true))) as CoreResult.Ok).value
        val current = CurrentProject().apply { set(OpenProject(dir, saved)) }; val picker = Picker(listOf("/incoming.mp4"))
        fs.writeDurably("/incoming.mp4", byteArrayOf(1, 2, 3))
        var scanRelease = CompletableDeferred<Unit>(); var fail = true
        val commands = mutableListOf<List<String>>()
        val runner = ToolRunner { cmd, _, _, _, onLine ->
            commands += cmd
            when {
                cmd.first().endsWith("ffprobe") -> ToolExec.Done(0, """{"streams":[{"codec_type":"video","codec_name":"h264","width":1920,"height":1080},{"codec_type":"audio","codec_name":"aac"},{"index":5,"codec_type":"subtitle","codec_name":"subrip","tags":{"language":"my"}},{"index":7,"codec_type":"subtitle","codec_name":"hdmv_pgs_subtitle"}],"format":{"duration":"60"}}""", emptyList(), 1, false)
                cmd.any { it.contains("scdet=") } -> {
                    scanRelease.await()
                    if (fail) ToolExec.Done(1, "", listOf("scan fixture failure"), 1, false) else {
                        onLine("[scdet] lavfi.scd.score: 30, lavfi.scd.time: 10")
                        ToolExec.Done(0, "", emptyList(), 1, false)
                    }
                }
                else -> {
                    fs.writeDurably(cmd.last(), "1\n00:00:00,000 --> 00:00:01,000\nExtracted\n".encodeToByteArray())
                    ToolExec.Done(0, "", emptyList(), 1, false)
                }
            }
        }
        val locator = object : ToolLocator {
            override fun findFfmpeg() = "/bin/ffmpeg"
            override fun findVlc(): String? = null
            override fun isVlcDir(dir: String) = false
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val model = RecapModel(ProjectSession(fs, store, ProjectIndex(fs, "/index.json"), current), fs,
            MediaTools(SettingsStore(fs, "/settings.json"), locator, runner), picker, scope)
        try {
            setContent { StudioRecapScreen(model, RecapStep.SOURCE, 412f, {}, RecapNavigation({}, {}, {})) }
            fun click(tag: String) = onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performMouseInput { click() }
            fun capture(state: String) {
                val out = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "source").apply { mkdirs() }
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "02-source-$state-412-en-1.0-true.png"))
            }
            capture("empty")
            onNodeWithTag("source-import").assertIsDisplayed().performMouseInput { click() }
            waitUntil { model.state.value.recap?.movie != null && !model.state.value.busy }
            assertEquals(listOf(5, 7), model.state.value.movieSubtitleTracks.map { it.index })
            runOnIdle { model.refresh() }
            assertEquals(listOf(5, 7), model.state.value.movieSubtitleTracks.map { it.index })
            click("source-track-7"); assertEquals(7, model.state.value.recap!!.sources.subtitleTrack)
            onNodeWithTag("source-track-7").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "selected"))
            onNodeWithTag("source-track-5").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "not selected"))
            assertTrue(commands.none { "-map" in it })
            onNodeWithText(UiText.IMAGE_TRACK_HINT.en).performScrollTo().assertIsDisplayed()
            onAllNodesWithText(UiText.V3_NO_SCENES.en).assertCountEquals(2)
            onNodeWithText(UiText.FIND_SCENES_HINT.en).assertDoesNotExist()
            capture("tracks")
            click("source-track-5")
            waitUntil { model.state.value.recap?.sources?.subtitles != null }
            assertTrue(commands.any { it.windowed(2).contains(listOf("-map", "0:5")) })
            assertEquals(5, model.state.value.recap!!.sources.subtitleTrack)
            onNodeWithText(UiText.REMOVE.en).performScrollTo().assertIsDisplayed().performMouseInput { click() }
            assertTrue(fs.exists("$dir/source/subtitles.srt"), "remove clears the pointer, not the source file")
            val before = fs.read("$dir/project.json")
            click("source-density-6"); click("source-scan")
            waitUntil { model.state.value.busy }
            onNodeWithTag("source-density-6").assertIsNotEnabled()
            onNodeWithTag("recap-next").assertIsNotEnabled()
            onNodeWithText(UiText.SCENES_FINDING.en, substring = true).performScrollTo().assertIsDisplayed()
            capture("scanning")
            runOnIdle { scanRelease.complete(Unit) }; waitUntil { !model.state.value.busy }
            assertContentEquals(before, fs.read("$dir/project.json"))
            onNodeWithText("scan fixture failure", substring = true).performScrollTo().assertIsDisplayed()
            capture("scan-failed")
            runOnIdle { fail = false; scanRelease = CompletableDeferred(Unit) }
            click("source-density-16"); click("source-scan"); waitUntil { model.state.value.recap!!.scenes.isNotEmpty() }
            assertEquals(16, (store.open(dir) as CoreResult.Ok).value.recap!!.sceneThreshold)
            assertTrue(commands.any { cmd -> cmd.any { "scdet=threshold=16" in it } })
            assertEquals(1, picker.calls)
            val other = (store.create("/other", "Source actions", Mode.MOVIE_RECAP) as CoreResult.Ok).value
            runOnIdle { current.set(OpenProject("/other", other)); model.refresh() }
            assertTrue(model.state.value.movieSubtitleTracks.isEmpty(), "probe metadata must not cross projects")
        } finally { scope.cancel() }
    }

    @Test fun reopenReadsTracksWithoutWritingAndCanRetryAnUnavailableProbe() = runDesktopComposeUiTest(width = 412, height = 760) {
        val fs = InMemoryFileSystem(); val store = ProjectStore(fs); val dir = "/reopened"
        val created = (store.create(dir, "Reopened", Mode.MOVIE_RECAP) as CoreResult.Ok).value
        store.save(dir, created.copy(recap = RecapV2(rightsConfirmed = true, movie = RecapMovie("source/movie.mp4", 3, 60_000))))
        fs.writeDurably("$dir/source/movie.mp4", byteArrayOf(1, 2, 3))
        val reopened = (store.open(dir) as CoreResult.Ok).value
        val current = CurrentProject().apply { set(OpenProject(dir, reopened)) }
        val bytes = fs.read("$dir/project.json"); var probes = 0
        var hold = false; val release = CompletableDeferred<Unit>()
        val runner = ToolRunner { _, _, _, _, _ ->
            if (hold) release.await()
            probes++
            if (probes == 1) ToolExec.CannotStart("fixture probe unavailable") else ToolExec.Done(0,
                """{"streams":[{"codec_type":"video","codec_name":"h264"},{"index":4,"codec_type":"subtitle","codec_name":"subrip"}],"format":{"duration":"60"}}""", emptyList(), 1, false)
        }
        val locator = object : ToolLocator {
            override fun findFfmpeg() = "/bin/ffmpeg"
            override fun findVlc(): String? = null
            override fun isVlcDir(dir: String) = false
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val model = RecapModel(ProjectSession(fs, store, ProjectIndex(fs, "/index.json"), current), fs,
            MediaTools(SettingsStore(fs, "/settings.json"), locator, runner), null, scope)
        try {
            setContent { StudioRecapScreen(model, RecapStep.SOURCE, 412f, {}, RecapNavigation({}, {}, {})) }
            fun read() = onNodeWithTag("source-read-tracks").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            onNodeWithText(UiText.NO_EMBEDDED_SUBS.en).assertDoesNotExist()
            read(); waitUntil { probes == 1 && !model.state.value.busy }
            onNodeWithText(UiText.V3_TRACKS_UNREADABLE.text(UiLanguage.ENGLISH,
                "detail" to "ffprobe cannot start: fixture probe unavailable")).performScrollTo().assertIsDisplayed()
            onNodeWithText(UiText.MOVIE_UNREADABLE.en).assertDoesNotExist()
            onNodeWithTag("source-movie-metadata").assertTextEquals("1:00")
            val out = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "source").apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "02-source-probe-failed-412-en-1.0-true.png"))
            read(); waitUntil { model.state.value.movieSubtitleTracksRead }
            onNodeWithTag("source-track-4").performScrollTo().assertIsDisplayed()
            assertEquals(listOf(4), model.state.value.movieSubtitleTracks.map { it.index })
            assertContentEquals(bytes, fs.read("$dir/project.json"))
            assertContentEquals(byteArrayOf(1, 2, 3), fs.read("$dir/source/movie.mp4"))
            // Model race guard, after the visible read/retry above: a late probe cannot attach to another project.
            val pending = runOnIdle { hold = true; model.readMovieSubtitleTracks()!! }
            waitUntil { model.state.value.busy }
            val other = (store.create("/other", "Reopened", Mode.MOVIE_RECAP) as CoreResult.Ok).value
            val otherBytes = fs.read("/other/project.json")
            runOnIdle { current.set(OpenProject("/other", other)); model.refresh(); release.complete(Unit) }
            runBlocking { withTimeout(5_000) { pending.join() } }
            assertTrue(model.state.value.movieSubtitleTracks.isEmpty()); assertFalse(model.state.value.movieSubtitleTracksRead)
            assertContentEquals(otherBytes, fs.read("/other/project.json"))
        } finally { scope.cancel() }
    }

    @Test fun pageSelectionAndSameNameProjectSwitchDoNotReuseAReplacementConfirmation() = runDesktopComposeUiTest(width = 412, height = 760) {
        val fs = InMemoryFileSystem(); val store = ProjectStore(fs)
        fun project(dir: String) = (store.create(dir, "Same name", Mode.MOVIE_RECAP) as CoreResult.Ok).value.let {
            (store.save(dir, it.copy(recap = RecapV2(rightsConfirmed = true, movie = RecapMovie("source/movie.mp4", 3, 120_000),
                scenes = (0..30).map { n -> RecapScene(n * 1_000L, (n + 1) * 1_000L) }))) as CoreResult.Ok).value
        }
        val a = project("/a"); val b = project("/b"); val current = CurrentProject().apply { set(OpenProject("/a", a)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val picker = Picker(emptyList())
        val model = RecapModel(ProjectSession(fs, store, ProjectIndex(fs, "/index.json"), current), fs, null, picker, scope)
        try {
            setContent { StudioRecapScreen(model, RecapStep.SOURCE, 412f, {}, RecapNavigation({}, {}, {})) }
            fun click(tag: String) = onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performMouseInput { click() }
            click("source-page-next"); click("scene-pick-30")
            onNodeWithTag("scene-pick-30").assertIsOn(); onNodeWithTag("scene-pick-0").assertDoesNotExist()
            click("source-replace"); onNodeWithTag("source-replace-confirm").assertIsDisplayed()
            val before = fs.read("/b/project.json")
            runOnIdle { current.set(OpenProject("/b", b)); model.refresh() }
            onNodeWithTag("source-replace-confirm").assertDoesNotExist()
            assertEquals(0, picker.calls); assertContentEquals(before, fs.read("/b/project.json"))
        } finally { scope.cancel() }
    }
}
