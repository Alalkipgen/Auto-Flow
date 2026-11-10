package io.github.alalkipgen.autoflow.media.selftest

import io.github.alalkipgen.autoflow.media.FfmpegCapabilities

/** Which engine produced a render: the bundled ffmpeg (D-23 primary) or Android Media3 Transformer (D-23 fallback). */
enum class Engine(val label: String) { FFMPEG("ffmpeg"), MEDIA3("Media3") }

sealed interface RenderOutcome {
    /** [probe] is the ffprobe summary (null when ffprobe is unavailable); [framePng] the path of the frame at 4 s. */
    data class Ok(val outputBytes: Long, val probe: String?, val framePng: String?) : RenderOutcome

    /** [command] and [logTail] are what the owner pastes back so the failure can be reproduced off-device. */
    data class Failed(val reason: String, val exitCode: Int?, val command: String?, val logTail: String?) : RenderOutcome

    data object Cancelled : RenderOutcome
}

/** [peakRssKb] is the process peak RSS (ffmpeg `-benchmark` maxrss, or VmHWM for Media3); [rssNote] says which. */
data class RenderResult(
    val id: String,
    val title: String,
    val engine: Engine,
    val wallMs: Long,
    val peakRssKb: Long?,
    val rssNote: String?,
    val outcome: RenderOutcome,
) {
    val ok: Boolean get() = outcome is RenderOutcome.Ok
}

/** What the S6 run tells the D-23 decision (PLAN S6 exit criterion). */
enum class Verdict(val summary: String) {
    FFMPEG_OK("PASS — bundled ffmpeg runs all renders (D-23 primary path)"),
    FFMPEG_PARTIAL("PARTIAL — bundled ffmpeg runs, but some renders failed"),
    MEDIA3_ONLY("FALLBACK — ffmpeg cannot run here; Media3 rendered (D-23 fallback path)"),
    FAIL("FAIL — no engine produced a video"),
    INCOMPLETE("INCOMPLETE — cancelled or not run"),
}

/**
 * The on-device Self-test result (PLAN S6). Plain data so it is unit-testable; [toMarkdown] is what the owner copies
 * from the phone and pastes back. [sections] are ordered key/value groups (Device, App, ffmpeg binary, …).
 */
data class SelfTestReport(
    val startedAt: String,
    val totalMs: Long,
    val sections: List<Section>,
    val capabilities: FfmpegCapabilities?,
    val execError: String?,
    val renders: List<RenderResult>,
    val notes: List<String>,
) {
    data class Section(val title: String, val rows: List<Pair<String, String>>)

    fun verdict(): Verdict {
        val ffmpeg = renders.filter { it.engine == Engine.FFMPEG }
        val media3 = renders.filter { it.engine == Engine.MEDIA3 }
        return when {
            renders.isEmpty() || renders.any { it.outcome is RenderOutcome.Cancelled } -> Verdict.INCOMPLETE
            execError == null && ffmpeg.size == SelfTestRenders.COUNT && ffmpeg.all { it.ok } -> Verdict.FFMPEG_OK
            execError == null && ffmpeg.any { it.ok } -> Verdict.FFMPEG_PARTIAL
            media3.any { it.ok } -> Verdict.MEDIA3_ONLY
            else -> Verdict.FAIL
        }
    }

    fun toMarkdown(): String = buildString {
        appendLine("# Auto Flow self-test: ${verdict().summary}")
        appendLine("Started $startedAt, took ${seconds(totalMs)}.")
        for (s in sections) {
            appendLine()
            appendLine("## ${s.title}")
            for ((k, v) in s.rows) appendLine("- $k: $v")
        }
        appendLine()
        appendLine("## ffmpeg capabilities")
        if (execError != null) appendLine("- exec FAILED: $execError")
        val c = capabilities
        if (c == null) {
            appendLine("- not available")
        } else {
            appendLine("- version: ${c.version ?: "unknown"}")
            appendLine(
                "- ass (libass): ${yn(c.hasAss)}, xfade: ${yn(c.hasXfade)}, libx264: ${yn(c.hasLibx264)}, aac: ${yn(c.hasAac)}" +
                    " → overlay backend ${c.overlayBackend}",
            )
            appendLine("- filters: ${c.filters.size}, encoders: ${c.encoders.size}")
            c.configuration?.let { appendLine("- configuration: `$it`") }
        }
        appendLine()
        appendLine("## Renders")
        if (renders.isEmpty()) {
            appendLine("- none")
        } else {
            appendLine("| Render | Engine | Result | Time | Peak RSS | Output |")
            appendLine("|---|---|---|---|---|---|")
            for (r in renders) appendLine("| ${r.title} | ${r.engine.label} | ${result(r.outcome)} | ${seconds(r.wallMs)} | ${rss(r)} | ${output(r.outcome)} |")
        }
        val failed = renders.mapNotNull { r -> (r.outcome as? RenderOutcome.Failed)?.let { r to it } }
        for ((r, f) in failed) {
            appendLine()
            appendLine("### ${r.id} (${r.engine.label}) failed")
            appendLine("- reason: ${f.reason}")
            f.exitCode?.let { appendLine("- exit code: $it") }
            f.command?.let { appendLine("- command: `$it`") }
            f.logTail?.takeIf { it.isNotBlank() }?.let {
                appendLine("```")
                appendLine(it.trimEnd())
                appendLine("```")
            }
        }
        if (notes.isNotEmpty()) {
            appendLine()
            appendLine("## Notes")
            for (n in notes) appendLine("- $n")
        }
    }

    private fun yn(b: Boolean) = if (b) "yes" else "NO"

    private fun result(o: RenderOutcome) = when (o) {
        is RenderOutcome.Ok -> "ok"
        is RenderOutcome.Failed -> "FAILED"
        RenderOutcome.Cancelled -> "cancelled"
    }

    private fun output(o: RenderOutcome) = when (o) {
        is RenderOutcome.Ok -> listOfNotNull(mb(o.outputBytes), o.probe).joinToString(", ")
        is RenderOutcome.Failed -> o.reason.replace("|", "/").take(120)
        RenderOutcome.Cancelled -> ""
    }

    private fun rss(r: RenderResult) = r.peakRssKb?.let { "${it / 1024} MB" + (r.rssNote?.let { n -> " ($n)" } ?: "") } ?: "n/a"

    companion object {
        fun seconds(ms: Long): String = "${ms / 1000}.${(ms % 1000) / 100} s"

        fun mb(bytes: Long): String = "${bytes / (1024 * 1024)}.${(bytes % (1024 * 1024)) * 10 / (1024 * 1024)} MB"
    }
}
