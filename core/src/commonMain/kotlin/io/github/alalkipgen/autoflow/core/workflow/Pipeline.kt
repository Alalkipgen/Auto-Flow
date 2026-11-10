package io.github.alalkipgen.autoflow.core.workflow

import io.github.alalkipgen.autoflow.core.model.HandoffState
import io.github.alalkipgen.autoflow.core.model.Project

/** Pipeline steps in order (specs/workflow-and-approval.md "Pipeline steps"). */
enum class PipelineStep { INPUT, STORYBOARD, PROMPTS, REFERENCES, FLOW_VIDEO, ASSEMBLY }

/**
 * Project-level view of the gates for the shell: which step screens may open (sidebar locks, DESIGN.md §2) and how
 * far the project is (stepper). Pure functions over the per-shot gates in [WorkflowRules]; the UI only reflects them.
 */
object Pipeline {
    /**
     * Whether [step]'s screen may open. A step opens as soon as one shot can work on it, so shots move at their own
     * pace. Work already done (a reference, a sent shot, a video) keeps its screen open.
     */
    fun gate(project: Project, step: PipelineStep, lookup: ArtifactLookup): Gate {
        val shots = project.shots
        return when (step) {
            PipelineStep.INPUT, PipelineStep.STORYBOARD -> Gate.ALLOWED
            PipelineStep.PROMPTS -> Gate.of(shots.any { it.spec != null } to Blocker.NO_SPEC)
            PipelineStep.REFERENCES -> when {
                shots.any { it.refImages.isNotEmpty() || WorkflowRules.canGenerateRef(project, it, lookup).allowed } -> Gate.ALLOWED
                shots.any { it.promptIr != null } -> Gate(listOf(Blocker.PROMPT_NOT_APPROVED))
                else -> Gate(listOf(Blocker.NO_PROMPT))
            }
            PipelineStep.FLOW_VIDEO -> when {
                shots.any { WorkflowRules.canHandoff(it).allowed || it.handoff.state != HandoffState.NONE || it.videos.isNotEmpty() } ->
                    Gate.ALLOWED
                shots.any { it.chosenRef != null } -> Gate(listOf(Blocker.REF_NOT_APPROVED))
                else -> Gate(listOf(Blocker.NO_CHOSEN_REF))
            }
            PipelineStep.ASSEMBLY -> WorkflowRules.canRender(project)
        }
    }

    /** Every shot has finished [step] with current (not stale) artifacts. Assembly is never done here (export, 1D). */
    fun done(project: Project, step: PipelineStep, lookup: ArtifactLookup): Boolean {
        val shots = project.shots
        if (shots.isEmpty()) return false
        return when (step) {
            PipelineStep.INPUT -> project.currentStoryboard != null
            PipelineStep.STORYBOARD -> shots.all { it.spec != null && it.approvals.storyboard && !Stale.spec(project, it, lookup) }
            PipelineStep.PROMPTS -> shots.all { it.promptIr != null && it.approvals.prompt && !Stale.prompt(project, it, lookup) }
            PipelineStep.REFERENCES -> shots.all { it.chosenRef != null && it.approvals.ref && !Stale.ref(it, lookup) }
            PipelineStep.FLOW_VIDEO -> shots.all { it.acceptedVideo != null && !Stale.video(it, lookup) }
            PipelineStep.ASSEMBLY -> false
        }
    }

    /** The first step that is not done: the stepper highlights it. */
    fun current(project: Project, lookup: ArtifactLookup): PipelineStep =
        PipelineStep.entries.first { !done(project, it, lookup) }
}
