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

class RecapClipEditorTest {
    private class Fixture {
        val f = QaRecapFixture.create(Files.createTempDirectory("uiv3-clips").toFile())
        val fs = JvmFs(); val store = ProjectStore(fs)
        val current = CurrentProject().apply { set(OpenProject(f.dir.path, f.project)) }
        val session = ProjectSession(fs, store, ProjectIndex(fs, File(f.dir.parentFile, "index.json").path), current)
        val editor = RecapBlockEditor(session, fs) {}
        val id = f.story.blocks.first().id
        fun saved() = (store.open(f.dir.path) as CoreResult.Ok).value
        fun story(): RecapStory = (session.readJson(saved().recap!!.story!!, RecapStory.serializer()) as CoreResult.Ok).value
        fun bytes() = File(f.dir, "project.json").readBytes().toList()
        fun artifacts() = File(f.dir, "artifacts").listFiles()!!.associate { it.name to it.readBytes().toList() }
    }
    @Test fun trimAddReorderAndRemovePersistNativeRangesOnlyAndReopenWithoutChangingTakesOrOldArtifacts() {
        val f = Fixture(); val old = f.artifacts(); val original = f.f.project.recap!!
        val ranges = listOf(RecapClip(6_000, 7_000), RecapClip(2_500, 4_000), RecapClip(8_000, 9_000))
        f.editor.edit(f.id, clips = true); assertTrue(f.editor.saveClips(ranges))
        val saved = f.saved().recap!!
        assertEquals(f.f.story.copy(blocks = listOf(f.f.story.blocks.first().copy(clips = ranges), f.f.story.blocks.last())), f.story())
        assertEquals(original.copy(story = saved.story, storyApproved = false), saved)
        assertFalse(SpokenText.isStale(saved.takes.first(), f.story().blocks.first()))
        old.forEach { (name, bytes) -> assertEquals(bytes, f.artifacts()[name], "immutable $name") }
        f.current.set(OpenProject(f.f.dir.path, f.saved())); f.editor.edit(f.id, clips = true)
        assertEquals(ranges, f.editor.state.value.draft!!.block.clips)
        assertTrue(f.editor.saveClips(ranges.drop(1)))
        assertEquals(ranges.drop(1), f.story().blocks.first().clips)
        assertEquals(original.chosenTakes, f.saved().recap!!.chosenTakes)
    }
    @Test fun invalidRangesAndWrongEditorModeCannotWriteAnyBytes() {
        val f = Fixture(); val before = f.bytes(); val old = f.artifacts()
        f.editor.edit(f.id); assertFalse(f.editor.saveClips(listOf(RecapClip(0, 500))))
        f.editor.edit(f.id, clips = true); assertFalse(f.editor.save("wrong editor", ""))
        for (ranges in listOf(emptyList(), listOf(RecapClip(-1, 500)), listOf(RecapClip(1000, 1499)),
            listOf(RecapClip(2000, 1000)), listOf(RecapClip(9000, 10001)), listOf(RecapClip(0, Long.MAX_VALUE)))) {
            assertFalse(f.editor.saveClips(ranges)); assertNotNull(f.editor.state.value.problem)
            assertEquals(before, f.bytes()); assertEquals(old, f.artifacts())
        }
    }
    @Test fun cancelNoOpProjectSwitchAndBothRevisionConflictsPreserveBothProjects() {
        val f = Fixture(); val before = f.bytes(); val old = f.artifacts()
        f.editor.edit(f.id, clips = true); f.editor.cancel()
        f.editor.edit(f.id, clips = true); assertTrue(f.editor.saveClips(f.f.story.blocks.first().clips))
        assertEquals(before, f.bytes()); assertEquals(old, f.artifacts())
        f.editor.edit(f.id, clips = true)
        val otherDir = File(f.f.dir.parentFile, "other.autoflow").path
        val other = (f.store.create(otherDir, "Other", Mode.MOVIE_RECAP) as CoreResult.Ok).value
        val otherBytes = File(otherDir, "project.json").readBytes()
        f.current.set(OpenProject(otherDir, other)); assertFalse(f.editor.saveClips(listOf(RecapClip(0, 500))))
        assertEquals(before, f.bytes()); assertContentEquals(otherBytes, File(otherDir, "project.json").readBytes())
        f.current.set(OpenProject(f.f.dir.path, f.f.project)); f.editor.edit(f.id, clips = true)
        assertIs<CoreResult.Ok<*>>(f.session.save(f.f.project.copy(name = "In-session edit")))
        val sessionBytes = f.bytes(); assertFalse(f.editor.saveClips(listOf(RecapClip(0, 500)))); assertEquals(sessionBytes, f.bytes())
        f.editor.edit(f.id, clips = true)
        assertIs<CoreResult.Ok<*>>(f.store.update(f.f.dir.path) { it.copy(name = "Other window") })
        val externalBytes = f.bytes(); assertFalse(f.editor.saveClips(listOf(RecapClip(0, 500)))); assertEquals(externalBytes, f.bytes())
        assertEquals("Other window", f.saved().name); assertEquals(old, f.artifacts().filterKeys { it in old })
    }
    @Test fun exactInputSplitAndTouchingJoinNeverInventMovieGapOrOverflow() {
        assertEquals(2_001L, ClipRangeInput.parse("2.001")); assertEquals(500L, ClipRangeInput.parse(" 0.5 "))
        assertEquals(Long.MAX_VALUE, ClipRangeInput.parse("9223372036854775.807"))
        listOf("-1", "NaN", "Infinity", "1e3", "1.0001", "", "9223372036854775.808").forEach { assertNull(ClipRangeInput.parse(it)) }
        val original = RecapClip(2_000, 4_000)
        val split = ClipRangeInput.split(original, 2_750)!!
        assertEquals(listOf(RecapClip(2_000, 2_750), RecapClip(2_750, 4_000)), split)
        assertEquals(original, ClipRangeInput.merge(split[0], split[1]))
        assertNull(ClipRangeInput.split(original, 2_499)); assertNull(ClipRangeInput.split(original, 3_501))
        assertNull(ClipRangeInput.merge(original, RecapClip(6_000, 8_000)))
        assertNull(ClipRangeInput.merge(original, RecapClip(3_500, 5_000)))
        assertFalse(ClipRangeInput.valid(listOf(RecapClip(0, Long.MAX_VALUE), RecapClip(0, 500)), Long.MAX_VALUE))
        assertTrue(ClipRangeInput.valid(split, 10_000))
    }
}