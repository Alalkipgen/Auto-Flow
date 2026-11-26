package io.github.alalkipgen.autoflow.ui.jobs

import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.subtitles.SubtitleTranslateJob
import io.github.alalkipgen.autoflow.ui.subtitles.VideoTranscriber
import io.github.alalkipgen.autoflow.ui.voice.EdgeVoices
import io.github.alalkipgen.autoflow.ui.voice.VoiceSpeakJob

/**
 * Group 5 §8.1 / Group 6 §8.2: an AI job's `error` as the job card says it in [language] (shared by the Subtitles panels,
 * Voice & Audio's Speak card and the Jobs card). The engines keep their English texts (and the jobs file its old ones, so
 * old jobs still read); the known ones get the UI's words: the pause request says nothing (the card already says Paused),
 * "Gemini is busy (…)" drops the raw API detail, a TTS model's "… is busy (…). Resume to go on." names the TTS
 * ([EdgeVoices.ttsName] of [lock] when known), no Gemini / no TTS / no sound / no ffmpeg / video changed get their own
 * strings, and anything else stays as it is (the detail).
 */
internal fun jobErrorText(error: String?, language: UiLanguage = UiLanguage.ENGLISH, lock: VoiceLock? = null): String? = when {
    error == null || error == VideoTranscriber.PAUSED -> null
    error.startsWith(GEMINI_BUSY) -> UiText.V3_GEMINI_BUSY_RESUME.text(language)
    error == VideoTranscriber.NO_GEMINI || error == SubtitleTranslateJob.NO_GEMINI -> UiText.V3_SUB_NO_GEMINI.text(language)
    error == VoiceSpeakJob.NO_TTS -> UiText.V3_VA_NO_TTS.text(language)
    error == VideoTranscriber.NO_SOUND -> UiText.MOVIE_NO_SOUND_TO_TRANSCRIBE.text(language)
    error == VideoTranscriber.NO_FFMPEG -> UiText.V3_SUB_NO_FFMPEG.text(language)
    error == VideoTranscriber.VIDEO_CHANGED -> UiText.V3_SUB_VIDEO_CHANGED.text(language)
    else -> TTS_BUSY.matchEntire(error)?.let { m ->
        UiText.V3_VOICE_BUSY_RESUME.text(language, "name" to (lock?.let(EdgeVoices::ttsName) ?: ttsNameOf(m.groupValues[1])))
    } ?: error
}

/** How [VideoTranscriber] starts its busy pause ("Gemini is busy (429 …). Resume to go on."). */
private const val GEMINI_BUSY = "Gemini is busy"

/** [VoiceSpeakJob]'s busy pause: "<model or TTS name> is busy (<detail>). Resume to go on." */
private val TTS_BUSY = Regex("""^(.+?) is busy \(.*\)\. Resume to go on\.$""", RegexOption.DOT_MATCHES_ALL)

/** Without the job's voice: the name the job wrote (a model id or "Edge TTS" / "Gemini TTS") said as the TTS it is. */
private fun ttsNameOf(name: String): String = if (name.startsWith("Edge", ignoreCase = true)) "Edge TTS" else "Gemini TTS"
