package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.*
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.*
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.projects.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.platform.DesktopToolLocator
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class RecapPreviewSourceTest {
    @OptIn(ExperimentalTestApi::class)
    @Test fun badVlcPathShowsWhyAndRetainsReachableExactFrameInsteadOfPretendingToPlay() = runDesktopComposeUiTest(width = 412, height = 760) {
        val root = Files.createTempDirectory("preview-fallback").toFile()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val fs = JvmFs(); val store = ProjectStore(fs); val current = CurrentProject()
            val dir = File(root, "fallback.autoflow").path
            val p = (store.create(dir, "Fallback", Mode.MOVIE_RECAP) as CoreResult.Ok).value
            File(dir, "source/movie.mp4").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(0)) }
            val story = RecapStory(source = "manual", blocks = listOf(RecapBlock("stable", listOf(RecapClip(0, 1_000)), "written")))
            val script = (ArtifactStore(fs, dir).put(CoreJson.encodeToString(RecapStory.serializer(), story).encodeToByteArray(), ArtifactKind.RECAP_SCRIPT, "json") as CoreResult.Ok).value
            val recap = RecapV2(movie = RecapMovie("source/movie.mp4", 1, 1_000, 320, 180), story = script.id)
            val saved = (store.save(dir, p.copy(recap = recap)) as CoreResult.Ok).value
            current.set(OpenProject(dir, saved))
            val settings = SettingsStore(fs, File(root, "settings.json").path)
            settings.update { it.copy(vlcPath = File(root, "no-vlc").path) }
            val model = RecapModel(ProjectSession(fs, store, ProjectIndex(fs, File(root, "index.json").path), current), fs,
                MediaTools(settings, DesktopToolLocator(fs), null), null, scope).also { it.refresh() }
            setContent { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { RecapTimelinePreview(model); ExactFrameCard(model) } }
            onNodeWithTag("timeline-play").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("VLC path does not contain", substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("timeline-video").assertDoesNotExist()
            onNodeWithTag("timeline-position").assertTextContains("0 / 1000", substring = true)
            onNodeWithText(UiText.EXACT_FRAME.en).performScrollTo().assertIsDisplayed().performMouseInput { click() }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("ffmpeg was not found", substring = true).fetchSemanticsNodes().isNotEmpty() }
        } finally { scope.cancel(); root.deleteRecursively() }
    }


    @Test fun resolvedTakePresenceAndOutputCursorAreBoundToProjectRevisionNotTheNewProject() {
        val root = Files.createTempDirectory("preview-source").toFile()
        try {
            val fs = JvmFs(); val store = ProjectStore(fs); val current = CurrentProject()
            val session = ProjectSession(fs, store, ProjectIndex(fs, File(root, "index.json").path), current)
            val dir = File(root, "a.autoflow").path
            val p = (store.create(dir, "A", Mode.MOVIE_RECAP) as CoreResult.Ok).value
            val a = ArtifactStore(fs, dir)
            val file = (a.put(byteArrayOf(1), ArtifactKind.AUDIO, "wav") as CoreResult.Ok).value
            val block = RecapBlock("stable", listOf(RecapClip(2_000, 3_000)), "written")
            val story = RecapStory(source = "manual", blocks = listOf(block))
            val take = BlockTake(block.id, file.id, TakeSource.RECORDED, durationMs = 2_000, spokenHash = SpokenText.hash(block))
            val r = RecapV2(movie = RecapMovie("source/movie.mp4", 1, 9_000, 320, 180), takes = listOf(take))
            val saved = (store.save(dir, p.copy(recap = r)) as CoreResult.Ok).value
            current.set(OpenProject(dir, saved))
            val resolver = RecapPreviewSource(session, fs, null)
            val state = RecapUiState(projectDir = dir, recap = r, story = story)
            val source = assertNotNull(resolver.resolve(state))
            assertEquals(2_400L, source.timeline.durationMs)
            // F-5 (D-94): the preview plays the export's voice cue (the take at the block start + lead) and no music yet.
            val takePath = File(dir, "artifacts/" + file.file).path
            assertEquals(listOf(takePath), source.timeline.voice.map { it.file })
            assertEquals(RecapFit.LEAD_MS, source.timeline.voice.single().startMs)
            assertNull(source.timeline.voiceAt(RecapFit.LEAD_MS - 1)); assertEquals(takePath, source.timeline.voiceAt(1_000)?.file)
            assertNull(source.music)
            resolver.position(source, 1_200); assertEquals(1_200L, resolver.currentPosition())
            resolver.position(source, 9_999); assertEquals(2_399L, resolver.currentPosition())
            val newer = (store.save(dir, saved.copy(name = "new revision")) as CoreResult.Ok).value
            current.set(OpenProject(dir, newer))
            assertNull(resolver.currentPosition(), "old Exact frame cursor is not reused across project revisions")
            resolver.position(source, 500); assertNull(resolver.currentPosition(), "late old cursor refused")
            val otherDir = File(root, "b.autoflow").path
            current.set(OpenProject(otherDir, (store.create(otherDir, "B", Mode.MOVIE_RECAP) as CoreResult.Ok).value))
            assertNull(resolver.resolve(state)); resolver.position(source, 800); assertNull(resolver.currentPosition())
            // A take with no file has the same no-voice interval as the real native export layout.
            current.set(OpenProject(dir, saved.copy(recap = r.copy(takes = listOf(take.copy(audio = ArtifactId("au_0123456789ab")))))))
            val missing = assertNotNull(resolver.resolve(state.copy(recap = current.state.value!!.project.recap)))
            assertEquals(1_000L, missing.timeline.durationMs); assertTrue(missing.timeline.captions.isEmpty())
            val bgm = (a.put(byteArrayOf(2), ArtifactKind.AUDIO, "m4a") as CoreResult.Ok).value
            val scored = (store.save(dir, newer.copy(recap = r.copy(audio = RecapAudio(bgm = bgm.id, bgmVolume = 0.3)))) as CoreResult.Ok).value
            current.set(OpenProject(dir, scored))
            val music = assertNotNull(assertNotNull(resolver.resolve(state.copy(recap = scored.recap))).music)
            assertEquals(File(dir, "artifacts/" + bgm.file).path, music.file); assertEquals(0.3f, music.volume)
        } finally { root.deleteRecursively() }
    }
}
