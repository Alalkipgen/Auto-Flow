package io.github.alalkipgen.autoflow.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
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
        modifier.fillMaxWidth().background(AutoFlowColors.Panel).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AfText("$running…", color = AutoFlowColors.Cyan)
        AfButton("Cancel", jobs::cancel, kind = ButtonKind.Danger)
    }
}

/** Pipeline stepper (mockup `.steps`): ✓ for done steps, the current step highlighted, the rest numbered. */
@Composable
fun Stepper(steps: List<StepView>, modifier: Modifier = Modifier) {
    val cell = RoundedCornerShape(8.dp)
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
                    .semantics(mergeDescendants = true) { stateDescription = if (s.current) "current" else if (s.done) "done" else "to do" }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AfText(if (s.done && !s.current) "✓" else "${i + 1}", size = 14.sp, bold = true, color = ink)
                AfText(s.label, size = 11.sp, color = ink)
            }
        }
    }
}
