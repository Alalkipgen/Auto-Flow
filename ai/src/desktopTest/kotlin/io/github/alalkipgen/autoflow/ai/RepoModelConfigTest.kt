package io.github.alalkipgen.autoflow.ai

import io.github.alalkipgen.autoflow.ai.config.ImageMode
import io.github.alalkipgen.autoflow.ai.config.ModelCapability
import io.github.alalkipgen.autoflow.ai.config.ModelConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Guards the checked-in `config/models.json` (Gradle runs tests from the module directory). */
class RepoModelConfigTest {
    @Test
    fun repositoryConfigParses() {
        val config = assertIs<AiResult.Ok<ModelConfig>>(ModelConfig.parse(File("../config/models.json").readText())).value
        assertTrue(config.text.candidates.isNotEmpty())
        assertEquals(ImageMode.MANUAL, ModelCapability.from(config).imageMode) // D-14
        assertEquals(config.image.ratios.size, config.image.supportedRatios.size)
        // Edge voices (D-86): set up, Burmese Thiha + Nilar, and their model id is never a Gemini candidate.
        assertTrue(config.tts.edge.available)
        assertEquals(listOf("Thiha", "Nilar"), config.tts.edge.voicesFor("my").map { it.name })
        assertEquals(20, config.tts.edge.rpm, "F-3: Edge keeps its own pace, not Gemini's 3 rpm")
        assertTrue(config.tts.candidates.none { it.id == config.tts.edge.model })
    }
}
