package io.github.alalkipgen.autoflow.core

import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shipped `config/tools.json` (D-84) reads, and equals the built-in defaults the app falls back to. */
class ToolsConfigFileTest {
    @Test
    fun theShippedFileEqualsTheDefaults() {
        assertEquals(ToolsConfig(), ToolsConfig.parse(File("../config/tools.json").readText()))
    }

    @Test
    fun missingBrokenOrNewerFilesNeverBreakStartUp() {
        assertEquals(ToolsConfig(), ToolsConfig.parse(null))
        assertEquals(ToolsConfig(), ToolsConfig.parse("{ not json"))
        val newer = ToolsConfig.parse("""{ "schemaVersion": 2, "subtitles": { "minCueMs": 500, "future": true }, "karaoke": {} }""")
        assertEquals(ToolsConfig.Subtitles(minCueMs = 500), newer.subtitles)
        assertEquals(ToolsConfig().transcribe, newer.transcribe)
    }
}
