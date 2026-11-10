package io.github.alalkipgen.autoflow.ai.fake

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.manual.ManualImageProvider
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.GlobalStyle
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.model.Section
import io.github.alalkipgen.autoflow.core.model.ShotSpec
import io.github.alalkipgen.autoflow.core.model.SongAnalysis
import io.github.alalkipgen.autoflow.core.prompt.PromptCompiler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class FakeTextProviderTest {
    private fun schema(text: String) = JsonSchema(Json.parseToJsonElement(text).jsonObject)

    private val str = """{"type":"string"}"""
    private val analysisSchema = schema(
        """{"type":"object","properties":{"themeMy":$str,"arcMy":$str,
           "sections":{"type":"array","items":{"type":"object","properties":{
             "type":{"type":"string","enum":["INTRO","VERSE","PRECHORUS","CHORUS","BRIDGE","OUTRO"]},
             "startMs":{"type":"integer"},"endMs":{"type":"integer"},"lyricsMy":$str}}},
           "motifsEn":{"type":"array","items":$str},"paletteEn":$str,"charactersMy":{"type":"array","items":$str}}}""",
    )
    private val shotProps = listOf("sceneMy", "lyricsMy", "subjectEn", "actionEn", "settingEn", "cameraEn", "moodEn",
        "lightingEn", "motifEn", "transitionEn").joinToString(",") { "\"$it\":$str" }
    private val storyboardSchema = schema(
        """{"type":"object","properties":{"shots":{"type":"array","minItems":4,"maxItems":4,"items":{"type":"object",
           "properties":{"shot":{"type":"integer"},$shotProps,"characters":{"type":"array","items":$str}}}}}}""",
    )

    private suspend fun ok(p: FakeTextProvider, prompt: String, s: JsonSchema): JsonElement =
        assertIs<AiResult.Ok<JsonElement>>(p.json(prompt, s, "system")).value

    @Test
    fun analysisDecodesIntoTheDomainModel() = runTest {
        val a = CoreJson.decodeFromString<SongAnalysis>(ok(FakeTextProvider(), "lyrics", analysisSchema).toString())
        assertTrue(a.themeMy.any { it in '\u1000'..'\u109F' }, a.themeMy) // Burmese script
        assertTrue(a.sections.isNotEmpty() && a.sections.all { it.type in Section.entries })
        assertTrue(a.sections.zipWithNext().all { (x, y) -> x.endMs == y.startMs })
    }

    @Test
    fun storyboardHasRequestedShotCountAndCompiles() = runTest {
        val shots = ok(FakeTextProvider(), "storyboard", storyboardSchema).jsonObject.getValue("shots").jsonArray
            .map { CoreJson.decodeFromString<ShotSpec>(it.toString()) }
        assertEquals(listOf(1, 2, 3, 4), shots.map { it.shot })
        assertTrue(shots.all { it.subjectEn.isNotBlank() && it.sceneMy.any { c -> c in '\u1000'..'\u109F' } })
        shots.forEach { assertIs<CoreResult.Ok<*>>(PromptCompiler.compile(it, GlobalStyle(), emptyList(), Ratio.R9_16, 8_000)) }
    }

    @Test
    fun deterministicPerInputAndDifferentAcrossInputs() = runTest {
        val p = FakeTextProvider()
        assertEquals(ok(p, "a", analysisSchema), ok(p, "a", analysisSchema))
        assertNotEquals(ok(p, "a", analysisSchema), ok(p, "b", analysisSchema))
        assertEquals(4, p.calls)
    }

    @Test
    fun failFirstSimulatesOverload() = runTest {
        val p = FakeTextProvider(failFirst = 1)
        assertEquals("http_503", assertIs<AiResult.Failed>(p.json("x", analysisSchema)).code)
        assertIs<AiResult.Ok<JsonObject>>(p.json("x", analysisSchema))
    }

    @Test
    fun manualImageCopiesPromptAndDefers() = runTest {
        val copied = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val p = ManualImageProvider({ copied += it }, { opened += it }, studioUrl = "https://studio.example")
        assertEquals(ManualImageProvider.INSTRUCTION, assertIs<AiResult.Deferred>(p.image("a still", Ratio.R9_16)).instruction)
        assertEquals(listOf("a still"), copied)
        assertEquals(listOf("https://studio.example"), opened)
        assertIs<AiResult.Failed>(p.image(" ", Ratio.R9_16))
        val broken = ManualImageProvider({ error("no clipboard") }, studioUrl = "x")
        assertEquals("manual_handoff", assertIs<AiResult.Failed>(broken.image("p", Ratio.R1_1)).code)
    }
}
