package io.github.alalkipgen.autoflow.ui.storyboard

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.workflow.SongBeats
import io.github.alalkipgen.autoflow.media.audio.BeatDetector
import io.github.alalkipgen.autoflow.media.audio.PcmWav
import io.github.alalkipgen.autoflow.media.audio.SongAudio
import io.github.alalkipgen.autoflow.ui.i18n.UiNotice
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The song's beats for "Snap cuts to beat" (D-32): cached in `.cache/beats.json`, else decoded with ffmpeg to an
 * 11 kHz mono WAV (`.cache/beats.wav`) and tracked by [BeatDetector]. [find] answers the beats or why not.
 */
class BeatFinder(private val session: ProjectSession, private val media: MediaTools?) {
    sealed interface Found {
        data class Ok(val beats: SongBeats) : Found
        /** [notice] = [message] in the UI language (Group 3 §8-2), when there is a key for it. */
        data class Failed(val message: String, val notice: UiNotice? = null) : Found
    }

    suspend fun find(): Found = withContext(Dispatchers.Default) {
        session.readBeats()?.let { return@withContext Found.Ok(it) }
        val song = session.songPath() ?: return@withContext noSong
        val runner = media?.runner
        val ffmpeg = media?.ffmpeg()
        val wav = session.cacheFile(WAV_FILE)
        if (runner == null || ffmpeg == null || wav == null) return@withContext Found.Failed(NO_FFMPEG, UiNotice(UiText.V3_BEAT_NO_FFMPEG))
        SongAudio.decodeMonoWav(runner, ffmpeg, song, wav, MediaTools.parentOf(wav), BeatDetector.RATE)?.let {
            return@withContext Found.Failed("Cannot read the song: $it")
        }
        val pcm = session.readFile(wav)?.let(PcmWav::parse) ?: return@withContext Found.Failed("Cannot read the decoded song")
        val beats = BeatDetector.detect(pcm.samples, pcm.rate) ?: return@withContext Found.Failed(NO_BEAT, UiNotice(UiText.V3_BEAT_NO_BEAT))
        val sha = session.songSha256() ?: return@withContext noSong
        val found = SongBeats(sha, beats.bpm, beats.beatsMs)
        (session.writeBeats(found) as? CoreResult.Err)?.let { return@withContext Found.Failed(it.error.message) }
        Found.Ok(found)
    }

    private val noSong get() = Found.Failed(NO_SONG, UiNotice(UiText.V3_BEAT_NO_SONG))

    companion object {
        const val WAV_FILE = "beats.wav"
        const val NO_SONG = "Import the song first (Input → သီချင်းဖိုင် ထည့်မယ်)."
        const val NO_FFMPEG = "ffmpeg is not available, so the song cannot be read."
        const val NO_BEAT = "No steady beat found in this song."
    }
}
