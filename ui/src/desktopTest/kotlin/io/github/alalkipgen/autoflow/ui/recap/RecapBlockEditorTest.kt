package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.*
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.*
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.projects.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Real serialized files and store conflicts, not mocked setter/callback assertions. */
class RecapBlockEditorTest {
    private class Fixture {
        val root = Files.createTempDirectory("uiv3-block-edit").toFile()
        val dir = File(root, "one.autoflow").path
        val fs = JvmFs(); val store = ProjectStore(fs); val current = CurrentProject()
        val session = ProjectSession(fs, store, ProjectIndex(fs, File(root, "index.json").path), current)
        val story = RecapStory(source = RecapStory.SOURCE_MANUAL, blocks = listOf(
            RecapBlock("first", listOf(RecapClip(2_000, 4_000), RecapClip(6_000, 8_000)), "မူလ စာသား"),
            RecapBlock("second", listOf(RecapClip(4_000, 5_500)), "မပြောင်းရ")))
        val original: Project
        val editor = RecapBlockEditor(session, fs) {}
        init {
            val p = (store.create(dir, "One", Mode.MOVIE_RECAP) as CoreResult.Ok).value
            val id = (ArtifactStore(fs, dir).put(CoreJson.encodeToString(RecapStory.serializer(), story).encodeToByteArray(), ArtifactKind.RECAP_SCRIPT, "json") as CoreResult.Ok).value.id
            val take = BlockTake("first", ArtifactId("au_0123456789ab"), TakeSource.RECORDED, durationMs = 1000, spokenHash = SpokenText.hash(story.blocks.first()))
            original = (store.save(dir, p.copy(recap = RecapV2(story = id, storyApproved = true, takes = listOf(take),
                chosenTakes = mapOf(take.block to take.audio), layers = listOf(Layer("border", LayerType.BORDER))))) as CoreResult.Ok).value
            current.set(OpenProject(dir, original))
        }
        fun saved() = (store.open(dir) as CoreResult.Ok).value
        fun bytes() = File(dir, "project.json").readBytes().toList()
        fun artifacts() = File(dir, "artifacts").listFiles()!!.associate { it.name to it.readBytes().toList() }
        fun readStory() = (session.readJson(saved().recap!!.story!!, RecapStory.serializer()) as CoreResult.Ok).value
    }
    @Test fun nativeTextAndSpeakAsSaveReopenPreserveIdsClipsOtherBlocksAndImmutableHistory() {
        val f = Fixture(); val old = f.artifacts()
        f.editor.edit("first"); assertTrue(f.editor.save(" စာတန်း အသစ် ", " အသံထွက် အသစ် "))
        val saved = f.saved().recap!!; val story = f.readStory()
        assertEquals(f.story.copy(blocks = listOf(f.story.blocks.first().copy(textMy = "စာတန်း အသစ်", speakMy = "အသံထွက် အသစ်"), f.story.blocks.last())), story)
        assertEquals(f.original.recap!!.copy(story = saved.story, storyApproved = false), saved)
        assertNotEquals(f.original.recap!!.story, saved.story); assertNull(f.editor.state.value.draft)
        old.forEach { (name, bytes) -> assertEquals(bytes, f.artifacts()[name], "immutable $name") }
        assertTrue(SpokenText.isStale(saved.takes.single(), story.blocks.first()))
        f.current.set(OpenProject(f.dir, f.saved())); f.editor.edit("first")
        assertTrue(f.editor.save("စာတန်းသာ ပြောင်း", "")); assertNull(f.readStory().blocks.first().speakMy)
        assertEquals("စာတန်းသာ ပြောင်း", SpokenText.of(f.readStory().blocks.first()))
    }
    @Test fun cancelUnchangedSaveAndUnknownIdWriteNothing() {
        val f = Fixture(); val bytes = f.bytes(); val artifacts = f.artifacts()
        f.editor.edit("first"); f.editor.cancel()
        f.editor.edit("first"); assertTrue(f.editor.save(f.story.blocks.first().textMy, ""))
        f.editor.edit("not-a-block"); assertNull(f.editor.state.value.draft); assertFalse(f.editor.save("wrong", ""))
        assertEquals(bytes, f.bytes()); assertEquals(artifacts, f.artifacts())
    }
    @Test fun projectSwitchCannotWriteIntoEitherFolder() {
        val f = Fixture(); f.editor.edit("first"); val before = f.bytes(); val artifacts = f.artifacts()
        val dir = File(f.root, "two.autoflow").path
        val other = (f.store.create(dir, "Two", Mode.MOVIE_RECAP) as CoreResult.Ok).value
        f.current.set(OpenProject(dir, other)); val otherBytes = File(dir, "project.json").readBytes()
        assertFalse(f.editor.save("wrong project", "")); assertEquals(before, f.bytes()); assertEquals(artifacts, f.artifacts())
        assertContentEquals(otherBytes, File(dir, "project.json").readBytes())
    }
    @Test fun sessionAndExternalRevisionChangesAreRejectedWithoutLosingTheirData() {
        val f = Fixture(); f.editor.edit("first")
        assertIs<CoreResult.Ok<*>>(f.session.save(f.original.copy(name = "In-session change")))
        val sessionBytes = f.bytes(); assertFalse(f.editor.save("stale", "")); assertEquals(sessionBytes, f.bytes())
        f.editor.edit("first")
        assertIs<CoreResult.Ok<*>>(f.store.update(f.dir) { it.copy(name = "Other window") })
        val externalBytes = f.bytes(); assertFalse(f.editor.save("stale", "")); assertEquals(externalBytes, f.bytes())
        assertEquals("Other window", f.saved().name); assertEquals(f.original.recap, f.saved().recap)
    }
}
