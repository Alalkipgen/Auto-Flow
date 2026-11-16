package io.github.alalkipgen.autoflow.ui.shell

import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.core.getOrNull
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.recap.RecapRules
import io.github.alalkipgen.autoflow.core.recap.RecapScript
import io.github.alalkipgen.autoflow.core.recap.RecapStory
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
    val state: Flow<ShellUiState> = session.state.map { build(it) }

    fun current(language: UiLanguage = UiLanguage.ENGLISH): ShellUiState = build(session.open, language)

    private fun build(open: OpenProject?, language: UiLanguage = UiLanguage.ENGLISH): ShellUiState {
        val p = open?.project ?: return ShellUiState(locks = (SCREEN_STEPS.keys + RECAP_SCREEN).associateWith { UiText.LOCK_NO_PROJECT.text(language) })
        if (p.mode == Mode.MOVIE_RECAP) return recapShell(p, language)
        val lookup = session.lookup()
        val locks = SCREEN_STEPS.mapNotNull { (screen, step) ->
            Pipeline.gate(p, step, lookup).blockers.firstOrNull()?.let { screen to lockText(it, language) }
        }.toMap()
        val current = Pipeline.current(p, lookup)
        val steps = PipelineStep.entries.map { StepView(it, stepLabel(it, language), Pipeline.done(p, it, lookup), it == current) }
        return ShellUiState(locks + (RECAP_SCREEN to UiText.LOCK_MUSIC_VIDEO.text(language)), steps)
    }

    /** A Movie Recap project (D-33): the music-video screens are locked, Export opens when [RecapRules] allows a render; no stepper. */
    private fun recapShell(p: Project, language: UiLanguage): ShellUiState {
        val recapV2 = p.recap
        val story = recapV2?.story?.let { session.readJson(it, RecapStory.serializer()).getOrNull() }
        val export = io.github.alalkipgen.autoflow.core.recap.RecapRulesV2
            .canRender(recapV2 ?: io.github.alalkipgen.autoflow.core.recap.RecapV2(), story).blockers.firstOrNull()?.text(language)
        val locks = SCREEN_STEPS.keys.filter { it != "Export" }.associateWith { UiText.LOCK_RECAP.text(language) } + listOfNotNull(export?.let { "Export" to it })
        return ShellUiState(locks)
    }

    companion object {
        const val NO_PROJECT_LOCK = "Open a project on the Dashboard first"
        const val RECAP_LOCK = "This is a Movie Recap project — use Movie Recap"
        const val MUSIC_VIDEO_LOCK = "This is a Music Video project — create a Movie Recap on the Dashboard"

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

        fun stepLabel(step: PipelineStep, language: UiLanguage): String = when (step) {
            PipelineStep.INPUT -> UiText.INPUT
            PipelineStep.STORYBOARD -> UiText.STORYBOARD
            PipelineStep.PROMPTS -> UiText.PROMPTS
            PipelineStep.REFERENCES -> UiText.REFERENCES
            PipelineStep.FLOW_VIDEO -> UiText.FLOW_VIDEO
            PipelineStep.ASSEMBLY -> UiText.ASSEMBLY
        }.text(language)

        /** Project-level wording: the lock names the step where the missing work is done. */
        fun lockText(blocker: Blocker, language: UiLanguage = UiLanguage.ENGLISH): String = when (blocker) {
            Blocker.NO_SPEC -> UiText.LOCK_STORYBOARD.text(language)
            Blocker.NO_PROMPT -> UiText.LOCK_PROMPT.text(language)
            Blocker.PROMPT_NOT_APPROVED -> UiText.LOCK_APPROVE_PROMPT.text(language)
            Blocker.NO_CHOSEN_REF -> UiText.LOCK_REF.text(language)
            Blocker.REF_NOT_APPROVED -> UiText.LOCK_APPROVE_REF.text(language)
            Blocker.NO_ACCEPTED_VIDEOS -> UiText.LOCK_VIDEO.text(language)
            else -> blocker.text(language)
        }
    }
}
