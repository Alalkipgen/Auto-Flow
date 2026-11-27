package io.github.alalkipgen.autoflow.ai.dubbing

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.TextProvider
import io.github.alalkipgen.autoflow.ai.Usage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/** D-2c Shorten contract (specs/dubbing.md §5): input with maxChars, line-aligned answer, one retry with the reason. */
class DubShortenerTest {
    private val lines = listOf(ShortenLine("c1", "ဒီည ကျွန်တော်တို့ ထွက်သွားကြရမယ်။", 14), ShortenLine("c3", "မိုးမရွာခင် သွားကြစို့။", 12))

    private class Scripted(vararg val answers: AiResult<JsonElement>) : TextProvider {
        val prompts = mutableListOf<String>()
        override suspend fun json(prompt: String, schema: JsonSchema, system: String?): AiResult<JsonElement> {
            prompts += prompt
            return answers[prompts.size - 1]
        }
    }

    private fun ok(json: String) = AiResult.Ok(Json.parseToJsonElement(json), Usage("t"))

    @Test fun sendsIdsTextAndMaxCharsAndReturnsLinesInOrder() = runTest {
        val p = Scripted(ok("""{"lines":[{"id":"c1","spokenMy":" ဒီည ထွက်ရမယ်။ "},{"id":"c3","spokenMy":"မိုးမရွာခင်"}]}"""))
        assertEquals(AiResult.Ok(listOf("c1" to "ဒီည ထွက်ရမယ်။", "c3" to "မိုးမရွာခင်"), Usage("t")), DubShortener(p).shorten(lines))
        val sent = Json.parseToJsonElement(p.prompts.single()).jsonObject["lines"]!!.jsonArray
        assertEquals(listOf("c1", "c3"), sent.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(listOf(14, 12), sent.map { it.jsonObject["maxChars"]!!.jsonPrimitive.int })
    }

    @Test fun overTheLimitOrInvalidIsAskedAgainOnceWithTheReason() = runTest {
        val long = """{"lines":[{"id":"c1","spokenMy":"ဒီည ကျွန်တော်တို့ ထွက်ရမယ်။"},{"id":"c3","spokenMy":"မိုးမရွာခင်"}]}"""
        val p = Scripted(ok(long), ok(long))
        val r = assertIs<AiResult.Ok<List<Pair<String, String>>>>(DubShortener(p).shorten(lines))
        assertEquals("ဒီည ကျွန်တော်တို့ ထွက်ရမယ်။", r.value[0].second, "still long after the retry: returned, the owner decides")
        assertTrue("line c1 has" in p.prompts[1] && "at most 14" in p.prompts[1], p.prompts[1])

        val bad = ok("""{"lines":[{"id":"c3","spokenMy":"x"},{"id":"c1","spokenMy":"y"}]}""")
        val q = Scripted(bad, bad)
        assertEquals(DubShortener.INVALID_OUTPUT, assertIs<AiResult.Failed>(DubShortener(q).shorten(lines)).code)
        assertEquals(2, q.prompts.size)
        val busy = AiResult.Failed("http_503", "overloaded")
        assertEquals(busy, DubShortener(Scripted(busy)).shorten(lines))
    }
}
