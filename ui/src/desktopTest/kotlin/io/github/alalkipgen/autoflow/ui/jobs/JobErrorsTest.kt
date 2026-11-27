package io.github.alalkipgen.autoflow.ui.jobs

import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.voice.EdgeVoices
import io.github.alalkipgen.autoflow.ui.voice.VoiceSpeakJob
import kotlin.test.*

/** Group 6 §8.2: the Speak job's English errors are said in the UI language on the Speak card and the Jobs card. */
class JobErrorsTest {
    private val nilar = VoiceLock(EdgeVoices.PROVIDER, "edge-tts", "my-MM-NilarNeural", "my")
    private val kore = VoiceLock("gemini", "tts-a", "Kore", "my")

    @Test fun ttsBusyNamesTheTts() = UiLanguage.entries.forEach { l ->
        val edge = UiText.V3_VOICE_BUSY_RESUME.text(l, "name" to "Edge TTS")
        val gemini = UiText.V3_VOICE_BUSY_RESUME.text(l, "name" to "Gemini TTS")
        assertEquals(edge, jobErrorText("Edge TTS is busy (rate limited). Resume to go on.", l, nilar))
        assertEquals(gemini, jobErrorText("tts-a is busy (429 Resource exhausted). Resume to go on.", l, kore))
        // Without the job's voice (Jobs card): the name the job wrote.
        assertEquals(edge, jobErrorText("Edge TTS is busy (x). Resume to go on.", l))
        assertEquals(gemini, jobErrorText("gemini-3.8-flash-tts is busy (x). Resume to go on.", l))
        assertFalse("{name}" in gemini)
    }

    @Test fun noTtsKeyHasItsOwnString() = UiLanguage.entries.forEach { l ->
        assertEquals(UiText.V3_VA_NO_TTS.text(l), jobErrorText(VoiceSpeakJob.NO_TTS, l))
    }

    @Test fun anyOtherErrorStaysAsTheDetail() {
        assertEquals("Edge: socket closed", jobErrorText("Edge: socket closed", UiLanguage.BURMESE, nilar))
        assertNull(jobErrorText(null, UiLanguage.BURMESE, kore))
    }
}
