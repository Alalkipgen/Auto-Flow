package io.github.alalkipgen.autoflow.ui.export

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.hash.Sha256
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.ProjectWrites
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.recap.RecapStory
import io.github.alalkipgen.autoflow.core.recap.Layer
import io.github.alalkipgen.autoflow.core.recap.LayerParams
import io.github.alalkipgen.autoflow.core.recap.LayerType
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.StateFlow
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.media.probe.MediaProbe
import io.github.alalkipgen.autoflow.media.probe.FrameGrab
import io.github.alalkipgen.autoflow.media.probe.MediaInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /** The Library snapshot used by preview, Exact frames, export and their stale checks. */
    val lexicon: StateFlow<Lexicon>? = null,
    private val previewCacheDir: String? = null,
) {
    private val lock = Mutex()
    private val posterLock = Mutex()

    internal data class PreviewMedia(val info: MediaInfo, val poster: ByteArray?)
    internal fun previewVlcDir() = media?.vlcDir()

    /** Read the actual encoded asset, not the project setting. Use one bounded app-cache scratch slot. */
    internal suspend fun inspectPreview(path: String): PreviewMedia? = withContext(Dispatchers.Default) {
        val runner = media?.runner ?: return@withContext null
        val probe = media.ffprobe() ?: return@withContext null
        val info = (MediaProbe(runner, probe).probe(path, MediaTools.parentOf(path)) as? MediaProbe.Result.Ok)?.info
            ?.takeIf { it.hasVideo && (it.width ?: 0) > 0 && (it.height ?: 0) > 0 } ?: return@withContext null
        val dir = previewCacheDir
        val ffmpeg = media.ffmpeg()
        val poster = if (dir == null || ffmpeg == null) null else posterLock.withLock {
            val target = joinPath(dir, "poster.jpg")
            if (runCatching { fs.createDirectories(dir) }.isFailure) null
            else if (FrameGrab(runner, ffmpeg).thumb(path, 0L, target, dir, width = 480) == FrameGrab.Result.Ok)
                runCatching { fs.read(target) }.getOrNull() else null
        }
        PreviewMedia(info, poster)
    }

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

    /** The story of [open] — read from [open]'s own folder, not whichever project is open by the time this runs. */
    private fun story(open: OpenProject): RecapStory? {
        val id = open.project.recap?.story ?: return null
        val bytes = (ArtifactStore(fs, open.dir).readBytes(id) as? CoreResult.Ok)?.value ?: return null
        return runCatching { io.github.alalkipgen.autoflow.core.model.CoreJson.decodeFromString(RecapStory.serializer(), bytes.decodeToString()) }.getOrNull()
    }

    /** `artifacts/<file>` of [id] in [open]'s folder when the file is there. */
    private fun artifactFile(open: OpenProject, id: ArtifactId): String? {
        val artifact = (ArtifactStore(fs, open.dir).get(id) as? CoreResult.Ok)?.value ?: return null
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
            // Layer images are artifacts (`im_…`, spec §4); the watermark is a project path the runner finds itself.
            imageFile = { id -> ArtifactId(id).takeIf { it.isWellFormed }?.let { artifactFile(open, it) } },
            lexicon = lexicon?.value ?: Lexicon.EMPTY,
        )
    }

    /**
     * Output times worth a look (pre-flight thumbnails, 5B-9): the middle of each caption cue, else of each clip —
     * at most [max], spread over the whole recap.
     */
    fun sampleTimes(max: Int = 6, open: OpenProject? = session.open): List<Long> {
        open ?: return emptyList()
        val job = job(open, Quality.PREVIEW, font()?.family, "exports/preview", "frames") ?: return emptyList()
        return spread(sampleCandidates(job), max)
    }

    /** The time the Style preview's Exact frame shows: the first caption's middle, else the first clip's. */
    fun firstTime(open: OpenProject? = session.open): Long? = sampleTimes(1, open).firstOrNull()

    /**
     * What a frame of [open] would show, as a stable hash of its Preview render job (graph, files, captions): equal
     * hashes = the same picture, so a thumbnail made from another hash is out of date. Null = no job (yet).
     */
    fun fingerprint(open: OpenProject? = session.open): String? {
        open ?: return null
        val job = job(open, Quality.PREVIEW, font()?.family, "exports/preview", "frames") ?: return null
        return Sha256.hex(FINGERPRINT_JSON.encodeToString(RenderJob.serializer(), job).encodeToByteArray())
    }

    /**
     * Renders the still at [atMs] of the recap ([quality]: Preview = 540p, quick; Export = the export size) into
     * [image] (relative to the project; default `.cache/frames/<t>.png`). [extra] may add to the graph (the Publish
     * Kit's title layer). [open] = the project to render (default: the one open now); its own folder is used
     * throughout, so a project switched to meanwhile is never read or written.
     */
    suspend fun frame(
        atMs: Long,
        quality: Quality = Quality.PREVIEW,
        image: String = joinPath(FRAMES_DIR, ExactFrame.cacheName(atMs)),
        extra: (RenderJob) -> RenderJob = { it },
        open: OpenProject? = session.open,
    ): Result {
        open ?: return Result.Failed("No project is open.")
        val tools = media ?: return Result.Failed(NO_FFMPEG)
        val runner = tools.runner ?: return Result.Failed(NO_FFMPEG)
        val ffmpeg = tools.ffmpeg() ?: return Result.Failed(NO_FFMPEG)
        val font = font()
        val job = job(open, quality, font?.family, "exports/frame", "Exact frame")?.let(extra)
            ?: return Result.Failed("Import the movie and write the script first.")
        return lock.withLock {
            val release = ProjectWrites.awaitWriter(fs.canonicalPath(open.dir))
            try {
                if (session.projectAt(open.dir)?.id != open.project.id) return@withLock Result.Failed("Project unavailable")
                installFont(open, font)?.let { return@withLock Result.Failed(it) }
                val ffprobe = tools.ffprobe() ?: ffmpeg
                when (val r = RenderJobRunner(fs, FfmpegRunner(runner, ffmpeg), MediaProbe(runner, ffprobe)).frame(job, atMs, image)) {
                    is RenderState.Done -> runCatching { fs.read(r.output) }.getOrNull()?.let { Result.Ok(Frame(atMs, r.output, it)) }
                        ?: Result.Failed("The frame was made but cannot be read.")
                    is RenderState.Failed -> Result.Failed(listOf(r.reason, r.tail.lastOrNull { it.isNotBlank() }.orEmpty()).filter { it.isNotBlank() }.joinToString(": "))
                    else -> Result.Failed("The frame was not made.")
                }
            } finally { release() }
        }
    }

    /**
     * The Publish Kit thumbnail (5B-10): the export-size frame of [open] at [atMs] with [title] as a TEXT layer on
     * the lower third and no burned-in captions (they would cover the title), into [image] (`exports/<name>.thumb.jpg`).
     */
    suspend fun thumbnail(open: OpenProject, atMs: Long, title: String, image: String): Result {
        if (title.isBlank()) return Result.Failed("Write the video title first.")
        val family = font()?.family
        return frame(atMs, Quality.EXPORT, image, extra = { withTitle(it, title, family) }, open = open)
    }

    companion object {
        const val FONTS_DIR = ".cache/fonts"
        const val FRAMES_DIR = ".cache/frames"
        private val FINGERPRINT_JSON = Json { encodeDefaults = true }

        /** Where the thumbnail's title goes: the lower third, inside the frame ([x, y, w, h] of the output). */
        val TITLE_RECT = listOf(0.06f, 0.64f, 0.88f, 0.28f)

        /**
         * [job] with [title] as the topmost TEXT layer over the whole output, in [font] (the captions' Burmese font;
         * named on the layer because the captions it would otherwise borrow it from are taken out).
         */
        fun withTitle(job: RenderJob, title: String, font: String?): RenderJob {
            val family = font ?: job.graph.overlays.firstOrNull()?.font ?: job.placeholderFont
            val layer = Layer("publish-title", LayerType.TEXT, rect = TITLE_RECT, params = LayerParams(textMy = title.trim(), style = family.orEmpty()))
            return job.copy(graph = job.graph.copy(overlays = emptyList(), layers = job.graph.layers + layer))
        }

        /** Caption middles, else clip middles, of [job]'s output (ms). */
        fun sampleCandidates(job: RenderJob): List<Long> {
            val captions = job.srt?.let { srtMids(it) }.orEmpty()
            return captions.ifEmpty {
                var at = 0L
                job.graph.clips.map { c -> (at + c.durationMs / 2).also { at += c.durationMs } }
            }
        }

        /**
         * At most [max] of [times], first and last included, evenly spread. One = the first (it used to divide by
         * `max - 1` = 0 and throw, so "Exact frame" crashed on a recap with two or more captions).
         */
        fun spread(times: List<Long>, max: Int): List<Long> = when {
            max <= 0 -> emptyList()
            times.size <= max -> times
            max == 1 -> times.take(1)
            else -> (0 until max).map { i -> times[i * (times.size - 1) / (max - 1)] }
        }
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
