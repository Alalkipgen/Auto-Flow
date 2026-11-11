package io.github.alalkipgen.autoflow.android.render

import androidx.work.Data
import androidx.work.WorkInfo
import io.github.alalkipgen.autoflow.media.render.FfmpegProgress
import io.github.alalkipgen.autoflow.media.render.RenderState
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class RenderDataTest {
    private fun info(state: WorkInfo.State, output: Data = Data.EMPTY, progress: Data = Data.EMPTY) =
        WorkInfo(UUID.randomUUID(), state, setOf("render"), output, progress)

    private fun roundTrip(state: RenderState) = RenderData.state(info(WorkInfo.State.SUCCEEDED, RenderData.output(state, "fg")))

    @Test
    fun everyOutcomeIsASucceededWorkThatReadsBack() {
        assertEquals(RenderState.Done("/p/exports/a.mp4", 1_200, 21_000, 810_000), roundTrip(RenderState.Done("/p/exports/a.mp4", 1_200, 21_000, 810_000)))
        assertEquals(RenderState.Done("/p/a.mp4", 1, 2, null), roundTrip(RenderState.Done("/p/a.mp4", 1, 2, null)))
        assertEquals(RenderState.Failed("ffmpeg failed (exit 1)", listOf("x")), roundTrip(RenderState.Failed("ffmpeg failed (exit 1)", listOf("x"))))
        assertEquals(RenderState.Cancelled, roundTrip(RenderState.Cancelled))
        assertEquals("fg", RenderData.foregroundNote(info(WorkInfo.State.SUCCEEDED, RenderData.output(RenderState.Cancelled, "fg"))))
    }

    @Test
    fun longErrorsAreCutToFitWorkManagersLimit() {
        val failed = roundTrip(RenderState.Failed("e".repeat(5_000), List(100) { "line $it " + "x".repeat(500) })) as RenderState.Failed
        assertEquals(480, failed.reason.length)
        assertEquals(15, failed.tail.size)
        assertEquals("line 85 ", failed.tail.first().take(8))
        assertEquals(120, failed.tail.first().length)
    }

    @Test
    fun workManagerStatesMapToRenderStates() {
        assertEquals(RenderState.Queued, RenderData.state(info(WorkInfo.State.ENQUEUED)))
        assertEquals(RenderState.Queued, RenderData.state(info(WorkInfo.State.BLOCKED)))
        assertEquals(RenderState.Running(0.0, null), RenderData.state(info(WorkInfo.State.RUNNING)))
        assertEquals(
            RenderState.Running(0.25, 1.5),
            RenderData.state(info(WorkInfo.State.RUNNING, progress = RenderData.progress(FfmpegProgress.Progress(250, 0.25, 1.5, false)))),
        )
        assertEquals(RenderState.Cancelled, RenderData.state(info(WorkInfo.State.CANCELLED)))
        assertEquals(RenderState.Failed("the render stopped"), RenderData.state(info(WorkInfo.State.FAILED)))
        assertEquals(RenderState.Failed(RenderData.UNKNOWN), RenderData.state(null))
    }
}
