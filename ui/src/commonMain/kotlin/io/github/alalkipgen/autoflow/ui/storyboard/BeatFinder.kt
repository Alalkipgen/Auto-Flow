package io.github.alalkipgen.autoflow.ui.storyboard

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.workflow.SongBeats
import io.github.alalkipgen.autoflow.media.audio.BeatDetector
import io.github.alalkipgen.autoflow.media.audio.PcmWav
import io.github.alalkipgen.autoflow.media.audio.SongAudio
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
        data class Failed(val message: String) : Found
    }

    suspend fun find(): Found = withContext(Dispatchers.Default) {
        session.readBeats()?.let { return@withContext Found.Ok(it) }
        val song = session.songPath() ?: return@withContext Found.Failed(NO_SONG)
        val runner = media?.runner
        val ffmpeg = media?.ffmpeg()
        val wav = session.cacheFile(WAV_FILE)
        if (runner == null || ffmpeg == null || wav == null) return@withContext Found.Failed(NO_FFMPEG)
        SongAudio.decodeMonoWav(runner, ffmpeg, song, wav, MediaTools.parentOf(wav), BeatDetector.RATE)?.let {
            return@withContext Found.Failed("Cannot read the song: $it")
        }
        val pcm = session.readFile(wav)?.let(PcmWav::parse) ?: return@withContext Found.Failed("Cannot read the decoded song")
        val beats = BeatDetector.detect(pcm.samples, pcm.rate) ?: return@withContext Found.Failed(NO_BEAT)
        val sha = session.songSha256() ?: return@withContext Found.Failed(NO_SONG)
        val found = SongBeats(sha, beats.bpm, beats.beatsMs)
        (session.writeBeats(found) as? CoreResult.Err)?.let { return@withContext Found.Failed(it.error.message) }
        Found.Ok(found)
    }

    companion object {
        const val WAV_FILE = "beats.wav"
        const val NO_SONG = "Import the song first (Input → သီချင်းဖိုင် ထည့်မယ်)."
        const val NO_FFMPEG = "ffmpeg is not available, so the song cannot be read."
        const val NO_BEAT = "No steady beat found in this song."
    }
}
