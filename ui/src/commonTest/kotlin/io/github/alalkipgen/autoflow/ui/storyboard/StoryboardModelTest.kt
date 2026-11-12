package io.github.alalkipgen.autoflow.ui.storyboard

import io.github.alalkipgen.autoflow.ai.fake.FakeTextProvider
import io.github.alalkipgen.autoflow.ai.storyboard.StoryboardGenerator
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.workflow.SongBeats
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class StoryboardModelTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val service = StoryboardService(session, StoryboardGenerator(FakeTextProvider()))

    private suspend fun TestScope.model(): Pair<StoryboardModel, StoryboardJobs> {
        val dir = "/p/Rain.autoflow"
        val p = (store.create(dir, "Rain", Mode.MUSIC_VIDEO, ProjectSettings(durationMs = 30_000, clipMs = 8_000)) as CoreResult.Ok).value
        current.set(OpenProject(dir, p))
        session.writeLyrics("မိုးတွေရွာနေတယ်")
        service.generateAll()
        val jobs = StoryboardJobs(service, backgroundScope)
        return StoryboardModel(session, service, jobs) to jobs
    }

    @Test
    fun cardsShowTimecodesScenesVersionsAndApprovals() = runTest {
        val (m, _) = model()
        val s = m.state.first()
        assertEquals("Rain", s.projectName)
        assertEquals(listOf("0:00–0:08", "0:08–0:16", "0:16–0:24", "0:24–0:30"), s.cards.map { it.timecode })
        assertTrue(s.cards.all { it.spec!!.sceneMy.isNotBlank() && it.tag == "v1" && !it.approved && !it.stale && !it.hasDownstream })
        assertEquals(listOf("v1"), s.versions.map { it.label })

        assertTrue(m.approve(2))
        assertEquals(listOf(false, true, false, false), m.current().cards.map { it.approved })
        assertTrue(m.approveAll())
        assertEquals(4, m.current().approvedCount)

        assertTrue(m.edit(3) { it.copy(sceneMy = "ကော်ဖီဆိုင်ထဲမှာ") })
        val edited = m.current().cards[2]
        assertEquals("ကော်ဖီဆိုင်ထဲမှာ", edited.spec!!.sceneMy)
        assertEquals("v1 · edited", edited.tag)
        assertFalse(edited.approved, "D-21: a changed spec needs approval again")
        assertNull(m.message.value)
        assertFalse(m.edit(9) { it }, "unknown shot")
        assertEquals("Shot 9 has no storyboard yet", m.message.value)
    }

    @Test
    fun aiActionsRunAsJobsAndVersionsCanBeSwitched() = runTest {
        val (m, jobs) = model()
        val v1 = m.current().versions.single().id
        assertTrue(m.regenerate(1, keepScene = false))
        assertEquals(StoryboardModel.regenerateLabel(1), jobs.state.value.running)
        assertEquals("Regenerating shot #01", jobs.state.value.running)
        runCurrent()
        assertEquals(StoryboardResult.Done, jobs.state.value.last)

        val p = session.open!!.project
        session.save(p.copy(settings = p.settings.copy(durationMs = 16_000)))
        assertTrue(m.regenerateAll())
        runCurrent()
        val s = m.current()
        assertEquals(listOf("v1", "v2"), s.versions.map { it.label })
        assertTrue(s.versions[1].current)
        assertTrue(s.cards.all { it.tag == "v2" })

        assertEquals(2, s.cards.size)
        assertTrue(m.switchVersion(v1))
        assertTrue(m.current().versions[0].current)
        assertEquals(4, m.current().cards.size)
        assertTrue(m.current().cards.all { it.tag == "v1" })
    }

    @Test
    fun quickActionsWaitForARunningJob() = runTest {
        val (m, jobs) = model()
        jobs.run("Regenerating shot #02") { awaitCancellation() }
        assertFalse(m.approveAll())
        assertEquals("Wait for “Regenerating shot #02” to finish.", m.message.value)
        assertEquals(0, m.current().approvedCount)
        assertFalse(m.regenerate(1, keepScene = true), "one job at a time")
        jobs.cancel()
        runCurrent()
        assertTrue(m.approveAll())
        assertNull(m.message.value)
    }

    @Test
    fun snapToBeatMovesCutsOntoTheBeatsAndOffRestoresThem() = runTest {
        val (m, jobs) = model()
        fs.createDirectories("/c")
        fs.writeDurably("/c/s.mp3", byteArrayOf(1, 2, 3))
        session.importSong("/c/s.mp3")
        val starts = { session.open!!.project.shots.map { it.startMs } }

        assertTrue(m.setSnapToBeat(true))
        jobs.state.first { it.running == null }
        assertEquals(StoryboardResult.Failed(BeatFinder.NO_FFMPEG), jobs.state.value.last, "no ffmpeg in this test and nothing cached")
        assertFalse(m.current().snapToBeat)

        session.writeBeats(SongBeats(session.songSha256()!!, 119.6, listOf(7_800, 16_100, 24_400)))
        assertTrue(m.setSnapToBeat(true))
        jobs.state.first { it.running == null }
        assertEquals(StoryboardResult.Done, jobs.state.value.last)
        assertEquals(listOf(0L, 7_800L, 16_100L, 24_000L), starts(), "24 400 is outside ±250 ms")
        assertEquals(30_000, session.open!!.project.shots.last().let { it.startMs + it.durationMs })
        assertTrue(m.current().snapToBeat)
        assertEquals("♩ Cuts snapped to the beat: 2 of 3 moved · about 120 BPM.", m.message.value)

        service.generateAll()
        assertEquals(listOf(0L, 7_800L, 16_100L, 24_000L), starts(), "a new version snaps too while the switch is on")

        assertTrue(m.setSnapToBeat(false))
        assertEquals(listOf(0L, 8_000L, 16_000L, 24_000L), starts())
        assertFalse(m.current().snapToBeat)
    }
}
