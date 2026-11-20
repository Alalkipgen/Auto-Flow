package io.github.alalkipgen.autoflow.ui.subtitles

import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.subtitles.ExportBlock
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleChecks
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleExport
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleOps
import io.github.alalkipgen.autoflow.core.subtitles.TextChoice
import io.github.alalkipgen.autoflow.media.probe.MediaProbe
import io.github.alalkipgen.autoflow.media.render.BurnLook
import io.github.alalkipgen.autoflow.media.render.BurnSize
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
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.shell.SUBTITLES_SCREEN
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The video part of the export dialog: look, size, the last started burn ([output]) and why one did not start. */
data class SubtitleBurnUi(val look: BurnLook = BurnLook.CLEAN, val size: BurnSize = BurnSize.MEDIUM, val output: String? = null,
    val notice: UiNotice? = null, val ok: Boolean = false)

/**
 * "Video with subtitles" (specs/quick-tools.md §5 Export, §9 Burn, S-4c): the saved version in the chosen language
 * burned into the source video as a [MediaTaskJob] — Jobs row, real progress, Cancel. Pre-flight: no lines / bad
 * times (as `.srt`), and a Burmese burn needs a Burmese font (Settings → Fonts or built in). The font is copied into
 * the project's `.cache/fonts` like the Recap export; the `.ass` goes to `.cache/burn/` (not an artifact). Output
 * `exports/<project>-<lang>-<version>-<look>-<size>.mp4`: the same choice gives the same file, never overwritten.
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

    fun setLook(look: BurnLook) { _state.value = _state.value.copy(look = look, notice = null) }
    fun setSize(size: BurnSize) { _state.value = _state.value.copy(size = size, notice = null) }

    val runs: StateFlow<List<MediaTaskRun>> get() = tasks.runs

    /** The run writing the last started output, if any (it stays after it ends, so the card shows how). */
    fun run(): MediaTaskRun? = _state.value.output?.let(tasks::forOutput)

    fun cancel() { run()?.let { tasks.cancel(it.id) } }

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
        val open = session.open ?: return
        val video = open.project.subtitles?.video ?: return
        if (run()?.state?.finished == false) return
        SubtitleChecks.exportBlocks(doc).firstOrNull()?.let { b ->
            return say(UiNotice(if (b == ExportBlock.NO_LINES) UiText.SUB_EXPORT_NO_LINES else UiText.SUB_EXPORT_BAD_TIMES))
        }
        val burmese = choice == TextChoice.BURMESE && doc.cues.any { !it.textMy.isNullOrBlank() }
        val font = frames?.font()
        if (font == null && burmese) return say(UiNotice(UiText.SUB_BURN_NO_FONT))
        frames?.installFont(open, font)?.let { return say(UiNotice(UiText.RESULT_DETAIL, "detail" to it)) }
        if (!tasks.available) return say(UiNotice(UiText.RESULT_DETAIL, "detail" to "This app cannot make videos here."))
        val s = _state.value
        val name = SubtitleExport.fileName(open.project.name, choice, version?.value.orEmpty(), "mp4").removeSuffix(".mp4") +
            "-${s.look.name.lowercase()}-${s.size.name.lowercase()}"
        val ass = ".cache/burn/$name.ass"
        val width = video.width.takeIf { it > 0 } ?: 1920
        val height = video.height.takeIf { it > 0 } ?: 1080
        try {
            fs.createDirectories(joinPath(open.dir, ".cache/burn"))
            fs.writeDurably(joinPath(open.dir, ass), SubtitleBurn.ass(SubtitleOps.toSrt(doc, choice), width, height, font?.family ?: "Sans",
                s.look, s.size, config.subtitles.maxCharsPerLine, font?.let { FontInfo.parse(it.bytes)?.assScale }).encodeToByteArray())
        } catch (e: Exception) {
            return say(UiNotice(UiText.RESULT_DETAIL, "detail" to (e.message ?: e::class.simpleName.orEmpty())))
        }
        val codec = if (!video.hasAudio) null else (audioCodec ?: ::probeAudio)(joinPath(open.dir, video.file), open.dir)
        val args = SubtitleBurn.args(video.file, ass, RecapFrames.FONTS_DIR, codec, config.burn.x264Preset, config.burn.crf)
        val task = MediaTaskJob(MediaTaskJob.SUBTITLE_BURN, args, "${SubtitleExport.EXPORTS_DIR}/$name.mp4", video.durationMs)
        val started = tasks.start(task, open.dir, "${open.project.name} · ${UiText.SUB_EXPORT_VIDEO.en}", SUBTITLES_SCREEN) ?: return
        _state.value = _state.value.copy(output = started.output, notice = null)
    }

    /** The source's sound codec; unknown → null, which copies (an mp4 source from a phone has AAC). */
    private suspend fun probeAudio(file: String, dir: String): String? {
        val runner = media?.runner ?: return null
        val probe = media.ffprobe() ?: return null
        return (MediaProbe(runner, probe).probe(file, dir) as? MediaProbe.Result.Ok)?.info?.audioCodec
    }

    private fun say(notice: UiNotice?, ok: Boolean = false) { _state.value = _state.value.copy(notice = notice, ok = ok) }

    private companion object { const val MP4 = "video/mp4" }
}
