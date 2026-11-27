package io.github.alalkipgen.autoflow.ui.subtitles

import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.jobs.jobErrorText
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import kotlin.test.*

/** Group 5 §8.1: the job card says a Transcribe / Translate job's error in the UI's words, not the engine's. */
class SubtitleJobErrorsTest {
    @Test fun knownErrorsGetTheirOwnStringsInBothLanguages() = UiLanguage.entries.forEach { l ->
        assertNull(jobErrorText(null, l))
        assertNull(jobErrorText(VideoTranscriber.PAUSED, l), "the card already says Paused")
        assertEquals(UiText.V3_GEMINI_BUSY_RESUME.text(l), jobErrorText("Gemini is busy (429 Resource exhausted). Resume to go on.", l))
        assertEquals(UiText.V3_SUB_NO_GEMINI.text(l), jobErrorText(VideoTranscriber.NO_GEMINI, l))
        assertEquals(UiText.V3_SUB_NO_GEMINI.text(l), jobErrorText(SubtitleTranslateJob.NO_GEMINI, l))
        assertEquals(UiText.MOVIE_NO_SOUND_TO_TRANSCRIBE.text(l), jobErrorText(VideoTranscriber.NO_SOUND, l))
        assertEquals(UiText.V3_SUB_NO_FFMPEG.text(l), jobErrorText(VideoTranscriber.NO_FFMPEG, l))
        assertEquals(UiText.V3_SUB_VIDEO_CHANGED.text(l), jobErrorText(VideoTranscriber.VIDEO_CHANGED, l))
    }

    @Test fun anyOtherErrorStaysAsTheDetail() {
        assertEquals("Gemini: bad request", jobErrorText("Gemini: bad request", UiLanguage.BURMESE))
    }

    @Test fun stoppedWithoutADetailHasNoDoubleSpace() = UiLanguage.entries.forEach { l -> // §8.2
        val text = UiText.SUB_TRANSLATE_STOPPED.text(l).replace("{detail}", "").replace("{done}", "6").replace("{n}", "13").collapseSpaces()
        assertFalse("  " in text, text)
        assertEquals(if (l == UiLanguage.ENGLISH) "Stopped: 6 of 13 batches saved." else text, text)
    }
}
