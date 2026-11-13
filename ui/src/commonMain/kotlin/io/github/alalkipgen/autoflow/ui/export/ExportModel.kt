package io.github.alalkipgen.autoflow.ui.export

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.lyrics.LyricLine
import io.github.alalkipgen.autoflow.core.lyrics.LyricSync
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.recap.ExportQuality
import io.github.alalkipgen.autoflow.core.recap.RecapFit
import io.github.alalkipgen.autoflow.core.recap.RecapRules
import io.github.alalkipgen.autoflow.core.recap.RecapScript
import io.github.alalkipgen.autoflow.core.recap.VoiceTake
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.media.render.BatteryReader
import io.github.alalkipgen.autoflow.media.render.FontInfo
import io.github.alalkipgen.autoflow.media.render.ProjectRender
import io.github.alalkipgen.autoflow.media.render.Quality
import io.github.alalkipgen.autoflow.media.render.RecapRender
import io.github.alalkipgen.autoflow.media.render.RenderJob
import io.github.alalkipgen.autoflow.media.render.RenderGraph
import io.github.alalkipgen.autoflow.media.render.RenderQueue
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.platform.FileShare
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.storyboard.text
import io.github.alalkipgen.autoflow.ui.theme.UiFont
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch

/** One render started from this screen; [output] is the mp4 it writes (absolute). */
data class ExportRun(val projectDir: String, val quality: Quality, val id: String, val output: String, val state: RenderState)

/**
 * A Movie Recap project on the Export screen (docs/specs/recap.md §6): [segments] scenes, [voiced] with a current
 * take, [durationMs] of output, captions burned in or not, [blocker] = why it cannot render yet (null = it can).
 */
data class RecapExport(
    val segments: Int,
    val voiced: Int,
    val durationMs: Long,
    val movieFile: String?,
    val burnCaptions: Boolean,
    val looks: Int,
    val movieVolume: Double,
    val blocker: String?,
    val quality: ExportQuality = ExportQuality.AUTO,
)

/** What the Export screen shows (PLAN Q-09 c). [unavailable] = this app cannot render here (no render queue). */
data class ExportUiState(
    val projectName: String? = null,
    val shots: Int = 0,
    val accepted: Int = 0,
    val durationMs: Long = 0,
    val songFile: String? = null,
    val font: String? = null,
    val lyricShots: Int = 0,
    /** Lines of the saved lyric timings that match the lyrics (PLAN Q-11); they replace the per-shot lyrics. */
    val syncedLines: Int = 0,
    /** Timings exist but the lyrics changed since: per-shot lyrics are used. */
    val timingOutdated: Boolean = false,
    val lowBattery: Boolean = false,
    val unavailable: String? = null,
    val run: ExportRun? = null,
    val message: String? = null,
    /** [message] is good news (green), e.g. where Save to Gallery put the copy. */
    val messageOk: Boolean = false,
    val savingToGallery: Boolean = false,
    /** Set in a Movie Recap project; then the music-video fields above stay empty. */
    val recap: RecapExport? = null,
) {
    val busy: Boolean get() = run != null && !run.state.finished
    val canRender: Boolean
        get() = projectName != null && unavailable == null && !busy && (recap?.let { it.blocker == null } ?: (shots > 0))
}

/**
 * Export screen logic (PLAN Q-09 c): builds the job with [ProjectRender] (accepted videos, song, lyrics in the
 * Burmese Settings font), keeps the graph as `project.timeline`, queues it on [queue] (Android: the WorkManager
 * worker, which keeps going with the screen off) and follows its progress. A Koin single, so a render started here
 * is still shown after the user leaves and comes back. Preview = 540p `exports/preview.mp4`, Export = 1080p
 * `exports/export.mp4`; each render replaces the previous file of its kind only when it succeeds.
 */
class ExportModel(
    private val session: ProjectSession,
    private val settings: SettingsStore,
    private val fs: FileSystem,
    private val queue: RenderQueue?,
    private val share: FileShare?,
    private val battery: BatteryReader?,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ExportUiState())
    val state: StateFlow<ExportUiState> = _state.asStateFlow()
    private var watching: Job? = null

    init {
        refresh()
    }

    /** Re-reads the open project, the song, the font and the battery (the screen calls it when it opens). */
    fun refresh() {
        val open = session.open
        val run = _state.value.run?.takeIf { it.projectDir == open?.dir }
        if (open == null) {
            _state.value = ExportUiState(run = null)
            return
        }
        val p = open.project
        val font = font()
        recapExport(open)?.let { r ->
            _state.value = ExportUiState(
                projectName = p.name,
                font = font?.family,
                lowBattery = battery?.read()?.lowWarning == true,
                unavailable = if (queue == null) NO_QUEUE else null,
                run = run,
                recap = r,
            )
            return
        }
        val saved = session.readLyricTimings()
        val synced = timings()
        _state.value = ExportUiState(
            projectName = p.name,
            shots = p.shots.count { it.durationMs > 0 },
            accepted = p.shots.count { s -> s.durationMs > 0 && s.acceptedVideo?.let { videoFile(open, it) } != null },
            durationMs = p.shots.filter { it.durationMs > 0 }.sumOf { it.durationMs },
            songFile = session.songPath()?.substringAfterLast('/'),
            font = font?.family,
            lyricShots = p.shots.count { it.durationMs > 0 && it.lyricsMy.isNotBlank() },
            syncedLines = synced?.size ?: 0,
            timingOutdated = saved != null && synced == null,
            lowBattery = battery?.read()?.lowWarning == true,
            unavailable = if (queue == null) NO_QUEUE else null,
            run = run,
        )
    }

    /** Sets the [ExportQuality] for the current Recap project and refreshes the screen. */
    fun setRecapQuality(q: ExportQuality) {
        val open = session.open ?: return
        session.save(open.project.copy(recap = open.project.recap?.copy(quality = q)))
        refresh()
    }

    /** Queues a Preview (540p) or Export (1080p) render of the open project. */
    fun render(quality: Quality) {
        refresh()
        val q = queue ?: return
        val open = session.open ?: return
        if (!_state.value.canRender) return
        val font = font()
        val fontsDir = joinPath(ProjectSession.CACHE_DIR, "fonts")
        if (font != null) {
            val copied = runCatching {
                fs.createDirectories(joinPath(open.dir, fontsDir))
                val target = joinPath(joinPath(open.dir, fontsDir), font.fileName)
                fs.writeDurably("$target.tmp", font.bytes)
                fs.atomicMove("$target.tmp", target)
            }
            if (copied.isFailure) return say("Cannot copy the font into the project: ${copied.exceptionOrNull()?.message}")
        }
        val name = if (quality == Quality.PREVIEW) PREVIEW_NAME else EXPORT_NAME
        if (isRecap(open.project)) {
            val job = recapJob(open, quality, font?.family, fontsDir, name) ?: return say("The recap script cannot be read.")
            return start(q, job, open, quality, name)
        }
        val song = session.songPath()?.let { relative(open.dir, it) }
        val job = ProjectRender.job(
            open.project, open.dir, quality, song, { videoFile(open, it) }, font?.family, fontsDir,
            outputName = joinPath(EXPORTS_DIR, name),
            title = "${open.project.name} · ${label(quality)}",
            timings = timings(),
        )
        val timeline = CoreJson.encodeToJsonElement(RenderGraph.serializer(), job.graph)
        (session.save(open.project.copy(timeline = timeline)) as? CoreResult.Err)?.let { return say(it.error.message) }
        start(q, job, open, quality, name)
    }

    private fun start(q: RenderQueue, job: RenderJob, open: OpenProject, quality: Quality, name: String) {
        val id = q.enqueue(job)
        val run = ExportRun(open.dir, quality, id, joinPath(joinPath(open.dir, EXPORTS_DIR), "$name.mp4"), RenderState.Queued)
        _state.value = _state.value.copy(run = run, message = null)
        watching?.cancel()
        watching = scope.launch {
            q.observe(id).transformWhile { emit(it); !it.finished }.collect { st ->
                val cur = _state.value.run
                if (cur?.id == id) _state.value = _state.value.copy(run = cur.copy(state = st))
            }
        }
    }

    private fun isRecap(p: Project) = p.mode == Mode.MOVIE_RECAP && p.recap != null

    private fun recapScript(): RecapScript? =
        session.open?.project?.recap?.script?.let { (session.readJson(it, RecapScript.serializer()) as? CoreResult.Ok)?.value }

    /** The take's audio, `artifacts/<file>`, when the file is there. */
    private fun takeFile(open: OpenProject, take: VoiceTake): String? {
        val artifact = (session.artifacts()?.get(take.audio) as? CoreResult.Ok)?.value ?: return null
        return joinPath(ArtifactStore.ARTIFACTS_DIR, artifact.file).takeIf { fs.exists(joinPath(open.dir, it)) }
    }

    private fun recapExport(open: OpenProject): RecapExport? {
        if (!isRecap(open.project)) return null
        val recap = open.project.recap!!
        val script = recapScript()
        val gate = RecapRules.canRender(recap, script)
        val rows = script?.takeIf { RecapRules.isCurrent(recap, it) }?.let { RecapFit.rows(recap, it) }.orEmpty()
        return RecapExport(
            segments = rows.size,
            voiced = rows.count { it.takeMs != null },
            durationMs = RecapFit.totalMs(rows),
            movieFile = session.moviePath()?.substringAfterLast('/'),
            burnCaptions = recap.burnCaptions,
            looks = recap.looks.size,
            movieVolume = recap.movieVolume,
            blocker = gate.blockers.firstOrNull()?.text() ?: if (recap.movie == null) "Import the movie first (Movie Recap → 1 · Movie)." else null,
            quality = recap.quality,
        )
    }

    /** The recap render (spec §6): movie clips, takes, looks, captions (burned in when wanted and a font is set) + `.srt`. */
    private fun recapJob(open: OpenProject, quality: Quality, font: String?, fontsDir: String, name: String): RenderJob? {
        val recap = open.project.recap ?: return null
        val script = recapScript() ?: return null
        return RecapRender.job(
            recap, script, open.project.settings.ratio, open.dir, quality, { takeFile(open, it) }, font, fontsDir,
            outputName = joinPath(EXPORTS_DIR, name),
            title = "${open.project.name} · ${label(quality)}",
        )
    }

    /** The saved line timings when they still match the lyrics, else null (per-shot lyrics). */
    private fun timings(): List<LyricLine>? =
        session.readLyricTimings()?.takeIf { LyricSync.matches(it, session.readLyrics()) }?.lines?.takeIf { it.isNotEmpty() }

    fun cancel() {
        val run = _state.value.run?.takeIf { !it.state.finished } ?: return
        queue?.cancel(run.id)
    }

    /** Plays the finished mp4 in another app. */
    fun open() {
        val file = finished() ?: return
        result(share?.open(file, MP4) ?: PlatformResult.Unsupported(NO_SHARE))
    }

    /** Share sheet for the finished mp4. */
    fun share() {
        val file = finished() ?: return
        result(share?.share(file, MP4, "Share the video") ?: PlatformResult.Unsupported(NO_SHARE))
    }

    /**
     * Copies the finished mp4 into the phone's gallery (Movies/AutoFlow) as `<project>-export-<n>.mp4`, so it shows
     * in the Gallery without Share…; the file in `exports/` stays. The name carries the render's finish count so a
     * newer render does not replace an older copy the user may already have sent.
     */
    fun saveToGallery() {
        val run = _state.value.run ?: return
        val file = finished() ?: return
        val fs = share ?: return say(NO_SHARE)
        if (_state.value.savingToGallery) return
        _state.value = _state.value.copy(savingToGallery = true, message = null)
        scope.launch {
            val r = fs.saveToGallery(file, MP4, galleryName(_state.value.projectName ?: "AutoFlow", run))
            _state.value = when (r) {
                is PlatformResult.Ok -> _state.value.copy(savingToGallery = false, message = "Saved to Gallery ✓ · ${r.value}", messageOk = true)
                is PlatformResult.Failed -> _state.value.copy(savingToGallery = false, message = r.reason, messageOk = false)
                is PlatformResult.Unsupported -> _state.value.copy(savingToGallery = false, message = r.reason, messageOk = false)
            }
        }
    }

    private fun finished(): String? = (_state.value.run?.state as? RenderState.Done)?.output

    private fun result(r: PlatformResult<Unit>) {
        when (r) {
            is PlatformResult.Ok -> say(null)
            is PlatformResult.Failed -> say(r.reason)
            is PlatformResult.Unsupported -> say(r.reason)
        }
    }

    private fun say(message: String?) {
        _state.value = _state.value.copy(message = message, messageOk = false)
    }

    private class LyricFont(val family: String, val fileName: String, val bytes: ByteArray)

    /** The Burmese Settings font (Pyidaungsu, else Noto Sans Myanmar; regular weight first), or null. */
    private fun font(): LyricFont? {
        val read = settings.state.value.fontPaths.mapNotNull { path ->
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

    private fun videoFile(open: OpenProject, id: ArtifactId): String? {
        val artifact = (session.artifacts()?.get(id) as? CoreResult.Ok)?.value ?: return null
        val rel = joinPath(ArtifactStore.ARTIFACTS_DIR, artifact.file)
        return rel.takeIf { fs.exists(joinPath(open.dir, it)) }
    }

    private fun relative(dir: String, path: String): String = path.removePrefix(dir.trimEnd('/') + "/")

    companion object {
        const val EXPORTS_DIR = "exports"
        const val PREVIEW_NAME = "preview"
        const val EXPORT_NAME = "export"
        const val MP4 = "video/mp4"
        const val NO_QUEUE = "Rendering is not available in this version on this computer yet (Android renders in the background)."
        const val NO_SHARE = "Opening and sharing files is not available here; the video is in the project's exports folder."

        /**
         * Gallery file name: the project name (letters, digits, Burmese kept; others → "-"), the kind and the render
         * id, e.g. `ဟယ်လို-export-job3.mp4`. MediaStore adds " (1)" itself if the name is taken.
         */
        fun galleryName(project: String, run: ExportRun): String {
            val safe = project.map { if (it.isLetterOrDigit() || it.code in 0x1000..0x109F) it else '-' }.joinToString("")
                .trim('-').ifEmpty { "AutoFlow" }.take(60)
            val kind = if (run.quality == Quality.PREVIEW) PREVIEW_NAME else EXPORT_NAME
            val id = run.id.filter { it.isLetterOrDigit() }.takeLast(12)
            return "$safe-$kind-$id.mp4"
        }

        fun label(quality: Quality) = if (quality == Quality.PREVIEW) "Preview 540p" else "Export 1080p"

        /** Display label for an [ExportQuality] chip. */
        fun label(exportQuality: ExportQuality): String = exportQuality.label
    }
}
