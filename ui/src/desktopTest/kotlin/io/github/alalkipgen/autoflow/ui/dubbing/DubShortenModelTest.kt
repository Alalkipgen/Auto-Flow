package io.github.alalkipgen.autoflow.ui.dubbing

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.TextProvider
import io.github.alalkipgen.autoflow.ai.Usage
import io.github.alalkipgen.autoflow.ai.dubbing.DubShortener
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.dubbing.DubRules
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.DubTake
import io.github.alalkipgen.autoflow.core.model.DubbingTool
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.SubtitlesTool
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleCue
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.subtitles.SubtitleVersions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/** D-2c (specs/dubbing.md §5): Shorten only TOO_LONG lines, show old / new, Use per line → one new version, approval cleared. */
class DubShortenModelTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val dir = "/p/Dub.autoflow"
    private val lock = VoiceLock("gemini", "tts-a", "Kore", "my")
    private val doc = SubtitleDoc(source = "srt", nextId = 3, cues = listOf(
        SubtitleCue("c1", 0, 1_500, "We have to leave tonight.", textMy = "ဒီည ကျွန်တော်တို့ ထွက်သွားကြရမယ်။"),
        SubtitleCue("c2", 2_000, 4_000, "Now.", textMy = "အခု။"),
    ))
    private val prompts = mutableListOf<String>()
    private val text = object : TextProvider {
        override suspend fun json(prompt: String, schema: JsonSchema, system: String?): AiResult<JsonElement> {
            prompts += prompt
            return AiResult.Ok(Json.parseToJsonElement("""{"lines":[{"id":"c1","spokenMy":"ဒီည ထွက်ရမယ်။"}]}"""), Usage("t"))
        }
    }

    init {
        val p = (store.create(dir, "Dub", Mode.DUBBING) as CoreResult.Ok).value
        val st = (ArtifactStore(fs, dir).put(CoreJson.encodeToString(SubtitleDoc.serializer(), doc).encodeToByteArray(),
            ArtifactKind.SUBTITLES, "json") as CoreResult.Ok).value.id
        val take = { c: SubtitleCue, ms: Long -> DubTake(ArtifactId("au_00000000000${c.id.last()}"), DubRules.takeKey(c, Lexicon.EMPTY, lock, null), ms) }
        val tool = DubRules.approve(DubbingTool(lock = lock, takes = mapOf("c1" to take(doc.cues[0], 3_000), "c2" to take(doc.cues[1], 600))),
            8_000, 600_000, doc, 1)!!
        val saved = (store.save(dir, p.copy(dubbing = tool, subtitles = SubtitlesTool(
            video = SourceVideo("source/movie.mp4", 9, 8_000, 1920, 1080, 25.0), current = st))) as CoreResult.Ok).value
        current.set(OpenProject(dir, saved))
    }

    @Test fun onlyTooLongLinesAreSentAndChosenOnesBecomeOneNewVersion() = runTest {
        val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val voice = DubVoiceModel(session, null, null, scope)
        val script = DubScriptModel(session, fs).also { it.open() }
        val m = DubShortenModel(DubShortener(text), scope)
        val rows = voice.rows(current.state.value, doc)
        val lines = m.lines(rows, doc)
        val chars = doc.cues[0].textMy!!.length
        assertEquals(listOf("c1"), lines.map { it.id }, "c2 fits: never sent")
        assertEquals(chars * 1_900 * 95 / (3_000 * 100), lines.single().maxChars, "chars × slot / take × 0.95")

        m.ask(dir, rows, doc)
        advanceUntilIdle()
        val sent = Json.parseToJsonElement(prompts.single()).jsonObject["lines"]!!.jsonArray.single().jsonObject
        assertEquals(lines.single().maxChars, sent["maxChars"]!!.jsonPrimitive.int)
        val row = m.state.value.rows.single()
        assertEquals(doc.cues[0].textMy, row.old)
        assertEquals("ဒီည ထွက်ရမယ်။", row.new)
        assertFalse(m.use(script), "nothing chosen yet: nothing saved")
        val before = current.state.value!!.project.subtitles!!.current

        m.toggle("c1")
        assertTrue(m.use(script))
        val p = current.state.value!!.project
        assertNotEquals(before, p.subtitles!!.current, "one new st version")
        val now = SubtitleVersions(session, fs, kotlin.time.Clock.System).read(dir, p.subtitles!!.current!!)!!
        assertEquals("ဒီည ထွက်ရမယ်။", now.cues[0].spokenMy)
        assertEquals(doc.cues[0].textMy, now.cues[0].textMy, "the subtitle text is never changed")
        assertNull(p.dubbing!!.scriptApproved, "a spoken-line change clears the approval")
        assertEquals(listOf("c1"), DubRules.toSpeak(p.dubbing, now, Lexicon.EMPTY), "only the changed line needs a new voice")
        assertEquals(UiText.DUB_SHORTEN_USED, script.state.value.notice?.key)
        assertTrue(m.state.value.rows.isEmpty())
        scope.cancel()
    }

    @Test fun withoutGeminiItSaysSo() {
        val m = DubShortenModel(null, CoroutineScope(Job()))
        m.ask(dir, emptyList(), doc)
        assertEquals(UiText.GEMINI_UNAVAILABLE, m.state.value.notice?.key)
    }
}
