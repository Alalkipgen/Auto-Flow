package io.github.alalkipgen.autoflow.ui.subtitles

import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.subtitles.ExportBlock
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleChecks
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleExport
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleOps
import io.github.alalkipgen.autoflow.core.subtitles.TextChoice
import io.github.alalkipgen.autoflow.media.probe.MediaProbe
import io.github.alalkipgen.autoflow.media.render.BurnLook
import io.github.alalkipgen.autoflow.media.render.BurnSize
import io.github.alalkipgen.autoflow.media.render.BurnFit
import io.github.alalkipgen.autoflow.media.render.FontInfo
import io.github.alalkipgen.autoflow.media.render.MediaTaskJob
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.media.render.SubtitleBurn
import io.github.alalkipgen.autoflow.media.render.Overlay
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.platform.FileShare
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.export.RecapFrames
import io.github.alalkipgen.autoflow.ui.i18n.UiNotice
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.media.MediaTaskRun
import io.github.alalkipgen.autoflow.ui.media.MediaTasks
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.shell.SUBTITLES_SCREEN
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The video part of the export dialog: look, size, the last started burn ([output]) and why one did not start. */
data class SubtitleBurnUi(val look: BurnLook = BurnLook.CLEAN, val size: BurnSize = BurnSize.MEDIUM, val output: String? = null,
    val notice: UiNotice? = null, val ok: Boolean = false, val checking: Boolean = false, val frame: BurnFrame? = null,
    /** Lines on screen and line length; null = [BurnFit.auto] by the video's frame. */
    val fit: BurnFit? = null)

/** A checked frame: line index [line] at [atMs], PNG [bytes]. */
class BurnFrame(val line: Int, val atMs: Long, val bytes: ByteArray)

/**
 * "Video with subtitles" (specs/quick-tools.md §5 Export, §9 Burn, S-4c): the saved version in the chosen language
 * burned into the source video as a [MediaTaskJob] — Jobs row, real progress, Cancel. Pre-flight: no lines / bad
 * times (as `.srt`), and a Burmese burn needs a Burmese font (Settings → Fonts or built in). The font is copied into
 * the project's `.cache/fonts` like the Recap export; the `.ass` goes to `.cache/burn/` (not an artifact). Output
 * `exports/<project>-<lang>-<version>-<look>-<size>-<lines>l-<length>.mp4`: the same choice gives the same file, never overwritten.
 */
class SubtitleBurnModel(
    private val session: ProjectSession,
    private val fs: FileSystem,
    private val tasks: MediaTasks,
    private val frames: RecapFrames? = null,
    private val media: MediaTools? = null,
    private val config: ToolsConfig = ToolsConfig(),
    private val share: FileShare? = null,
    private val audioCodec: (suspend (file: String, dir: String) -> String?)? = null,
) {
    private val _state = MutableStateFlow(SubtitleBurnUi())
    val state: StateFlow<SubtitleBurnUi> = _state.asStateFlow()

    fun setLook(look: BurnLook) { _state.value = _state.value.copy(look = look, notice = null, frame = null) }
    fun setSize(size: BurnSize) { _state.value = _state.value.copy(size = size, notice = null, frame = null) }
    fun setFit(fit: BurnFit) { _state.value = _state.value.copy(fit = fit, notice = null, frame = null) }

    /** The lines / line length the burn uses now: the owner's choice, else by the open video's frame. */
    fun fit(): BurnFit = _state.value.fit ?: session.open?.project?.subtitles?.video.let { v ->
        BurnFit.auto(v?.width?.takeIf { it > 0 } ?: 1920, v?.height?.takeIf { it > 0 } ?: 1080)
    }

    /**
     * F-6 (D-95): the subtitles of [choice] exactly as Make video will burn them now (look, size, lines, line length;
     * [SubtitleBurn.overlays], the same call as the `.ass`), for the live preview before the render.
     */
    fun previewOverlays(doc: SubtitleDoc, choice: TextChoice): List<Overlay> {
        val video = session.open?.project?.subtitles?.video
        val s = _state.value
        val fit = fit()
        return SubtitleBurn.overlays(SubtitleOps.toSrt(doc, choice), video?.width?.takeIf { it > 0 } ?: 1920,
            video?.height?.takeIf { it > 0 } ?: 1080, "", s.look, s.size, fit.maxChars(config.subtitles), maxLines = fit.lines)
    }

    val runs: StateFlow<List<MediaTaskRun>> get() = tasks.runs

    /** The run writing the last started output, if any (it stays after it ends, so the card shows how). */
    fun run(): MediaTaskRun? = _state.value.output?.let(tasks::forOutput)

    fun cancel() { run()?.let { tasks.cancel(it.id) } }

    fun clearFrame() { _state.value = _state.value.copy(frame = null) }

    val sharesFiles: Boolean get() = share?.sharesFiles ?: false

    /** After Done: play, share, Save to Gallery (phone) or Open folder (desktop) — the finished file only. */
    fun open() = done()?.let { result(share?.open(it, MP4) ?: unsupported(it)) }
    fun share(title: String) = done()?.let { result(share?.share(it, MP4, title) ?: unsupported(it)) }
    fun openFolder() = done()?.let { result(share?.openFolder(it.substringBeforeLast('/')) ?: unsupported(it)) }
    suspend fun saveToGallery() {
        val path = done() ?: return
        when (val r = share?.saveToGallery(path, MP4, path.substringAfterLast('/')) ?: unsupported(path)) {
            is PlatformResult.Ok -> say(UiNotice(UiText.EXPORT_GALLERY_SAVED, "path" to r.value), ok = true)
            is PlatformResult.Failed -> say(UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason))
            is PlatformResult.Unsupported -> say(UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason))
        }
    }

    private fun done(): String? = (run()?.state as? RenderState.Done)?.output
    private fun unsupported(path: String) = PlatformResult.Unsupported("The file is in $path")
    private fun result(r: PlatformResult<Unit>) = when (r) {
        is PlatformResult.Ok -> say(null)
        is PlatformResult.Failed -> say(UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason))
        is PlatformResult.Unsupported -> say(UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason))
    }

    suspend fun start(doc: SubtitleDoc, version: ArtifactId?, choice: TextChoice) {
        if (run()?.state?.finished == false) return
        if (!tasks.available) return say(UiNotice(UiText.RESULT_DETAIL, "detail" to "This app cannot make videos here."))
        val p = prepare(doc, version, choice) ?: return
        val codec = if (!p.video.hasAudio) null else (audioCodec ?: ::probeAudio)(joinPath(p.dir, p.video.file), p.dir)
        val args = SubtitleBurn.args(p.video.file, p.ass, RecapFrames.FONTS_DIR, codec, config.burn.x264Preset, config.burn.crf)
        val task = MediaTaskJob(MediaTaskJob.SUBTITLE_BURN, args, "${SubtitleExport.EXPORTS_DIR}/${p.name}.mp4", p.video.durationMs)
        val started = tasks.start(task, p.dir, "${p.project} · ${UiText.SUB_EXPORT_VIDEO.en}", SUBTITLES_SCREEN) ?: return
        _state.value = _state.value.copy(output = started.output, notice = null)
    }

    /**
     * Check one frame (S-4d): line [line]'s middle frame from the source with the same `.ass` and fonts as Make video
     * (`-ss -copyts`, spec §9 Exact frame), written to `.cache/burn/` and shown before the long render.
     */
    suspend fun checkFrame(doc: SubtitleDoc, version: ArtifactId?, choice: TextChoice, line: Int) {
        val cue = doc.cues.getOrNull(line) ?: return
        if (_state.value.checking) return
        val runner = media?.runner
        val ffmpeg = media?.ffmpeg()
        if (runner == null || ffmpeg == null) return say(UiNotice(UiText.RESULT_DETAIL, "detail" to "ffmpeg is not available here."))
        val p = prepare(doc, version, choice) ?: return
        val at = (cue.startMs + cue.endMs) / 2
        val image = ".cache/burn/${p.name}-$at.png"
        _state.value = _state.value.copy(checking = true, frame = null, notice = null)
        val r = runner.run(listOf(ffmpeg, "-hide_banner") + SubtitleBurn.frameArgs(p.video.file, p.ass, RecapFrames.FONTS_DIR, at, image), p.dir,
            FRAME_TIMEOUT_MS, false) {}
        val bytes = if (r is ToolExec.Done && r.exitCode == 0) runCatching { fs.read(joinPath(p.dir, image)) }.getOrNull() else null
        _state.value = _state.value.copy(checking = false, frame = bytes?.let { BurnFrame(line, at, it) })
        if (bytes == null) say(UiNotice(UiText.RESULT_DETAIL, "detail" to when (r) {
            is ToolExec.Done -> r.tail.lastOrNull { it.isNotBlank() } ?: "ffmpeg exit ${r.exitCode}"
            is ToolExec.CannotStart -> r.message
        }))
    }

    private class Prepared(val dir: String, val project: String, val video: SourceVideo, val name: String, val ass: String)

    /** Pre-flight, the font into `.cache/fonts`, and the `.ass` of this choice in `.cache/burn/`; null = [say] why. */
    private fun prepare(doc: SubtitleDoc, version: ArtifactId?, choice: TextChoice): Prepared? {
        val open = session.open ?: return null
        val video = open.project.subtitles?.video ?: return null
        SubtitleChecks.exportBlocks(doc).firstOrNull()?.let { b ->
            say(UiNotice(if (b == ExportBlock.NO_LINES) UiText.SUB_EXPORT_NO_LINES else UiText.SUB_EXPORT_BAD_TIMES)); return null
        }
        val burmese = choice == TextChoice.BURMESE && doc.cues.any { !it.textMy.isNullOrBlank() }
        val font = frames?.font()
        if (font == null && burmese) { say(UiNotice(UiText.SUB_BURN_NO_FONT)); return null }
        frames?.installFont(open, font)?.let { say(UiNotice(UiText.RESULT_DETAIL, "detail" to it)); return null }
        val s = _state.value
        val fit = s.fit ?: BurnFit.auto(video.width.takeIf { it > 0 } ?: 1920, video.height.takeIf { it > 0 } ?: 1080)
        val name = SubtitleExport.fileName(open.project.name, choice, version?.value.orEmpty(), "mp4").removeSuffix(".mp4") +
            "-${s.look.name.lowercase()}-${s.size.name.lowercase()}-${fit.nameSuffix()}"
        val ass = ".cache/burn/$name.ass"
        return try {
            fs.createDirectories(joinPath(open.dir, ".cache/burn"))
            fs.writeDurably(joinPath(open.dir, ass), SubtitleBurn.ass(SubtitleOps.toSrt(doc, choice), video.width.takeIf { it > 0 } ?: 1920,
                video.height.takeIf { it > 0 } ?: 1080, font?.family ?: "Sans", s.look, s.size, fit.maxChars(config.subtitles),
                font?.let { FontInfo.parse(it.bytes)?.assScale }, maxLines = fit.lines).encodeToByteArray())
            Prepared(open.dir, open.project.name, video, name, ass)
        } catch (e: Exception) {
            say(UiNotice(UiText.RESULT_DETAIL, "detail" to (e.message ?: e::class.simpleName.orEmpty()))); null
        }
    }

    /** The source's sound codec; unknown → null, which copies (an mp4 source from a phone has AAC). */
    private suspend fun probeAudio(file: String, dir: String): String? {
        val runner = media?.runner ?: return null
        val probe = media.ffprobe() ?: return null
        return (MediaProbe(runner, probe).probe(file, dir) as? MediaProbe.Result.Ok)?.info?.audioCodec
    }

    private fun say(notice: UiNotice?, ok: Boolean = false) { _state.value = _state.value.copy(notice = notice, ok = ok) }

    private companion object { const val MP4 = "video/mp4"; const val FRAME_TIMEOUT_MS = 60_000L }
}
