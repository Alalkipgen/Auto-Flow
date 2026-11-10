package io.github.alalkipgen.autoflow.core.workflow

import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.Shot

/** Why an action is not allowed. The UI maps each value to a message; it never re-implements the rule. */
enum class Blocker {
    EMPTY_LYRICS, NO_DURATION, INVALID_CLIP_LENGTH,
    NO_SPEC, STORYBOARD_NOT_APPROVED,
    NO_PROMPT, PROMPT_NOT_APPROVED, PROMPT_STALE,
    NO_CHOSEN_REF, REF_NOT_APPROVED,
    NO_VIDEOS, NO_ACCEPTED_VIDEOS,
}

/** Result of a gate check: allowed when there are no [blockers]. */
data class Gate(val blockers: List<Blocker>) {
    val allowed: Boolean get() = blockers.isEmpty()

    companion object {
        val ALLOWED = Gate(emptyList())
        fun of(vararg checks: Pair<Boolean, Blocker>) = Gate(checks.filter { !it.first }.map { it.second })
    }
}

enum class ApprovalStep { STORYBOARD, PROMPT, REF, VIDEO }

/** Approval gates (specs/workflow-and-approval.md "Gates"). Pure functions; the UI only reflects their result. */
object WorkflowRules {
    /** Lyrics live in `source/`, not in project.json, so the caller passes them in. */
    fun canGenerateStoryboard(project: Project, lyricsMy: String): Gate = Gate.of(
        lyricsMy.isNotBlank() to Blocker.EMPTY_LYRICS,
        (project.settings.durationMs > 0) to Blocker.NO_DURATION,
        (project.settings.clipMs > 0) to Blocker.INVALID_CLIP_LENGTH,
    )

    fun canCompilePrompt(shot: Shot): Gate = Gate.of(
        (shot.spec != null) to Blocker.NO_SPEC,
        shot.approvals.storyboard to Blocker.STORYBOARD_NOT_APPROVED,
    )

    fun canGenerateRef(project: Project, shot: Shot, lookup: ArtifactLookup): Gate = Gate.of(
        (shot.promptIr != null) to Blocker.NO_PROMPT,
        shot.approvals.prompt to Blocker.PROMPT_NOT_APPROVED,
        !Stale.prompt(project, shot, lookup) to Blocker.PROMPT_STALE,
    )

    /** The chosen reference may be user-uploaded. */
    fun canHandoff(shot: Shot): Gate = Gate.of(
        (shot.chosenRef != null) to Blocker.NO_CHOSEN_REF,
        shot.approvals.ref to Blocker.REF_NOT_APPROVED,
    )

    fun canAccept(shot: Shot): Gate = Gate.of(shot.videos.isNotEmpty() to Blocker.NO_VIDEOS)

    /** Needs at least one accepted video; [placeholderShots] lists what renders as "#NN — pending" cards. */
    fun canRender(project: Project): Gate =
        Gate.of(project.shots.any { it.acceptedVideo != null } to Blocker.NO_ACCEPTED_VIDEOS)

    fun placeholderShots(project: Project): List<Int> =
        project.shots.filter { it.acceptedVideo == null }.map { it.number }

    /**
     * "Approve all": sets [step]'s flag on every shot whose required artifact exists and is not stale. Other shots
     * and other flags are left as they are; approvals are never cleared here.
     */
    fun approveAll(project: Project, step: ApprovalStep, lookup: ArtifactLookup): Project = project.copy(
        shots = project.shots.map { shot ->
            val eligible = when (step) {
                ApprovalStep.STORYBOARD -> shot.spec != null && !Stale.spec(project, shot, lookup)
                ApprovalStep.PROMPT -> shot.promptIr != null && !Stale.prompt(project, shot, lookup)
                ApprovalStep.REF -> shot.chosenRef != null && !Stale.ref(shot, lookup)
                ApprovalStep.VIDEO -> shot.acceptedVideo != null && !Stale.video(shot, lookup)
            }
            if (!eligible) shot else shot.copy(
                approvals = when (step) {
                    ApprovalStep.STORYBOARD -> shot.approvals.copy(storyboard = true)
                    ApprovalStep.PROMPT -> shot.approvals.copy(prompt = true)
                    ApprovalStep.REF -> shot.approvals.copy(ref = true)
                    ApprovalStep.VIDEO -> shot.approvals.copy(video = true)
                },
            )
        },
    )
}
