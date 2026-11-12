package io.github.alalkipgen.autoflow.core.hash

import io.github.alalkipgen.autoflow.core.model.Character
import io.github.alalkipgen.autoflow.core.model.ShotSpec
import io.github.alalkipgen.autoflow.core.prompt.PromptCompiler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** D-29 / Q-10: the `characters` prompt input covers only the characters a shot uses. */
class CharacterHashTest {
    private val a = Character("CHAR1", "မမ", "young woman, red raincoat")
    private val b = Character("CHAR2", "ကိုကို", "tall man, grey coat")

    @Test
    fun recordsSortedTokensAndAHashOfTheirDescriptions() {
        val v = CharacterHash.input(listOf(a, b), listOf("CHAR2", "CHAR1", "CHAR2"))!!
        assertTrue(Regex("""CHAR1,CHAR2\|ch_[0-9a-f]{12}""").matches(v), v)
        assertEquals(listOf("CHAR1", "CHAR2"), CharacterHash.tokens(v))
        assertEquals(v, CharacterHash.input(listOf(b, a), listOf("CHAR1", "CHAR2")), "order does not matter")
    }

    @Test
    fun onlyTheUsedCharactersCount() {
        val before = CharacterHash.input(listOf(a, b), listOf("CHAR1"))
        assertEquals(before, CharacterHash.input(listOf(a, b.copy(descriptionEn = "short man")), listOf("CHAR1")))
        assertEquals(before, CharacterHash.input(listOf(a.copy(nameMy = "နာမည်သစ်"), b), listOf("CHAR1")), "Burmese name is not in the prompt")
        assertNotEquals(before, CharacterHash.input(listOf(a.copy(descriptionEn = "young woman, blue raincoat"), b), listOf("CHAR1")))
        assertNotEquals(before, CharacterHash.input(listOf(b), listOf("CHAR1")), "a removed character changes it")
        assertNull(CharacterHash.input(listOf(a, b), emptyList()))
    }

    @Test
    fun usedTokensComeFromInlinedTokensAndTheSpecList() {
        val spec = ShotSpec(shot = 1, subjectEn = "{{CHAR1}} by the window", actionEn = "waves at {{CHAR3}}", characters = listOf("CHAR2"))
        assertEquals(setOf("CHAR1", "CHAR2", "CHAR3"), PromptCompiler.usedTokens(spec))
    }
}
