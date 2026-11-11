package io.github.alalkipgen.autoflow.ui.input

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.model.StoryboardMode
import io.github.alalkipgen.autoflow.core.workflow.Blocker
import io.github.alalkipgen.autoflow.core.workflow.WorkflowRules
import io.github.alalkipgen.autoflow.platform.FilePicker
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardJobs
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** The Input form (DESIGN.md §3). [settings] and [lyrics] are the draft; [saved] is what project.json holds. */
data class InputUiState(
    val projectName: String?,
    val lyrics: String = "",
    val durationText: String = "",
    val settings: ProjectSettings = ProjectSettings(),
    val hasStoryboard: Boolean = false,
    /** File name of the imported song in `source/` (e.g. `song.mp3`), or null. */
    val songFile: String? = null,
    val importingSong: Boolean = false,
    /** Result of the last song import, shown in the Song card. */
    val songMessage: String? = null,
    val songMessageOk: Boolean = false,
    val blockers: List<Blocker> = emptyList(),
    val message: String? = null,
)

/**
 * Input screen logic. Chips save at once; text fields save on [save] (the screen calls it when it closes and before
 * generating), so typing does not rewrite project.json on every key.
 */
class InputModel(
    private val session: ProjectSession,
    private val jobs: StoryboardJobs,
    private val picker: FilePicker? = null,
    private val media: MediaTools? = null,
) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<InputUiState> = _state.asStateFlow()

    fun setLyrics(text: String) = draft { it.copy(lyrics = text) }

    /** `m:ss`, `mm:ss` or plain seconds. Unparseable text clears the duration, so the NO_DURATION blocker shows. */
    fun setDuration(text: String) = draft { s ->
        val ms = parseDuration(text)
        s.copy(durationText = text, settings = s.settings.copy(durationMs = ms ?: 0))
    }

    fun setSongStyle(text: String) = draft { it.copy(settings = it.settings.copy(songStyle = text)) }
    fun setVideoStyle(text: String) = draft { it.copy(settings = it.settings.copy(videoStyle = text)) }
    fun setMood(text: String) = draft { it.copy(settings = it.settings.copy(mood = text)) }

    fun setRatio(ratio: Ratio) = chip { it.copy(ratio = ratio) }
    fun setClipMs(ms: Long) = chip { it.copy(clipMs = ms) }
    fun setMode(mode: StoryboardMode) = chip { it.copy(storyboardMode = mode) }
    fun pickVideoStyle(style: String) = chip { it.copy(videoStyle = style) }

    /** "Import song": pick an audio file, then [importSongFile]. */
    suspend fun importSong() {
        if (jobs.busy || session.open == null || _state.value.importingSong) return
        val picked = picker?.pickFiles(PICK_SONG_TITLE, ProjectSession.SONG_EXTENSIONS) ?: return say(NO_PICKER)
        val path = when (picked) {
            is PlatformResult.Ok -> picked.value.firstOrNull() ?: return
            is PlatformResult.Failed -> return say("File pick failed: ${picked.reason}")
            is PlatformResult.Unsupported -> return say(NO_PICKER)
        }
        importSongFile(path)
    }

    /**
     * Copies [path] to `source/song.<ext>`; when ffprobe reads its length, that becomes the duration (saved at once),
     * otherwise the typed duration stays and the message asks for it.
     */
    suspend fun importSongFile(path: String) {
        _state.value = _state.value.copy(importingSong = true, songMessage = null)
        val saved = when (val r = withContext(Dispatchers.Default) { session.importSong(path) }) {
            is CoreResult.Ok -> r.value
            is CoreResult.Err -> {
                _state.value = _state.value.copy(importingSong = false)
                return say(r.error.message)
            }
        }
        val ms = media?.audioDurationMs(saved)
        _state.value = _state.value.copy(importingSong = false, songFile = saved.substringAfterLast('/'))
        if (ms == null) return say(SONG_NO_LENGTH)
        draft { it.copy(durationText = formatDuration(ms), settings = it.settings.copy(durationMs = ms)) }
        if (save()) say("Song imported · ${formatDuration(ms)}", ok = true)
    }

    /** Writes the draft (lyrics file + project.json) if it differs from what is saved. */
    fun save(): Boolean {
        val open = session.open ?: return false
        val s = _state.value
        if (s.lyrics != session.readLyrics()) {
            (session.writeLyrics(s.lyrics) as? CoreResult.Err)?.let { return fail(it.error.message) }
        }
        if (s.settings != open.project.settings) {
            (session.save(open.project.copy(settings = s.settings)) as? CoreResult.Err)?.let { return fail(it.error.message) }
        }
        return true
    }

    /** "Storyboard ထုတ်မယ်": saves, then starts the job; [onDone] runs after a successful generation. */
    fun generate(onDone: () -> Unit): Boolean {
        if (!save() || _state.value.blockers.isNotEmpty()) return false
        return jobs.run(
            GENERATE_LABEL,
            onDone = {
                _state.value = load().copy(message = null)
                if (it == StoryboardResult.Done) onDone()
            },
        ) { generateAll() }
    }

    private fun chip(edit: (ProjectSettings) -> ProjectSettings) {
        draft { it.copy(settings = edit(it.settings)) }
        save()
    }

    private fun draft(edit: (InputUiState) -> InputUiState) {
        if (jobs.busy) return
        _state.value = withGate(edit(_state.value)).copy(message = null)
    }

    private fun say(message: String, ok: Boolean = false) {
        _state.value = _state.value.copy(songMessage = message, songMessageOk = ok)
    }

    private fun fail(message: String): Boolean {
        _state.value = _state.value.copy(message = message)
        return false
    }

    private fun load(): InputUiState {
        val p: Project = session.open?.project ?: return InputUiState(projectName = null)
        return withGate(
            InputUiState(
                projectName = p.name,
                lyrics = session.readLyrics(),
                durationText = if (p.settings.durationMs > 0) formatDuration(p.settings.durationMs) else "",
                settings = p.settings,
                hasStoryboard = p.currentStoryboard != null,
                songFile = session.songPath()?.substringAfterLast('/'),
            ),
        )
    }

    private fun withGate(s: InputUiState): InputUiState {
        val p = session.open?.project ?: return s
        return s.copy(blockers = WorkflowRules.canGenerateStoryboard(p.copy(settings = s.settings), s.lyrics).blockers)
    }

    companion object {
        const val GENERATE_LABEL = "Generating the storyboard"
        const val PICK_SONG_TITLE = "Import song"
        const val NO_PICKER = "File picker not available on this platform yet"
        const val SONG_NO_LENGTH = "Song imported, but its length could not be read. Type the duration (m:ss)."
        val VIDEO_STYLES = listOf("Cinematic", "Anime", "3D animation", "Watercolor", "Documentary", "Retro film")

        fun parseDuration(text: String): Long? {
            val t = text.trim()
            val parts = t.split(':')
            val seconds = when (parts.size) {
                1 -> parts[0].toLongOrNull()
                2 -> {
                    val m = parts[0].toLongOrNull()
                    val s = parts[1].toLongOrNull()?.takeIf { it in 0..59 && parts[1].length == 2 }
                    if (m != null && s != null) m * 60 + s else null
                }
                else -> null
            }
            return seconds?.takeIf { it > 0 }?.times(1000)
        }

        fun formatDuration(ms: Long): String = "${ms / 60_000}:${((ms / 1000) % 60).toString().padStart(2, '0')}"
    }
}
