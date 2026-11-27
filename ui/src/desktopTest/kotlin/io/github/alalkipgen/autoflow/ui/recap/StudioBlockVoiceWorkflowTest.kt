package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.ai.fake.FakeTtsProvider
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.*
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.projects.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class StudioBlockVoiceWorkflowTest {
    @Test fun visibleConfirmRevoicesOnlyItsNativeBlockAndReopensWithAllOtherContentAndImmutableHistory() = runDesktopComposeUiTest(width = 412, height = 760) {
        val f = QaRecapFixture.create(Files.createTempDirectory("uiv3-one-voice").toFile())
        val fs = JvmFs(); val store = ProjectStore(fs)
        val current = CurrentProject().apply { set(OpenProject(f.dir.path, f.project)) }
        val session = ProjectSession(fs, store, ProjectIndex(fs, File(f.dir.parentFile, "index.json").path), current)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val tts = FakeTtsProvider()
        val model = RecapModel(session, fs, null, null, scope, tts = tts)
        val before = File(f.dir, "project.json").readBytes()
        val artifacts = File(f.dir, "artifacts").listFiles()!!.associate { it.name to it.readBytes().toList() }
        try {
            setContent { CompositionLocalProvider(LocalCompact provides true) {
                StudioRecapScreen(model, RecapStep.VOICE, 412f, {}, RecapNavigation({}, {}, {}))
            } }
            onNodeWithTag("voice-open-1").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("voice-block-request").assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("voice-block-confirm-text").assertTextEquals("SECOND")
            assertEquals(0, tts.calls)
            onNodeWithTag("voice-block-cancel").assertIsDisplayed().performMouseInput { click() }
            assertContentEquals(before, File(f.dir, "project.json").readBytes())
            onNodeWithTag("voice-block-request").assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("voice-block-confirm").assertIsDisplayed().performMouseInput { click() }
            waitUntil(timeoutMillis = 5_000) { tts.calls == 1 && !model.state.value.busy }
            val saved = (store.open(f.dir.path) as CoreResult.Ok).value.recap!!
            val original = f.project.recap!!; val id = f.story.blocks.last().id
            assertEquals(1, tts.calls)
            assertEquals(original.takes + saved.takes.last(), saved.takes)
            assertEquals(id, saved.takes.last().block)
            assertNotEquals(original.chosenTakes[id], saved.chosenTakes[id])
            assertEquals(original.chosenTakes + (id to saved.takes.last().audio), saved.chosenTakes)
            assertEquals(original.copy(takes = saved.takes, chosenTakes = saved.chosenTakes, voiceLock = saved.voiceLock), saved)
            assertEquals(original.takes.first(), saved.takes.first())
            artifacts.forEach { (name, bytes) -> assertEquals(bytes, File(f.dir, "artifacts/$name").readBytes().toList(), "immutable $name") }
            onNodeWithTag("voice-detail-number").performScrollTo().assertIsDisplayed().assertTextEquals("Block 2 of 2")
        } finally { scope.cancel() }
    }
    @Test fun oldProjectOrRevisionConfirmationAndLateHistoryNeverTargetNewProject() {
        val f = QaRecapFixture.create(Files.createTempDirectory("uiv3-voice-guard").toFile())
        val fs = JvmFs(); val store = ProjectStore(fs); val current = CurrentProject().apply { set(OpenProject(f.dir.path, f.project)) }
        val session = ProjectSession(fs, store, ProjectIndex(fs, File(f.dir.parentFile, "index.json").path), current)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val tts = FakeTtsProvider()
        val model = RecapModel(session, fs, null, null, scope, tts = tts)
        try {
            val id = f.story.blocks.first().id; val request = model.voiceInspector.begin(id)!!
            val entries = model.voiceInspector.history(request)!!
            assertEquals(1, entries.size); assertTrue(entries.single().selected)
            assertTrue(File(entries.single().file!!).isFile)
            val before = File(f.dir, "project.json").readBytes()
            assertNull(model.voiceInspector.begin("missing-id"))
            val otherDir = File(f.dir.parentFile, "other.autoflow").path
            val other = (store.create(otherDir, "Other", Mode.MOVIE_RECAP) as CoreResult.Ok).value
            current.set(OpenProject(otherDir, other))
            val otherBytes = File(otherDir, "project.json").readBytes()
            assertFalse(model.voiceInspector.confirm(request)); assertNull(model.voiceInspector.history(request))
            assertContentEquals(before, File(f.dir, "project.json").readBytes()); assertContentEquals(otherBytes, File(otherDir, "project.json").readBytes())
            current.set(OpenProject(f.dir.path, f.project))
            assertIs<CoreResult.Ok<*>>(session.save(f.project.copy(name = "Edited while confirming")))
            val edited = File(f.dir, "project.json").readBytes()
            assertFalse(model.voiceInspector.confirm(request)); assertContentEquals(edited, File(f.dir, "project.json").readBytes())
            assertEquals(0, tts.calls)
        } finally { scope.cancel() }
    }
    @Test fun externalRevisionAndLivePronunciationInvalidateTheCapturedRequestAndMissingHistoryFilesStayExplicit() {
        val f = QaRecapFixture.create(Files.createTempDirectory("uiv3-voice-revision").toFile())
        val fs = JvmFs(); val store = ProjectStore(fs); val current = CurrentProject().apply { set(OpenProject(f.dir.path, f.project)) }
        val session = ProjectSession(fs, store, ProjectIndex(fs, File(f.dir.parentFile, "index.json").path), current)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val tts = FakeTtsProvider()
        val lexicons = MutableStateFlow(Lexicon.EMPTY)
        val model = RecapModel(session, fs, null, null, scope, tts = tts, lexicons = lexicons)
        try {
            val id = f.story.blocks.first().id; val request = model.voiceInspector.begin(id)!!
            val before = File(f.dir, "project.json").readBytes()
            lexicons.value = Lexicon(listOf(LexiconEntry("FIRST", "different pronunciation")))
            assertFalse(model.voiceInspector.confirm(request)); assertEquals(0, tts.calls)
            assertContentEquals(before, File(f.dir, "project.json").readBytes())
            lexicons.value = Lexicon.EMPTY
            assertIs<CoreResult.Ok<*>>(store.update(f.dir.path) { it.copy(name = "External edit") })
            val edited = File(f.dir, "project.json").readBytes()
            assertFalse(model.voiceInspector.confirm(request)); assertNull(model.voiceInspector.history(request))
            assertContentEquals(edited, File(f.dir, "project.json").readBytes())
            val missing = f.project.recap!!.takes.first().copy(audio = ArtifactId("au_ffffffffffff"))
            val saved = (store.update(f.dir.path) { p -> p.copy(recap = p.recap!!.copy(
                takes = p.recap!!.takes + missing, chosenTakes = p.recap!!.chosenTakes + (id to missing.audio))) } as CoreResult.Ok).value
            current.set(OpenProject(f.dir.path, saved))
            val bytes = File(f.dir, "project.json").readBytes()
            val rows = model.voiceInspector.history(model.voiceInspector.begin(id)!!)!!
            assertEquals(2, rows.size); assertTrue(rows.last().selected); assertNull(rows.last().file)
            assertTrue(File(rows.first().file!!).isFile)
            assertContentEquals(bytes, File(f.dir, "project.json").readBytes()); assertEquals(0, tts.calls)
        } finally { scope.cancel() }
    }
}
