package io.github.alalkipgen.autoflow.ui.dubbing

import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.dubbing.DubExport
import io.github.alalkipgen.autoflow.core.dubbing.DubExportCheck
import io.github.alalkipgen.autoflow.core.dubbing.DubMarks
import io.github.alalkipgen.autoflow.core.dubbing.DubMasks
import io.github.alalkipgen.autoflow.core.getOrNull
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.DubMask
import io.github.alalkipgen.autoflow.core.model.DubMaskKind
import io.github.alalkipgen.autoflow.core.model.DubOriginal
import io.github.alalkipgen.autoflow.core.model.DubWatermark
import io.github.alalkipgen.autoflow.core.model.DubbingTool
import io.github.alalkipgen.autoflow.core.hash.Sha256
import io.github.alalkipgen.autoflow.core.recap.CaptionSize
import io.github.alalkipgen.autoflow.core.recap.CaptionStyle
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import io.github.alalkipgen.autoflow.core.recap.MaskStrength
import io.github.alalkipgen.autoflow.core.recap.WatermarkAnchor
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleExport
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleOps
import io.github.alalkipgen.autoflow.core.subtitles.TextChoice
import io.github.alalkipgen.autoflow.media.audio.DubCue
import io.github.alalkipgen.autoflow.media.audio.DubMixer
import io.github.alalkipgen.autoflow.media.audio.VideoSound
import io.github.alalkipgen.autoflow.media.render.BurnSize
import io.github.alalkipgen.autoflow.media.render.BurnFit
import io.github.alalkipgen.autoflow.media.render.DubMark
import io.github.alalkipgen.autoflow.media.render.DubPicture
import io.github.alalkipgen.autoflow.ui.subtitles.maxChars
import io.github.alalkipgen.autoflow.ui.subtitles.nameSuffix
import io.github.alalkipgen.autoflow.media.render.FontInfo
import io.github.alalkipgen.autoflow.media.render.MediaTaskJob
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.media.render.SubtitleBurn
import io.github.alalkipgen.autoflow.media.render.Overlay
import io.github.alalkipgen.autoflow.platform.FilePicker
import io.github.alalkipgen.autoflow.platform.FileShare
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.export.RecapFrames
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiNotice
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.library.LibraryModel
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
import kotlin.math.roundToInt

/** A Brand Kit image Style can use as the watermark (D-108): the kit's watermark, or its logo when [logo]. */
data class DubKitImage(val kitId: String, val name: String, val logo: Boolean)

/** A line on the preview's scrubber (Group 4 🆕-1): its number, its subtitle time and how it sounds now. */
data class DubTick(val n: Int, val startMs: Long, val endMs: Long, val kind: DubTickKind, val voiceEndMs: Long = startMs)
enum class DubTickKind { VOICED, NO_VOICE, TOO_LONG }

/** Export (D-3b): the runs of the last start in order ([runs], of [total] tasks), the files it makes, and why it stopped. */
data class DubExportUi(val dir: String? = null, val wav: Boolean = false, val runs: List<String> = emptyList(), val total: Int = 0,
    val srt: String? = null, val voice: String? = null, val busy: Boolean = false, val notice: UiNotice? = null, val ok: Boolean = false,
    /** Burned Burmese subtitles: lines / line length (null = [BurnFit.auto] by the video's frame); the size is in the caption style. */
    val fit: BurnFit? = null,
    /** Style: what Save caption style… answered (saved to the Library, or why not). */
    val styleNotice: UiNotice? = null, val styleOk: Boolean = false,
    /** Style: the blur / colour box picked on the preview or in its card (index in `masks`), null = none. */
    val box: Int? = null,
    /** Style: what setting the watermark answered (set, or why not). */
    val markNotice: UiNotice? = null, val markOk: Boolean = false)

/**
 * The Export step (specs/dubbing.md §2, §6): pre-flight ([DubExport.check]), then the [DubMixer] tasks one after
 * another on the render queue — stems, the voice track, the video (picture copied, or the boxes and Burmese subtitles drawn) —
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
    /** The owner's UI language: the Jobs title of each step is written in it (§8.2), not always in English. */
    private val uiLanguage: () -> UiLanguage = { UiLanguage.ENGLISH },
    /** Save caption style… keeps the style in the Library, shared with Movie Recap (D-106). */
    private val library: LibraryModel? = null,
    /** Set watermark… chooses the image (D-108). */
    private val picker: FilePicker? = null,
) {
    private val _state = MutableStateFlow(DubExportUi())
    val state: StateFlow<DubExportUi> = _state.asStateFlow()
    val runs: StateFlow<List<MediaTaskRun>> get() = tasks.runs
    val sharesFiles: Boolean get() = share?.sharesFiles ?: false

    fun check(open: OpenProject?, doc: SubtitleDoc): DubExportCheck? = open?.project?.subtitles?.video?.let {
        DubExport.check(open.project.dubbing, doc, it.durationMs, config.dubbing.minGapMs, config.dubbing.maxVoiceSpeed, lexicon())
    }

    /** The lines that have something to say (as [check] counts them): "All 40 lines have a voice" on the checklist. */
    fun spokenLines(doc: SubtitleDoc): Int = lexicon().let { lex -> doc.cues.count { io.github.alalkipgen.autoflow.core.dubbing.DubRules.sayText(it, lex).isNotEmpty() } }

    /** Burmese subtitles on the video: saved in the project (`burnSubtitles`). */
    fun setBurn(on: Boolean) {
        val open = session.open ?: return
        if (_state.value.busy) return
        session.update(open.dir, open.project.id) { p -> p.copy(dubbing = (p.dubbing ?: DubbingTool()).copy(burnSubtitles = on)) }
    }

    fun setWav(on: Boolean) = edit { it.copy(wav = on) }

    /** How the burned subtitles look (D-106): saved in the project (`captionStyle`); the default = boxed white Medium. */
    fun style(open: OpenProject? = session.open): CaptionStyle = open?.project?.dubbing?.captionStyle ?: DubbingTool.DEFAULT_CAPTIONS

    /** A new caption style for the open project (saved); none while an export runs. */
    fun setCaptionStyle(f: (CaptionStyle) -> CaptionStyle) {
        val open = session.open ?: return
        if (_state.value.busy) return
        session.update(open.dir, open.project.id) { p ->
            val tool = p.dubbing ?: DubbingTool()
            p.copy(dubbing = tool.copy(captionStyle = f(tool.captionStyle)))
        }
    }

    /** Burned subtitles' text size Small / Medium / Large (saved in the caption style; clears a custom factor). */
    fun setSize(size: BurnSize) = setCaptionStyle {
        it.copy(size = when (size) { BurnSize.SMALL -> CaptionSize.S; BurnSize.MEDIUM -> CaptionSize.M; BurnSize.LARGE -> CaptionSize.L },
            customSizeFactor = null)
    }

    /** A free `#RRGGBB` colour (null or not a colour = back to the chosen one). */
    fun setCustomColour(hex: String?) = setCaptionStyle { it.copy(customColour = CaptionStyles.hex(hex)) }

    /** A custom size factor, 0.5–3.0 (null or not a number = back to Small / Medium / Large). */
    fun setCustomSize(factor: Float?) = setCaptionStyle { it.copy(customSizeFactor = factor?.takeIf { f -> f.isFinite() }?.coerceIn(0.5f, 3f)) }

    /**
     * Save caption style… (D-106): the open project's caption style into the Library as a caption preset, the same
     * kind Movie Recap saves, so either tool can use it. The answer shows on Style.
     */
    fun saveCaptionStyle(name: String) {
        val dir = session.open?.dir ?: return
        val lib = library ?: return styleSaid(dir, UiNotice(UiText.LIBRARY_UNAVAILABLE), false)
        lib.saveCaptionPreset(name)
        val answer = lib.state.value
        styleSaid(dir, answer.notice ?: UiNotice(UiText.LIBRARY_UNAVAILABLE), answer.messageOk)
    }

    private fun styleSaid(dir: String, notice: UiNotice, ok: Boolean) =
        _state.update { (if (it.dir == dir) it else DubExportUi(dir)).copy(styleNotice = notice, styleOk = ok) }

    /** A change to the open project's Dubbing choices (saved); none while an export runs. */
    private fun setTool(f: (DubbingTool) -> DubbingTool) {
        val open = session.open ?: return
        if (_state.value.busy) return
        session.update(open.dir, open.project.id) { p -> p.copy(dubbing = f(p.dubbing ?: DubbingTool())) }
    }

    /** The blur / colour boxes over the video (D-107), saved in the project. */
    fun masks(open: OpenProject? = session.open): List<DubMask> = open?.project?.dubbing?.masks.orEmpty()

    private fun setMasks(f: (List<DubMask>) -> List<DubMask>) = setTool { it.copy(masks = f(it.masks)) }

    /** A strip / logo corner on or off; [DubMaskKind.CUSTOM] adds one more box (and picks it). */
    fun toggleBox(kind: DubMaskKind) {
        setMasks { DubMasks.toggle(it, kind) }
        val added = masks().indexOfLast { it.kind == kind }.takeIf { it >= 0 }
        select(if (kind == DubMaskKind.CUSTOM) added else _state.value.box?.takeIf { it < masks().size })
    }

    fun removeBox(index: Int) {
        setMasks { DubMasks.remove(it, index) }
        select(null)
    }

    fun moveBox(index: Int, dx: Float, dy: Float) = setMasks { DubMasks.move(it, index, dx, dy) }
    fun resizeBox(index: Int, dw: Float, dh: Float) = setMasks { DubMasks.resize(it, index, dw, dh) }

    /** Box [index] at [rect] (a drag / pinch on the preview), kept inside the picture. */
    fun setBoxRect(index: Int, rect: List<Float>) = setMasks { DubMasks.setRect(it, index, rect) }

    /** Box [index] blurred (null) or filled with `#RRGGBB` [fill]. */
    fun fillBox(index: Int, fill: String?) = setMasks { DubMasks.fill(it, index, fill) }

    fun setBlurStrength(strength: MaskStrength) = setTool { it.copy(blurStrength = strength) }

    /** The Burmese subtitles on the lowest box in the lower half (the old subtitles' band), or in their usual place. */
    fun setCaptionsOnBox(on: Boolean) = setTool { it.copy(captionsOnBox = on) }

    /** The box picked on the preview / in the card (null = none); kept per project, not saved. */
    fun select(index: Int?) {
        val dir = session.open?.dir ?: return
        _state.update { (if (it.dir == dir) it else DubExportUi(dir)).copy(box = index) }
    }

    /** The watermark / logo on every frame (D-108), saved in the project; null = none. */
    fun watermark(open: OpenProject? = session.open): DubWatermark? = open?.project?.dubbing?.watermark

    /** The Brand Kits that have a watermark or a logo image (Use … on Style takes that image, nothing else of the kit). */
    fun kitImages(): List<DubKitImage> = library?.let { it.state.value.library.brandKits }.orEmpty().flatMap { k ->
        listOfNotNull(k.watermark?.let { DubKitImage(k.id, k.name, false) }, k.logo?.let { DubKitImage(k.id, k.name, true) })
    }

    /**
     * Set watermark… (D-108): an image chosen with the file picker, copied into the project as
     * `library/watermark-<hash>.<ext>` (a new image = a new name, so the export is made again), size and place kept.
     */
    fun importWatermark(): Job? {
        val dir = session.open?.dir ?: return null
        val p = picker ?: return markSaid(dir, UiNotice(UiText.CHOOSE_FILES_UNAVAILABLE), false)
        if (_state.value.busy) return null
        return scope.launch {
            val from = when (val r = p.pickFiles(UiText.PICK_RECAP_WATERMARK.text(uiLanguage()), DubMarks.EXTENSIONS)) {
                is PlatformResult.Ok -> r.value.firstOrNull() ?: return@launch
                is PlatformResult.Failed -> { markSaid(dir, UiNotice(UiText.WATERMARK_SAVE_FAILED, "detail" to r.reason), false); return@launch }
                is PlatformResult.Unsupported -> { markSaid(dir, UiNotice(UiText.CHOOSE_FILES_UNAVAILABLE), false); return@launch }
            }
            val bytes = session.readFile(from)?.takeIf { it.isNotEmpty() }
            if (bytes == null) { markSaid(dir, UiNotice(UiText.CANNOT_READ_FILE), false); return@launch }
            setWatermarkImage(dir, bytes, DubMarks.ext(from))
        }
    }

    /** Use a Brand Kit's watermark (or its [logo]) image as this dub's watermark (only the image, and the kit's width). */
    fun useKitImage(kitId: String, logo: Boolean) {
        val dir = session.open?.dir ?: return
        val lib = library ?: run { markSaid(dir, UiNotice(UiText.LIBRARY_UNAVAILABLE), false); return }
        val kit = lib.state.value.library.brandKits.firstOrNull { it.id == kitId } ?: run { markSaid(dir, UiNotice(UiText.V3_LIBRARY_NOT_FOUND), false); return }
        val rel = (if (logo) kit.logo else kit.watermark) ?: run { markSaid(dir, UiNotice(UiText.V3_LIBRARY_NOT_FOUND), false); return }
        val bytes = runCatching { fs.read(lib.assetPath(rel)) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: run { markSaid(dir, UiNotice(UiText.CANNOT_READ_FILE), false); return }
        setWatermarkImage(dir, bytes, DubMarks.ext(rel), kit.watermarkWidthPercent.takeUnless { logo })
    }

    private fun setWatermarkImage(dir: String, bytes: ByteArray, ext: String, width: Int? = null) {
        if (session.open?.dir != dir || _state.value.busy) return
        val rel = DubMarks.file(Sha256.hex(bytes), ext)
        val failed = runCatching { fs.createDirectories(joinPath(dir, "library")); fs.writeDurably(joinPath(dir, rel), bytes) }.exceptionOrNull()
        if (failed != null) { markSaid(dir, UiNotice(UiText.WATERMARK_SAVE_FAILED, "detail" to failed.message), false); return }
        setTool { t ->
            val old = t.watermark
            t.copy(watermark = DubWatermark(rel, old?.anchor ?: WatermarkAnchor.BOTTOM_RIGHT, DubMarks.width(width ?: old?.widthPercent ?: io.github.alalkipgen.autoflow.core.recap.WatermarkSize.MEDIUM)))
        }
        markSaid(dir, UiNotice(UiText.WATERMARK_SET, "name" to rel.substringAfterLast('/')), true)
    }

    fun removeWatermark() = setTool { it.copy(watermark = null) }.also { session.open?.dir?.let { d -> markSaid(d, null, false) } }

    fun setWatermarkWidth(percent: Int) = setTool { t -> t.copy(watermark = t.watermark?.copy(widthPercent = DubMarks.width(percent))) }

    fun setWatermarkAnchor(anchor: WatermarkAnchor) = setTool { t -> t.copy(watermark = t.watermark?.copy(anchor = anchor)) }

    /** The watermark's image for the preview (null = none, or it cannot be read). */
    fun watermarkBytes(open: OpenProject?): ByteArray? = open?.project?.dubbing?.watermark?.let { w ->
        runCatching { fs.read(joinPath(open.dir, w.file)) }.getOrNull()
    }

    private fun markSaid(dir: String, notice: UiNotice?, ok: Boolean): Job? {
        _state.update { (if (it.dir == dir) it else DubExportUi(dir)).copy(markNotice = notice, markOk = ok) }
        return null
    }

    /** Burned subtitles' lines on screen and line length (this export only). */
    fun setFit(fit: BurnFit) = edit { it.copy(fit = fit) }

    /** The lines / line length the burn uses now: the owner's choice, else by the open video's frame. */
    fun fit(open: OpenProject? = session.open): BurnFit = _state.value.takeIf { it.dir == open?.dir }?.fit ?: autoFit(open)

    private fun autoFit(open: OpenProject?): BurnFit = open?.project?.subtitles?.video.let { v ->
        BurnFit.auto(v?.width?.takeIf { it > 0 } ?: 1920, v?.height?.takeIf { it > 0 } ?: 1080)
    }

    /**
     * F-6 (D-95): the dub's voice before Export — every line that has a current take, where the mix puts it
     * ([DubExport.voiced]: from the line's start, lead skipped, at its speed, cut at its slot), as absolute paths.
     */
    fun previewVoice(open: OpenProject?, doc: SubtitleDoc): List<DubCue> {
        val video = open?.project?.subtitles?.video ?: return emptyList()
        val store = session.artifacts()?.takeIf { session.open?.dir == open.dir } ?: return emptyList()
        val dir = joinPath(open.dir, ArtifactStore.ARTIFACTS_DIR)
        return DubExport.voiced(open.project.dubbing, doc, video.durationMs, config.dubbing.minGapMs, config.dubbing.maxVoiceSpeed, lexicon())
            .mapNotNull { l -> store.get(l.au).getOrNull()?.let { DubCue(joinPath(dir, it.file), l.atMs, l.leadMs, l.slotMs, l.speed) } }
            .filter { fs.exists(it.file) }
    }

    /**
     * Group 4 🆕-1: one scrubber tick per line of the dub script (its start), with how the line sounds now —
     * [DubTickKind.NO_VOICE] without a current take, [DubTickKind.TOO_LONG] when the take does not fit even at the top speed.
     * Read only (the same [DubRules] / [DubFit] the Voice step shows).
     */
    fun previewTicks(open: OpenProject?, doc: SubtitleDoc): List<DubTick> {
        val tool = open?.project?.dubbing
        val slots = io.github.alalkipgen.autoflow.core.dubbing.DubRules.slots(doc, open?.project?.subtitles?.video?.durationMs ?: 0, config.dubbing.minGapMs)
        val lex = lexicon()
        return doc.cues.mapIndexed { i, c ->
            val take = io.github.alalkipgen.autoflow.core.dubbing.DubRules.currentTake(tool, c, lex)
            val fit = take?.let { io.github.alalkipgen.autoflow.core.dubbing.DubFit.of(it.ms, c.endMs - c.startMs, slots[i], config.dubbing.maxVoiceSpeed) }
            val kind = when {
                take == null || fit == null -> DubTickKind.NO_VOICE
                fit.kind == io.github.alalkipgen.autoflow.core.dubbing.DubFitKind.TOO_LONG -> DubTickKind.TOO_LONG
                else -> DubTickKind.VOICED
            }
            // Where the voice stops in the mix: its length at its speed, cut at the line's slot (as the mixer cuts it).
            val voiceEnd = if (take == null || fit == null) c.startMs else c.startMs + minOf((take.ms / fit.speed).toLong(), slots[i])
            DubTick(i + 1, c.startMs, c.endMs, kind, voiceEnd)
        }
    }

    /** F-6: the Burmese subtitles exactly as the burn draws them now (the caption style, lines and line length). */
    fun previewCaptions(open: OpenProject?, doc: SubtitleDoc): List<Overlay> {
        val video = open?.project?.subtitles?.video ?: return emptyList()
        val fit = fit(open)
        return SubtitleBurn.overlays(SubtitleOps.toSrt(doc, TextChoice.BURMESE), video.width.takeIf { it > 0 } ?: 1920,
            video.height.takeIf { it > 0 } ?: 1080, "", style(open), fit.maxChars(config.subtitles), maxLines = fit.lines,
            centerY = DubMasks.captionY(open.project.dubbing))
    }

    /** F-6: the movie's own sound in the mix, 0–1 (0 = muted), as chosen on Voice. */
    fun previewOriginal(open: OpenProject?): Float = open?.project?.dubbing?.original
        ?.let { if (it.mode == DubOriginal.MUTE) 0f else it.volume.toFloat() } ?: DubOriginal().volume.toFloat()

    /** A choice for the open project (another project's state starts fresh); none while an export runs. */
    private fun edit(f: (DubExportUi) -> DubExportUi) {
        val dir = session.open?.dir ?: return
        if (_state.value.busy) return
        _state.update { cur -> f(if (cur.dir == dir) cur else DubExportUi(dir)).copy(notice = null, styleNotice = null) }
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
        if (!tasks.available) return say(open.dir, UiNotice(UiText.V3_DUB_CANNOT_MAKE)) // §8.1: in the owner's language
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
        // D-108: the watermark's image must still be in the project.
        val mark = tool.watermark?.let { w ->
            if (!fs.exists(joinPath(open.dir, w.file))) return say(open.dir, UiNotice(UiText.DUB_WATERMARK_MISSING))
            DubMark(w.file, DubMarks.widthPx(w.widthPercent, video.width.takeIf { it > 0 } ?: 1280), w.anchor)
        }
        // D-107 / D-108: the boxes, the watermark, then the subtitles; nothing drawn = the picture copied as before.
        val picture = DubPicture(tool.masks, DubMasks.sigma(tool.blurStrength, video.height.takeIf { it > 0 } ?: 1080), ass, RecapFrames.FONTS_DIR, mark)
        val ext = if (picture.drawn) "mp4" else VideoSound.videoExt(video.file.substringAfterLast('.', "mp4"))
        val volume = if (tool.original.mode == DubOriginal.MUTE) 0.0 else tool.original.volume
        val args = DubMixer.exportArgs(video.file, voice.voice.output, video.durationMs, volume, video.hasAudio, ext, config.tts.m4aBitrate,
            picture, config.burn.x264Preset, config.burn.crf)
        val all = voice.tasks + listOfNotNull(keep) + MediaTaskJob(DubMixer.EXPORT, args, "${SubtitleExport.EXPORTS_DIR}/$base-dub-${DubMixer.key(args).take(8)}.$ext", video.durationMs)
        val srt = writeSrt(open, doc, "$base.srt") ?: return null
        val kept = _state.value.takeIf { it.dir == open.dir }
        _state.value = DubExportUi(open.dir, wav, total = all.size, srt = srt,
            voice = keep?.let { MediaTaskJob.absolute(open.dir, it.output) }, busy = true, fit = kept?.fit)
        return scope.launch {
            for (t in all) {
                // §8.2: the Jobs / notification title in the owner's language (was always `.en`).
                val title = "${open.project.name} · ${(if (t.kind == DubMixer.EXPORT) UiText.DUB_EXPORT_MAKING else UiText.DUB_EXPORT_MIXING).text(uiLanguage())}"
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
     * The font into `.cache/fonts` and the Burmese `.ass` (as Subtitles' burn, in the caption style, lines and line
     * length — named by them, so another choice makes another video; the default style keeps its old name); null = [say] why.
     */
    private fun burnAss(open: OpenProject, doc: SubtitleDoc, base: String): String? {
        val video = open.project.subtitles?.video ?: return null
        val font = frames?.font() ?: return say(open.dir, UiNotice(UiText.DUB_BURN_NO_FONT)).let { null }
        frames.installFont(open, font)?.let { return say(open.dir, UiNotice(UiText.RESULT_DETAIL, "detail" to it)).let { null } }
        val style = style(open)
        val fit = fit(open)
        val centerY = DubMasks.captionY(open.project.dubbing)
        val ass = ".cache/burn/$base-dub-${CaptionStyles.sizeName(style)}-${fit.nameSuffix()}${CaptionStyles.lookSuffix(style)}" +
            "${centerY?.let { "-y${(it * 1000).roundToInt()}" }.orEmpty()}.ass"
        return try {
            fs.createDirectories(joinPath(open.dir, ".cache/burn"))
            fs.writeDurably(joinPath(open.dir, ass), SubtitleBurn.ass(SubtitleOps.toSrt(doc, TextChoice.BURMESE), video.width.takeIf { it > 0 } ?: 1920,
                video.height.takeIf { it > 0 } ?: 1080, font.family, style, fit.maxChars(config.subtitles),
                FontInfo.parse(font.bytes)?.assScale, maxLines = fit.lines, centerY = centerY).encodeToByteArray())
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

/** Dubbing caption style helpers (D-106): the hex check, and the parts of the burn's name. */
internal object CaptionStyles {
    /** `#RRGGBB` (with or without `#`), else null. */
    fun hex(text: String?): String? = text?.trim()?.let { if (it.startsWith("#")) it else "#$it" }?.takeIf { it.matches(Regex("#[0-9A-Fa-f]{6}")) }?.uppercase()

    /** `small` / `medium` / `large` as before, or `x1.4` for a custom factor. */
    fun sizeName(style: CaptionStyle): String = style.customSizeFactor?.let { "x${(style.effectiveSizeFactor * 100).roundToInt() / 100.0}" } ?: when (style.size) {
        CaptionSize.S -> "small"
        CaptionSize.M -> "medium"
        CaptionSize.L -> "large"
    }

    /** Nothing for the default look (white on the box), else `-` and 8 hex of the look, so each look is its own video. */
    fun lookSuffix(style: CaptionStyle): String {
        val look = style.copy(size = CaptionSize.M, customSizeFactor = null)
        if (look == DubbingTool.DEFAULT_CAPTIONS) return ""
        return "-" + Sha256.hex("${look.effectiveColour}|${look.box}|${look.strokeWidth}|${look.strokeColour}".encodeToByteArray()).take(8)
    }
}
