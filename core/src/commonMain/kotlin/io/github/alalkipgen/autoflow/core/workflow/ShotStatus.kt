package io.github.alalkipgen.autoflow.core.workflow

import io.github.alalkipgen.autoflow.core.model.HandoffState
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.Shot

/**
 * Shots-grid badge (DESIGN.md §3 "Shots"): computed at read time from pointers, approvals and the handoff state,
 * never stored. [STALE] wins, because a stale pointer blocks downstream work until it is redone.
 */
enum class ShotStatus {
    DRAFT, PROMPT_READY, NEEDS_FLOW, IMPORTED, ACCEPTED, STALE;

    companion object {
        fun of(project: Project, shot: Shot, lookup: ArtifactLookup): ShotStatus = when {
            Stale.any(project, shot, lookup) -> STALE
            shot.acceptedVideo != null -> ACCEPTED
            shot.videos.isNotEmpty() -> IMPORTED
            shot.handoff.state == HandoffState.SENT_TO_FLOW || WorkflowRules.canHandoff(shot).allowed -> NEEDS_FLOW
            shot.promptIr != null && shot.approvals.prompt -> PROMPT_READY
            else -> DRAFT
        }
    }
}
