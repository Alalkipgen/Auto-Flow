package io.github.alalkipgen.autoflow.core.prompt

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.Character
import io.github.alalkipgen.autoflow.core.model.GlobalStyle
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.model.ShotSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CharacterBibleTest {
    @Test
    fun tokensAreUppercaseWordsWithUnderscores() {
        assertEquals("HERO_GIRL", CharacterBible.normalizeToken(" hero girl "))
        assertEquals("MA_MA2", CharacterBible.normalizeToken("ma--ma2!"))
        assertEquals("", CharacterBible.normalizeToken("မမ"))
        assertEquals(CharacterBible.MAX_TOKEN, CharacterBible.normalizeToken("x".repeat(40)).length)
    }

    @Test
    fun nextTokenIsFree() {
        assertEquals("CHAR1", CharacterBible.nextToken(emptyList()))
        assertEquals("CHAR3", CharacterBible.nextToken(listOf(Character("CHAR1"), Character("CHAR2"))))
    }

    @Test
    fun problemsNameWhatBreaksCompiling() {
        val bible = listOf(
            Character("HERO", "ကောင်လေး", "a young man in a grey sweater"),
            Character("HERO", "ထပ်", "another"),
            Character("GIRL", "ကောင်မလေး", ""),
            Character("", "နာမည်ပဲ", "someone"),
            Character(""),
        )
        assertEquals(
            listOf(
                "A character has no token (e.g. HERO).",
                "{{GIRL}} has no English description: shots that use it will not compile.",
                "{{HERO}} is used by more than one character.",
            ),
            CharacterBible.problems(bible),
        )
        assertTrue(CharacterBible.problems(bible.take(1)).isEmpty())
    }

    @Test
    fun cleanDropsBlankRowsAndNormalizes() {
        val cleaned = CharacterBible.clean(listOf(Character(" hero ", " မောင် ", " a man "), Character("", "", "")))
        assertEquals(listOf(Character("HERO", "မောင်", "a man")), cleaned)
    }

    @Test
    fun theDescriptionReachesEveryShotThatUsesTheToken() {
        val bible = CharacterBible.clean(listOf(Character("hero", "မောင်", "a young man in a grey sweater")))
        val spec = ShotSpec(
            shot = 3, subjectEn = "{{HERO}} by the window", actionEn = "looks out", settingEn = "bedroom at night",
            cameraEn = "close-up", moodEn = "quiet", lightingEn = "neon through rain",
        )
        val ir = assertIs<CoreResult.Ok<PromptIR>>(PromptCompiler.compile(spec, GlobalStyle(), bible, Ratio.R9_16, 8_000)).value
        assertEquals("a young man in a grey sweater by the window", ir.subject)
    }
}
