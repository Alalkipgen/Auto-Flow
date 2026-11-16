package io.github.alalkipgen.autoflow.ui.shell

import io.github.alalkipgen.autoflow.ui.i18n.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.workflow.PipelineStep
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardJobs
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes

/**
 * The running AI job with Cancel, shown above every screen (specs/ai-providers.md: every running AI call shows a
 * Cancel button), so a job started on Input or Storyboard stays visible after moving to another screen.
 */
@Composable
fun JobBanner(jobs: StoryboardJobs, modifier: Modifier = Modifier) {
    val job by jobs.state.collectAsState()
    val running = job.running ?: return
    Row(
        modifier.fillMaxWidth().background(AutoFlowColors.Panel).padding(horizontal = screenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AfText("${job.notice?.text(LocalUiLanguage.current) ?: running}…", color = AutoFlowColors.Cyan)
        AfButton(tr(UiText.CANCEL), jobs::cancel, kind = ButtonKind.Danger)
    }
}

/**
 * Pipeline stepper (mockup `.steps`): ✓ for done steps, the current step highlighted, the rest numbered.
 * [viewing] is the step of the open screen: the phone stepper names it (DESIGN.md §2: the selected step is
 * highlighted), so Export no longer reads "Step 3 of 6: Prompts" while some shots still wait for a prompt.
 */
@Composable
fun Stepper(steps: List<StepView>, modifier: Modifier = Modifier, viewing: PipelineStep? = null) {
    if (LocalCompact.current) {
        CompactStepper(steps, modifier, viewing)
        return
    }
    val cell = RoundedCornerShape(8.dp)
    val currentWord = tr(UiText.STEP_CURRENT); val doneWord = tr(UiText.STEP_DONE); val todoWord = tr(UiText.STEP_TODO)
    Row(
        modifier
            .padding(start = 24.dp, end = 24.dp, top = 16.dp)
            .fillMaxWidth()
            .background(AutoFlowColors.Panel, AutoFlowShapes.Card)
            .border(AutoFlowShapes.Border, AutoFlowColors.Line, AutoFlowShapes.Card)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        steps.forEachIndexed { i, s ->
            val ink = when {
                s.current -> Color.White
                s.done -> AutoFlowColors.Green
                else -> AutoFlowColors.Muted
            }
            Column(
                Modifier
                    .weight(1f)
                    .then(
                        if (s.current) {
                            Modifier.background(AutoFlowColors.Purple.copy(alpha = 0.2f), cell).border(AutoFlowShapes.Border, AutoFlowColors.Purple, cell)
                        } else {
                            Modifier
                        },
                    )
                    .semantics(mergeDescendants = true) { stateDescription = if (s.current) currentWord else if (s.done) doneWord else todoWord }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AfText(if (s.done && !s.current) "✓" else "${i + 1}", size = 14.sp, bold = true, color = ink)
                AfText(s.label, size = 11.sp, color = ink)
            }
        }
    }
}

/**
 * Phone stepper: "Step 2 of 6: Storyboard" (the open screen's step, else the project's current one) over one bar
 * per step (green done, purple the named step) — six cells do not fit.
 */
@Composable
private fun CompactStepper(steps: List<StepView>, modifier: Modifier = Modifier, viewing: PipelineStep? = null) {
    val currentWord = tr(UiText.STEP_CURRENT); val doneWord = tr(UiText.STEP_DONE); val todoWord = tr(UiText.STEP_TODO)
    val at = steps.indexOfFirst { it.step == viewing }.takeIf { it >= 0 } ?: steps.indexOfFirst { it.current }
    Column(
        modifier
            .padding(start = 14.dp, end = 14.dp, top = 10.dp)
            .fillMaxWidth()
            .background(AutoFlowColors.Panel, AutoFlowShapes.Card)
            .border(AutoFlowShapes.Border, AutoFlowColors.Line, AutoFlowShapes.Card)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val done = steps.count { it.done }
        AfText(
            if (at >= 0) tr(UiText.CLASSIC_STEP, "n" to at + 1, "total" to steps.size, "label" to steps[at].label) else tr(UiText.CLASSIC_STEPS_DONE, "n" to done, "total" to steps.size),
            size = 12.sp,
            bold = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            steps.forEachIndexed { i, s ->
                val color = when {
                    i == at -> AutoFlowColors.Purple
                    s.done -> AutoFlowColors.Green
                    else -> AutoFlowColors.Line
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(color, RoundedCornerShape(2.dp))
                        .semantics { stateDescription = "${s.label} " + if (i == at) currentWord else if (s.done) doneWord else todoWord },
                )
            }
        }
    }
}
