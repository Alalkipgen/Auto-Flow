package io.github.alalkipgen.autoflow.android.selftest

import android.content.Context
import android.os.SystemClock
import androidx.work.WorkManager
import io.github.alalkipgen.autoflow.android.render.Battery
import io.github.alalkipgen.autoflow.android.render.ProcessToolRunner
import io.github.alalkipgen.autoflow.android.render.RenderWorker
import io.github.alalkipgen.autoflow.android.render.WorkManagerRenderQueue
import io.github.alalkipgen.autoflow.android.selftest.SelfTestState.Status
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.media.probe.FrameGrab
import io.github.alalkipgen.autoflow.media.probe.MediaProbe
import io.github.alalkipgen.autoflow.media.render.Anim
import io.github.alalkipgen.autoflow.media.render.AudioTrack
import io.github.alalkipgen.autoflow.media.render.Clip
import io.github.alalkipgen.autoflow.media.render.Overlay
import io.github.alalkipgen.autoflow.media.render.Pos
import io.github.alalkipgen.autoflow.media.render.RenderGraph
import io.github.alalkipgen.autoflow.media.render.RenderJob
import io.github.alalkipgen.autoflow.media.render.RenderPresets
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.media.render.Transition
import io.github.alalkipgen.autoflow.media.selftest.SelfTestReport
import io.github.alalkipgen.autoflow.platform.AndroidToolLocator
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Self-test "Render job (WorkManager)" (PLAN 1F): the real render path end to end on the phone — a [RenderGraph]
 * with crossfades, a cut, a placeholder card, the song over the clips' sound and Burmese overlays, compiled by
 * `:media` and run by [RenderWorker] through [WorkManagerRenderQueue], queued from the visible screen. The result
 * becomes the last section of the report.
 */
object SelfTestJob {
    private const val TITLE = "Self-test job"
    private const val FONT = "Noto Sans Myanmar"
    private const val OUTPUT = "exports/selftest-job"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var current: String? = null

    @Volatile private var cancelRequested = false

    @Volatile private var queue: WorkManagerRenderQueue? = null

    fun start(context: Context) {
        val app = context.applicationContext
        if (SelfTestState.ui.value.jobRunning) return
        cancelRequested = false
        SelfTestState.jobBegin()
        scope.launch {
            val section = try {
                run(app)
            } catch (e: Exception) {
                SelfTestState.step(SelfTestState.JOB_SECTION.removePrefix("## "), Status.FAILED, "${e::class.simpleName}: ${e.message}")
                section(listOf("- Result: **CRASHED** — ${e::class.simpleName}: ${e.message}"), e.stackTraceToString().lines().take(30))
            }
            val report = SelfTestState.withJobSection(SelfTestState.currentReport(), section)
            runCatching { File(publicDir(app), SelfTestRunner.REPORT).apply { parentFile?.mkdirs() }.writeText(report) }
            current = null
            SelfTestState.jobFinish(report)
        }
    }

    fun cancel() {
        cancelRequested = true
        current?.let { queue?.cancel(it) }
    }

    private suspend fun run(app: Context): String {
        val step = SelfTestState.JOB_SECTION.removePrefix("## ")
        SelfTestState.step(step, Status.RUNNING, "preparing the test media")
        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "selftest-job/${UUID.randomUUID()}")
        val ready = withContext(Dispatchers.IO) { SelfTestRunner.extractFixtures(app, File(dir, "in")) }
        if (!ready) {
            SelfTestState.step(step, Status.FAILED, "no test media in this APK (CI generates it)")
            return section(listOf("- Result: **SKIPPED** — the test media (assets/selftest) is not in this APK"))
        }
        if (cancelRequested) {
            SelfTestState.step(step, Status.FAILED, "cancelled")
            return section(listOf("- Result: **CANCELLED** during preparation"))
        }
        val job = job(dir)
        val battery = Battery.read(app)
        val q = WorkManagerRenderQueue(app, SelfTestActivity::class.java.name).also { queue = it }
        val t0 = SystemClock.elapsedRealtime()
        val id = q.enqueue(job).also { current = it }
        if (cancelRequested) q.cancel(id)
        var screenOff = false
        val final = q.observe(id).onEach { s ->
            if (!DeviceInfo.interactive(app)) screenOff = true
            val detail = when (s) {
                RenderState.Queued -> "queued"
                is RenderState.Running -> "${(s.fraction * 100).toInt()}%" + (s.speed?.let { " · %.2f×".format(it) } ?: "")
                else -> return@onEach
            }
            SelfTestState.step(step, Status.RUNNING, detail)
            SelfTestState.progress("$step: $detail")
        }.first { it.finished }
        val totalMs = SystemClock.elapsedRealtime() - t0
        val info = WorkManager.getInstance(app).getWorkInfoByIdFlow(UUID.fromString(id)).first()
        val lines = mutableListOf(
            "- Graph: 4 clips (8 s · placeholder card 3 s, crossfade 0.5 s · 8 s, cut · 4 s, crossfade 1 s), song over the " +
                "clips' sound (fade in 0.5 s, out 2 s), 2 Burmese overlays (fade); ${job.graph.output.width}×${job.graph.output.height} " +
                "Export (x264 ${job.graph.output.quality.x264Preset}, crf ${job.graph.output.quality.crf}); expected ${SelfTestReport.seconds(job.graph.durationMs)}",
        )
        var tail = emptyList<String>()
        val partLeft = File(RenderWorker.partPath(job)).exists()
        when (final) {
            is RenderState.Done -> {
                SelfTestState.step(step, Status.OK, "${SelfTestReport.seconds(final.wallMs)}, ${final.maxRssKb?.let { "${it / 1024} MB" } ?: "RSS n/a"}")
                lines += "- Result: **OK** — render ${SelfTestReport.seconds(final.wallMs)} (tap → done ${SelfTestReport.seconds(totalMs)}), " +
                    "ffmpeg peak RSS ${final.maxRssKb?.let { "${it / 1024} MB" } ?: "n/a"}"
                lines += inspect(app, dir, final, job.graph.durationMs)
            }
            is RenderState.Failed -> {
                SelfTestState.step(step, Status.FAILED, final.reason)
                lines += "- Result: **FAILED** — ${final.reason} (after ${SelfTestReport.seconds(totalMs)})"
                tail = final.tail
            }
            else -> {
                SelfTestState.step(step, Status.FAILED, "cancelled")
                lines += "- Result: **CANCELLED** (after ${SelfTestReport.seconds(totalMs)})"
            }
        }
        lines += "- Half-written ${File(RenderWorker.partPath(job)).name} left behind: ${if (partLeft) "YES (bug)" else "no"}"
        lines += "- Foreground: ${WorkManagerRenderQueue.foregroundNote(info) ?: "unknown"}"
        lines += "- Battery at start: $battery" + if (battery.lowWarning) " — below 20 %; warning shown before this test" else ""
        lines += "- Screen off during the render: " + if (screenOff) "yes, and it kept going" else "no"
        return section(lines, tail)
    }

    /** ffprobe the export, check the side files, grab frames for the screen (Burmese text, the placeholder card). */
    private suspend fun inspect(app: Context, dir: File, done: RenderState.Done, expectedMs: Long): List<String> = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        val out = File(done.output)
        val tools = AndroidToolLocator(app.applicationInfo.nativeLibraryDir)
        val runner = ProcessToolRunner()
        val mb = "%.1f MB".format(out.length() / 1_048_576.0)
        val probed = tools.findFfprobe()?.let { (MediaProbe(runner, it).probe(out.path, dir.path) as? MediaProbe.Result.Ok)?.info }
        lines += "- Output: ${out.relativeTo(dir)}, $mb — " + if (probed == null) {
            "ffprobe could not read it"
        } else {
            val fps = probed.fps?.let { " %.0f fps".format(it) } ?: ""
            val close = kotlin.math.abs(probed.durationMs - expectedMs) <= 100
            "${probed.videoCodec} ${probed.width}×${probed.height}$fps + ${probed.audioCodec ?: "NO AUDIO"}, " +
                "${SelfTestReport.seconds(probed.durationMs)} (${if (close) "matches" else "expected ${SelfTestReport.seconds(expectedMs)}"})"
        }
        val base = done.output.removeSuffix(".mp4")
        lines += "- Side files: .ass ${yes(File("$base.ass"))}, .rendergraph.json ${yes(File("$base.rendergraph.json"))}"
        val ffmpeg = tools.findFfmpeg()
        if (ffmpeg != null) {
            val grab = FrameGrab(runner, ffmpeg)
            val frames = File(publicDir(app), "job").apply { deleteRecursively(); mkdirs() }
            val shots = listOf("4 s" to 4_000L, "9 s placeholder" to 9_000L, "15 s" to 15_000L)
            val got = mutableListOf<String>()
            for ((label, ms) in shots) {
                val png = File(frames, "at-${ms / 1000}s.png")
                if (grab.at(out.path, ms, png.path, dir.path) == FrameGrab.Result.Ok && png.isFile) {
                    SelfTestState.frame(SelfTestState.JOB_FRAME + label, png.path)
                    got += label
                }
            }
            val last = File(frames, "last.png")
            if (grab.last(out.path, last.path, dir.path) == FrameGrab.Result.Ok && last.isFile) {
                SelfTestState.frame(SelfTestState.JOB_FRAME + "last", last.path)
                got += "last"
            }
            lines += "- Frames: ${got.joinToString().ifEmpty { "none could be read" }} (see the screen)"
        }
        lines
    }

    private fun yes(f: File) = if (f.isFile && f.length() > 0) "yes" else "MISSING"

    private fun section(lines: List<String>, tail: List<String> = emptyList()): String = buildString {
        append(SelfTestState.JOB_SECTION).append("\n\n")
        lines.forEach { append(it).append('\n') }
        if (tail.isNotEmpty()) append("\n```\n").append(tail.joinToString("\n")).append("\n```\n")
    }

    private fun publicDir(app: Context) = File(app.getExternalFilesDir(null) ?: app.filesDir, "selftest")

    /** The graph: every 1F render feature the phone has to get right, on the CI fixtures (tools/selftest-fixtures.sh). */
    fun job(dir: File): RenderJob {
        val v1 = ArtifactId("vd_000000000001")
        val v2 = ArtifactId("vd_000000000002")
        val graph = RenderGraph(
            clips = listOf(
                Clip(v1, trimOutMs = 8_000),
                Clip(null, trimOutMs = 3_000, transition = Transition.CROSSFADE, transitionMs = 500, label = "#02 — pending"),
                Clip(v2, trimOutMs = 8_000),
                Clip(v1, trimInMs = 2_000, trimOutMs = 6_000, transition = Transition.CROSSFADE, transitionMs = 1_000),
            ),
            audio = AudioTrack("in/song.mp3", fadeInMs = 500, fadeOutMs = 2_000, muteClips = false, clipsVolume = 0.3),
            overlays = listOf(
                Overlay("မင်္ဂလာပါ — စမ်းသပ်ချက်", 500, 6_500, FONT, 72, position = Pos.BOTTOM, anim = Anim.FADE),
                Overlay("ကျေးဇူးတင်ပါတယ်", 11_000, 20_000, FONT, 80, "#FFD700", Pos.CENTER, Anim.FADE),
            ),
            output = RenderPresets.export(Ratio.R9_16),
        )
        return RenderJob(
            title = TITLE,
            graph = graph,
            workDir = dir.path,
            files = mapOf(v1.value to "in/clip1.mp4", v2.value to "in/clip2.mp4"),
            songPath = "in/song.mp3",
            fontsDir = "in/fonts",
            outputName = OUTPUT,
            placeholderFont = FONT,
        )
    }
}
