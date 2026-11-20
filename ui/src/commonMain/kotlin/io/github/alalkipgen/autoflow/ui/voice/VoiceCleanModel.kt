package io.github.alalkipgen.autoflow.ui.voice

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.CleanChoice
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.VoiceTool
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleExport
import io.github.alalkipgen.autoflow.media.audio.CleanAudio
import io.github.alalkipgen.autoflow.media.probe.MediaInfo
import io.github.alalkipgen.autoflow.media.render.MediaTaskJob
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.platform.FilePicker
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiNotice
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.media.MediaTasks
import io.github.alalkipgen.autoflow.ui.media.MediaTools
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

/** [busy] one honest line while working; [before] / [after] the Before / After files of the current choice. */
data class VoiceCleanUi(val dir: String? = null, val busy: UiNotice? = null, val notice: UiNotice? = null,
    val before: String? = null, val after: String? = null)

/**
 * Clean up sound (specs/quick-tools.md §6, V-2b): the chooser tile opens the picker at once (Cancel creates nothing);
 * the picked audio or video is copied into `source/` of a new Voice & Audio project. Shorten long pauses · Reduce
 * noise · Even loudness; Before / After = the first `clean.previewMs`; Save m4a / wav = the whole file as a media
 * task into `exports/` (its progress, Share and Save to Music in [export]). One [CleanAudio] analysis per choice.
 */
class VoiceCleanModel(
    private val session: ProjectSession,
    private val fs: FileSystem,
    private val dashboard: DashboardModel,
    private val picker: FilePicker?,
    private val tasks: MediaTasks,
    private val export: VoiceExportModel,
    private val config: ToolsConfig,
    private val scope: CoroutineScope,
    private val probe: suspend (String) -> MediaInfo?,
    /** ffmpeg with these arguments in a folder → exit code and log; null when ffmpeg cannot run here. */
    private val ffmpeg: suspend (args: List<String>, dir: String, timeoutMs: Long) -> Pair<Int, String>?,
    private val clock: Clock = Clock.System,
    private val offsetMs: (Long) -> Int = ::localOffsetMs,
    private val uiLanguage: () -> UiLanguage = { UiLanguage.ENGLISH },
) {
    private val _state = MutableStateFlow(VoiceCleanUi())
    val state: StateFlow<VoiceCleanUi> = _state.asStateFlow()
    private var job: Job? = null
    private var analysis: Pair<String, CleanAudio.Analysis>? = null
    val previewMs: Long get() = config.clean.previewMs

    /** Home → Voice & Audio → Clean up sound: pick, create, copy, probe. [onOpened] once the project is open. */
    fun start(onOpened: () -> Unit = {}): Job? {
        val p = picker ?: run { _state.value = VoiceCleanUi(notice = UiNotice(UiText.CHOOSE_FILES_UNAVAILABLE)); return null }
        if (job?.isActive == true) return null
        return launch {
            var dir: String? = null
            fun fail(notice: UiNotice) { _state.value = VoiceCleanUi(dir, notice = notice) }
            val from = when (val r = p.pickFiles(UiText.VA_PICK_SOUND.text(uiLanguage()), EXTENSIONS)) {
                is PlatformResult.Ok -> r.value.firstOrNull() ?: return@launch
                is PlatformResult.Failed -> return@launch fail(UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason))
                is PlatformResult.Unsupported -> return@launch fail(UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason))
            }
            val first = probe(from)
            if (first != null && !first.hasAudio) return@launch fail(UiNotice(UiText.VA_NO_SOUND))
            val parent = dashboard.lastParent ?: dashboard.pickParent() ?: return@launch
            val base = from.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.').ifBlank { "Sound" }
            if (!dashboard.createVoice(parent, "$base · ${localStamp(clock.now(), offsetMs)}", VoiceTool(task = VoiceTool.TASK_CLEAN))) {
                return@launch fail(dashboard.state.value.notice ?: UiNotice(UiText.RESULT_DETAIL, "detail" to dashboard.state.value.message))
            }
            val open = session.open ?: return@launch
            dir = open.dir
            onOpened()
            _state.value = VoiceCleanUi(dir, busy = UiNotice(UiText.VA_COPYING))
            val ext = from.substringAfterLast('.', "").lowercase()
            val copied = withContext(Dispatchers.Default) { if (ext in ProjectSession.SONG_EXTENSIONS) session.importSong(from) else session.importMovie(from) }
            val path = when (copied) {
                is CoreResult.Ok -> copied.value
                is CoreResult.Err -> return@launch fail(UiNotice(UiText.RESULT_DETAIL, "detail" to copied.error.message))
            }
            val info = first ?: probe(path) ?: return@launch fail(UiNotice(UiText.VA_UNREADABLE))
            if (!info.hasAudio) return@launch fail(UiNotice(UiText.VA_NO_SOUND))
            val source = SourceVideo(path.removePrefix(open.dir.trimEnd('/') + "/"), runCatching { fs.size(path) }.getOrDefault(0L),
                info.durationMs, info.width ?: 0, info.height ?: 0, info.fps, hasAudio = true)
            when (val saved = session.update(open.dir, open.project.id) { it.copy(voice = (it.voice ?: VoiceTool(task = VoiceTool.TASK_CLEAN)).copy(source = source)) }) {
                is CoreResult.Ok -> _state.value = VoiceCleanUi(dir)
                is CoreResult.Err -> fail(UiNotice(UiText.RESULT_DETAIL, "detail" to saved.error.message))
            }
        }
    }

    /** A choice chip; the last one on stays on. Before / After are for the old choice, so they go. */
    fun setChoice(choice: CleanChoice) {
        val open = session.open ?: return
        if (!choice.any || job?.isActive == true) return
        session.update(open.dir, open.project.id) { it.copy(voice = it.voice?.copy(clean = choice)) }
        _state.value = VoiceCleanUi(open.dir)
    }

    /** Before / After: the first `clean.previewMs` as it is and cleaned (files in `.cache/clean/`). */
    fun preview(): Job? = withSource { dir, source, choice ->
        val a = analyze(dir, source, choice) ?: return@withSource
        val folder = ".cache/clean"
        fs.createDirectories(joinPath(dir, folder))
        val before = "$folder/before-${source.sizeBytes.toString(36)}.wav"
        val after = "$folder/after-${CleanAudio.fileName(source.file, choice, source.sizeBytes, "wav")}"
        val limit = config.clean.previewMs
        if (!ok(dir, CleanAudio.beforeArgs(source.file, limit, before), limit)) return@withSource
        if (!ok(dir, CleanAudio.args(source.file, choice, kept(a, choice), a.loudness, "wav", config.tts.m4aBitrate, after, limit), limit)) return@withSource
        _state.value = VoiceCleanUi(dir, before = joinPath(dir, before), after = joinPath(dir, after))
    }

    /** Save m4a / Save wav: the whole file cleaned into `exports/` as a media task (Jobs row, Cancel). */
    fun save(format: String): Job? = withSource { dir, source, choice ->
        if (!tasks.available) return@withSource say<Unit>(dir, UiNotice(UiText.RESULT_DETAIL, "detail" to "This app cannot make audio files here.")).let {}
        val a = analyze(dir, source, choice) ?: return@withSource
        val kept = kept(a, choice)
        val ms = kept?.sumOf { (from, to) -> ((to ?: (source.durationMs / 1000.0)) - from).coerceAtLeast(0.0) }?.times(1000)?.toLong() ?: source.durationMs
        val output = "${SubtitleExport.EXPORTS_DIR}/${CleanAudio.fileName(source.file, choice, source.sizeBytes, format)}"
        val task = MediaTaskJob(CleanAudio.CLEAN_AUDIO, CleanAudio.args(source.file, choice, kept, a.loudness, format, config.tts.m4aBitrate,
            MediaTaskJob.PART), output, ms)
        val name = session.open?.project?.name.orEmpty()
        val run = tasks.start(task, dir, "$name · ${UiText.VA_CLEAN.en}", VOICE_SCREEN) ?: return@withSource
        export.follow(dir, run.output)
        _state.value = _state.value.copy(busy = null)
    }

    private fun kept(a: CleanAudio.Analysis, choice: CleanChoice) =
        if (choice.pauses) CleanAudio.kept(a, config.clean.pauseMs, config.clean.keepMs) else null

    /** The pauses and loudness of the source for [choice] (once per choice); null with a notice when ffmpeg fails. */
    private suspend fun analyze(dir: String, source: SourceVideo, choice: CleanChoice): CleanAudio.Analysis? {
        val key = "$dir|${source.file}|${source.sizeBytes}|$choice"
        analysis?.takeIf { it.first == key }?.let { return it.second }
        val args = CleanAudio.analyzeArgs(source.file, choice, config.clean.pauseMs)
            ?: return CleanAudio.Analysis(emptyList(), null, source.durationMs / 1000.0)
        _state.value = VoiceCleanUi(dir, busy = UiNotice(UiText.VA_LISTENING))
        val r = ffmpeg(args, dir, 120_000 + source.durationMs) ?: return say(dir, UiNotice(UiText.VA_NO_FFMPEG))
        if (r.first != 0) return say(dir, UiNotice(UiText.VA_CLEAN_FAILED, "detail" to r.second.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()))
        val a = CleanAudio.parseAnalysis(r.second).let { it.copy(durationSec = it.durationSec ?: (source.durationMs / 1000.0)) }
        analysis = key to a
        return a
    }

    private suspend fun ok(dir: String, args: List<String>, limitMs: Long): Boolean {
        _state.value = _state.value.copy(busy = UiNotice(UiText.VA_LISTENING))
        val r = ffmpeg(args, dir, 60_000 + 2 * limitMs)
        if (r?.first == 0) return true
        say<Unit>(dir, if (r == null) UiNotice(UiText.VA_NO_FFMPEG)
            else UiNotice(UiText.VA_CLEAN_FAILED, "detail" to r.second.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()))
        return false
    }

    private fun withSource(block: suspend (String, SourceVideo, CleanChoice) -> Unit): Job? {
        val open = session.open ?: return null
        val voice = open.project.voice ?: return null
        val source = voice.source ?: return null
        if (job?.isActive == true) return null
        return launch { block(open.dir, source, voice.clean) }
    }

    private fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block).also { job = it }
    private fun <T> say(dir: String, notice: UiNotice): T? { _state.value = VoiceCleanUi(dir, notice = notice); return null }

    companion object {
        val EXTENSIONS: Set<String> = ProjectSession.SONG_EXTENSIONS + ProjectSession.MOVIE_EXTENSIONS

        /** The app's ffmpeg as [ffmpeg]. */
        fun ffmpegWith(media: MediaTools): suspend (List<String>, String, Long) -> Pair<Int, String>? = { args, dir, timeout ->
            withContext(Dispatchers.Default) {
                val runner = media.runner ?: return@withContext null
                val bin = media.ffmpeg() ?: return@withContext null
                when (val r = runner.run(listOf(bin) + args, dir, timeout, keepOutput = true) {}) {
                    is ToolExec.Done -> (if (r.timedOut) -1 else r.exitCode) to r.output
                    is ToolExec.CannotStart -> null
                }
            }
        }
    }
}
