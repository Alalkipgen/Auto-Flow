package io.github.alalkipgen.autoflow.ui.voice

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.getOrNull
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleExport
import io.github.alalkipgen.autoflow.media.audio.PcmWav
import io.github.alalkipgen.autoflow.media.audio.VoiceJoin
import io.github.alalkipgen.autoflow.media.render.MediaTaskJob
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.platform.FileShare
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.i18n.UiNotice
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.media.MediaTaskRun
import io.github.alalkipgen.autoflow.ui.media.MediaTasks
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.shell.VOICE_SCREEN
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** [file] = the saved wav or the m4a being made (its run in [MediaTasks]); [notice] how the last action ended. */
data class VoiceExportUi(val dir: String? = null, val file: String? = null, val notice: UiNotice? = null, val ok: Boolean = false)

/**
 * Text → voice Save (specs/quick-tools.md §6, V-1f): every current take joined in order with `tts.partGapMs` of
 * silence into one `au` wav (`project.voice.joined`, made once until a part changes), then Save wav copies it to
 * `exports/` and Save m4a encodes it there as a media task (Jobs row, Cancel). Share / Save to Music / Open folder.
 */
class VoiceExportModel(
    private val session: ProjectSession,
    private val fs: FileSystem,
    private val tasks: MediaTasks,
    private val config: ToolsConfig,
    private val share: FileShare?,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(VoiceExportUi())
    val state: StateFlow<VoiceExportUi> = _state.asStateFlow()
    val runs = tasks.runs

    fun run(file: String?): MediaTaskRun? = file?.takeIf { it.endsWith(".m4a") }?.let(tasks::forOutput)

    /** The joined voice of the open project (made now when needed); null with a notice when a part has no take. */
    fun joined(): ArtifactId? {
        val open = session.open ?: return null
        val voice = open.project.voice ?: return null
        val store = ArtifactStore(fs, open.dir)
        voice.joined?.takeIf { store.get(it) is CoreResult.Ok }?.let { return it }
        if (voice.parts.isEmpty() || voice.parts.any { it.take == null }) return say(UiNotice(UiText.VA_JOIN_NOT_ALL))
        val takes = voice.parts.map { p -> (store.readBytes(p.take!!) as? CoreResult.Ok)?.value ?: return say(UiNotice(UiText.VA_JOIN_FAILED)) }
        val bytes = VoiceJoin.join(takes, config.tts.partGapMs) ?: return say(UiNotice(UiText.VA_JOIN_FAILED))
        val id = when (val put = store.put(bytes, ArtifactKind.AUDIO, "wav", note = "joined voice")) {
            is CoreResult.Ok -> put.value.id
            is CoreResult.Err -> return say(UiNotice(UiText.RESULT_DETAIL, "detail" to put.error.message))
        }
        session.update(open.dir, open.project.id) { p -> p.copy(voice = p.voice?.takeIf { it.parts == voice.parts }?.copy(joined = id) ?: p.voice) }
        return id
    }

    /** Save wav: the joined voice copied to `exports/<project>-voice-<id>.wav` (kept when it is there already). */
    fun saveWav(): String? {
        val open = session.open ?: return null
        val id = joined() ?: return null
        val src = file(open.dir, id) ?: return say(UiNotice(UiText.VA_JOIN_FAILED))
        val path = joinPath(joinPath(open.dir, SubtitleExport.EXPORTS_DIR), VoiceJoin.fileName(open.project.name, id.value, "wav"))
        return try {
            if (!fs.exists(path)) {
                fs.createDirectories(path.substringBeforeLast('/'))
                fs.copyDurably(src, "$path.part")
                fs.atomicMove("$path.part", path)
            }
            _state.value = VoiceExportUi(open.dir, path, UiNotice(UiText.SUB_EXPORT_SAVED, "path" to path), ok = true)
            path
        } catch (e: Exception) {
            say(UiNotice(UiText.RESULT_DETAIL, "detail" to (e.message ?: e::class.simpleName.orEmpty())))
        }
    }

    /** Save m4a: AAC at `tts.m4aBitrate` from the joined wav, as a media task. */
    fun saveM4a(): MediaTaskRun? {
        val open = session.open ?: return null
        if (!tasks.available) return say(UiNotice(UiText.RESULT_DETAIL, "detail" to "This app cannot make audio files here."))
        run(_state.value.file)?.takeIf { !it.state.finished }?.let { return it }
        val id = joined() ?: return null
        val src = ArtifactStore(fs, open.dir).get(id).getOrNull()?.file ?: return say(UiNotice(UiText.VA_JOIN_FAILED))
        val ms = (ArtifactStore(fs, open.dir).readBytes(id) as? CoreResult.Ok)?.value?.let { PcmWav.parse(it)?.durationMs } ?: 0
        val output = "${SubtitleExport.EXPORTS_DIR}/${VoiceJoin.fileName(open.project.name, id.value, "m4a")}"
        val task = MediaTaskJob(VoiceJoin.VOICE_M4A, VoiceJoin.m4aArgs("${ArtifactStore.ARTIFACTS_DIR}/$src", config.tts.m4aBitrate), output, ms)
        val started = tasks.start(task, open.dir, "${open.project.name} · ${UiText.VA_SAVE_M4A.en}", VOICE_SCREEN) ?: return null
        _state.value = VoiceExportUi(open.dir, started.output)
        return started
    }

    fun cancel() { run(_state.value.file)?.let { tasks.cancel(it.id) } }

    /** The finished file of the last Save (the wav at once, the m4a when its task is done). */
    fun ready(): String? {
        val f = _state.value.file ?: return null
        return if (f.endsWith(".m4a")) (run(f)?.state as? RenderState.Done)?.output else f
    }

    fun share(title: String) = ready()?.let { result(share?.share(it, mime(it), title) ?: unsupported(it)) }
    fun openFolder() = ready()?.let { result(share?.openFolder(it.substringBeforeLast('/')) ?: unsupported(it)) }

    fun saveToMusic() {
        val path = ready() ?: return
        val to = share ?: return result(unsupported(path))
        scope.launch {
            when (val r = to.saveToMusic(path, mime(path), path.substringAfterLast('/'))) {
                is PlatformResult.Ok -> keep(UiNotice(UiText.VA_SAVED_MUSIC, "path" to r.value), ok = true)
                is PlatformResult.Failed -> keep(UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason))
                is PlatformResult.Unsupported -> keep(UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason))
            }
        }
    }

    val sharesFiles: Boolean get() = share?.sharesFiles == true

    private fun file(dir: String, id: ArtifactId): String? =
        ArtifactStore(fs, dir).get(id).getOrNull()?.let { joinPath(joinPath(dir, ArtifactStore.ARTIFACTS_DIR), it.file) }
    private fun mime(path: String) = if (path.endsWith(".m4a")) "audio/mp4" else "audio/wav"
    private fun unsupported(path: String) = PlatformResult.Unsupported("The file is in $path")
    private fun result(r: PlatformResult<Unit>) = when (r) {
        is PlatformResult.Ok -> keep(null)
        is PlatformResult.Failed -> keep(UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason))
        is PlatformResult.Unsupported -> keep(UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason))
    }
    private fun keep(notice: UiNotice?, ok: Boolean = false) { _state.value = _state.value.copy(notice = notice, ok = ok) }
    private fun <T> say(notice: UiNotice): T? { _state.value = VoiceExportUi(session.open?.dir, notice = notice); return null }
}
