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
import kotlin.test.assertNull

class JobRowsTest {
    @Test fun runnerProjectionDoesNotInventARunningRowBeforeTheQueueStarts() {
        val run = AiJob("tx-id", AiJobKind.TRANSCRIBE, "/p/B.autoflow", listOf("request"))
        val source = RecapUiState(projectDir = run.project, job = RecapModel.TRANSCRIBING, runnerJobId = run.id)
        assertTrue(jobRows(source, null, null, emptyList()).isEmpty())
        for (state in listOf(AiJobState.QUEUED, AiJobState.RUNNING)) {
            val rows = jobRows(source, null, null, listOf(run.copy(state = state)))
            val row = rows.single()
            assertEquals(run.id, row.jobId)
            assertEquals(state.name, row.status.name)
            assertTrue(row.canCancel)
            assertEquals(1, jobsBadgeValue(rows))
        }
    }

    @Test fun geminiShowsOnlyCommittedUnitsOrAnIndeterminateWait() {
        val single = AiJob("single", AiJobKind.TRANSCRIBE, "/p/B.autoflow", listOf("request"), AiJobState.RUNNING, initialItemCount = 1)
        fun row(job: AiJob) = jobRows(null, null, null, listOf(job)).single()
        assertTrue(row(single).indeterminate)
        assertNull(row(single).progress)
        val multi = row(single.copy(initialItemCount = 3, items = listOf("request", "request-3")))
        assertEquals(1 / 3.0, multi.progress)
        assertTrue(multi.detail!!.contains("Request 2 of 3"))
        val clips = row(single.copy(kind = AiJobKind.TTS_ALL, initialItemCount = 10, savedClips = 3, items = listOf("b8", "b9", "b10")))
        assertEquals(.3, clips.progress)
        assertTrue(clips.detail!!.contains("3/10 voice clips saved"))
        assertFalse(clips.indeterminate)
    }

    @Test fun needsYouCountsPausedAndFailedBeforeRunningWithoutInventingApprovalRows() {
        val jobs = AiJobState.entries.map { AiJob(it.name, AiJobKind.TTS_ALL, "/p/A.autoflow", listOf("stable-block"), it) }
        val rows = jobRows(null, null, null, jobs)
        assertEquals(-2, jobsBadgeValue(rows))
        assertEquals(2, rows.count { it.group == JobGroup.NEEDS_YOU })
        assertEquals(2, rows.count { it.group == JobGroup.RUNNING })
        assertEquals(2, rows.count { it.group == JobGroup.FINISHED })
        assertEquals(2, jobsBadgeValue(rows.filter { it.group == JobGroup.RUNNING }))
        assertEquals(0, jobsBadgeValue(rows.filter { it.group == JobGroup.FINISHED }))
    }
    @Test fun resumeOnlyOffersSupportedRemainingItemsAndKeepsRoutingIdentity() {
        AiJobKind.entries.forEach { kind ->
            val job = AiJob("stable", kind, "/p/A.autoflow", listOf("stable-b"), AiJobState.PAUSED)
            val row = jobRows(null, null, null, listOf(job), projectNames = mapOf(job.project to "Real saved name")).single()
            assertEquals(if (kind in listOf(AiJobKind.TTS_ALL, AiJobKind.TRANSCRIBE, AiJobKind.MEDIA_TRANSCRIBE)) 1 else null, row.resumeCount)
            assertEquals(job.project, row.projectDir); assertEquals(job.id, row.jobId)
            assertEquals("Real saved name", row.projectTitle)
            assertNull(jobRows(null, null, null, listOf(job.copy(items = emptyList()))).single().resumeCount)
        }
    }
    @Test fun failedRenderKeepsProjectAndReasonWhileCancelledIsNotDone() {
        val failed = jobRows(null, run(RenderState.Failed("retained failure")), null, emptyList()).single()
        assertEquals("/p/Ngam.autoflow", failed.projectDir)
        assertEquals("Ngam", failed.projectTitle); assertEquals("retained failure", failed.detail)
        val cancelled = jobRows(null, run(RenderState.Cancelled), null, emptyList()).single()
        assertEquals(io.github.alalkipgen.autoflow.ui.components.StudioStatus.CANCELLED, cancelled.status)
        assertEquals(JobGroup.FINISHED, cancelled.group)
    }
    @Test fun homeAndJobsUseActualStatesAndNeverRpmBasedEtas() {
        AiJobState.entries.forEach { state ->
            val job = AiJob("stable-job", AiJobKind.TTS_ALL, "/projects/QA Landscape.autoflow", listOf("stable-a"), state = state)
            val row = jobRows(null, null, null, listOf(job)).single()
            assertEquals(state.name, row.status.name)
            assertEquals(state in listOf(AiJobState.QUEUED, AiJobState.RUNNING), row.running)
            assertFalse(row.detail!!.contains("~"), "No theoretical ETA in either surface")
            assertTrue(row.detail!!.contains("QA Landscape"))
        }
    }

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
    @Test
    fun changingUiLanguageChangesRowsButNotJobRoutingIdentityOrCancellation() {
        val ai = AiJob("stable-job", AiJobKind.TTS_ALL, "projects/မြန်မာ.autoflow", items = listOf("stable-block"), state = AiJobState.RUNNING)
        val en = jobRows(null, null, null, listOf(ai), io.github.alalkipgen.autoflow.ui.i18n.UiLanguage.ENGLISH).single()
        val my = jobRows(null, null, null, listOf(ai), io.github.alalkipgen.autoflow.ui.i18n.UiLanguage.BURMESE).single()
        assertTrue(en.title.contains("Speak all"))
        assertTrue(my.title.contains("အားလုံး အသံထုတ်"))
        assertEquals(en.source, my.source)
        assertEquals(en.aiJobId, my.aiJobId)
        assertEquals(en.canCancel, my.canCancel)
        assertEquals(en.progress, my.progress)
        assertEquals("projects/မြန်မာ.autoflow", ai.project)
        assertEquals(listOf("stable-block"), ai.items)
    }
}
