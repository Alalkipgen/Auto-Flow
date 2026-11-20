package io.github.alalkipgen.autoflow.ui.subtitles

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.SubtitlesTool
import io.github.alalkipgen.autoflow.core.recap.SubtitleTrack
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.subtitles.SrtEncoding
import io.github.alalkipgen.autoflow.core.subtitles.SrtImport
import io.github.alalkipgen.autoflow.core.subtitles.SrtImportResult
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleOps
import io.github.alalkipgen.autoflow.media.probe.MediaInfo
import io.github.alalkipgen.autoflow.media.probe.MediaProbe
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.platform.FilePicker
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiNotice
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.DashboardModel
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.recap.RecapModel
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One honest busy line or one result line. [dir] is the project it belongs to; null = nothing was created, so Home
 * shows the line instead of the tool screen.
 */
data class SubtitlesUiState(
    val dir: String? = null,
    val busy: UiNotice? = null,
    val notice: UiNotice? = null,
    val ok: Boolean = false,
    /** Subtitle tracks inside the video; null = not read, or [tracksRead] and unreadable here. */
    val tracks: List<SubtitleTrack>? = null,
    val tracksRead: Boolean = false,
    /** Second line under [notice]: the Burmese looks like Zawgyi (Q-44, warn only, nothing converted). */
    val warning: UiNotice? = null,
    /** The `st` version `project.subtitles.current` points at, once read. */
    val doc: SubtitleDoc? = null,
)

/**
 * Subtitles tool (specs/quick-tools.md §3 and §5, D-77). S-1b: create on pick — the system picker opens at once and
 * Cancel creates nothing; a file that is surely not a video creates nothing; otherwise the project gets an automatic
 * name, the video is copied into `source/` and its facts are saved in `project.subtitles.video`. If the video can
 * only be checked after the copy and fails, the project stays with the error (the app never deletes files itself).
 */
class SubtitlesModel(
    private val session: ProjectSession,
    private val fs: FileSystem,
    private val dashboard: DashboardModel,
    private val picker: FilePicker?,
    private val scope: CoroutineScope,
    /** ffprobe facts of a file, or null when it cannot be read here (no ffprobe, unreadable path). */
    private val probe: suspend (String) -> MediaInfo?,
    /** Writes text track `index` of `video` as `.srt` at `out`; null = done, else why not (see [extractWith]). */
    private val extract: suspend (video: String, index: Int, out: String) -> UiNotice? = { _, _, _ -> UiNotice(UiText.MOVIE_FFMPEG_UNAVAILABLE) },
    private val clock: Clock = Clock.System,
    private val offsetMs: (Long) -> Int = ::localOffsetMs,
    private val uiLanguage: () -> UiLanguage = { UiLanguage.ENGLISH },
) {
    private val _state = MutableStateFlow(SubtitlesUiState())
    val state: StateFlow<SubtitlesUiState> = _state.asStateFlow()
    private var job: Job? = null

    /** Home tile: pick a video and make a Subtitles project from it; [onOpened] runs once the project is open. */
    fun start(onOpened: () -> Unit): Job? {
        val p = picker ?: run { _state.value = SubtitlesUiState(notice = UiNotice(UiText.CHOOSE_FILES_UNAVAILABLE)); return null }
        if (job?.isActive == true) return null
        _state.value = SubtitlesUiState()
        return scope.launch {
            var dir: String? = null
            fun fail(notice: UiNotice?) { _state.value = SubtitlesUiState(dir, notice = notice) }
            val from = when (val r = p.pickFiles(UiText.SUBTITLES_PICK_VIDEO.text(uiLanguage()), ProjectSession.MOVIE_EXTENSIONS)) {
                is PlatformResult.Ok -> r.value.firstOrNull() ?: return@launch // Cancel: nothing is created
                is PlatformResult.Failed -> return@launch fail(UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason))
                is PlatformResult.Unsupported -> return@launch fail(UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason))
            }
            val before = probe(from)
            if (before != null && !isVideo(before)) return@launch fail(UiNotice(UiText.MOVIE_NO_PICTURE))
            val parent = dashboard.lastParent ?: dashboard.pickParent() ?: return@launch
            val base = from.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.').ifBlank { "Subtitles" }
            if (!dashboard.createSubtitles(parent, "$base · ${localStamp(clock.now(), offsetMs)}")) {
                return@launch fail(dashboard.state.value.notice ?: UiNotice(UiText.RESULT_DETAIL, "detail" to dashboard.state.value.message))
            }
            dir = session.open?.dir
            onOpened()
            _state.value = SubtitlesUiState(dir, busy = UiNotice(UiText.MOVIE_COPYING))
            val path = when (val r = withContext(Dispatchers.Default) { session.importMovie(from) }) {
                is CoreResult.Ok -> r.value
                is CoreResult.Err -> return@launch fail(UiNotice(UiText.RESULT_DETAIL, "detail" to r.error.message))
            }
            val info = before ?: probe(path) ?: return@launch fail(UiNotice(UiText.MOVIE_UNREADABLE))
            if (!isVideo(info)) return@launch fail(UiNotice(UiText.MOVIE_NO_PICTURE))
            val folder = dir ?: return@launch fail(null)
            val video = SourceVideo(
                file = path.removePrefix(folder.trimEnd('/') + "/"),
                sizeBytes = runCatching { fs.size(path) }.getOrDefault(0L),
                durationMs = info.durationMs,
                width = info.width ?: 0,
                height = info.height ?: 0,
                fps = info.fps,
                hasAudio = info.hasAudio,
            )
            val saved = session.update(folder, session.open?.project?.id ?: return@launch fail(null)) {
                it.copy(subtitles = (it.subtitles ?: SubtitlesTool()).copy(video = video))
            }
            when (saved) {
                is CoreResult.Ok -> _state.value = SubtitlesUiState(dir,
                    notice = UiNotice(UiText.SUBTITLES_VIDEO_READY, "duration" to RecapModel.clock(video.durationMs)), ok = true,
                    tracks = info.subtitleTracks, tracksRead = true)
                is CoreResult.Err -> fail(UiNotice(UiText.RESULT_DETAIL, "detail" to saved.error.message))
            }
        }.also { job = it }
    }

    private fun isVideo(info: MediaInfo) = info.hasVideo && info.durationMs > 0

    // --- Source (S-2b, specs/quick-tools.md §5): a text track or an .srt file becomes the first `st` version. ----------

    /** The screen opened: reads the current `st`, and the video's subtitle tracks once. */
    fun refresh(): Job? {
        val open = session.open ?: return null
        val tool = open.project.subtitles ?: return null
        if (job?.isActive == true) return null
        val known = _state.value.takeIf { it.dir == open.dir }
        return scope.launch {
            val doc = tool.current?.let { withContext(Dispatchers.Default) { readDoc(open.dir, it) } }
            val tracks = if (known?.tracksRead == true) known.tracks
                else tool.video?.let { probe(joinPath(open.dir, it.file)) }?.subtitleTracks
            if (session.open?.dir != open.dir) return@launch
            val base = _state.value.takeIf { it.dir == open.dir } ?: SubtitlesUiState(open.dir)
            _state.value = base.copy(tracks = tracks, tracksRead = true, doc = doc,
                notice = if (tool.current != null && doc == null) UiNotice(UiText.SUB_DOC_UNREADABLE) else base.notice,
                ok = base.ok && !(tool.current != null && doc == null))
        }.also { job = it }
    }

    /** Uses a text track of the video; a picture track only explains why it cannot be edited and saves nothing. */
    fun useTrack(track: SubtitleTrack): Job? {
        val open = session.open ?: return null
        val video = open.project.subtitles?.video ?: return null
        if (!track.isText) { say(open.dir, UiNotice(UiText.SUB_PICTURE_TRACK, "n" to 1, "codec" to track.codecName)); return null }
        if (job?.isActive == true) return null
        return scope.launch {
            busy(open.dir, UiNotice(UiText.SUBTITLE_EXTRACTING, "n" to track.index))
            val cache = joinPath(open.dir, ".cache")
            val out = joinPath(cache, "subtitles-track-${track.index}.srt")
            val bytes = withContext(Dispatchers.Default) {
                fs.createDirectories(cache)
                extract(joinPath(open.dir, video.file), track.index, out)?.let { return@withContext it }
                session.readFile(out) ?: UiNotice(UiText.CANNOT_READ_FILE)
            }
            if (bytes is UiNotice) return@launch say(open.dir, bytes)
            when (val r = SrtImport.read(bytes as ByteArray)) {
                is SrtImportResult.Ok -> save(open, SubtitleOps.fromCues(r.cues, SubtitleDoc.trackSource(track.index), lang = track.language),
                    UiNotice(UiText.SUB_SAVED_TRACK, "n" to r.cues.size, "track" to track.index), r.zawgyiLikely)
                SrtImportResult.Empty -> say(open.dir, UiNotice(UiText.SUBTITLE_TRACK_EMPTY))
                is SrtImportResult.Broken -> say(open.dir, UiNotice(UiText.SUB_SRT_BROKEN, "n" to r.cueNumber))
            }
        }.also { job = it }
    }

    /** Imports an `.srt` file: says which encoding was used and warns about likely Zawgyi; empty or broken saves nothing. */
    fun importSrt(): Job? {
        val open = session.open ?: return null
        val p = picker ?: run { say(open.dir, UiNotice(UiText.CHOOSE_FILES_UNAVAILABLE)); return null }
        if (job?.isActive == true) return null
        return scope.launch {
            val from = when (val r = p.pickFiles(UiText.SUB_PICK_SRT.text(uiLanguage()), setOf("srt"))) {
                is PlatformResult.Ok -> r.value.firstOrNull() ?: return@launch
                is PlatformResult.Failed -> return@launch say(open.dir, UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason))
                is PlatformResult.Unsupported -> return@launch say(open.dir, UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason))
            }
            val bytes = withContext(Dispatchers.Default) { session.readFile(from) } ?: return@launch say(open.dir, UiNotice(UiText.CANNOT_READ_FILE))
            when (val r = SrtImport.read(bytes)) {
                is SrtImportResult.Ok -> save(open, SubtitleOps.fromCues(r.cues, SubtitleDoc.SOURCE_SRT),
                    UiNotice(UiText.SUB_SAVED_SRT, "n" to r.cues.size, "encoding" to encodingLabel(r.encoding)), r.zawgyiLikely)
                SrtImportResult.Empty -> say(open.dir, UiNotice(UiText.SUB_SRT_EMPTY))
                is SrtImportResult.Broken -> say(open.dir, UiNotice(UiText.SUB_SRT_BROKEN, "n" to r.cueNumber))
            }
        }.also { job = it }
    }

    /** Saves [doc] as a new immutable `st` (after the current one) and moves only `project.subtitles.current`. */
    private fun save(open: OpenProject, doc: SubtitleDoc, done: UiNotice, zawgyi: Boolean) {
        val latest = session.open?.takeIf { it.dir == open.dir } ?: return say(open.dir, null)
        val next = doc.copy(derivedFrom = latest.project.subtitles?.current)
        val bytes = CoreJson.encodeToString(SubtitleDoc.serializer(), next).encodeToByteArray()
        val id = when (val r = ArtifactStore(fs, open.dir, clock).put(bytes, ArtifactKind.SUBTITLES, "json")) {
            is CoreResult.Ok -> r.value.id
            is CoreResult.Err -> return say(open.dir, UiNotice(UiText.RESULT_DETAIL, "detail" to r.error.message))
        }
        when (val r = session.update(open.dir, latest.project.id) { it.copy(subtitles = (it.subtitles ?: SubtitlesTool()).copy(current = id)) }) {
            is CoreResult.Ok -> _state.value = state(open.dir).copy(busy = null, notice = done, ok = true, doc = next,
                warning = if (zawgyi) UiNotice(UiText.SUB_ZAWGYI) else null)
            is CoreResult.Err -> say(open.dir, UiNotice(UiText.RESULT_DETAIL, "detail" to r.error.message))
        }
    }

    private fun readDoc(dir: String, id: ArtifactId): SubtitleDoc? =
        (ArtifactStore(fs, dir, clock).readBytes(id) as? CoreResult.Ok)?.value?.let {
            runCatching { CoreJson.decodeFromString(SubtitleDoc.serializer(), it.decodeToString()) }.getOrNull()
        }

    private fun state(dir: String) = _state.value.takeIf { it.dir == dir } ?: SubtitlesUiState(dir)
    private fun busy(dir: String, notice: UiNotice) { _state.value = state(dir).copy(busy = notice, notice = null, warning = null, ok = false) }
    private fun say(dir: String, notice: UiNotice?) { _state.value = state(dir).copy(busy = null, notice = notice, warning = null, ok = false) }

    companion object {
        private val BURMESE = setOf("my", "mya", "bur")

        /** Spec §5 default: a Burmese text track, else the first text track, else none (Import). */
        fun defaultTrack(tracks: List<SubtitleTrack>): Int? = tracks.filter { it.isText }
            .let { text -> (text.firstOrNull { it.language?.lowercase() in BURMESE } ?: text.firstOrNull())?.index }

        fun encodingLabel(e: SrtEncoding): String = when (e) {
            SrtEncoding.UTF8 -> "UTF-8"
            SrtEncoding.UTF8_BOM -> "UTF-8 BOM"
            SrtEncoding.UTF16LE_BOM -> "UTF-16 LE"
            SrtEncoding.UTF16BE_BOM -> "UTF-16 BE"
            SrtEncoding.WINDOWS_1252 -> "Windows-1252"
        }

        /** The app's ffmpeg as [extract] (same command as Recap's track extraction, D-1). */
        fun extractWith(media: MediaTools): suspend (String, Int, String) -> UiNotice? = { video, index, out ->
            withContext(Dispatchers.Default) {
                val runner = media.runner ?: return@withContext UiNotice(UiText.MOVIE_FFMPEG_UNAVAILABLE)
                val ffmpeg = media.ffmpeg() ?: return@withContext UiNotice(UiText.MOVIE_FFMPEG_UNAVAILABLE)
                val r = runner.run(listOf(ffmpeg) + MediaProbe.extractSubtitleArgs(video, index, out), MediaTools.parentOf(video), 120_000L, keepOutput = true) {}
                if (r is ToolExec.Done && r.exitCode == 0) null
                else UiNotice(UiText.SUBTITLE_EXTRACT_FAILED, "detail" to ((r as? ToolExec.Done)?.tail?.lastOrNull() ?: "ffmpeg failed"))
            }
        }

        /** The app's ffprobe as [probe]; null when ffprobe is missing or the file cannot be read. */
        fun probeWith(media: MediaTools): suspend (String) -> MediaInfo? = { path ->
            withContext(Dispatchers.Default) {
                val runner = media.runner ?: return@withContext null
                val ffprobe = media.ffprobe() ?: return@withContext null
                (MediaProbe(runner, ffprobe).probe(path, MediaTools.parentOf(path)) as? MediaProbe.Result.Ok)?.info
            }
        }
    }
}
