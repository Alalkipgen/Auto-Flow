package io.github.alalkipgen.autoflow.ui.jobs

import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.media.render.Quality
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.ui.export.ExportRun
import io.github.alalkipgen.autoflow.ui.recap.RecapModel
import io.github.alalkipgen.autoflow.ui.recap.RecapUiState
import io.github.alalkipgen.autoflow.ui.storyboard.JobState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JobRowsTest {
    private fun run(state: RenderState) = ExportRun("/p/Ngam.autoflow", Quality.EXPORT, "j1", "exports/export.mp4", state)

    @Test
    fun nothingRunningMeansNoRowsAndNoBadge() {
        val rows = jobRows(RecapUiState(), null, JobState(), emptyList())
        assertTrue(rows.isEmpty())
        assertEquals(0, jobsBadgeValue(rows))
    }

    @Test
    fun recapScriptJobCanBeCancelledAndShowsTheProject() {
        val rows = jobRows(RecapUiState(projectDir = "/p/Ngam.autoflow", job = RecapModel.WRITING, progress = null), null, null, emptyList())
        assertEquals(1, rows.size)
        assertEquals(JobSource.RECAP, rows[0].source)
        assertEquals("Ngam", rows[0].detail)
        assertTrue(rows[0].canCancel)
        assertEquals(1, jobsBadgeValue(rows))
    }

    @Test
    fun voiceJobShowsProgressButNoCancel() {
        val rows = jobRows(RecapUiState(job = "Speaking #1 (1 of 3)…", progress = 0.33), null, null, emptyList())
        assertEquals(0.33, rows[0].progress)
        assertFalse(rows[0].canCancel)
    }

    @Test
    fun renderStates() {
        val running = jobRows(null, run(RenderState.Running(0.5, 1.0)), null, emptyList()).single()
        assertTrue(running.running && running.canCancel)
        assertEquals(0.5, running.progress)
        val done = jobRows(null, run(RenderState.Done("o", 1, 1, null)), null, emptyList())
        assertFalse(done.single().running)
        assertEquals(0, jobsBadgeValue(done))
        val failed = jobRows(null, run(RenderState.Failed("ffmpeg exited 1")), null, emptyList())
        assertEquals("ffmpeg exited 1", failed.single().detail)
        assertEquals(-1, jobsBadgeValue(failed))
    }

    @Test
    fun storyboardAndAiRunnerJobsAreListed() {
        val ai = AiJob("a1", AiJobKind.entries.first(), "projects/Ngam.autoflow", items = listOf("1", "2"), state = AiJobState.RUNNING)
        val rows = jobRows(null, null, JobState(running = "Generating storyboard"), listOf(ai))
        assertEquals(listOf(JobSource.STORYBOARD, JobSource.AI), rows.map { it.source })
        assertEquals("a1", rows[1].aiJobId)
        assertEquals(2, jobsBadgeValue(rows))
        assertEquals(1, jobsBadgeValue(listOf(ai)))
    }
}
