package io.github.alalkipgen.autoflow.android.render

import androidx.work.Data
import androidx.work.WorkInfo
import io.github.alalkipgen.autoflow.media.audio.LoudnessMeasurement
import io.github.alalkipgen.autoflow.media.render.FfmpegProgress
import io.github.alalkipgen.autoflow.media.render.RenderState
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun loudnessComesBackFromTheWorkerAsItWasMet() {
        // 5B-4: the worker's result carries how the target was met; before this the note was dropped on Android.
        val m = LoudnessMeasurement(-29.7, -18.2, 3.1, -40.0, 0.4)
        val twoPass = RenderState.Done("/p/exports/export.mp4", 9_000, 60_000, null, RenderState.Loudness(-14.0, m, null))
        val back = roundTrip(twoPass) as RenderState.Done
        assertEquals(twoPass, back)
        assertTrue(back.loudness!!.twoPass)
        val fallback = RenderState.Done(
            "/p/exports/export.mp4", 9_000, 60_000, 700,
            RenderState.Loudness(-14.0, null, "the loudness could not be measured, so one-pass loudnorm was used"),
        )
        val fb = roundTrip(fallback) as RenderState.Done
        assertEquals(fallback, fb)
        assertFalse(fb.loudness!!.twoPass)
        assertNull((roundTrip(RenderState.Done("/p/a.mp4", 1, 2, null)) as RenderState.Done).loudness, "no target asked: none made up")
    }

    @Test
    fun olderOrDamagedLoudnessDataNeverReadsAsTwoPass() {
        val old = Data.Builder().putString(RenderData.KEY_OUTCOME, RenderData.DONE).putString(RenderData.KEY_OUTPUT, "/p/a.mp4")
            .putLong(RenderData.KEY_WALL_MS, 1).putLong(RenderData.KEY_DURATION_MS, 2).build()
        // A result written before 5B-4 has no loudness keys: nothing is invented.
        assertEquals(RenderState.Done("/p/a.mp4", 1, 2, null), RenderData.state(info(WorkInfo.State.SUCCEEDED, old)))
        fun read(measured: DoubleArray) = (RenderData.state(info(
            WorkInfo.State.SUCCEEDED,
            Data.Builder().putAll(old).putDouble(RenderData.KEY_LOUDNESS_TARGET, -14.0).putDoubleArray(RenderData.KEY_LOUDNESS_MEASURED, measured).build(),
        )) as RenderState.Done).loudness!!
        for (bad in listOf(doubleArrayOf(-20.0, -3.0), doubleArrayOf(Double.NaN, -3.0, 2.0, -30.0, 0.1), doubleArrayOf(Double.NEGATIVE_INFINITY, -3.0, 2.0, -30.0, 0.1))) {
            val l = read(bad)
            assertNull(l.measured, bad.toList().toString())
            assertFalse(l.twoPass)
            assertEquals(RenderData.LOUDNESS_UNREADABLE, l.note)
            assertEquals(-14.0, l.targetLufs)
        }
        // A long note is cut, and the Data still builds within WorkManager's 10 KB limit (build() throws above it).
        val long = roundTrip(RenderState.Done("/p/a.mp4", 1, 2, null, RenderState.Loudness(-14.0, null, "n".repeat(5_000)))) as RenderState.Done
        assertEquals(240, long.loudness!!.note!!.length)
    }
}
