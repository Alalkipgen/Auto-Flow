package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.recap.BlockTake
import io.github.alalkipgen.autoflow.core.recap.FitChoice
import io.github.alalkipgen.autoflow.core.recap.Layer
import io.github.alalkipgen.autoflow.core.recap.LayerParams
import io.github.alalkipgen.autoflow.core.recap.LayerType
import io.github.alalkipgen.autoflow.core.recap.RecapBlock
import io.github.alalkipgen.autoflow.core.recap.RecapClip
import io.github.alalkipgen.autoflow.core.recap.RecapFit
import io.github.alalkipgen.autoflow.core.recap.RecapMovie
import io.github.alalkipgen.autoflow.core.recap.RecapScene
import io.github.alalkipgen.autoflow.core.recap.RecapStory
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.core.recap.SpokenText
import io.github.alalkipgen.autoflow.core.recap.Srt
import io.github.alalkipgen.autoflow.core.recap.TakeSource
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.render.Quality
import io.github.alalkipgen.autoflow.ui.export.RecapFrames
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Audit F05 / F07 through the app's own models: caption timing never rewrites the movie clips, and the export job
 * (the one Export renders, [RecapFrames.job]) keeps every clip and layer and plays the chosen take.
 */
class RecapV2EditExportTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val settings = SettingsStore(fs, "/a/settings.json")
    private val dir = "/p/R.autoflow"
    private val artifacts = ArtifactStore(fs, dir)

    private val a = RecapBlock("b_a", listOf(RecapClip(0, 4_200), RecapClip(6_000, 8_000)), "ကောင်လေး")
    private val b = RecapBlock("b_b", listOf(RecapClip(9_000, 11_000)), "သူငယ်ချင်း")
    private val story = RecapStory(source = RecapStory.SOURCE_MANUAL, blocks = listOf(a, b))
    private val border = Layer("border", LayerType.BORDER, params = LayerParams(px = 12, colour = "#FF00FF"))

    private fun recap() = current.state.value!!.project.recap!!

    /** The project folder, made before any artifact goes in. */
    private val created by lazy { (store.create(dir, "R", Mode.MOVIE_RECAP) as CoreResult.Ok).value }

    private fun audio(tag: String): ArtifactId =
        created.let { (artifacts.put("RIFF-$tag".encodeToByteArray(), ArtifactKind.AUDIO, "wav", provider = "gemini") as CoreResult.Ok).value.id }

    private fun take(block: RecapBlock, audio: ArtifactId, ms: Long) =
        BlockTake(block.id, audio, TakeSource.TTS, provider = "gemini", durationMs = ms, spokenHash = SpokenText.hash(block))

    /** A v2 project: approved two-block story (block a has two clips), a BORDER layer and a fit choice for b. */
    private fun open(edit: (RecapV2) -> RecapV2 = { it }): RecapModel {
        val p = created
        val sid = (artifacts.put(CoreJson.encodeToString(RecapStory.serializer(), story).encodeToByteArray(), ArtifactKind.RECAP_SCRIPT, "json", provider = "manual") as CoreResult.Ok).value.id
        val recap = RecapV2(
            rightsConfirmed = true,
            movie = RecapMovie("source/movie.mp4", 9, 20_000, 1920, 1080),
            scenes = listOf(RecapScene(0, 4_200, picked = true)),
            story = sid, storyApproved = true,
            layers = listOf(border),
            fit = mapOf(b.id to FitChoice.SPEED.name),
        )
        val saved = (store.save(dir, p.copy(recap = edit(recap))) as CoreResult.Ok).value
        current.set(OpenProject(dir, saved))
        return RecapModel(session, fs, null, null, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
            .also { it.refresh() }
    }

    private fun storyOnDisk(): RecapStory = (session.readJson(recap().story!!, RecapStory.serializer()) as CoreResult.Ok).value

    @Test fun captionTimingEditMustNotRewriteMovieClips() = runTest {
        val m = open()
        val before = recap()
        assertTrue(m.saveSegmentTiming(0, 300, 1_900))
        val after = recap()
        // Stored as the block's caption chunk edges (ms into the take), keyed by block id.
        assertEquals(mapOf(a.id to listOf(300L, 1_900L)), after.captions.chunkEdits)
        // The story — both clips of block a, their order, block b — the approval and the fit stay as they were.
        assertEquals(before.story, after.story, "no new story artifact")
        assertEquals(story, storyOnDisk())
        assertTrue(after.storyApproved)
        assertEquals(before.fit, after.fit)
        assertEquals(before.layers, after.layers)
        // Reloaded from disk (another window / a restart) the edges are there.
        val reloaded = (ProjectStore(fs).open(dir) as CoreResult.Ok).value.recap!!
        assertEquals(mapOf(a.id to listOf(300L, 1_900L)), reloaded.captions.chunkEdits)
        assertEquals(300L to 1_900L, m.state.value.captionWindow(0))
        assertTrue(m.state.value.hasCaptionTiming(0))
        // Invalid input is refused without touching anything; Automatic drops the edges.
        assertFalse(m.saveSegmentTiming(0, 1_900, 300))
        assertFalse(m.saveSegmentTiming(5, 0, 300))
        assertTrue(m.resetCaptionTiming(0))
        assertEquals(emptyMap(), recap().captions.chunkEdits)
        assertEquals(story, storyOnDisk())
    }

    @Test fun captionTimingMustFitTheTakeAndTheSrtAndBurnedCaptionsAgree() = runTest {
        val va = audio("a")
        val m = open { it.copy(takes = listOf(take(a, va, 2_400))) }
        assertFalse(m.saveSegmentTiming(0, 300, 2_500), "past the end of the 2.4 s take")
        assertEquals(0L to 2_400L, m.state.value.captionWindow(0), "automatic: the whole take")
        assertTrue(m.saveSegmentTiming(0, 300, 1_900))
        val job = assertNotNull(RecapFrames(session, settings, fs).job(current.state.value!!, Quality.PREVIEW, "Padauk", "exports/x", "x"))
        val srt = Srt.parse(job.srt!!)
        assertEquals(listOf(500L to 2_100L), srt.map { it.startMs to it.endMs }, "0.2 s lead + the owner's window")
        assertEquals(srt.map { Triple(it.startMs, it.endMs, it.text) }, job.graph.overlays.map { Triple(it.startMs, it.endMs, it.text) })
    }

    @Test fun exportMustKeepAllV2LayersAndClips() = runTest {
        open()
        val job = assertNotNull(RecapFrames(session, settings, fs).job(current.state.value!!, Quality.EXPORT, null, "exports/x", "x"))
        assertEquals(listOf(0L to 4_200L, 6_000L to 8_000L, 9_000L to 11_000L), job.graph.clips.map { it.trimInMs to it.trimOutMs })
        assertEquals(8_200, job.graph.clips.sumOf { it.durationMs })
        assertTrue(border in job.graph.layers, "the BORDER layer is exported")
        assertEquals(RecapFit.totalMs(RecapFit.rows(recap(), story)), job.graph.clips.sumOf { it.durationMs })
    }

    @Test fun exportMustUseTheChosenTake() = runTest {
        val old = audio("old")
        val newer = audio("new")
        val m = open { it.copy(takes = listOf(take(a, old, 1_000), take(a, newer, 2_000)), chosenTakes = mapOf(a.id to old)) }
        val job = assertNotNull(RecapFrames(session, settings, fs).job(current.state.value!!, Quality.EXPORT, null, "exports/x", "x"))
        val file = (artifacts.get(old) as CoreResult.Ok).value.file
        assertEquals(listOf("artifacts/$file"), job.graph.voice!!.cues.map { it.file })
        assertEquals(old, m.state.value.currentTake(0)?.audio)
        assertEquals(1, RecapFit.rows(recap(), story).count { it.takeMs == 1_000L })
    }

    @Test fun fitChoiceIsStoredByBlockId() = runTest {
        val m = open()
        m.setFitChoice(a.id, FitChoice.FREEZE)
        assertEquals(mapOf(b.id to "SPEED", a.id to "FREEZE"), recap().fit)
        m.setFitChoice("b_unknown", FitChoice.SPEED)
        assertEquals(setOf(a.id, b.id), recap().fit.keys, "a block that is not in the story gets nothing")
        assertEquals(FitChoice.FREEZE, recap().fitChoice(a.id))
    }
}
