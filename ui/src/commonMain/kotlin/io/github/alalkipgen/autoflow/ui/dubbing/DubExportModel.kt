package io.github.alalkipgen.autoflow.ui.dubbing

import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.dubbing.DubExport
import io.github.alalkipgen.autoflow.core.dubbing.DubExportCheck
import io.github.alalkipgen.autoflow.core.getOrNull
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.DubOriginal
import io.github.alalkipgen.autoflow.core.model.DubbingTool
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleExport
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleOps
import io.github.alalkipgen.autoflow.core.subtitles.TextChoice
import io.github.alalkipgen.autoflow.media.audio.DubCue
import io.github.alalkipgen.autoflow.media.audio.DubMixer
import io.github.alalkipgen.autoflow.media.audio.VideoSound
import io.github.alalkipgen.autoflow.media.render.BurnLook
import io.github.alalkipgen.autoflow.media.render.BurnSize
import io.github.alalkipgen.autoflow.media.render.BurnFit
import io.github.alalkipgen.autoflow.ui.subtitles.maxChars
import io.github.alalkipgen.autoflow.ui.subtitles.nameSuffix
import io.github.alalkipgen.autoflow.media.render.FontInfo
import io.github.alalkipgen.autoflow.media.render.MediaTaskJob
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.media.render.SubtitleBurn
import io.github.alalkipgen.autoflow.platform.FileShare
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.export.RecapFrames
import io.github.alalkipgen.autoflow.ui.i18n.UiNotice
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.media.MediaTaskRun
import io.github.alalkipgen.autoflow.ui.media.MediaTasks
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.shell.DUBBING_SCREEN
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Export (D-3b): the runs of the last start in order ([runs], of [total] tasks), the files it makes, and why it stopped. */
data class DubExportUi(val dir: String? = null, val wav: Boolean = false, val runs: List<String> = emptyList(), val total: Int = 0,
    val srt: String? = null, val voice: String? = null, val busy: Boolean = false, val notice: UiNotice? = null, val ok: Boolean = false,
    /** Burned Burmese subtitles: text size, and lines / line length (null = [BurnFit.auto] by the video's frame). */
    val size: BurnSize = BurnSize.MEDIUM, val fit: BurnFit? = null)

/**
 * The Export step (specs/dubbing.md §2, §6): pre-flight ([DubExport.check]), then the [DubMixer] tasks one after
 * another on the render queue — stems, the voice track, the video (picture copied, or Burmese subtitles burned) —
 * each a Jobs row; a step that fails or is cancelled stops the chain, and Export again reuses the finished files.
 * Also writes the Burmese `.srt` of the current version and, when asked, keeps the voice track as a `.wav` in exports.
 */
class DubExportModel(
    private val session: ProjectSession,
    private val fs: FileSystem,
    private val tasks: MediaTasks,
    private val scope: CoroutineScope,
    private val frames: RecapFrames? = null,
    private val config: ToolsConfig = ToolsConfig(),
    private val share: FileShare? = null,
    private val lexicon: () -> Lexicon = { Lexicon.EMPTY },
) {
    private val _state = MutableStateFlow(DubExportUi())
    val state: StateFlow<DubExportUi> = _state.asStateFlow()
    val runs: StateFlow<List<MediaTaskRun>> get() = tasks.runs
    val sharesFiles: Boolean get() = share?.sharesFiles ?: false

    fun check(open: OpenProject?, doc: SubtitleDoc): DubExportCheck? = open?.project?.subtitles?.video?.let {
        DubExport.check(open.project.dubbing, doc, it.durationMs, config.dubbing.minGapMs, config.dubbing.maxVoiceSpeed, lexicon())
    }

    /** Burmese subtitles on the video: saved in the project (`burnSubtitles`). */
    fun setBurn(on: Boolean) {
        val open = session.open ?: return
        if (_state.value.busy) return
        session.update(open.dir, open.project.id) { p -> p.copy(dubbing = (p.dubbing ?: DubbingTool()).copy(burnSubtitles = on)) }
    }

    fun setWav(on: Boolean) = edit { it.copy(wav = on) }

    /** Burned subtitles' text size (this export only). */
    fun setSize(size: BurnSize) = edit { it.copy(size = size) }

    /** Burned subtitles' lines on screen and line length (this export only). */
    fun setFit(fit: BurnFit) = edit { it.copy(fit = fit) }

    /** The lines / line length the burn uses now: the owner's choice, else by the open video's frame. */
    fun fit(open: OpenProject? = session.open): BurnFit = _state.value.takeIf { it.dir == open?.dir }?.fit ?: autoFit(open)

    private fun autoFit(open: OpenProject?): BurnFit = open?.project?.subtitles?.video.let { v ->
        BurnFit.auto(v?.width?.takeIf { it > 0 } ?: 1920, v?.height?.takeIf { it > 0 } ?: 1080)
    }

    /** A choice for the open project (another project's state starts fresh); none while an export runs. */
    private fun edit(f: (DubExportUi) -> DubExportUi) {
        val dir = session.open?.dir ?: return
        if (_state.value.busy) return
        _state.update { cur -> f(if (cur.dir == dir) cur else DubExportUi(dir)).copy(notice = null) }
    }

    /** The run of the step going now, or the last one (it stays after it ends, so the card shows how). */
    fun current(): MediaTaskRun? = _state.value.runs.lastOrNull()?.let(tasks::run)

    /** The finished video of the last start for the open project, else null. */
    fun done(): String? = _state.value.takeIf { it.dir == session.open?.dir && it.runs.size == it.total }?.let { (current()?.state as? RenderState.Done)?.output }

    fun cancel() { current()?.let { tasks.cancel(it.id) } }

    fun start(doc: SubtitleDoc): Job? {
        if (_state.value.busy) return null
        val open = session.open ?: return null
        val video = open.project.subtitles?.video ?: return null
        val tool = open.project.dubbing ?: return null
        if (!tasks.available) return say(open.dir, UiNotice(UiText.RESULT_DETAIL, "detail" to "This app cannot make videos here."))
        val lines = DubExport.lines(tool, doc, video.durationMs, config.dubbing.minGapMs, config.dubbing.maxVoiceSpeed, lexicon())
            ?: return say(open.dir, UiNotice(UiText.DUB_EXPORT_NOT_READY))
        val store = session.artifacts() ?: return null
        val cues = lines.map { l ->
            val file = store.get(l.au).getOrNull()?.file ?: return say(open.dir, UiNotice(UiText.DUB_EXPORT_NO_VOICE, "n" to 1))
            DubCue("${ArtifactStore.ARTIFACTS_DIR}/$file", l.atMs, l.leadMs, l.slotMs, l.speed)
        }
        val base = SubtitleExport.fileName(open.project.name, TextChoice.BURMESE, open.project.subtitles?.current?.value.orEmpty(), "srt").removeSuffix(".srt")
        val group = config.dubbing.stemGroup
        val wav = _state.value.takeIf { it.dir == open.dir }?.wav == true
        val voice = DubMixer.voicePlan(cues, video.durationMs, group)
        // the kept .wav is the same mix written once more into exports, so the video's name never depends on it
        val keep = if (!wav) null else voice.voice.copy(output = "${SubtitleExport.EXPORTS_DIR}/$base-voice-${voice.voice.output.substringAfterLast('-').take(8)}.wav")
        val ass = if (tool.burnSubtitles) burnAss(open, doc, base) ?: return null else null
        val ext = if (ass != null) "mp4" else VideoSound.videoExt(video.file.substringAfterLast('.', "mp4"))
        val volume = if (tool.original.mode == DubOriginal.MUTE) 0.0 else tool.original.volume
        val args = DubMixer.exportArgs(video.file, voice.voice.output, video.durationMs, volume, video.hasAudio, ext, config.tts.m4aBitrate,
            ass, RecapFrames.FONTS_DIR, config.burn.x264Preset, config.burn.crf)
        val all = voice.tasks + listOfNotNull(keep) + MediaTaskJob(DubMixer.EXPORT, args, "${SubtitleExport.EXPORTS_DIR}/$base-dub-${DubMixer.key(args).take(8)}.$ext", video.durationMs)
        val srt = writeSrt(open, doc, "$base.srt") ?: return null
        val kept = _state.value.takeIf { it.dir == open.dir }
        _state.value = DubExportUi(open.dir, wav, total = all.size, srt = srt,
            voice = keep?.let { MediaTaskJob.absolute(open.dir, it.output) }, busy = true, size = kept?.size ?: BurnSize.MEDIUM, fit = kept?.fit)
        return scope.launch {
            for (t in all) {
                val title = "${open.project.name} · ${(if (t.kind == DubMixer.EXPORT) UiText.DUB_EXPORT_MAKING else UiText.DUB_EXPORT_MIXING).en}"
                val run = tasks.start(t, open.dir, title, DUBBING_SCREEN) ?: break
                _state.update { it.copy(runs = it.runs + run.id) }
                val end = tasks.runs.first { l -> l.firstOrNull { it.id == run.id }?.state?.finished == true }.first { it.id == run.id }.state
                if (end !is RenderState.Done) break
            }
            _state.update { it.copy(busy = false) }
        }
    }

    /** After Done: play, share, Save to Gallery (phone) or Open folder (desktop) — the finished file only. */
    fun open() = done()?.let { result(share?.open(it, MP4) ?: unsupported(it)) }
    fun share(title: String) = done()?.let { result(share?.share(it, MP4, title) ?: unsupported(it)) }
    fun openFolder() = done()?.let { result(share?.openFolder(it.substringBeforeLast('/')) ?: unsupported(it)) }
    suspend fun saveToGallery() {
        val path = done() ?: return
        when (val r = share?.saveToGallery(path, MP4, path.substringAfterLast('/')) ?: unsupported(path)) {
            is PlatformResult.Ok -> say(_state.value.dir.orEmpty(), UiNotice(UiText.EXPORT_GALLERY_SAVED, "path" to r.value), ok = true)
            else -> result(r)
        }
    }

    /**
     * The font into `.cache/fonts` and the Burmese `.ass` (as Subtitles' burn, boxed, at the chosen size, lines and
     * line length — named by them, so another choice makes another video); null = [say] why.
     */
    private fun burnAss(open: OpenProject, doc: SubtitleDoc, base: String): String? {
        val video = open.project.subtitles?.video ?: return null
        val font = frames?.font() ?: return say(open.dir, UiNotice(UiText.DUB_BURN_NO_FONT)).let { null }
        frames.installFont(open, font)?.let { return say(open.dir, UiNotice(UiText.RESULT_DETAIL, "detail" to it)).let { null } }
        val size = _state.value.takeIf { it.dir == open.dir }?.size ?: BurnSize.MEDIUM
        val fit = fit(open)
        val ass = ".cache/burn/$base-dub-${size.name.lowercase()}-${fit.nameSuffix()}.ass"
        return try {
            fs.createDirectories(joinPath(open.dir, ".cache/burn"))
            fs.writeDurably(joinPath(open.dir, ass), SubtitleBurn.ass(SubtitleOps.toSrt(doc, TextChoice.BURMESE), video.width.takeIf { it > 0 } ?: 1920,
                video.height.takeIf { it > 0 } ?: 1080, font.family, BurnLook.BOXED, size, fit.maxChars(config.subtitles),
                FontInfo.parse(font.bytes)?.assScale, maxLines = fit.lines).encodeToByteArray())
            ass
        } catch (e: Exception) {
            say(open.dir, UiNotice(UiText.RESULT_DETAIL, "detail" to (e.message ?: e::class.simpleName.orEmpty()))); null
        }
    }

    /** The Burmese `.srt` of this version in exports (written once; versions never change). */
    private fun writeSrt(open: OpenProject, doc: SubtitleDoc, name: String): String? {
        val dir = joinPath(open.dir, SubtitleExport.EXPORTS_DIR)
        val path = joinPath(dir, name)
        return try {
            if (!fs.exists(path)) {
                fs.createDirectories(dir)
                fs.writeDurably("$path.part", SubtitleExport.srt(doc, TextChoice.BURMESE).encodeToByteArray())
                fs.atomicMove("$path.part", path)
            }
            path
        } catch (e: Exception) {
            say(open.dir, UiNotice(UiText.RESULT_DETAIL, "detail" to (e.message ?: e::class.simpleName.orEmpty()))); null
        }
    }

    private fun unsupported(path: String) = PlatformResult.Unsupported("The file is in $path")
    private fun result(r: PlatformResult<*>) = when (r) {
        is PlatformResult.Ok -> _state.update { it.copy(notice = null) }
        is PlatformResult.Failed -> _state.update { it.copy(notice = UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason), ok = false) }
        is PlatformResult.Unsupported -> _state.update { it.copy(notice = UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason), ok = false) }
    }

    private fun say(dir: String, notice: UiNotice, ok: Boolean = false): Job? {
        _state.update { if (it.dir == dir) it.copy(notice = notice, ok = ok) else DubExportUi(dir, notice = notice, ok = ok) }
        return null
    }

    private companion object { const val MP4 = "video/mp4" }
}
