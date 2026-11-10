package io.github.alalkipgen.autoflow.core.model

import io.github.alalkipgen.autoflow.core.readTestResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class ProjectJsonTest {
    private val fixture = readTestResource("fixtures/project-v1.json")

    @Test
    fun decodesSpecExample() {
        val p = CoreJson.decodeFromString<Project>(fixture)
        assertEquals(1, p.schemaVersion)
        assertEquals(Mode.MUSIC_VIDEO, p.mode)
        assertEquals(Ratio.R9_16, p.settings.ratio)
        assertEquals(Instant.parse("2026-11-10T08:00:00Z"), p.createdAt)
        assertEquals(ArtifactId("sb_222222222222"), p.currentStoryboard)
        val shot = p.shots.single()
        assertEquals(Section.INTRO, shot.section)
        assertEquals("မိုးရွာနေတဲ့ ညမှာ တစ်ယောက်တည်း", shot.lyricsMy)
        assertEquals(ArtifactId("im_555555555555"), shot.chosenRef)
        assertNull(shot.acceptedVideo)
        assertEquals(Approvals(storyboard = true, prompt = true, ref = true, video = false), shot.approvals)
        assertEquals(HandoffState.SENT_TO_FLOW, shot.handoff.state)
        assertEquals(Pass.DRAFT, shot.handoff.pass)
        assertEquals(CreditEntry("2026-11-10", 20, 1), p.flowCredits.manualLog.single())
    }

    /** Guards every persisted key name: re-encoding must give exactly the fixture's JSON tree. */
    @Test
    fun reEncodesToTheSameJsonTree() {
        val p = CoreJson.decodeFromString<Project>(fixture)
        val encoded = CoreJson.encodeToString(p)
        assertEquals(Json.parseToJsonElement(fixture), Json.parseToJsonElement(encoded))
        assertEquals(p, CoreJson.decodeFromString<Project>(encoded))
    }

    @Test
    fun unknownKeyFailsInsteadOfBeingDropped() {
        val tree = Json.parseToJsonElement(fixture).jsonObject
        val withExtra = JsonObject(tree + ("futureField" to Json.parseToJsonElement("1")))
        assertFailsWith<SerializationException> {
            CoreJson.decodeFromString<Project>(withExtra.toString())
        }
    }

    @Test
    fun minimalProjectUsesDefaults() {
        val json = """{"id":"x","name":"n","mode":"MOVIE_RECAP",
            "createdAt":"2026-11-10T08:00:00Z","updatedAt":"2026-11-10T08:00:00Z"}"""
        val p = CoreJson.decodeFromString<Project>(json)
        assertEquals(Project.SCHEMA_VERSION, p.schemaVersion)
        assertEquals(ProjectSettings(), p.settings)
        assertTrue(p.shots.isEmpty())
        assertNull(p.timeline)
    }

    @Test
    fun decodesStageBShotSpecWithoutSchemaVersion() {
        val json = """{ "shot": 3, "sceneMy": "ကော်ဖီဆိုင် မှန်ပြတင်းနောက်ကနေ...", "lyricsMy": "...",
            "subjectEn": "young Burmese woman", "actionEn": "gazes through rain-streaked café window",
            "settingEn": "night café interior, neon street outside", "cameraEn": "slow push-in, shallow DOF",
            "moodEn": "melancholic", "lightingEn": "warm interior vs cold blue exterior",
            "characters": ["HERO"], "motifEn": "rain on glass", "transitionEn": "cut" }"""
        val spec = CoreJson.decodeFromString<ShotSpec>(json)
        assertEquals(ShotSpec.SCHEMA_VERSION, spec.schemaVersion)
        assertEquals(3, spec.shot)
        assertEquals(listOf("HERO"), spec.characters)
    }

    @Test
    fun artifactIdShape() {
        val id = ArtifactId.of(ArtifactKind.IMAGE, "3fa9c01b22de8899aabbccddeeff")
        assertEquals("im_3fa9c01b22de", id.value)
        assertEquals(ArtifactKind.IMAGE, id.kind)
        assertTrue(id.isWellFormed)
        assertFalse(ArtifactId("im_XYZ").isWellFormed)
        assertFalse(ArtifactId("zz_3fa9c01b22de").isWellFormed)
        assertNull(ArtifactId("nounderscore").kind)
    }

    @Test
    fun artifactRoundTrip() {
        val a = Artifact(
            id = ArtifactId("pr_444444444444"), kind = ArtifactKind.PROMPT_IR, file = "pr_444444444444.json",
            shot = 1, inputs = mapOf("spec" to "sp_333333333333", "globalStyle" to "gs_5d41402abc4b"),
            createdAt = Instant.parse("2026-11-10T09:00:00Z"),
        )
        val encoded = CoreJson.encodeToString(a)
        assertTrue("\"kind\": \"pr\"" in encoded)
        assertEquals(a, CoreJson.decodeFromString<Artifact>(encoded))
    }
}
