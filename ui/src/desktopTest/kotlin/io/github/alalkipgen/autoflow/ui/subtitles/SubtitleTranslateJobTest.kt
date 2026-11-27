package io.github.alalkipgen.autoflow.ui.subtitles

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.TextProvider
import io.github.alalkipgen.autoflow.ai.Usage
import io.github.alalkipgen.autoflow.ai.subtitles.SubtitleTranslator
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.SubtitlesTool
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.recap.AiJobStore
import io.github.alalkipgen.autoflow.core.recap.RpmPacer
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleCue
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.core.subtitles.TranslateName
import io.github.alalkipgen.autoflow.core.subtitles.myHash
import io.github.alalkipgen.autoflow.ui.jobs.DesktopAiJobRunner
import io.github.alalkipgen.autoflow.ui.jobs.RecapJobProcessor
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject

/**
 * S-6a with the spec's fake translator (`textMy = "MY:" + text`): batches of `translate.batchLines`, context from the
 * lines before, one new version at the end; an unusable batch fails alone and the rest is kept; busy pauses and
 * Resume sends only the batches left; hand-edited lines and lines changed meanwhile are never overwritten.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubtitleTranslateJobTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), CurrentProject())
    private val jobs = AiJobStore(fs, "/files/ai-jobs")
    private val dir = "/p/Film.autoflow"
    private val config = ToolsConfig(translate = ToolsConfig.Translate(batchLines = 2, contextLines = 1))

    /** Every request's input JSON; [bad] request numbers answer with swapped ids, [busy] ones with 503. */
    private val sent = mutableListOf<kotlinx.serialization.json.JsonObject>()
    private val bad = mutableSetOf<Int>()
    private val busy = mutableSetOf<Int>()
    private val text = object : TextProvider {
        override suspend fun json(prompt: String, schema: JsonSchema, system: String?): AiResult<JsonElement> {
            val input = Json.parseToJsonElement(prompt.substringBefore("\n\n")).jsonObject
            sent += input
            val n = sent.size
            if (n in busy) return AiResult.Failed("http_503", "overloaded")
            val lines = input["lines"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["text"]!!.jsonPrimitive.content }
                .let { if (n in bad) it.reversed() else it }
            return AiResult.Ok(buildJsonObject { putJsonArray("lines") { lines.forEach { (id, t) -> addJsonObject { put("id", id); put("textMy", "MY:$t") } } } }, Usage("t"))
        }
    }

    private val old: ArtifactId
    init {
        val p = (store.create(dir, "Film", Mode.SUBTITLES) as CoreResult.Ok).value
        val doc = SubtitleDoc(source = SubtitleDoc.SOURCE_SRT, lang = "en", nextId = 6, cues = listOf(
            SubtitleCue("c1", 1_000, 2_000, "One"),
            SubtitleCue("c2", 3_000, 4_000, "Two", textMy = "နှစ်", myFrom = myHash("Two")),
            SubtitleCue("c3", 5_000, 6_000, "Three"),
            SubtitleCue("c4", 7_000, 8_000, "Four", textMy = "ကိုယ်ပြင်", myFrom = myHash("Four"), myEdited = true),
            SubtitleCue("c5", 9_000, 10_000, "Five"),
        ))
        old = put(doc)
        store.save(dir, p.copy(subtitles = SubtitlesTool(video = SourceVideo("source/movie.mp4", 9, 60_000), current = old)))
    }

    private fun put(doc: SubtitleDoc) =
        (ArtifactStore(fs, dir).put(CoreJson.encodeToString(SubtitleDoc.serializer(), doc).encodeToByteArray(), ArtifactKind.SUBTITLES, "json") as CoreResult.Ok).value.id
    private fun current(): ArtifactId = (store.open(dir) as CoreResult.Ok).value.subtitles!!.current!!
    private fun doc(id: ArtifactId = current()): SubtitleDoc =
        CoreJson.decodeFromString(SubtitleDoc.serializer(), (ArtifactStore(fs, dir).readBytes(id) as CoreResult.Ok).value.decodeToString())

    private val job = SubtitleTranslateJob(session, fs, SubtitleTranslator(text), config)

    private fun TestScope.runner(): Pair<CoroutineScope, DesktopAiJobRunner> {
        val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        return scope to DesktopAiJobRunner(jobs, scope, RecapJobProcessor({ null }, { null }, { job }), pacer = { RpmPacer(1_000) })
    }

    @Test fun translatesNewLinesInBatchesWithContextIntoOneNewVersion() = runTest {
        val plan = job.plan(dir, replaceEdited = false)!!
        assertEquals(listOf(listOf("c1", "c3"), listOf("c5")), plan.batches, "c2 has fresh Burmese, c4 was typed by hand")
        assertEquals(3, plan.lines)
        assertTrue(sent.isEmpty(), "planning sends nothing")
        val (scope, r) = runner()
        val id = job.enqueue(r, dir, plan, SubtitleTranslator.STYLE_FORMAL, listOf(TranslateName("One", "ဝမ်း")), replaceEdited = false)
        advanceUntilIdle()
        val saved = jobs.load(id)!!
        assertEquals(AiJobKind.SUB_TRANSLATE, saved.kind)
        assertEquals(AiJobState.DONE, saved.state, saved.error)
        assertEquals(2, sent.size)
        assertEquals("formal", sent[0]["style"]!!.jsonPrimitive.content)
        assertEquals("en", sent[0]["lang"]!!.jsonPrimitive.content)
        assertEquals("ဝမ်း", sent[0]["names"]!!.jsonArray.single().jsonObject["my"]!!.jsonPrimitive.content)
        assertEquals(listOf("Four"), sent[1]["context"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content }, "the line just before c5")

        val st = doc()
        assertEquals(old, st.derivedFrom)
        assertEquals(SubtitleDoc.SOURCE_EDIT, st.source)
        assertEquals(listOf("MY:One", "နှစ်", "MY:Three", "ကိုယ်ပြင်", "MY:Five"), st.cues.map { it.textMy })
        assertTrue(st.cues.all { it.textMy == null || it.myFrom == myHash(it.text) })
        assertEquals(SubtitleTranslateJob.Result(3, 0), job.result(dir, id))
        assertNull(doc(old).cues.first().textMy, "the old version is untouched")
        assertEquals(0, job.plan(dir, false)!!.lines, "nothing left to translate")
        assertEquals(1, job.plan(dir, true)!!.lines, "only the hand-edited line, when asked")
        scope.cancel()
    }

    @Test fun anUnusableBatchFailsAloneAndRetryIsOnlyItsLines() = runTest {
        bad += setOf(1, 2) // batch 1 answers with swapped ids twice
        val (scope, r) = runner()
        val id = job.enqueue(r, dir, job.plan(dir, false)!!, SubtitleTranslator.STYLE_SPOKEN, emptyList(), false)
        advanceUntilIdle()
        assertEquals(AiJobState.DONE, jobs.load(id)!!.state, jobs.load(id)!!.error)
        assertEquals(3, sent.size, "one retry for the bad batch, then the next batch")
        assertEquals(SubtitleTranslateJob.Result(1, 2), job.result(dir, id))
        assertEquals(listOf(null, "နှစ်", null, "ကိုယ်ပြင်", "MY:Five"), doc().cues.map { it.textMy })
        assertEquals(listOf(listOf("c1", "c3")), job.plan(dir, false)!!.batches, "Retry = the lines not translated")
        scope.cancel()
    }

    @Test fun busyPausesResumeSendsOnlyTheRestAndChangedOriginalsAreKept() = runTest {
        busy += 2
        val (scope, r) = runner()
        val id = job.enqueue(r, dir, job.plan(dir, false)!!, SubtitleTranslator.STYLE_SPOKEN, emptyList(), false)
        advanceUntilIdle()
        assertEquals(AiJobState.PAUSED, jobs.load(id)!!.state)
        assertEquals(old, current(), "no version before every batch is in")
        // Meanwhile the original of c5 changes (a new version): its Burmese must not be set from the old text.
        val changed = doc().let { d -> d.copy(derivedFrom = old, cues = d.cues.map { if (it.id == "c5") it.copy(text = "Five!") else it }) }
        val edited = put(changed)
        store.save(dir, (store.open(dir) as CoreResult.Ok).value.let { it.copy(subtitles = it.subtitles!!.copy(current = edited)) })
        scope.cancel()

        val (scope2, r2) = runner()
        r2.enqueue(jobs.load(id)!!)
        advanceUntilIdle()
        assertEquals(AiJobState.DONE, jobs.load(id)!!.state)
        assertEquals(3, sent.size, "batch 1 was not sent again")
        val st = doc()
        assertEquals(edited, st.derivedFrom)
        assertEquals(listOf("MY:One", "နှစ်", "MY:Three", "ကိုယ်ပြင်", null), st.cues.map { it.textMy })
        assertEquals("Five!", st.cues.last().text)
        assertEquals(SubtitleTranslateJob.Result(2, 0), job.result(dir, id))
        scope2.cancel()
    }
}
