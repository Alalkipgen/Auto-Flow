package io.github.alalkipgen.autoflow.ui.subtitles

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.SubtitlesTool
import io.github.alalkipgen.autoflow.media.probe.MediaInfo
import io.github.alalkipgen.autoflow.media.probe.MediaProbe
import io.github.alalkipgen.autoflow.platform.FilePicker
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiNotice
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.DashboardModel
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
data class SubtitlesUiState(val dir: String? = null, val busy: UiNotice? = null, val notice: UiNotice? = null, val ok: Boolean = false)

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
                    notice = UiNotice(UiText.SUBTITLES_VIDEO_READY, "duration" to RecapModel.clock(video.durationMs)), ok = true)
                is CoreResult.Err -> fail(UiNotice(UiText.RESULT_DETAIL, "detail" to saved.error.message))
            }
        }.also { job = it }
    }

    private fun isVideo(info: MediaInfo) = info.hasVideo && info.durationMs > 0

    companion object {
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
