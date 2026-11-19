package io.github.alalkipgen.autoflow.core.model

import io.github.alalkipgen.autoflow.core.readTestResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Phase 7 tool projects (D-77) and forward compatibility (D-78). */
class ToolProjectTest {
    private fun subtitlesProject() = Project(
        id = "id-sub", name = "Clip", mode = Mode.SUBTITLES,
        createdAt = Instant.parse("2026-11-20T08:00:00Z"), updatedAt = Instant.parse("2026-11-20T08:00:00Z"),
        subtitles = SubtitlesTool(video = SourceVideo("source/clip.mp4", 1_000, 60_000, 1920, 1080, 29.97, hasAudio = true)),
    )

    @Test
    fun musicVideoAndRecapBytesNeverGainTheSubtitlesKey() {
        val recap = Project(id = "id-r", name = "R", mode = Mode.MOVIE_RECAP, createdAt = Instant.parse("2026-11-20T08:00:00Z"),
            updatedAt = Instant.parse("2026-11-20T08:00:00Z"), recap = io.github.alalkipgen.autoflow.core.recap.RecapV2(rightsConfirmed = true))
        val projects = mapOf("music video fixture" to CoreJson.decodeFromString<Project>(readTestResource("fixtures/project-v1.json")),
            "movie recap" to recap)
        for ((name, p) in projects) {
            val encoded = CoreJson.encodeToString(p)
            assertFalse("subtitles" in Json.parseToJsonElement(encoded).jsonObject, name) // recap has its own nested one
            // Same bytes as encoding the same tree without the field, i.e. what the previous build wrote.
            val previous = CoreJson.encodeToString(JsonObject.serializer(),
                Json.parseToJsonElement(encoded).jsonObject.filterKeys { it != "subtitles" }.let(::JsonObject))
            assertEquals(previous, encoded, name)
            assertEquals(p, CoreJson.decodeFromString<Project>(encoded))
        }
    }

    @Test
    fun subtitlesProjectRoundTrips() {
        val p = subtitlesProject()
        val encoded = CoreJson.encodeToString(p)
        assertTrue("\"mode\": \"SUBTITLES\"" in encoded || "\"mode\":\"SUBTITLES\"" in encoded)
        assertEquals(p, CoreJson.decodeFromString<Project>(encoded))
        assertFalse(ProjectCompat.madeByNewerApp(encoded.encodeToByteArray()))
    }

    @Test
    fun recognisesProjectsFromANewerApp() {
        val tree = Json.parseToJsonElement(CoreJson.encodeToString(subtitlesProject())).jsonObject
        fun newer(change: (Map<String, kotlinx.serialization.json.JsonElement>) -> Map<String, kotlinx.serialization.json.JsonElement>) =
            ProjectCompat.madeByNewerApp(JsonObject(change(tree)).toString().encodeToByteArray())
        assertTrue(newer { it + ("schemaVersion" to JsonPrimitive(Project.SCHEMA_VERSION + 1)) })
        assertTrue(newer { it + ("mode" to JsonPrimitive("DUBBING_NEXT")) })
        assertTrue(newer { it + ("voice" to JsonObject(emptyMap())) })
        assertFalse(newer { it })
    }

    @Test
    fun damagedFilesAreNotMistakenForNewer() {
        assertFalse(ProjectCompat.madeByNewerApp("{\"id\": ".encodeToByteArray()))
        assertFalse(ProjectCompat.madeByNewerApp("[]".encodeToByteArray()))
        assertFalse(ProjectCompat.madeByNewerApp(byteArrayOf()))
    }
}
