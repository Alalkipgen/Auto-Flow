package io.github.alalkipgen.autoflow.ui.jobs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.recap.etaSeconds
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfCard
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Studio v2 Jobs screen (5A-5, DESIGN.md §7 "Jobs").
 * Lists AI and render jobs with project, progress, ETA, Pause / Resume / Cancel.
 */
@Composable
fun JobsScreen(
    runner: AiJobRunner,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val jobs by runner.jobs.collectAsState()
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(screenPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AfText("Jobs", size = 20.sp, bold = true)
        if (jobs.isEmpty()) {
            AfText("No AI or render jobs yet.", muted = true)
        } else {
            jobs.forEach { job ->
                JobCard(job, onCancel = { scope.launch { runner.cancel(job.id) } })
            }
        }
    }
}

@Composable
private fun JobCard(job: AiJob, onCancel: () -> Unit) {
    AfCard(title = "${job.kind.name} — ${job.state.name}") {
        AfText("Project: ${job.project}", muted = true, size = 12.sp)
        AfText("Items remaining: ${job.items.size}", muted = true, size = 12.sp)
        job.etaSeconds()?.let { AfText("ETA: ${it}s", muted = true, size = 12.sp) }
        job.error?.let { AfText("Error: $it", color = AutoFlowColors.Amber, size = 12.sp) }
        if (job.state == AiJobState.RUNNING || job.state == AiJobState.QUEUED) {
            AfButton("Cancel", onCancel)
        }
    }
}

/** Compute the jobs badge value: > 0 = running count, < 0 = any failure (negated count), 0 = none. */
fun jobsBadgeValue(jobs: List<AiJob>): Int {
    val running = jobs.count { it.state == AiJobState.RUNNING || it.state == AiJobState.QUEUED }
    val failed = jobs.count { it.state == AiJobState.FAILED }
    return when {
        failed > 0 -> -failed
        running > 0 -> running
        else -> 0
    }
}
