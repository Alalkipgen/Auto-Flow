package io.github.alalkipgen.autoflow.ui.voice

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.VoiceTool
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleExport
import io.github.alalkipgen.autoflow.media.audio.VideoSound
import io.github.alalkipgen.autoflow.media.probe.MediaInfo
import io.github.alalkipgen.autoflow.media.render.MediaTaskJob
import io.github.alalkipgen.autoflow.platform.FilePicker
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiNotice
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.media.MediaTasks
import io.github.alalkipgen.autoflow.ui.projects.DashboardModel
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.shell.VOICE_SCREEN
import io.github.alalkipgen.autoflow.ui.subtitles.localOffsetMs
import io.github.alalkipgen.autoflow.ui.subtitles.localStamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** [action] [SOUND_ONLY] / [REPLACE] / [ADD]; [sound] the picked sound (in `source/`); [startMs], [volume], [lower] for Add. */
data class VideoSoundUi(val dir: String? = null, val busy: UiNotice? = null, val notice: UiNotice? = null, val action: String = SOUND_ONLY,
    val sound: String? = null, val startMs: Long = 0, val volume: Double = 1.0, val lower: Boolean = true)

const val SOUND_ONLY = "sound"
const val REPLACE = "replace"
const val ADD = "add"

/**
 * Video sound (specs/quick-tools.md §6, V-3b): the chooser tile opens the picker for a video at once (Cancel or a file
 * without picture creates nothing), copied into `source/` of a new Voice & Audio project. Save the sound only (m4a /
 * wav), Replace the sound or Add voice or music on top (a picked sound, copied into `source/`), each a media task into
 * `exports/` with [VideoSound] — the picture is never re-encoded. The result is shown by [export].
 */
class VideoSoundModel(
    private val session: ProjectSession,
    private val fs: FileSystem,
    private val dashboard: DashboardModel,
    private val picker: FilePicker?,
    private val tasks: MediaTasks,
    private val export: VoiceExportModel,
    private val config: ToolsConfig,
    private val scope: CoroutineScope,
    private val probe: suspend (String) -> MediaInfo?,
    private val clock: Clock = Clock.System,
    private val offsetMs: (Long) -> Int = ::localOffsetMs,
    private val uiLanguage: () -> UiLanguage = { UiLanguage.ENGLISH },
) {
    private val _state = MutableStateFlow(VideoSoundUi())
    val state: StateFlow<VideoSoundUi> = _state.asStateFlow()
    private var job: Job? = null

    /** Home → Voice & Audio → Video sound: pick the video, create, copy, probe. */
    fun start(onOpened: () -> Unit = {}): Job? {
        val p = picker ?: run { _state.value = VideoSoundUi(notice = UiNotice(UiText.CHOOSE_FILES_UNAVAILABLE)); return null }
        if (job?.isActive == true) return null
        return scope.launch {
            var dir: String? = null
            fun fail(notice: UiNotice) { _state.value = VideoSoundUi(dir, notice = notice) }
            val from = pick(p, UiText.VA_PICK_VIDEO, ProjectSession.MOVIE_EXTENSIONS) { fail(it) } ?: return@launch
            val first = probe(from)
            if (first != null && !first.hasVideo) return@launch fail(UiNotice(UiText.VA_NOT_VIDEO))
            val parent = dashboard.lastParent ?: dashboard.pickParent() ?: return@launch
            val base = from.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.').ifBlank { "Video" }
            if (!dashboard.createVoice(parent, "$base · ${localStamp(clock.now(), offsetMs)}", VoiceTool(task = VoiceTool.TASK_VIDEO_SOUND))) {
                return@launch fail(dashboard.state.value.notice ?: UiNotice(UiText.RESULT_DETAIL, "detail" to dashboard.state.value.message))
            }
            val open = session.open ?: return@launch
            dir = open.dir
            onOpened()
            _state.value = VideoSoundUi(dir, busy = UiNotice(UiText.VA_COPYING))
            val path = when (val r = withContext(Dispatchers.Default) { session.importMovie(from) }) {
                is CoreResult.Ok -> r.value
                is CoreResult.Err -> return@launch fail(UiNotice(UiText.RESULT_DETAIL, "detail" to r.error.message))
            }
            val info = first ?: probe(path) ?: return@launch fail(UiNotice(UiText.VA_UNREADABLE))
            if (!info.hasVideo) return@launch fail(UiNotice(UiText.VA_NOT_VIDEO))
            val source = SourceVideo(path.removePrefix(open.dir.trimEnd('/') + "/"), runCatching { fs.size(path) }.getOrDefault(0L),
                info.durationMs, info.width ?: 0, info.height ?: 0, info.fps, info.hasAudio)
            when (val saved = session.update(open.dir, open.project.id) { it.copy(voice = (it.voice ?: VoiceTool(task = VoiceTool.TASK_VIDEO_SOUND)).copy(source = source)) }) {
                is CoreResult.Ok -> _state.value = VideoSoundUi(dir)
                is CoreResult.Err -> fail(UiNotice(UiText.RESULT_DETAIL, "detail" to saved.error.message))
            }
        }.also { job = it }
    }

    fun setAction(action: String) { edit { it.copy(action = action, notice = null) } }
    fun setStart(ms: Long) { edit { it.copy(startMs = ms.coerceAtLeast(0)) } }
    fun setVolume(volume: Double) { edit { it.copy(volume = volume) } }
    fun setLower(lower: Boolean) { edit { it.copy(lower = lower) } }

    /** Replace / Add: pick the sound; it is copied into `source/` (an older one moves to `.cache/replaced/`). */
    fun pickSound(): Job? {
        val p = picker ?: return null
        val dir = session.open?.dir ?: return null
        if (job?.isActive == true) return null
        return scope.launch {
            val from = pick(p, UiText.VA_PICK_SOUND_FILE, ProjectSession.SONG_EXTENSIONS) { say(dir, it) } ?: return@launch
            edit { it.copy(busy = UiNotice(UiText.VA_COPYING)) }
            when (val r = withContext(Dispatchers.Default) { session.importSong(from) }) {
                is CoreResult.Ok -> edit { it.copy(busy = null, notice = null, sound = r.value.removePrefix(dir.trimEnd('/') + "/")) }
                is CoreResult.Err -> say(dir, UiNotice(UiText.RESULT_DETAIL, "detail" to r.error.message))
            }
        }.also { job = it }
    }

    /** Save m4a / wav (sound only) or Make video (replace / add) as a media task into `exports/`. */
    fun save(format: String = "mp4"): Job? {
        val open = session.open ?: return null
        val source = open.project.voice?.source ?: return null
        val s = _state.value.takeIf { it.dir == open.dir } ?: VideoSoundUi(open.dir)
        if (job?.isActive == true) return null
        return scope.launch {
            if (!tasks.available) return@launch say(open.dir, UiNotice(UiText.RESULT_DETAIL, "detail" to "This app cannot make files here."))
            val br = config.tts.m4aBitrate
            val ext = VideoSound.videoExt(source.file.substringAfterLast('.'))
            val base = name(source.file) + "-" + source.sizeBytes.toString(36)
            val (kind, args, output) = when (s.action) {
                SOUND_ONLY -> {
                    val codec = probe(joinPath(open.dir, source.file))?.audioCodec
                    if (!source.hasAudio) return@launch say(open.dir, UiNotice(UiText.VA_NO_ORIGINAL_SOUND))
                    Triple(VideoSound.SOUND_ONLY, VideoSound.soundOnlyArgs(source.file, codec, format, br), "$base-sound.$format")
                }
                else -> {
                    val sound = s.sound ?: return@launch say(open.dir, UiNotice(UiText.VA_PICK_SOUND_FILE))
                    val key = runCatching { fs.size(joinPath(open.dir, sound)) }.getOrDefault(0L).toString(36) + sound.substringAfterLast('.')
                    if (s.action == REPLACE) Triple(VideoSound.REPLACE, VideoSound.replaceArgs(source.file, sound, source.durationMs, ext, br), "$base-replaced-$key.$ext")
                    else Triple(VideoSound.ADD, VideoSound.addArgs(source.file, sound, source.durationMs, s.startMs, s.volume, s.lower, source.hasAudio, ext, br),
                        "$base-added-$key-${s.startMs}-${(s.volume * 100).toInt()}${if (s.lower) "l" else ""}.$ext")
                }
            }
            val label = when (kind) { VideoSound.SOUND_ONLY -> UiText.VA_SOUND_ONLY; VideoSound.REPLACE -> UiText.VA_REPLACE_SOUND; else -> UiText.VA_ADD_ON_TOP }
            val run = tasks.start(MediaTaskJob(kind, args, "${SubtitleExport.EXPORTS_DIR}/$output", source.durationMs), open.dir,
                "${open.project.name} · ${label.en}", VOICE_SCREEN) ?: return@launch
            export.follow(open.dir, run.output)
            edit { it.copy(notice = null) }
        }.also { job = it }
    }

    private suspend fun pick(p: FilePicker, title: UiText, ext: Set<String>, fail: (UiNotice) -> Unit): String? =
        when (val r = p.pickFiles(title.text(uiLanguage()), ext)) {
            is PlatformResult.Ok -> r.value.firstOrNull()
            is PlatformResult.Failed -> null.also { fail(UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason)) }
            is PlatformResult.Unsupported -> null.also { fail(UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason)) }
        }

    private fun name(file: String) = file.substringAfterLast('/').substringBeforeLast('.')
        .map { if (it.isLetterOrDigit() || it.code in 0x1000..0x109F) it else '-' }.joinToString("").trim('-').ifEmpty { "video" }.take(60)

    private fun edit(change: (VideoSoundUi) -> VideoSoundUi) {
        val dir = session.open?.dir ?: return
        _state.value = change(_state.value.takeIf { it.dir == dir } ?: VideoSoundUi(dir))
    }

    private fun say(dir: String, notice: UiNotice) { _state.value = (_state.value.takeIf { it.dir == dir } ?: VideoSoundUi(dir)).copy(busy = null, notice = notice) }
}
