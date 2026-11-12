package io.github.alalkipgen.autoflow.ai.recap

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.TextProvider
import io.github.alalkipgen.autoflow.ai.fake.FakeTextProvider
import io.github.alalkipgen.autoflow.core.recap.RecapStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** docs/specs/recap.md §4: one Burmese text per picked scene; a wrong count is retried once, then invalid_output. */
class RecapScriptGeneratorTest {
    private class Scripted(vararg replies: String) : TextProvider {
        private val queue = replies.toMutableList()
        val prompts = mutableListOf<String>()
        override suspend fun json(prompt: String, schema: JsonSchema, system: String?): AiResult<JsonElement> {
            prompts += prompt
            return AiResult.Ok(Json.parseToJsonElement(queue.removeAt(0)))
        }
    }

    private val brief = RecapBrief(
        title = "Rain",
        summary = "A boy waits for his friend.",
        style = RecapStyle.COMMENTARY,
        scenes = listOf(SceneBrief(12_000, 16_200, "Where are you?"), SceneBrief(60_000, 70_000)),
    )

    @Test
    fun promptCarriesScenesSummaryDialogueAndStyle() {
        val p = RecapPrompts.prompt(brief)
        assertTrue("2 segment(s)" in p)
        assertTrue("scene 1: 0:00:12-0:00:16 (4 s, about 16 syllables) — dialogue: Where are you?" in p, p)
        assertTrue("scene 2: 0:01:00-0:01:10 (10 s, about 40 syllables)\n" in p, p)
        assertTrue("A boy waits for his friend." in p)
        assertTrue("commentary" in p)
    }

    @Test
    fun aWrongCountIsRetriedOnceWithTheError() = runTest {
        val text = Scripted("""{"segments":[{"scene":1,"textMy":"က"}]}""", """{"segments":[{"scene":1,"textMy":" က "},{"scene":2,"textMy":"ခ"}]}""")
        val r = assertIs<AiResult.Ok<List<String>>>(RecapScriptGenerator(text).write(brief))
        assertEquals(listOf("က", "ခ"), r.value)
        assertTrue("expected 2 segments, got 1" in text.prompts[1])
    }

    @Test
    fun twiceInvalidFails() = runTest {
        val text = Scripted("""{"segments":[]}""", """{"segments":[{"scene":1,"textMy":""},{"scene":2,"textMy":"ခ"}]}""")
        val r = assertIs<AiResult.Failed>(RecapScriptGenerator(text).write(brief))
        assertEquals(RecapScriptGenerator.INVALID_OUTPUT, r.code)
    }

    @Test
    fun fakeProviderGivesOneTextPerScene() = runTest {
        val r = assertIs<AiResult.Ok<List<String>>>(RecapScriptGenerator(FakeTextProvider()).write(brief))
        assertEquals(2, r.value.size)
    }
}
