package io.github.alalkipgen.autoflow.ui.shell

import io.github.alalkipgen.autoflow.core.workflow.Blocker
import io.github.alalkipgen.autoflow.core.workflow.Pipeline
import io.github.alalkipgen.autoflow.core.workflow.PipelineStep
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.storyboard.text
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One stepper cell (mockup `.step`): ✓ when [done], highlighted when [current]. */
data class StepView(val step: PipelineStep, val label: String, val done: Boolean, val current: Boolean)

/** [locks]: sidebar entry → why it is locked. [steps] is empty while no project is open. */
data class ShellUiState(val locks: Map<String, String> = emptyMap(), val steps: List<StepView> = emptyList())

/** Sidebar locks and the pipeline stepper (DESIGN.md §2), from [Pipeline] gates over the open project. */
class ShellModel(private val session: ProjectSession) {
    val state: Flow<ShellUiState> = session.state.map(::build)

    fun current(): ShellUiState = build(session.open)

    private fun build(open: OpenProject?): ShellUiState {
        val p = open?.project ?: return ShellUiState(locks = SCREEN_STEPS.keys.associateWith { NO_PROJECT_LOCK })
        val lookup = session.lookup()
        val locks = SCREEN_STEPS.mapNotNull { (screen, step) ->
            Pipeline.gate(p, step, lookup).blockers.firstOrNull()?.let { screen to lockText(it) }
        }.toMap()
        val current = Pipeline.current(p, lookup)
        val steps = PipelineStep.entries.map { StepView(it, STEP_LABELS.getValue(it), Pipeline.done(p, it, lookup), it == current) }
        return ShellUiState(locks, steps)
    }

    companion object {
        const val NO_PROJECT_LOCK = "Open a project on the Dashboard first"

        /** Sidebar entries that belong to a pipeline step; the others never lock. */
        val SCREEN_STEPS: Map<String, PipelineStep> = linkedMapOf(
            "Input" to PipelineStep.INPUT,
            "Storyboard" to PipelineStep.STORYBOARD,
            "Shots" to PipelineStep.PROMPTS,
            "References" to PipelineStep.REFERENCES,
            "Flow Handoff" to PipelineStep.FLOW_VIDEO,
            "Timeline" to PipelineStep.ASSEMBLY,
            "Export" to PipelineStep.ASSEMBLY,
        )

        /** Mockup stepper labels. */
        val STEP_LABELS: Map<PipelineStep, String> = mapOf(
            PipelineStep.INPUT to "Input",
            PipelineStep.STORYBOARD to "Storyboard",
            PipelineStep.PROMPTS to "Prompts",
            PipelineStep.REFERENCES to "References",
            PipelineStep.FLOW_VIDEO to "Flow Video",
            PipelineStep.ASSEMBLY to "Assembly",
        )

        /** Project-level wording: the lock names the step where the missing work is done. */
        fun lockText(blocker: Blocker): String = when (blocker) {
            Blocker.NO_SPEC -> "Generate the storyboard on Input first"
            Blocker.NO_PROMPT -> "Compile a shot's prompt on Shots first"
            Blocker.PROMPT_NOT_APPROVED -> "Approve a shot's prompt on Shots first"
            Blocker.NO_CHOSEN_REF -> "Import a reference image for a shot first"
            Blocker.REF_NOT_APPROVED -> "Approve a shot's reference image first"
            Blocker.NO_ACCEPTED_VIDEOS -> "Accept at least one video from Flow first"
            else -> blocker.text()
        }
    }
}
