package io.github.alalkipgen.autoflow.ui.input

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.model.StoryboardMode
import io.github.alalkipgen.autoflow.core.workflow.Blocker
import io.github.alalkipgen.autoflow.core.workflow.WorkflowRules
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardJobs
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The Input form (DESIGN.md §3). [settings] and [lyrics] are the draft; [saved] is what project.json holds. */
data class InputUiState(
    val projectName: String?,
    val lyrics: String = "",
    val durationText: String = "",
    val settings: ProjectSettings = ProjectSettings(),
    val hasStoryboard: Boolean = false,
    val blockers: List<Blocker> = emptyList(),
    val message: String? = null,
)

/**
 * Input screen logic. Chips save at once; text fields save on [save] (the screen calls it when it closes and before
 * generating), so typing does not rewrite project.json on every key.
 */
class InputModel(private val session: ProjectSession, private val jobs: StoryboardJobs) {
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
            ),
        )
    }

    private fun withGate(s: InputUiState): InputUiState {
        val p = session.open?.project ?: return s
        return s.copy(blockers = WorkflowRules.canGenerateStoryboard(p.copy(settings = s.settings), s.lyrics).blockers)
    }

    companion object {
        const val GENERATE_LABEL = "Generating the storyboard"
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
