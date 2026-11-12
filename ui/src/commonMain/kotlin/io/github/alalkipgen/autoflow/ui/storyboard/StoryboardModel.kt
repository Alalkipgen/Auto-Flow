package io.github.alalkipgen.autoflow.ui.storyboard

import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.Shot
import io.github.alalkipgen.autoflow.core.model.ShotSpec
import io.github.alalkipgen.autoflow.core.workflow.Stale
import io.github.alalkipgen.autoflow.ui.input.InputModel
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlin.math.round
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/** One storyboard card (DESIGN.md §3). [tag] is the version, plus "edited" for the user's own text. */
data class StoryCard(
    val number: Int,
    val timecode: String,
    val lyric: String,
    val spec: ShotSpec?,
    val tag: String,
    val approved: Boolean,
    val stale: Boolean,
    /** The shot already has a prompt, reference image or video that a new spec would make stale. */
    val hasDownstream: Boolean,
)

data class StoryVersion(val id: ArtifactId, val label: String, val current: Boolean)

data class StoryboardUiState(
    val projectName: String? = null,
    val cards: List<StoryCard> = emptyList(),
    val versions: List<StoryVersion> = emptyList(),
    /** "Snap cuts to beat" (D-32). */
    val snapToBeat: Boolean = false,
) {
    val approvedCount: Int get() = cards.count { it.approved }
}

/**
 * Storyboard screen logic. State is rebuilt from the open project after every save. Quick actions (approve, edit,
 * switch version) run at once; AI actions go through [jobs]. Nothing runs while a job is busy, because the job saves
 * the project when it ends and would overwrite the change.
 */
class StoryboardModel(
    private val session: ProjectSession,
    private val service: StoryboardService,
    private val jobs: StoryboardJobs,
    media: MediaTools? = null,
) {
    private val beats = BeatFinder(session, media)

    val state: Flow<StoryboardUiState> = session.state.map(::build)
    private val _message = MutableStateFlow<String?>(null)

    /** Result of the last quick action; AI job results are in [StoryboardJobs.state]. */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun current(): StoryboardUiState = build(session.open)

    fun approve(number: Int, approved: Boolean = true) = quick { service.approve(number, approved) }
    fun approveAll() = quick { service.approveAll() }
    fun switchVersion(id: ArtifactId) = quick { service.switchVersion(id) }

    /**
     * "♩ Snap cuts to beat" (Q-12, D-32): on = find the song's beats (cached after the first time) and move every cut
     * to the nearest beat within ±250 ms; off = the storyboard's own cuts again. Approvals and prompts stay.
     */
    fun setSnapToBeat(on: Boolean): Boolean {
        _message.value = null
        if (!on) return quick { service.retime(false) }
        val before = session.open?.project?.shots.orEmpty()
        return jobs.run(BEAT_LABEL, onDone = { r -> if (r == StoryboardResult.Done) _message.value = snapSummary(before) }) {
            when (val found = beats.find()) {
                is BeatFinder.Found.Failed -> StoryboardResult.Failed(found.message)
                is BeatFinder.Found.Ok -> retime(true)
            }
        }
    }

    private fun snapSummary(before: List<Shot>): String {
        val p = session.open?.project ?: return ""
        val bpm = session.readBeats()?.bpm?.let { " · about ${round(it).toInt()} BPM" } ?: ""
        val old = before.associate { it.number to it.startMs }
        val moved = p.shots.count { s -> s.number != p.shots.first().number && old[s.number]?.let { it != s.startMs } == true }
        return "♩ Cuts snapped to the beat: $moved of ${p.shots.size - 1} moved$bpm."
    }

    /** ✎ ပြင်မယ်: saves the edited Burmese scene and English fields of shot [number]. */
    fun edit(number: Int, edit: (ShotSpec) -> ShotSpec) = quick {
        val shot = session.open?.project?.shots?.firstOrNull { it.number == number }
        val spec = shot?.let(service::spec) ?: return@quick StoryboardResult.Failed("Shot $number has no storyboard yet")
        val next = edit(spec)
        if (next == spec) StoryboardResult.Done else service.editSpec(number, next)
    }

    /** ↻ ပြန်ထုတ်မယ်: one AI request for shot [number]; [keepScene] keeps the Burmese scene. */
    fun regenerate(number: Int, keepScene: Boolean): Boolean {
        _message.value = null
        return jobs.run(regenerateLabel(number)) { regenerateShot(number, keepScene) }
    }

    /** Regenerate all: a new storyboard version; the current one stays in the version list. */
    fun regenerateAll(): Boolean {
        _message.value = null
        return jobs.run(InputModel.GENERATE_LABEL) { generateAll() }
    }

    private fun quick(action: () -> StoryboardResult): Boolean {
        if (jobs.busy) {
            _message.value = "Wait for “${jobs.state.value.running}” to finish."
            return false
        }
        jobs.clearLast()
        val result = action()
        _message.value = result.message()
        return result == StoryboardResult.Done
    }

    private fun build(open: OpenProject?): StoryboardUiState {
        val p: Project = open?.project ?: return StoryboardUiState()
        val lookup = session.lookup()
        val currentIndex = p.storyboards.indexOf(p.currentStoryboard)
        val versionTag = if (currentIndex >= 0) "v${currentIndex + 1}" else "v?"
        val cards = p.shots.map { shot ->
            val edited = shot.spec?.let { lookup(it) }?.provider == PROVIDER_USER
            StoryCard(
                number = shot.number,
                timecode = timecode(shot),
                lyric = shot.lyricsMy,
                spec = service.spec(shot),
                tag = if (edited) "$versionTag · edited" else versionTag,
                approved = shot.approvals.storyboard,
                stale = Stale.spec(p, shot, lookup),
                hasDownstream = hasDownstream(shot),
            )
        }
        val versions = p.storyboards.mapIndexed { i, id -> StoryVersion(id, "v${i + 1}", id == p.currentStoryboard) }
        return StoryboardUiState(p.name, cards, versions, snapToBeat = p.settings.snapToBeat)
    }

    companion object {
        private const val PROVIDER_USER = "user"

        const val BEAT_LABEL = "Finding the beat"

        fun regenerateLabel(number: Int) = "Regenerating shot #${number.toString().padStart(2, '0')}"

        fun timecode(shot: Shot): String =
            "${InputModel.formatDuration(shot.startMs)}–${InputModel.formatDuration(shot.startMs + shot.durationMs)}"

        fun hasDownstream(shot: Shot): Boolean = shot.promptIr != null || shot.refImages.isNotEmpty() || shot.videos.isNotEmpty()
    }
}
