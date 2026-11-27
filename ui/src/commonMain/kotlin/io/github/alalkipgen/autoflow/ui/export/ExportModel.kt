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
import io.github.alalkipgen.autoflow.core.recap.PreflightCheck
import io.github.alalkipgen.autoflow.core.recap.PublishKit
import io.github.alalkipgen.autoflow.core.recap.PublishKitText
import io.github.alalkipgen.autoflow.core.recap.RecapFit
import io.github.alalkipgen.autoflow.core.recap.RecapRulesV2
import io.github.alalkipgen.autoflow.core.recap.RecapStory
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.media.render.BatteryReader
import io.github.alalkipgen.autoflow.media.render.ProjectRender
import io.github.alalkipgen.autoflow.media.render.Quality
import io.github.alalkipgen.autoflow.media.render.RenderJob
import io.github.alalkipgen.autoflow.media.render.RenderGraph
import io.github.alalkipgen.autoflow.media.render.RenderQueue
import io.github.alalkipgen.autoflow.media.render.RenderResult
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.platform.FileShare
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.storyboard.text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * A finished render of [quality] found in [projectDir]'s `exports/` with its [RenderResult] record (5B-4): shown when
 * no render of this session is on screen, e.g. after the app restarted. [output] is the mp4 (absolute).
 */
data class LastRender(val projectDir: String, val quality: Quality, val output: String, val result: RenderResult)

/**
 * Exact-frame thumbnails (5B-9) of [projectDir]'s recap: stills made by the export compiler at [times] of the output,
 * from the render job with [fingerprint] ([RecapFrames.fingerprint]); [frames] so far, in [times] order. [stale] =
 * the recap changed since, so they no longer show what Export will render.
 */
data class ExportThumbs(
    val projectDir: String,
    val fingerprint: String,
    val times: List<Long>,
    val frames: List<RecapFrames.Frame> = emptyList(),
    val working: Boolean = true,
    val failed: String? = null,
    val stale: Boolean = false,
)

/**
 * The Publish Kit thumbnail (5B-10) of [projectDir]: `exports/export.thumb.jpg` ([file], absolute) = the export-size
 * frame at [atMs] with [title] on it. [frame] = the image on disk (null while the first one is being made). [stale] =
 * the title or the recap changed since it was made (or it has no record), so it should be made again.
 */
data class PublishThumb(
    val projectDir: String,
    val file: String,
    val atMs: Long,
    val title: String,
    val frame: RecapFrames.Frame? = null,
    val working: Boolean = false,
    val failed: String? = null,
    val stale: Boolean = false,
)

/** `exports/<name>.thumb.json`: what the thumbnail beside it was made from (title, time, [RecapFrames.fingerprint]). */
@Serializable
internal data class PublishThumbRecord(val title: String, val atMs: Long, val fingerprint: String, val bytes: Long)

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
    val publishKit: PublishKit = PublishKit(),
    /** Pre-flight items (5B-9): computed from data, no render required. */
    val preflight: List<PreflightCheck.Item> = emptyList(),
    /** Chapters from the story blocks' start times in the export (5B-10), used when the kit has none of its own. */
    val chapters: List<io.github.alalkipgen.autoflow.core.recap.PublishChapter> = emptyList(),
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
    /** Finished Preview / Export of the open project read back from `exports/<name>.result.json` (5B-4). */
    val lastRenders: List<LastRender> = emptyList(),
    /** Exact-frame thumbnails of the open recap (5B-9); null = not made in this session. */
    val thumbs: ExportThumbs? = null,
    /** The Publish Kit thumbnail of the open recap (5B-10); null = none yet. */
    val publishThumb: PublishThumb? = null,
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
    private val clipboard: io.github.alalkipgen.autoflow.platform.Clipboard? = null,
    private val builtInFonts: io.github.alalkipgen.autoflow.ui.settings.BuiltInFonts? = null,
    frames: RecapFrames? = null,
) {
    /** The recap job and its Exact frames (D-42), the same graph as the Recap screen's Style preview. */
    private val frames = frames ?: RecapFrames(session, settings, fs, null, builtInFonts)

    private val _state = MutableStateFlow(ExportUiState())
    val state: StateFlow<ExportUiState> = _state.asStateFlow()
    private var watching: Job? = null

    init {
        refresh()
        this.frames.lexicon?.let { changes -> scope.launch { changes.collect { refresh() } } }
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
        val font = frames.font()
        val last = lastRenders(open.dir)
        recapExport(open)?.let { r ->
            val fingerprint = runCatching { frames.fingerprint(open) }.getOrNull()
            val before = _state.value
            _state.value = ExportUiState(
                projectName = p.name,
                font = font?.family,
                lowBattery = battery?.read()?.lowWarning == true,
                unavailable = if (queue == null) NO_QUEUE else null,
                run = run,
                recap = r,
                lastRenders = last,
                thumbs = before.thumbs?.takeIf { it.projectDir == open.dir }?.let { it.copy(stale = it.fingerprint != fingerprint) },
                publishThumb = before.publishThumb?.takeIf { it.projectDir == open.dir && it.working }
                    ?: savedPublishThumb(open, r.publishKit, fingerprint),
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
            lastRenders = last,
        )
    }

    /** The Preview and Export of [dir] that left a matching result record (absent, stale or damaged ones are skipped). */
    private fun lastRenders(dir: String): List<LastRender> = listOf(Quality.PREVIEW to PREVIEW_NAME, Quality.EXPORT to EXPORT_NAME).mapNotNull { (q, name) ->
        val mp4 = joinPath(joinPath(dir, EXPORTS_DIR), "$name.mp4")
        runCatching { RenderResult.read(fs, mp4) }.getOrNull()?.let { LastRender(dir, q, mp4, it) }
    }

    /** Plays [last] (a render found after a restart) in another app; only while its project is open. */
    fun openLast(last: LastRender) {
        if (session.open?.dir != last.projectDir) return
        result(share?.open(last.output, MP4) ?: PlatformResult.Unsupported(NO_SHARE))
    }

    /** Share sheet for [last]; only while its project is open. */
    fun shareLast(last: LastRender) {
        if (session.open?.dir != last.projectDir) return
        result(share?.share(last.output, MP4, "Share the video") ?: PlatformResult.Unsupported(NO_SHARE))
    }

    /**
     * Makes the Exact-frame thumbnails (5B-9): up to [max] stills of the open recap, spread over captions (else
     * clips), each made by the export compiler one after another and shown as it comes. Bound to the project it was
     * started in: a switch to another project drops them; a newer request replaces them.
     */
    fun makeThumbnails(max: Int = THUMBS) {
        val open = session.open ?: return
        if (!isRecap(open.project) || _state.value.thumbs?.let { it.working && it.projectDir == open.dir } == true) return
        val fingerprint = frames.fingerprint(open) ?: return say(NO_RECAP_JOB)
        val times = frames.sampleTimes(max, open)
        if (times.isEmpty()) return say(NO_RECAP_JOB)
        val start = ExportThumbs(open.dir, fingerprint, times)
        _state.value = _state.value.copy(thumbs = start, message = null)
        scope.launch {
            val made = mutableListOf<RecapFrames.Frame>()
            for (t in times) {
                val r = withContext(Dispatchers.Default) { frames.frame(t, open = open) }
                val cur = _state.value.thumbs?.takeIf { it.projectDir == open.dir && it.times == times && it.working } ?: return@launch
                if (session.open?.dir != open.dir) {
                    _state.value = _state.value.copy(thumbs = null)
                    return@launch
                }
                when (r) {
                    is RecapFrames.Result.Ok -> {
                        made += r.frame
                        _state.value = _state.value.copy(thumbs = cur.copy(frames = made.toList()))
                    }
                    is RecapFrames.Result.Failed -> {
                        _state.value = _state.value.copy(thumbs = cur.copy(working = false, failed = r.reason))
                        return@launch
                    }
                }
            }
            _state.value.thumbs?.takeIf { it.projectDir == open.dir && it.times == times }?.let {
                _state.value = _state.value.copy(thumbs = it.copy(working = false))
            }
        }
    }

    /**
     * Makes the Publish Kit thumbnail (5B-10) `exports/export.thumb.jpg`: the export-size frame at [atMs] (default:
     * the time of the last thumbnail, else the first caption) with the saved title as a TEXT layer, no captions.
     * Written whole (temporary file + move) with a record of what it shows, so a reopen can tell when it is stale.
     */
    fun makePublishThumbnail(atMs: Long? = null) {
        val open = session.open ?: return
        if (!isRecap(open.project)) return
        val previous = _state.value.publishThumb?.takeIf { it.projectDir == open.dir }
        if (previous?.working == true) return
        val title = open.project.recap?.publishKit?.title?.trim().orEmpty()
        if (title.isEmpty()) return say("Write the video title and press Save first.")
        val fingerprint = frames.fingerprint(open) ?: return say(NO_RECAP_JOB)
        val at = atMs ?: previous?.atMs ?: frames.firstTime(open) ?: return say(NO_RECAP_JOB)
        val exports = joinPath(open.dir, EXPORTS_DIR)
        val file = joinPath(open.dir, PublishKitText.thumbPath(EXPORT_NAME))
        val working = PublishThumb(open.dir, file, at, title, frame = previous?.frame, working = true)
        _state.value = _state.value.copy(publishThumb = working, message = null)
        scope.launch {
            val still = joinPath(joinPath(open.dir, RecapFrames.FRAMES_DIR), "thumb-$at.jpg")
            val r = withContext(Dispatchers.Default) { frames.thumbnail(open, at, title, still) }
            if (session.open?.dir != open.dir || _state.value.publishThumb !== working) return@launch
            val saved = (r as? RecapFrames.Result.Ok)?.let { ok ->
                runCatching {
                    withContext(Dispatchers.Default) {
                        fs.createDirectories(exports)
                        fs.writeDurably("$file.tmp", ok.frame.bytes)
                        fs.atomicMove("$file.tmp", file)
                        val record = PublishThumbRecord(title, at, fingerprint, ok.frame.bytes.size.toLong())
                        fs.writeDurably(thumbRecordPath(file), CoreJson.encodeToString(PublishThumbRecord.serializer(), record).encodeToByteArray())
                    }
                    RecapFrames.Frame(at, file, ok.frame.bytes)
                }
            }
            _state.value = when {
                saved?.isSuccess == true -> _state.value.copy(publishThumb = working.copy(frame = saved.getOrThrow(), working = false))
                saved != null -> _state.value.copy(publishThumb = working.copy(working = false, failed = "Cannot save the thumbnail: ${saved.exceptionOrNull()?.message}"))
                else -> _state.value.copy(publishThumb = working.copy(working = false, failed = (r as RecapFrames.Result.Failed).reason))
            }
        }
    }

    /** Share sheet for the saved thumbnail; only the open project's own file. */
    fun sharePublishThumbnail() {
        val open = session.open ?: return
        val thumb = _state.value.publishThumb?.takeIf { it.projectDir == open.dir && it.frame != null && !it.working } ?: return
        if (!fs.exists(thumb.file)) return say("The thumbnail file is gone; make it again.")
        result(share?.share(thumb.file, JPEG, "Share the thumbnail") ?: PlatformResult.Unsupported(NO_SHARE))
    }

    /** The thumbnail saved in [open]'s exports with its record: stale when the title or the picture changed since. */
    private fun savedPublishThumb(open: OpenProject, kit: PublishKit, fingerprint: String?): PublishThumb? {
        val file = joinPath(open.dir, PublishKitText.thumbPath(EXPORT_NAME))
        val bytes = runCatching { fs.read(file) }.getOrNull() ?: return null
        val record = runCatching {
            CoreJson.decodeFromString(PublishThumbRecord.serializer(), fs.read(thumbRecordPath(file)).decodeToString())
        }.getOrNull()?.takeIf { it.bytes == bytes.size.toLong() }
        val atMs = record?.atMs ?: 0L
        val stale = record == null || record.title != kit.title.trim() || record.fingerprint != fingerprint
        return PublishThumb(open.dir, file, atMs, record?.title ?: kit.title.trim(), RecapFrames.Frame(atMs, file, bytes), stale = stale)
    }

    /** Updates the Publish Kit for the current Recap project. */
    fun setPublishKit(kit: PublishKit) {
        val open = session.open ?: return
        session.save(open.project.copy(recap = open.project.recap?.copy(publishKit = kit)))
        refresh()
    }

    /** Copies [text] to the clipboard; shows a message on success/failure. */
    fun copyPublishText(text: String, label: String) {
        val cb = clipboard ?: return say("Clipboard is not available here")
        when (cb.copyText(text)) {
            is io.github.alalkipgen.autoflow.platform.PlatformResult.Ok -> say("Copied $label")
            is io.github.alalkipgen.autoflow.platform.PlatformResult.Failed -> say("Cannot copy $label")
            is io.github.alalkipgen.autoflow.platform.PlatformResult.Unsupported -> say("Clipboard is not supported here")
        }
    }

    /** Writes and shares the publish text file for the current Recap export. */
    suspend fun sharePublishFile(exportName: String) {
        val open = session.open ?: return
        val saved = open.project.recap?.publishKit ?: return
        val kit = PublishKitText.withChapters(saved, recapExport(open)?.chapters.orEmpty())
        val path = joinPath(open.dir, PublishKitText.path(exportName))
        try {
            fs.createDirectories(joinPath(open.dir, "exports"))
            fs.writeDurably(path, PublishKitText.file(kit).encodeToByteArray())
            result(share?.share(path, "text/plain", "Share publish kit") ?: io.github.alalkipgen.autoflow.platform.PlatformResult.Unsupported(NO_SHARE))
        } catch (e: Exception) {
            say("Cannot write publish file: ${e.message}")
        }
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
        val font = frames.font()
        val fontsDir = RecapFrames.FONTS_DIR
        frames.installFont(open, font)?.let { return say(it) }
        val name = if (quality == Quality.PREVIEW) PREVIEW_NAME else EXPORT_NAME
        if (isRecap(open.project)) {
            val job = frames.job(open, quality, font?.family, joinPath(EXPORTS_DIR, name), "${open.project.name} · ${label(quality)}")
                ?: return say("The recap script cannot be read.")
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

    private fun recapStory(): RecapStory? =
        session.open?.project?.recap?.story?.let { (session.readJson(it, RecapStory.serializer()) as? CoreResult.Ok)?.value }

    private fun recapExport(open: OpenProject): RecapExport? {
        if (!isRecap(open.project)) return null
        val recap = open.project.recap!!
        val story = recapStory()
        // v2 rules (audit F07): fit rows by block id with every clip and the chosen take — what the render plays.
        val lexicon = frames.lexicon?.value ?: io.github.alalkipgen.autoflow.core.recap.Lexicon.EMPTY
        val gate = RecapRulesV2.canRender(recap, story, lexicon)
        val rows = story?.takeIf { RecapRulesV2.isCurrent(recap, it) }?.let { RecapFit.rows(recap, it, lexicon) }.orEmpty()
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
            publishKit = recap.publishKit ?: PublishKit(),
            preflight = PreflightCheck.check(recap, story, lexicon),
            chapters = if (rows.isEmpty() || story == null) emptyList()
            else PublishKitText.chaptersFromBlocks(rows.map { it.clipMs }, story.blocks.map { it.textMy }),
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
        const val JPEG = "image/jpeg"
        /** Exact-frame thumbnails on the Export screen (5B-9). */
        const val THUMBS = 6
        const val NO_RECAP_JOB = "Import the movie and write the script first."

        /** `exports/export.thumb.jpg` → `exports/export.thumb.json`. */
        internal fun thumbRecordPath(jpg: String): String = jpg.removeSuffix(".jpg") + ".json"
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

        fun label(quality: Quality) = if (quality == Quality.PREVIEW) "နမူနာ 540p" else "ထုတ်ယူ 1080p"

        /** Display label for an [ExportQuality] chip. */
        fun label(exportQuality: ExportQuality): String = exportQuality.label
    }
}
