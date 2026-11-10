package io.github.alalkipgen.autoflow.ai.storyboard

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.TextProvider
import io.github.alalkipgen.autoflow.ai.fake.FakeTextProvider
import io.github.alalkipgen.autoflow.core.model.Character
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.model.Section
import io.github.alalkipgen.autoflow.core.model.Shot
import io.github.alalkipgen.autoflow.core.model.SongAnalysis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Answers with [replies] in order and records the prompts. */
private class ScriptedText(vararg replies: AiResult<String>) : TextProvider {
    private val queue = ArrayDeque(replies.toList())
    val prompts = mutableListOf<String>()

    override suspend fun json(prompt: String, schema: JsonSchema, system: String?): AiResult<JsonElement> {
        prompts += prompt
        return when (val r = queue.removeFirst()) {
            is AiResult.Ok -> AiResult.Ok(Json.parseToJsonElement(r.value))
            is AiResult.RateLimited -> r
            is AiResult.Failed -> r
            is AiResult.Deferred -> r
        }
    }
}

class StoryboardGeneratorTest {
    private val brief = StoryboardBrief(
        lyricsMy = "မိုးတွေရွာနေတယ်\nမင်းကိုသတိရတယ်",
        settings = ProjectSettings(durationMs = 24_000, clipMs = 8_000, songStyle = "ballad"),
    )
    private val shots = listOf(Shot(1, 0, 8_000), Shot(2, 8_000, 8_000, section = Section.CHORUS), Shot(3, 16_000, 8_000))

    private fun spec(n: Int) = """{"shot":$n,"sceneMy":"မိုး","lyricsMy":"","subjectEn":"a woman","actionEn":"walks",
        "settingEn":"street","cameraEn":"wide","moodEn":"sad","lightingEn":"blue","characters":[],"motifEn":"","transitionEn":"cut"}"""

    @Test
    fun fakeProviderGivesAFullStoryboardOffline() = runTest {
        val gen = StoryboardGenerator(FakeTextProvider())
        val analysis = assertIs<AiResult.Ok<SongAnalysis>>(gen.analyze(brief)).value
        assertTrue(analysis.sections.all { it.startMs < 24_000 && it.endMs <= 24_000 })
        val specs = assertIs<AiResult.Ok<List<io.github.alalkipgen.autoflow.core.model.ShotSpec>>>(gen.storyboard(brief, analysis, shots)).value
        assertEquals(listOf(1, 2, 3), specs.map { it.shot })
        assertTrue(specs.all { it.sceneMy.isNotBlank() && it.subjectEn.isNotBlank() })

        // Regenerating one shot keeps its number even though the fake counts from 1.
        val one = assertIs<AiResult.Ok<List<io.github.alalkipgen.autoflow.core.model.ShotSpec>>>(gen.storyboard(brief, analysis, listOf(shots[2]))).value
        assertEquals(3, one.single().shot)
    }

    @Test
    fun wrongShotCountIsRetriedOnceWithTheError() = runTest {
        val text = ScriptedText(AiResult.Ok("""{"shots":[${spec(1)}]}"""), AiResult.Ok("""{"shots":[${spec(3)},${spec(2)},${spec(1)}]}"""))
        val specs = assertIs<AiResult.Ok<List<io.github.alalkipgen.autoflow.core.model.ShotSpec>>>(
            StoryboardGenerator(text).storyboard(brief, SongAnalysis(), shots),
        ).value
        assertEquals(listOf(1, 2, 3), specs.map { it.shot }, "model numbering used when it is the asked set")
        assertEquals(2, text.prompts.size)
        assertTrue("expected 3 shots, got 1" in text.prompts[1])
    }

    @Test
    fun secondInvalidAnswerFailsAndOtherResultsPassThrough() = runTest {
        val bad = ScriptedText(AiResult.Ok("""{"shots":"no"}"""), AiResult.Ok("""{"nothing":1}"""))
        val failed = assertIs<AiResult.Failed>(StoryboardGenerator(bad).storyboard(brief, SongAnalysis(), shots))
        assertEquals(StoryboardGenerator.INVALID_OUTPUT, failed.code)

        val limited = ScriptedText(AiResult.RateLimited(30_000))
        assertIs<AiResult.RateLimited>(StoryboardGenerator(limited).analyze(brief))
        assertEquals(1, limited.prompts.size)
    }

    @Test
    fun promptsCarryTheBriefTimingsCharactersAndNotes() = runTest {
        val withHero = brief.copy(characters = listOf(Character("HERO", nameMy = "ကို", descriptionEn = "young man, 25")))
        val a = StoryboardPrompts.analysisPrompt(withHero)
        assertTrue(a.startsWith("Analyse these Burmese song lyrics for a 9:16"))
        assertTrue(a.endsWith("မိုးတွေရွာနေတယ်\nမင်းကိုသတိရတယ်"))
        assertTrue("Song style: ballad." in a)
        assertFalse(a.lines().any { it.startsWith(" ") }, "template is not indented")

        val b = StoryboardPrompts.storyboardPrompt(withHero, "{}", shots, notes = mapOf(2 to "ကော်ဖီဆိုင်ထဲမှာ"))
        assertTrue("shot 2: 0:08.0-0:16.0 CHORUS" in b)
        assertTrue("{{HERO}}" in b && "HERO = ကို (young man, 25)" in b)
        assertTrue("shot 2: ကော်ဖီဆိုင်ထဲမှာ" in b)
        assertTrue("no named characters" in StoryboardPrompts.storyboardPrompt(brief, "{}", shots, emptyMap()))
        assertEquals("1:04.5", StoryboardPrompts.timecode(64_500))
    }
}
