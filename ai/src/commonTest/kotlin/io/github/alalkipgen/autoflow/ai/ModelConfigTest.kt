package io.github.alalkipgen.autoflow.ai

import io.github.alalkipgen.autoflow.ai.config.ImageMode
import io.github.alalkipgen.autoflow.ai.config.ModelCapability
import io.github.alalkipgen.autoflow.ai.config.ModelConfig
import io.github.alalkipgen.autoflow.core.model.Ratio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelConfigTest {
    private fun parse(text: String) = assertIs<AiResult.Ok<ModelConfig>>(ModelConfig.parse(text)).value

    @Test
    fun parsesSpecShapeAndDropsPlaceholders() {
        val c = parse(
            """{ "text": { "default": "x", "candidates": ["a-1", " ... ", "a-1", "b-2"] },
                 "image": { "provider": "manual", "ratios": ["16:9", "9:16", "3:2"] },
                 "tts": { "candidates": [{"id":"m-tts","langs":["my","en"]}], "replication": "unknown" },
                 "flow": { "creditsHint": { "fast": 20, "quality": 100 }, "url": "https://labs.google/fx/tools/flow" },
                 "future": { "anything": 1 } }""",
        )
        assertEquals(listOf("a-1", "b-2"), c.text.candidates)
        assertEquals(listOf(Ratio.R16_9, Ratio.R9_16), c.image.supportedRatios)
        assertEquals(20, c.flow.creditsHint["fast"])
        assertEquals(Availability.UNKNOWN, c.tts.replicationAvailability)
        assertEquals(listOf("m-tts"), c.tts.chainFor("my"))
        assertEquals(listOf("m-tts"), c.tts.chainFor("en"))
        assertTrue(c.tts.chainFor("fr").isEmpty())
    }

    @Test
    fun ttsCandidatesFilterByLanguage() {
        val c = parse(
            """{ "tts": { "candidates": [
                  {"id":"tts-full","langs":["my","en"]},
                  {"id":"tts-en-only","langs":["en"]}
               ], "replication": "unavailable" } }""",
        )
        assertEquals(listOf("tts-full"), c.tts.chainFor("my"))
        assertEquals(listOf("tts-full", "tts-en-only"), c.tts.chainFor("en"))
        assertTrue(c.tts.chainFor("ja").isEmpty())
        assertEquals(Availability.UNAVAILABLE, c.tts.replicationAvailability)
    }

    @Test
    fun emptyTtsCandidatesGivesEmptyChain() {
        val c = parse("""{ "tts": { "candidates": [], "replication": "unavailable" } }""")
        assertTrue(c.tts.chainFor("my").isEmpty())
        assertTrue(c.tts.allIds.isEmpty())
    }

    @Test
    fun missingSectionsFallBackToSafeDefaults() {
        val c = parse("{}")
        assertTrue(c.text.candidates.isEmpty())
        val cap = ModelCapability.from(c)
        assertFalse(cap.canGenerateText)
        assertEquals(ImageMode.MANUAL, cap.imageMode)
    }

    @Test
    fun invalidJsonIsAFailureNotAnException() {
        assertEquals("invalid_config", assertIs<AiResult.Failed>(ModelConfig.parse("{ text: ")).code)
    }

    @Test
    fun imageApiNeedsConfigCandidatesAndAPositiveProbe() {
        val c = parse("""{ "image": { "provider": "gemini", "candidates": ["img-1"] } }""")
        assertEquals(ImageMode.MANUAL, ModelCapability.from(c).imageMode)
        assertEquals(ImageMode.MANUAL, ModelCapability.from(c, Availability.UNAVAILABLE).imageMode)
        assertEquals(ImageMode.API, ModelCapability.from(c, Availability.AVAILABLE).imageMode)
    }
}
