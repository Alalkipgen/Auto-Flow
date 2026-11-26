package io.github.alalkipgen.autoflow.ui.subtitles

import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText

/**
 * Group 5 §8.1: a Transcribe / Translate job's `error` as the job card says it in [language]. The engines keep their
 * English texts (and the jobs file its old ones, so old jobs still read); the known ones get the UI's words: the pause
 * request says nothing (the card already says Paused), "Gemini is busy (…)" drops the raw API detail, no Gemini / no sound /
 * no ffmpeg / video changed get their own strings, and anything else stays as it is (the detail).
 */
internal fun jobErrorText(error: String?, language: UiLanguage = UiLanguage.ENGLISH): String? = when {
    error == null || error == VideoTranscriber.PAUSED -> null
    error.startsWith(GEMINI_BUSY) -> UiText.V3_GEMINI_BUSY_RESUME.text(language)
    error == VideoTranscriber.NO_GEMINI || error == SubtitleTranslateJob.NO_GEMINI -> UiText.V3_SUB_NO_GEMINI.text(language)
    error == VideoTranscriber.NO_SOUND -> UiText.MOVIE_NO_SOUND_TO_TRANSCRIBE.text(language)
    error == VideoTranscriber.NO_FFMPEG -> UiText.V3_SUB_NO_FFMPEG.text(language)
    error == VideoTranscriber.VIDEO_CHANGED -> UiText.V3_SUB_VIDEO_CHANGED.text(language)
    else -> error
}

/** How [VideoTranscriber] starts its busy pause ("Gemini is busy (429 …). Resume to go on."). */
private const val GEMINI_BUSY = "Gemini is busy"
