package io.github.alalkipgen.autoflow.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.core.workflow.PipelineStep
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import kotlin.test.Test

/** Phone stepper names the open screen's step; without one it names the project's current step. */
@OptIn(ExperimentalTestApi::class)
class StepperTest {
    // Input and Storyboard done, Prompts is the project's current step.
    private val steps = PipelineStep.entries.mapIndexed { i, step ->
        StepView(step, ShellModel.STEP_LABELS.getValue(step), done = i < 2, current = i == 2)
    }

    @Test
    fun phoneStepperNamesTheOpenScreensStep() = runDesktopComposeUiTest(width = 412, height = 300) {
        setContent { CompositionLocalProvider(LocalCompact provides true) { Stepper(steps, viewing = PipelineStep.ASSEMBLY) } }
        onNodeWithText("Step 6 of 6: Assembly").assertExists()
        onNodeWithText("Step 3 of 6: Prompts").assertDoesNotExist()
    }

    @Test
    fun phoneStepperFallsBackToTheProjectsCurrentStep() = runDesktopComposeUiTest(width = 412, height = 300) {
        setContent { CompositionLocalProvider(LocalCompact provides true) { Stepper(steps) } }
        onNodeWithText("Step 3 of 6: Prompts").assertExists()
    }
}
