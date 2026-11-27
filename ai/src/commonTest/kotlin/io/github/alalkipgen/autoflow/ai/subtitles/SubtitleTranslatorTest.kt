package io.github.alalkipgen.autoflow.ai.subtitles

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.TextProvider
import io.github.alalkipgen.autoflow.ai.Usage
import io.github.alalkipgen.autoflow.ai.fake.FakeTextProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** S-6 translation contract (spec §8): input shape, line-aligned answer, one retry with the reason, then the batch fails. */
class SubtitleTranslatorTest {
    private val brief = TranslateBrief("en", SubtitleTranslator.STYLE_SPOKEN, names = listOf("Mira" to "မီရာ"),
        context = listOf("Hello." to "မင်္ဂလာပါ။", "Wait" to null), lines = listOf("c3" to "We have to leave tonight.", "c4" to "Mira, now!"))

    private class Scripted(vararg val answers: AiResult<JsonElement>) : TextProvider {
        val prompts = mutableListOf<String>(); val systems = mutableListOf<String?>()
        override suspend fun json(prompt: String, schema: JsonSchema, system: String?): AiResult<JsonElement> {
            prompts += prompt; systems += system
            return answers[prompts.size - 1]
        }
    }

    private fun ok(json: String) = AiResult.Ok(Json.parseToJsonElement(json), Usage("t"))

    @Test fun sendsTheBriefAsJsonAndReturnsLinesInOrder() = runTest {
        val p = Scripted(ok("""{"lines":[{"id":"c3","textMy":" ဒီည ထွက်ရမယ်။ "},{"id":"c4","textMy":"မီရာ၊ အခုပဲ!"}]}"""))
        val r = SubtitleTranslator(p).translate(brief)
        assertEquals(AiResult.Ok(listOf("c3" to "ဒီည ထွက်ရမယ်။", "c4" to "မီရာ၊ အခုပဲ!"), Usage("t")), r)
        val input = Json.parseToJsonElement(p.prompts.single()).jsonObject
        assertEquals("en", input["lang"]!!.jsonPrimitive.content)
        assertEquals("spoken", input["style"]!!.jsonPrimitive.content)
        assertEquals("မီရာ", input["names"]!!.jsonArray.single().jsonObject["my"]!!.jsonPrimitive.content)
        assertEquals(2, input["context"]!!.jsonArray.size)
        assertEquals(null, input["context"]!!.jsonArray[1].jsonObject["textMy"])
        assertEquals(listOf("c3", "c4"), input["lines"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertTrue(p.systems.single()!!.contains("everyday spoken Burmese"))
        assertTrue(SubtitleTranslator.system(SubtitleTranslator.STYLE_FORMAL).contains("formal written Burmese"))
    }

    @Test fun wrongIdsAreAskedAgainOnceWithTheReasonThenTheBatchFails() = runTest {
        val swapped = ok("""{"lines":[{"id":"c4","textMy":"a"},{"id":"c3","textMy":"b"}]}""")
        val fixed = ok("""{"lines":[{"id":"c3","textMy":"a"},{"id":"c4","textMy":"b"}]}""")
        val again = Scripted(swapped, fixed)
        assertIs<AiResult.Ok<*>>(SubtitleTranslator(again).translate(brief))
        assertTrue(again.prompts[1].contains("line 1 has id c4, expected c3"), again.prompts[1])

        val empty = ok("""{"lines":[{"id":"c3","textMy":"a"},{"id":"c4","textMy":"  "}]}""")
        val short = ok("""{"lines":[{"id":"c3","textMy":"a"}]}""")
        val bad = Scripted(empty, short)
        val r = SubtitleTranslator(bad).translate(brief)
        assertIs<AiResult.Failed>(r)
        assertEquals(SubtitleTranslator.INVALID_OUTPUT, r.code)
        assertTrue(r.msg.contains("expected 2 lines, got 1"), r.msg)
        assertTrue(bad.prompts[1].contains("line c4 has no textMy"))
    }

    @Test fun busyIsReturnedWithoutRetryAndTheFakeFitsTheSchema() = runTest {
        val busy = Scripted(AiResult.Failed("http_503", "overloaded"))
        assertEquals(AiResult.Failed("http_503", "overloaded"), SubtitleTranslator(busy).translate(brief))
        assertEquals(1, busy.prompts.size)
        // The offline fake answers the schema's shape (ids come out wrong, so it is a retry-then-fail path, never a crash).
        assertIs<AiResult<*>>(SubtitleTranslator(FakeTextProvider()).translate(brief))
    }
}
