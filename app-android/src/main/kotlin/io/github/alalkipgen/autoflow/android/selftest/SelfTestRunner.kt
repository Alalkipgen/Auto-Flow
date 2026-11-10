package io.github.alalkipgen.autoflow.android.selftest

import android.content.Context
import android.graphics.BitmapFactory
import io.github.alalkipgen.autoflow.android.selftest.SelfTestState.Status
import io.github.alalkipgen.autoflow.media.FfmpegBenchmark
import io.github.alalkipgen.autoflow.media.FfmpegCapabilities
import io.github.alalkipgen.autoflow.media.selftest.Engine
import io.github.alalkipgen.autoflow.media.selftest.RenderOutcome
import io.github.alalkipgen.autoflow.media.selftest.RenderResult
import io.github.alalkipgen.autoflow.media.selftest.SelfTestRenders
import io.github.alalkipgen.autoflow.media.selftest.SelfTestReport
import io.github.alalkipgen.autoflow.platform.AndroidToolLocator
import io.github.alalkipgen.autoflow.platform.ProcessRunner
import io.github.alalkipgen.autoflow.platform.ProcessRunner.Exec
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * PLAN S6 on the phone: device info → test media from the APK → exec the bundled ffmpeg (capabilities) → the three
 * renders with time / peak RSS / ffprobe / a frame each. If exec is refused, the Media3 fallback runs instead (D-23).
 */
class SelfTestRunner(private val context: Context, private val onProgress: (String, Int?) -> Unit) {
    enum class Mode { ALL, MEDIA3_ONLY }

    private val work = File(context.cacheDir, "selftest")

    @Volatile private var cancelRequested = false

    @Volatile private var active: Job? = null
    private val inDir = File(work, "in")
    private val outDir = File(work, "out")

    /** Report and frame PNGs, kept after the run (Android/data/<app>/files/selftest). */
    val publicDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "selftest")

    suspend fun run(mode: Mode): SelfTestReport = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val startedAt = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"))
        val notes = mutableListOf<String>()
        val renders = mutableListOf<RenderResult>()
        var caps: FfmpegCapabilities? = null
        var execError: String? = null
        val screenOffDuring = mutableSetOf<String>()
        work.deleteRecursively()
        publicDir.deleteRecursively()
        outDir.mkdirs()
        publicDir.mkdirs()

        SelfTestState.step("Device info", Status.OK)
        val buildInfo = asset("ffmpeg/BUILDINFO.txt")
        val sections = listOf(DeviceInfo.device(context), DeviceInfo.app(context), DeviceInfo.binary(context, buildInfo))
        if (buildInfo == null) notes += "assets/ffmpeg/BUILDINFO.txt is missing: this APK was not built by CI with the bundled ffmpeg"

        val inputs = extractFixtures()
        if (inputs == null) {
            SelfTestState.step("Test media", Status.FAILED, "not in this APK (CI generates it)")
            notes += "Test media (assets/selftest) is missing from the APK, so nothing could be rendered"
        } else {
            SelfTestState.step("Test media", Status.OK, "2 clips × 8 s 1080×1920, 20 s mp3, Burmese .ass, Noto Sans Myanmar")
        }

        val tools = AndroidToolLocator(context.applicationInfo.nativeLibraryDir)
        val ffmpeg = tools.findFfmpeg()
        if (inputs != null && mode == Mode.ALL) {
            SelfTestState.step("Run ffmpeg", Status.RUNNING)
            execError = if (ffmpeg == null) "libffmpeg.so is not in nativeLibraryDir" else null
            if (ffmpeg != null) {
                when (val v = cancellable { exec(listOf(ffmpeg, "-hide_banner", "-version")) }) {
                    null -> {} // cancelled
                    is Exec.CannotStart -> execError = v.message
                    is Exec.Done -> if (v.exitCode != 0) {
                        execError = "ffmpeg -version exited with ${v.exitCode}: ${v.tail.takeLast(3).joinToString(" / ")}"
                    } else {
                        val filters = (cancellable { exec(listOf(ffmpeg, "-hide_banner", "-filters")) } as? Exec.Done)?.output.orEmpty()
                        val encoders = (cancellable { exec(listOf(ffmpeg, "-hide_banner", "-encoders")) } as? Exec.Done)?.output.orEmpty()
                        caps = FfmpegCapabilities.parse(v.output, filters, encoders)
                    }
                }
            }
            SelfTestState.step(
                "Run ffmpeg",
                if (execError == null) Status.OK else Status.FAILED,
                execError ?: "ffmpeg ${caps?.version}, libass ${if (caps?.hasAss == true) "yes" else "NO"}",
            )
            if (execError == null && ffmpeg != null) {
                for (r in SelfTestRenders.all(inputs)) {
                    val result = cancellable { ffmpegRender(ffmpeg, tools.findFfprobe(), r) { screenOffDuring += r.id } }
                        ?: RenderResult(r.id, r.title, Engine.FFMPEG, 0, null, null, RenderOutcome.Cancelled).also(::finishStep)
                    renders += result
                    if (cancelRequested) break
                }
            } else if (!cancelRequested) {
                notes += "ffmpeg could not run, so the Media3 fallback ran instead (D-23)"
            }
        }
        if (inputs != null && !cancelRequested && (mode == Mode.MEDIA3_ONLY || execError != null)) {
            for (spec in Media3Fallback.SPECS) {
                SelfTestState.step(spec.title, Status.RUNNING)
                val interactiveAtStart = DeviceInfo.interactive(context)
                val r = cancellable { Media3Fallback.render(context, inputs, spec, publicDir) { p -> onProgress("${spec.title}: $p%", p) } }
                    ?: RenderResult(spec.id, spec.title, Engine.MEDIA3, 0, null, null, RenderOutcome.Cancelled)
                if (!interactiveAtStart || !DeviceInfo.interactive(context)) screenOffDuring += spec.id
                renders += r
                finishStep(r)
                if (cancelRequested) break
            }
        }
        if (cancelRequested) notes += "Cancelled by the user"
        if (screenOffDuring.isNotEmpty()) notes += "Screen was off during ${screenOffDuring.joinToString()}, and the run continued (foreground service)"
        notes += "At the end: battery ${DeviceInfo.battery(context)}, thermal ${DeviceInfo.thermal(context)}"
        notes += "Peak RSS: ffmpeg rows = maxrss of the ffmpeg process (-benchmark); Media3 rows = the app process"
        SelfTestReport(startedAt, System.currentTimeMillis() - t0, sections, caps, execError, renders, notes)
    }

    /** Stops the current step; [run] still returns a partial report (verdict INCOMPLETE). */
    fun cancel() {
        cancelRequested = true
        active?.cancel()
    }

    /** Runs [block] as a child job that [cancel] can stop without cancelling [run] itself; null when cancelled. */
    private suspend fun <T> cancellable(block: suspend () -> T): T? = coroutineScope {
        if (cancelRequested) return@coroutineScope null
        val job = async { block() }
        active = job
        try {
            job.await()
        } catch (e: CancellationException) {
            if (cancelRequested) null else throw e
        } finally {
            active = null
        }
    }

    fun save(report: SelfTestReport): String {
        val md = report.toMarkdown()
        runCatching { File(publicDir, REPORT).apply { parentFile?.mkdirs() }.writeText(md) }
        return md
    }

    private suspend fun ffmpegRender(ffmpeg: String, ffprobe: String?, r: SelfTestRenders.Render, onScreenOff: () -> Unit): RenderResult {
        SelfTestState.step(r.title, Status.RUNNING)
        val command = listOf(ffmpeg) + r.args
        var lastPercent = -1
        val result = ProcessRunner.run(command, work, RENDER_TIMEOUT_MS) { line ->
            val t = TIME.find(line)?.groupValues ?: return@run
            val s = t[1].toInt() * 3600 + t[2].toInt() * 60 + t[3].toDouble()
            val p = (s * 100 / TOTAL_S).toInt().coerceIn(0, 100)
            if (p != lastPercent) {
                lastPercent = p
                onProgress("${r.title}: $p%", p)
                if (!DeviceInfo.interactive(context)) onScreenOff()
            }
        }
        val outcome = when (result) {
            is Exec.CannotStart -> RenderOutcome.Failed(result.message, null, ProcessRunner.commandLine(command), null)
            is Exec.Done -> {
                val out = File(r.output)
                if (result.exitCode == 0 && !result.timedOut && out.length() > 0) {
                    val probe = ffprobe?.let { p ->
                        (exec(listOf(p) + SelfTestRenders.probeArgs(r.output)) as? Exec.Done)?.takeIf { it.exitCode == 0 }?.output
                    }?.let(SelfTestRenders::summarizeProbe)
                    val png = File(publicDir, "${r.id}.png")
                    val frame = (exec(listOf(ffmpeg) + SelfTestRenders.frameArgs(r.output, png.path)) as? Exec.Done)?.exitCode == 0 && png.isFile
                    RenderOutcome.Ok(out.length(), probe, if (frame) png.path else null)
                } else {
                    val reason = if (result.timedOut) "timed out after ${RENDER_TIMEOUT_MS / 60_000} min" else "ffmpeg exited with ${result.exitCode}"
                    RenderOutcome.Failed(reason, result.exitCode, ProcessRunner.commandLine(command), result.tail.joinToString("\n"))
                }
            }
        }
        val wall = (result as? Exec.Done)?.wallMs ?: 0
        val rss = (result as? Exec.Done)?.tail?.joinToString("\n")?.let(FfmpegBenchmark::maxRssKb)
        return RenderResult(r.id, r.title, Engine.FFMPEG, wall, rss, "ffmpeg maxrss", outcome).also(::finishStep)
    }

    private fun finishStep(r: RenderResult) {
        val o = r.outcome
        SelfTestState.step(
            r.title,
            if (o is RenderOutcome.Ok) Status.OK else Status.FAILED,
            when (o) {
                is RenderOutcome.Ok -> "${SelfTestReport.seconds(r.wallMs)}, ${r.peakRssKb?.let { "${it / 1024} MB" } ?: "RSS n/a"}"
                is RenderOutcome.Failed -> o.reason
                RenderOutcome.Cancelled -> "cancelled"
            },
        )
        (o as? RenderOutcome.Ok)?.framePng?.let { SelfTestState.frame(r.id, it) }
    }

    private suspend fun exec(command: List<String>): Exec = ProcessRunner.run(command, work, SHORT_TIMEOUT_MS, keepOutput = true)

    private fun asset(path: String): String? = runCatching { context.assets.open(path).use { it.readBytes().decodeToString() } }.getOrNull()

    /** Copies assets/selftest/ (CI fixtures, tools/selftest-fixtures.sh) to the cache; null when the APK has none. */
    private fun extractFixtures(): SelfTestRenders.Inputs? {
        fun copy(path: String, to: File): Boolean {
            val children = context.assets.list(path).orEmpty()
            if (children.isEmpty()) {
                return runCatching { context.assets.open(path).use { i -> to.outputStream().use { i.copyTo(it) } }; true }.getOrDefault(false)
            }
            to.mkdirs()
            return children.all { copy("$path/$it", File(to, it)) }
        }
        if (!copy(FIXTURES, inDir)) return null
        val i = SelfTestRenders.Inputs(
            clip1 = File(inDir, "clip1.mp4").path,
            clip2 = File(inDir, "clip2.mp4").path,
            song = File(inDir, "song.mp3").path,
            ass = File(inDir, "lyrics.ass").path,
            fontsDir = File(inDir, "fonts").path,
            outDir = outDir.path,
        )
        return i.takeIf { listOf(it.clip1, it.clip2, it.song, it.ass).all { p -> File(p).isFile } }
    }

    companion object {
        const val REPORT = "report.md"
        private const val FIXTURES = "selftest"
        private const val TOTAL_S = 16.0
        private const val RENDER_TIMEOUT_MS = 20 * 60_000L
        private const val SHORT_TIMEOUT_MS = 60_000L
        private val TIME = Regex("""time=(\d+):(\d+):(\d+(?:\.\d+)?)""")

        /** Decodes a frame PNG for the on-screen thumbnail. */
        fun thumbnail(path: String) = runCatching { BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 2 }) }.getOrNull()
    }
}
