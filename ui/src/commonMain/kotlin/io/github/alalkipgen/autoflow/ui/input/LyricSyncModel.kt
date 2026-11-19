package io.github.alalkipgen.autoflow.ui.input

import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.TranscribeProvider
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.lyrics.LyricSync
import io.github.alalkipgen.autoflow.core.lyrics.LyricTimings
import io.github.alalkipgen.autoflow.core.lyrics.TapSync
import io.github.alalkipgen.autoflow.core.recap.AiTimingStage
import io.github.alalkipgen.autoflow.core.recap.timedAiStage
import io.github.alalkipgen.autoflow.media.audio.SongAudio
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** What the saved line timings say about the current lyrics. */
enum class TimingStatus { NONE, SYNCED, OUTDATED }

data class LyricSyncUiState(
    val status: TimingStatus = TimingStatus.NONE,
    /** Lines in the saved timings. */
    val savedLines: Int = 0,
    val savedSource: String? = null,
    /** The tap-to-sync panel is open with this state; null when closed. */
    val sync: TapSync? = null,
    val message: String? = null,
    val notice: UiNotice? = null,
    val messageOk: Boolean = false,
    /** "Suggest timings" is waiting for Gemini. */
    val suggesting: Boolean = false,
)

/**
 * Tap-to-sync on Input (PLAN Q-11, D-30): the song plays, each tap marks where the next lyric line starts, Save
 * stores the timings as a `tx` artifact. Export then shows each line at its own time. "Suggest timings" asks
 * [transcribe] (Gemini, D-31) instead; the owner checks them on Export → Preview and re-taps when they are off.
 */
class LyricSyncModel(
    private val session: ProjectSession,
    private val transcribe: TranscribeProvider? = null,
    private val media: MediaTools? = null,
) {
    private val _state = MutableStateFlow(LyricSyncUiState())
    val state: StateFlow<LyricSyncUiState> = _state.asStateFlow()

    init {
        refresh(session.readLyrics())
    }

    /** Re-reads the saved timings against [lyrics] (the saved lyrics text). */
    fun refresh(lyrics: String) {
        val saved = session.readLyricTimings()
        _state.value = _state.value.copy(
            status = when {
                saved == null -> TimingStatus.NONE
                LyricSync.matches(saved, lyrics) -> TimingStatus.SYNCED
                else -> TimingStatus.OUTDATED
            },
            savedLines = saved?.lines?.size ?: 0,
            savedSource = saved?.source,
        )
    }

    /** Opens the panel for the lines of [lyrics]; false (with a message) when there is nothing to sync. */
    fun start(lyrics: String): Boolean {
        val lines = LyricSync.lines(lyrics)
        if (lines.isEmpty()) return say(UiNotice(UiText.SYNC_NO_LINES)).let { false }
        if (session.songPath() == null) return say(UiNotice(UiText.SYNC_NO_SONG)).let { false }
        _state.value = _state.value.copy(sync = TapSync(lines), message = null, notice = null)
        return true
    }

    fun tap(positionMs: Long) = edit { it.tap(positionMs) }

    /** Undoes the last tap and returns its position (the screen seeks back a little), or null when none. */
    fun undo(): Long? {
        val s = _state.value.sync ?: return null
        val removed = s.lastEndMs ?: s.starts.lastOrNull() ?: return null
        edit { it.undo() }
        return removed
    }

    fun restart() = edit { it.restart() }

    fun cancel() {
        _state.value = _state.value.copy(sync = null, message = null, notice = null)
    }

    /** Saves the complete sync ([songMs] cuts the last line at the song end) and closes the panel. */
    fun save(songMs: Long?): Boolean {
        val s = _state.value.sync ?: return false
        if (!s.complete) return say(UiNotice(UiText.SYNC_INCOMPLETE)).let { false }
        val timings = LyricTimings(source = LyricTimings.SOURCE_TAP, lines = s.timings(songMs))
        return when (val r = session.saveLyricTimings(timings)) {
            is CoreResult.Ok -> {
                _state.value = _state.value.copy(sync = null)
                refresh(session.readLyrics())
                say(UiNotice(UiText.TIMINGS_SAVED, "lines" to timings.lines.size), ok = true)
                true
            }
            is CoreResult.Err -> say(r.error.message).let { false }
        }
    }

    /** "Clear timings": back to per-shot lyrics (the `tx` artifact stays in artifacts/). */
    fun clear() = clear(CLEARED, UiNotice(UiText.TIMINGS_CLEARED))

    fun clear(reason: String, notice: UiNotice = UiNotice(UiText.RESULT_DETAIL, "detail" to reason)) {
        if (session.open?.project?.lyricTiming == null) return
        when (val r = session.saveLyricTimings(null)) {
            is CoreResult.Ok -> {
                refresh(session.readLyrics())
                say(notice, ok = true)
            }
            is CoreResult.Err -> say(r.error.message)
        }
    }

    /** "Suggest timings": the song (16 kHz mono WAV when ffmpeg is there) and the saved lyric lines go to Gemini. */
    suspend fun suggest(): Boolean {
        val provider = transcribe ?: return say(UiNotice(UiText.SYNC_NO_AI)).let { false }
        if (_state.value.suggesting) return false
        val origin = session.open ?: return false
        val lyrics = session.readLyrics()
        val lines = LyricSync.lines(lyrics)
        if (lines.isEmpty()) return say(UiNotice(UiText.SYNC_NO_LINES)).let { false }
        val song = session.songPath() ?: return say(UiNotice(UiText.SYNC_NO_SONG)).let { false }
        _state.value = _state.value.copy(suggesting = true, message = null, notice = null, sync = null)
        try {
            val wav = session.cacheFile(AI_WAV)
            val (audio, mime) = timedAiStage(AiTimingStage.MEDIA_PREP) { withContext(Dispatchers.Default) { audioFor(song, wav) } }
                ?: return say(UiNotice(UiText.SYNC_NO_AUDIO)).let { false }
            if (session.open != origin) return say(UiNotice(UiText.GEMINI_PROJECT_CHANGED)).let { false }
            return when (val r = provider.transcribe(audio, langOf(lyrics), mime, lines)) {
                is AiResult.Ok -> {
                    currentCoroutineContext().ensureActive()
                    if (session.open != origin) return say(UiNotice(UiText.GEMINI_PROJECT_CHANGED)).let { false }
                    val songMs = origin.project.settings.durationMs.takeIf { it > 0 }
                    val fixed = timedAiStage(AiTimingStage.PARSE) {
                        LyricSync.suggested(lines, r.value.segments.map { it.startMs to it.endMs }, songMs)
                    } ?: return say(UiNotice(UiText.SYNC_AI_INCOMPLETE)).let { false }
                    when (val saved = timedAiStage(AiTimingStage.SAVE) {
                        session.saveLyricTimings(LyricTimings(source = LyricTimings.SOURCE_GEMINI, lines = fixed))
                    }) {
                        is CoreResult.Ok -> {
                            refresh(session.readLyrics())
                            say(UiNotice(UiText.TIMINGS_SUGGESTED, "lines" to fixed.size), ok = true)
                            true
                        }
                        is CoreResult.Err -> say(saved.error.message).let { false }
                    }
                }
                is AiResult.RateLimited -> say(UiNotice(UiText.SYNC_RATE_LIMITED)).let { false }
                is AiResult.Failed -> say("Gemini: ${r.msg}").let { false }
                is AiResult.Deferred -> say(r.instruction).let { false }
            }
        } finally {
            _state.value = _state.value.copy(suggesting = false)
        }
    }

    /** Decoded small WAV, else the song file itself when Gemini knows its type; null when nothing can be read. */
    private suspend fun audioFor(song: String, wav: String?): Pair<ByteArray, String>? {
        val runner = media?.runner
        val ffmpeg = media?.ffmpeg()
        if (runner != null && ffmpeg != null && wav != null) {
            val failed = SongAudio.decodeMonoWav(runner, ffmpeg, song, wav, MediaTools.parentOf(wav), SongAudio.AI_RATE)
            if (failed == null) session.readFile(wav)?.takeIf { it.isNotEmpty() }?.let { return it to "audio/wav" }
        }
        val mime = MIME[song.substringAfterLast('.').lowercase()] ?: return null
        return session.readFile(song)?.takeIf { it.isNotEmpty() }?.let { it to mime }
    }

    private fun edit(change: (TapSync) -> TapSync) {
        val s = _state.value.sync ?: return
        _state.value = _state.value.copy(sync = change(s), message = null, notice = null)
    }

    private fun say(message: String, ok: Boolean = false) = say(UiNotice(UiText.RESULT_DETAIL, "detail" to message), ok)

    private fun say(notice: UiNotice, ok: Boolean = false) {
        _state.value = _state.value.copy(message = notice.text(UiLanguage.ENGLISH), notice = notice, messageOk = ok)
    }

    companion object {
        const val NO_LINES = "Write the lyrics first (one line per sung line)."
        const val NO_SONG = "Import the song first."
        const val NOT_COMPLETE = "Tap every line first."
        const val CLEARED = "Timings cleared: lyrics show for each whole shot."
        const val NO_AI = "AI is not available in this app."
        const val NO_AUDIO = "The song file cannot be read."
        const val INCOMPLETE = "Gemini did not time every line — try again, or use Sync lyrics (tap)."
        const val RATE_LIMITED = "Gemini's limit is reached for now — try again later, or use Sync lyrics (tap)."
        const val AI_WAV = "lyrics-ai.wav"
        private val MIME = mapOf(
            "mp3" to "audio/mp3", "wav" to "audio/wav", "ogg" to "audio/ogg", "opus" to "audio/ogg",
            "flac" to "audio/flac", "aac" to "audio/aac", "m4a" to "audio/mp4",
        )

        /** `my` when the lyrics have Myanmar letters, else `en`. */
        fun langOf(lyrics: String): String = if (lyrics.any { it in '\u1000'..'\u109F' }) "my" else "en"

        const val CLEARED_NEW_SONG = "New song: the old line timings were cleared — sync again."
    }
}
