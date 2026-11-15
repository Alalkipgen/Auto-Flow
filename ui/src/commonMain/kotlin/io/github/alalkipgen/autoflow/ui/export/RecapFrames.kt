package io.github.alalkipgen.autoflow.ui.export

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.recap.RecapStory
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.media.probe.MediaProbe
import io.github.alalkipgen.autoflow.media.render.ExactFrame
import io.github.alalkipgen.autoflow.media.render.FfmpegRunner
import io.github.alalkipgen.autoflow.media.render.FontInfo
import io.github.alalkipgen.autoflow.media.render.Quality
import io.github.alalkipgen.autoflow.media.render.RecapRender
import io.github.alalkipgen.autoflow.media.render.RenderJob
import io.github.alalkipgen.autoflow.media.render.RenderJobRunner
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.BuiltInFonts
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.theme.UiFont
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Movie Recap render job and its "Exact frames" (D-42, 5A-8), shared by the Style controls on the Recap screen
 * and the Export screen so both use one graph: [job] is exactly what Export renders, [frame] is one still of it made
 * by the export compiler ([RenderJobRunner.frame]) into `.cache/frames/<t>.png`. One ffmpeg at a time.
 */
class RecapFrames(
    private val session: ProjectSession,
    private val settings: SettingsStore,
    private val fs: FileSystem,
    private val media: MediaTools? = null,
    private val builtInFonts: BuiltInFonts? = null,
) {
    private val lock = Mutex()

    class LyricFont(val family: String, val fileName: String, val bytes: ByteArray)

    /** A finished still: [file] (absolute) and its PNG / JPEG bytes, at [atMs] of the output. */
    data class Frame(val atMs: Long, val file: String, val bytes: ByteArray) {
        override fun equals(other: Any?) = other is Frame && other.atMs == atMs && other.file == file && other.bytes.contentEquals(bytes)
        override fun hashCode() = 31 * (31 * atMs.hashCode() + file.hashCode()) + bytes.contentHashCode()
    }

    sealed interface Result {
        data class Ok(val frame: Frame) : Result

        data class Failed(val reason: String) : Result
    }

    /** The Burmese font (Pyidaungsu, else Noto Sans Myanmar; regular weight first) from Settings or built in, or null. */
    fun font(): LyricFont? {
        val paths = BuiltInFonts.withBuiltIns(settings.state.value.fontPaths, builtInFonts)
        val read = paths.mapNotNull { path ->
            val bytes = runCatching { fs.read(path) }.getOrNull() ?: return@mapNotNull null
            val info = FontInfo.parse(bytes) ?: return@mapNotNull null
            Triple(path, bytes, UiFont.Candidate(path, info.family, FontInfo.weightClass(bytes) ?: 400))
        }
        val best = UiFont.pick(read.map { it.third }).firstOrNull() ?: return null
        val (path, bytes, _) = read.first { it.first == best.path }
        val ext = path.substringAfterLast('.', "ttf").lowercase().takeIf { it == "ttf" || it == "otf" } ?: "ttf"
        val safe = best.family.filter { it.isLetterOrDigit() }.ifEmpty { "lyrics" }
        return LyricFont(best.family, "$safe.$ext", bytes)
    }

    /** Copies [font] into the project's `.cache/fonts` (libass reads fonts only from there); null = done. */
    fun installFont(open: OpenProject, font: LyricFont?): String? {
        font ?: return null
        val copied = runCatching {
            fs.createDirectories(joinPath(open.dir, FONTS_DIR))
            val target = joinPath(joinPath(open.dir, FONTS_DIR), font.fileName)
            fs.writeDurably("$target.tmp", font.bytes)
            fs.atomicMove("$target.tmp", target)
        }
        return copied.exceptionOrNull()?.let { "Cannot copy the font into the project: ${it.message}" }
    }

    private fun story(open: OpenProject): RecapStory? =
        open.project.recap?.story?.let { (session.readJson(it, RecapStory.serializer()) as? CoreResult.Ok)?.value }

    /** `artifacts/<file>` of [id] when the file is there. */
    private fun artifactFile(open: OpenProject, id: ArtifactId): String? {
        val artifact = (session.artifacts()?.get(id) as? CoreResult.Ok)?.value ?: return null
        return joinPath(ArtifactStore.ARTIFACTS_DIR, artifact.file).takeIf { fs.exists(joinPath(open.dir, it)) }
    }

    /** The recap render (spec §6): movie clips, takes, looks, captions (burned in when wanted and a font is set) + `.srt`. */
    fun job(open: OpenProject, quality: Quality, font: String?, name: String, title: String): RenderJob? {
        val recap = open.project.recap ?: return null
        val story = story(open) ?: return null
        if (recap.movie == null) return null
        // D-56: use per-recap output ratio when set; fall back to project-level ratio.
        val ratio = recap.visual.outputRatio ?: open.project.settings.ratio
        // v2-native (audit F07): every clip of every block, all layers, the chosen take, the caption chunk edges.
        return RecapRender.job(
            recap, story, ratio, open.dir, quality, { artifactFile(open, it.audio) }, font, FONTS_DIR,
            outputName = name,
            title = title,
            bgmFile = recap.audio.bgm?.let { artifactFile(open, it) },
        )
    }

    /**
     * Output times worth a look (pre-flight thumbnails, 5B-9): the middle of each caption cue, else of each clip —
     * at most [max], spread over the whole recap.
     */
    fun sampleTimes(max: Int = 6): List<Long> {
        val open = session.open ?: return emptyList()
        val job = job(open, Quality.PREVIEW, font()?.family, "exports/preview", "frames") ?: return emptyList()
        val captions = job.srt?.let { srtMids(it) }.orEmpty()
        val times = captions.ifEmpty {
            var at = 0L
            job.graph.clips.map { c -> (at + c.durationMs / 2).also { at += c.durationMs } }
        }
        if (times.size <= max) return times
        return (0 until max).map { i -> times[i * (times.size - 1) / (max - 1)] }
    }

    /** The time the Style preview's Exact frame shows: the first caption's middle, else the first clip's. */
    fun firstTime(): Long? = sampleTimes(1).firstOrNull()

    /**
     * Renders the still at [atMs] of the recap ([quality]: Preview = 540p, quick; Export = the export size) into
     * [image] (relative to the project; default `.cache/frames/<t>.png`). [extra] may add to the graph (the Publish
     * Kit's title layer).
     */
    suspend fun frame(
        atMs: Long,
        quality: Quality = Quality.PREVIEW,
        image: String = joinPath(FRAMES_DIR, ExactFrame.cacheName(atMs)),
        extra: (RenderJob) -> RenderJob = { it },
    ): Result {
        val open = session.open ?: return Result.Failed("No project is open.")
        val tools = media ?: return Result.Failed(NO_FFMPEG)
        val runner = tools.runner ?: return Result.Failed(NO_FFMPEG)
        val ffmpeg = tools.ffmpeg() ?: return Result.Failed(NO_FFMPEG)
        val font = font()
        installFont(open, font)?.let { return Result.Failed(it) }
        val job = job(open, quality, font?.family, "exports/frame", "Exact frame")?.let(extra)
            ?: return Result.Failed("Import the movie and write the script first.")
        return lock.withLock {
            val ffprobe = tools.ffprobe() ?: ffmpeg
            when (val r = RenderJobRunner(fs, FfmpegRunner(runner, ffmpeg), MediaProbe(runner, ffprobe)).frame(job, atMs, image)) {
                is RenderState.Done -> session.readFile(r.output)?.let { Result.Ok(Frame(atMs, r.output, it)) }
                    ?: Result.Failed("The frame was made but cannot be read.")
                is RenderState.Failed -> Result.Failed(listOf(r.reason, r.tail.lastOrNull { it.isNotBlank() }.orEmpty()).filter { it.isNotBlank() }.joinToString(": "))
                else -> Result.Failed("The frame was not made.")
            }
        }
    }

    companion object {
        const val FONTS_DIR = ".cache/fonts"
        const val FRAMES_DIR = ".cache/frames"
        const val NO_FFMPEG = "ffmpeg was not found: set its path in Settings → Tools (or add it to PATH)"

        /** Middle of every cue of an `.srt` (ms). */
        fun srtMids(srt: String): List<Long> {
            val time = Regex("""(\d+):(\d+):(\d+)[,.](\d+)\s*-->\s*(\d+):(\d+):(\d+)[,.](\d+)""")
            return time.findAll(srt).map { m ->
                val g = m.groupValues.drop(1).map { it.toLong() }
                val a = ((g[0] * 60 + g[1]) * 60 + g[2]) * 1000 + g[3]
                val b = ((g[4] * 60 + g[5]) * 60 + g[6]) * 1000 + g[7]
                (a + b) / 2
            }.toList()
        }
    }
}
