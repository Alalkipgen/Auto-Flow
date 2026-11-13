package io.github.alalkipgen.autoflow.ai.gemini

import io.github.alalkipgen.autoflow.ai.config.TtsCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Voice lock language filter (D-35): only models that declare the requested language are eligible for TTS.
 * Tested at the TtsCandidate + TtsModels level — no network, no coroutines needed.
 */
class VoiceLockLanguageFilterTest {
    private val burmese   = TtsCandidate("tts-full",     langs = listOf("my", "en"))
    private val engOnly   = TtsCandidate("tts-en-only",  langs = listOf("en"))
    private val noLangs   = TtsCandidate("tts-no-langs", langs = emptyList())

    @Test
    fun burmeseLangOnlyUsesModelsWithMyLang() {
        val eligible = listOf(burmese, engOnly, noLangs).filter { it.speaksLanguage("my") }.map { it.id }
        assertEquals(listOf("tts-full"), eligible)
    }

    @Test
    fun englishLangUsesBothEnglishCapableModels() {
        val eligible = listOf(burmese, engOnly, noLangs).filter { it.speaksLanguage("en") }.map { it.id }
        assertEquals(listOf("tts-full", "tts-en-only"), eligible)
    }

    @Test
    fun unknownLangWithNoMatchGivesEmptyList() {
        val eligible = listOf(burmese, engOnly).filter { it.speaksLanguage("fr") }
        assertTrue(eligible.isEmpty())
    }

    @Test
    fun speaksLanguageIsCaseInsensitive() {
        val c = TtsCandidate("m", langs = listOf("MY", "EN"))
        assertTrue(c.speaksLanguage("my"))
        assertTrue(c.speaksLanguage("En"))
    }

    @Test
    fun emptyLangsNeverSpeaksAnyLanguage() {
        assertFalse(noLangs.speaksLanguage("my"))
        assertFalse(noLangs.speaksLanguage("en"))
    }
}
