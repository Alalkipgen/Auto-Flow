package io.github.alalkipgen.autoflow.ui.input

import io.github.alalkipgen.autoflow.core.model.Character
import io.github.alalkipgen.autoflow.core.prompt.CharacterBible
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Group 3 §8-5: the Character Bible's problem lines in the UI language — V3_CHAR_DUP_TOKEN / V3_CHAR_NO_ENGLISH with
 * {token} = "{{HERO}}"; English reads exactly as the core's lines, and a line without a key stays as it is.
 */
class CharacterProblemTextTest {
    private val hero = Character("HERO", "မောင်မောင်", "a young Burmese man")

    @Test fun duplicateTokenAndMissingEnglishFollowTheLanguage() {
        val lines = CharacterBible.problems(listOf(hero, Character("HERO", "သီတာ")))
        assertEquals(2, lines.size, "no English for the second HERO, and HERO twice: $lines")
        val noEnglish = lines.first { "English" in it }
        val duplicate = lines.first { "more than one" in it }
        for (line in lines) assertEquals(line, characterProblemText(line, UiLanguage.ENGLISH), "English is the core's line")
        assertEquals(UiText.V3_CHAR_NO_ENGLISH.text(UiLanguage.BURMESE, "token" to "{{HERO}}"), characterProblemText(noEnglish, UiLanguage.BURMESE))
        assertEquals(UiText.V3_CHAR_DUP_TOKEN.text(UiLanguage.BURMESE, "token" to "{{HERO}}"), characterProblemText(duplicate, UiLanguage.BURMESE))
    }

    @Test fun aLineWithoutAKeyStaysAsTheCoreWroteIt() {
        val line = CharacterBible.problems(listOf(Character("", "သီတာ", "a young woman"))).single()
        assertEquals(line, characterProblemText(line, UiLanguage.BURMESE))
    }
}
