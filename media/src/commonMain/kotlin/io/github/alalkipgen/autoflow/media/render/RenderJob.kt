package io.github.alalkipgen.autoflow.media.render

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One queued render (PLAN 1F "Render in a WorkManager foreground worker", desktop the same in-process): the graph
 * plus where its files are. Paths are absolute or relative to [workDir] (the project folder). [outputName] has no
 * extension: the render writes `<outputName>.mp4`, `.ass` and `.rendergraph.json` (specs/render-graph.md).
 */
@Serializable
@SerialName("RenderJob")
data class RenderJob(
    val schemaVersion: Int = SCHEMA_VERSION,
    val title: String,
    val graph: RenderGraph,
    val workDir: String,
    val files: Map<String, String>,
    val songPath: String? = null,
    val fontsDir: String,
    val outputName: String,
    val placeholderFont: String? = null,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

sealed interface RenderState {
    data object Queued : RenderState

    data class Running(val fraction: Double, val speed: Double?) : RenderState

    data class Done(val output: String, val wallMs: Long, val durationMs: Long, val maxRssKb: Long?) : RenderState

    /** [tail] = ffmpeg's last error lines, for the error card and Copy. */
    data class Failed(val reason: String, val tail: List<String> = emptyList()) : RenderState

    data object Cancelled : RenderState

    val finished: Boolean get() = this is Done || this is Failed || this is Cancelled
}

/** Runs renders one at a time (PLAN 1F): Android = WorkManager foreground worker, desktop = in-process. */
interface RenderQueue {
    /** Queues [job] behind any running render and returns its id. */
    fun enqueue(job: RenderJob): String

    fun observe(id: String): Flow<RenderState>

    fun cancel(id: String)
}

/** PLAN 1F thermal/battery guard: warn (do not block) below 20 % when not charging. */
object BatteryGuard {
    const val LOW_PERCENT = 20

    fun shouldWarn(percent: Int?, charging: Boolean): Boolean = percent != null && percent < LOW_PERCENT && !charging
}

/** Battery level and charger state; [percent] null = unknown. */
data class BatteryReading(val percent: Int?, val charging: Boolean) {
    val lowWarning: Boolean get() = BatteryGuard.shouldWarn(percent, charging)
}

/** Reads the battery for the Export screen's [BatteryGuard] warning (Android binds it; desktop has none). */
fun interface BatteryReader {
    fun read(): BatteryReading
}
