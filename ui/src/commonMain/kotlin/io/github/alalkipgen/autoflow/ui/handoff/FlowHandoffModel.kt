package io.github.alalkipgen.autoflow.ui.handoff

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.CreditEntry
import io.github.alalkipgen.autoflow.core.model.FlowCredits
import io.github.alalkipgen.autoflow.core.model.HandoffState
import io.github.alalkipgen.autoflow.core.model.Pass
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.Shot
import io.github.alalkipgen.autoflow.core.model.ShotSpec
import io.github.alalkipgen.autoflow.core.prompt.PromptIR
import io.github.alalkipgen.autoflow.core.prompt.VeoFlowDialect
import io.github.alalkipgen.autoflow.core.workflow.Inputs
import io.github.alalkipgen.autoflow.core.workflow.ShotStatus
import io.github.alalkipgen.autoflow.core.workflow.WorkflowRules
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.media.probe.FrameGrab
import io.github.alalkipgen.autoflow.platform.Clipboard
import io.github.alalkipgen.autoflow.platform.FileShare
import io.github.alalkipgen.autoflow.platform.FilePicker
import io.github.alalkipgen.autoflow.platform.UrlOpener
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.shots.ShotsModel
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One row on the Flow Handoff screen. */
data class HandoffRow(
    val number: Int,
    val timecode: String,
    val lyric: String,
    val promptText: String?,
    val pass: Pass,
    val state: HandoffState,
    val status: ShotStatus,
    val hasRef: Boolean,
    val refApproved: Boolean,
    val videoCount: Int,
    val accepted: Boolean,
    /** Gate message when Copy Prompt is blocked. */
    val gateMsg: String?,
    /** Flow credits logged for this shot so far. */
    val creditsLogged: Int = 0,
    /** Ask "Credits used?" — a video was imported and not logged or skipped yet (specs/flow-handoff.md §Credit log). */
    val askCredits: Boolean = false,
    /** The suggested value for this shot's pass: Draft → Fast hint, Final → Quality hint. */
    val creditDefault: Int = 20,
    /** Pending for the batch sheet: compiled prompt, handoff gate passed, no accepted video yet. */
    val batchable: Boolean = false,
    /** Ticked for the next batch sheet (pending shots start ticked). */
    val inBatch: Boolean = false,
    /** The chosen reference image, for the card's image thumbnail; null → "No reference image". */
    val refImage: ArtifactId? = null,
    /** The video shown on the card: the accepted one, else the newest import; null → "No video yet". */
    val shownVideo: ArtifactId? = null,
)

/** One line of the manual credit log, newest first; [index] is its position in `flowCredits.manualLog`. */
data class CreditLogRow(val index: Int, val date: String, val shot: Int?, val credits: Int)

data class FlowHandoffUiState(
    val projectName: String? = null,
    val rows: List<HandoffRow> = emptyList(),
    val selected: Int? = null,
    val message: String? = null,
    val flowUrl: String = "https://labs.google/fx/tools/flow",
    /** Hint credit values from models.json. */
    val creditHintFast: Int = 20,
    val creditHintQuality: Int = 100,
    /** Credits logged on the current quota day (Pacific midnight, like the Gemini quota) and in its month. */
    val creditsToday: Int = 0,
    val creditsMonth: Int = 0,
    /** This month's log, newest first. */
    val creditLog: List<CreditLogRow> = emptyList(),
    /** Shots ticked for the batch sheet. */
    val batchCount: Int = 0,
    /** The last batch sheet written, relative to the project folder (`packages/batch-….html`). */
    val lastBatch: String? = null,
)

/**
 * Flow Handoff screen logic (specs/flow-handoff.md): copy prompt → open Flow → import video → accept.
 * File bytes are read by the caller (platform-specific) and passed to [importVideo].
 */
class FlowHandoffModel(
    private val session: ProjectSession,
    private val clipboard: Clipboard,
    private val urlOpener: UrlOpener,
    private val filePicker: FilePicker,
    private val flowUrl: String = "https://labs.google/fx/tools/flow",
    private val creditHintFast: Int = 20,
    private val creditHintQuality: Int = 100,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val fs: FileSystem? = null,
    private val fileShare: FileShare? = null,
    private val media: MediaTools? = null,
) {
    private val thumbs = Mutex()
    private data class BatchLocal(val unticked: Set<Int> = emptySet(), val last: String? = null)
    private val _batch = MutableStateFlow(BatchLocal())
    private val _selected = MutableStateFlow<Int?>(null)
    private val _message = MutableStateFlow<String?>(null)
    /** Shot number → video count when the user chose Skip; the question comes back after the next import. */
    private val _skipped = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val message = _message.asStateFlow()
    val state: Flow<FlowHandoffUiState> =
        combine(session.state, _selected, _message, _skipped, _batch) { open, _, _, _, _ -> build(open?.project) }

    fun current(): FlowHandoffUiState = build(session.open?.project)

    fun select(number: Int?) {
        _selected.value = number
        _message.value = null
    }

    /** Copy the Veo/Flow prompt for shot [number] to clipboard; set state → SENT_TO_FLOW. */
    fun copyPrompt(number: Int) {
        val project = session.open?.project ?: run { _message.value = "No project open"; return }
        val shot = project.shots.firstOrNull { it.number == number } ?: run { _message.value = "Shot $number not found"; return }
        val ir = readPromptIr(shot) ?: run { _message.value = "No compiled prompt for shot $number — compile it on the Shots screen first"; return }
        val prompt = VeoFlowDialect.render(ir)
        when (val r = clipboard.copyText(prompt)) {
            is io.github.alalkipgen.autoflow.platform.PlatformResult.Ok -> { /* ok */ }
            is io.github.alalkipgen.autoflow.platform.PlatformResult.Failed -> { _message.value = "Could not copy: ${r.reason}"; return }
            is io.github.alalkipgen.autoflow.platform.PlatformResult.Unsupported -> { _message.value = "Clipboard not available"; return }
        }
        val updated = project.copy(shots = project.shots.map { s ->
            if (s.number == number && s.handoff.state == HandoffState.NONE)
                s.copy(handoff = s.handoff.copy(state = HandoffState.SENT_TO_FLOW, sentAt = Clock.System.now()))
            else s
        })
        session.save(updated)
        _message.value = "Prompt copied! Now paste it in Flow."
    }

    /** Open the Flow website. */
    fun openFlow() {
        urlOpener.open(flowUrl)
    }

    /** Set pass (DRAFT/FINAL) for shot [number]. */
    fun setPass(number: Int, pass: Pass) {
        val project = session.open?.project ?: return
        val shots = project.shots.map { if (it.number == number) it.copy(handoff = it.handoff.copy(pass = pass)) else it }
        session.save(project.copy(shots = shots))
    }

    /**
     * Pick a video file and import it for shot [number].
     * [readBytes]: caller provides the bytes from the picked path (so the model stays testable).
     */
    suspend fun pickAndImportVideo(number: Int, readBytes: suspend (String) -> ByteArray?) {
        val project = session.open?.project ?: run { _message.value = "No project open"; return }
        val shot = project.shots.firstOrNull { it.number == number } ?: run { _message.value = "Shot $number not found"; return }
        val result = filePicker.pickFiles("Import video for Shot $number", extensions = setOf("mp4", "mov"), multiple = false)
        val path = when (result) {
            is io.github.alalkipgen.autoflow.platform.PlatformResult.Ok -> result.value.firstOrNull() ?: return
            is io.github.alalkipgen.autoflow.platform.PlatformResult.Failed -> { _message.value = "File pick failed: ${result.reason}"; return }
            is io.github.alalkipgen.autoflow.platform.PlatformResult.Unsupported -> { _message.value = "File picker not available on this platform yet"; return }
        }
        val bytes = readBytes(path) ?: run { _message.value = "Could not read the file"; return }
        val ext = path.substringAfterLast('.').lowercase().let { if (it in setOf("mp4", "mov")) it else "mp4" }
        importVideoBytes(project, shot, bytes, ext)
    }

    /** Import [bytes] directly (used in tests or when the caller already has the bytes). */
    fun importVideoBytes(project: Project, shot: Shot, bytes: ByteArray, ext: String) {
        val artifacts = session.artifacts() ?: run { _message.value = "No project open"; return }
        val inputs = buildMap {
            shot.promptIr?.let { put(Inputs.PROMPT_IR, it.value) }
            shot.chosenRef?.let { put(Inputs.REF, it.value) }
        }
        val artifact = when (val r = artifacts.put(bytes, ArtifactKind.VIDEO, ext, shot.number, inputs, provider = "user", note = shot.handoff.pass.name.lowercase())) {
            is CoreResult.Ok -> r.value
            is CoreResult.Err -> { _message.value = "Failed to save video: ${r.error.message}"; return }
        }
        val newHandoff = shot.handoff.copy(state = HandoffState.IMPORTED)
        val shots = project.shots.map { s ->
            if (s.number == shot.number)
                s.copy(videos = (s.videos + artifact.id).distinct(), handoff = newHandoff)
            else s
        }
        when (val r = session.save(project.copy(shots = shots))) {
            is CoreResult.Ok -> _message.value = "Video imported for Shot ${shot.number}."
            is CoreResult.Err -> _message.value = "Save failed: ${r.error.message}"
        }
    }

    /** Accept the latest imported video for shot [number]. */
    fun acceptVideo(number: Int) {
        val project = session.open?.project ?: return
        val shot = project.shots.firstOrNull { it.number == number } ?: return
        val latest = shot.videos.lastOrNull() ?: run { _message.value = "No video imported for Shot $number"; return }
        val shots = project.shots.map { s ->
            if (s.number == number)
                s.copy(acceptedVideo = latest, handoff = s.handoff.copy(state = HandoffState.ACCEPTED),
                    approvals = s.approvals.copy(video = true))
            else s
        }
        session.save(project.copy(shots = shots))
        _message.value = "Shot $number accepted ✓"
    }

    /** Log [credits] Flow credits used for shot [number] (manual, specs/flow-handoff.md §Credit log). */
    fun logCredits(number: Int, credits: Int) {
        val project = session.open?.project ?: return
        if (credits !in 1..MAX_CREDITS) { _message.value = "Credits must be a number from 1 to $MAX_CREDITS"; return }
        val entry = CreditEntry(date = today(), credits = credits, shot = number)
        val updated = project.copy(flowCredits = FlowCredits(manualLog = project.flowCredits.manualLog + entry))
        when (val r = session.save(updated)) {
            is CoreResult.Ok -> _message.value = "Logged $credits credits for Shot $number."
            is CoreResult.Err -> _message.value = "Save failed: ${r.error.message}"
        }
    }

    /** "Skip" on the credits question: nothing is logged; asked again after the next import for this shot. */
    fun skipCredits(number: Int) {
        val shot = session.open?.project?.shots?.firstOrNull { it.number == number } ?: return
        _skipped.value = _skipped.value + (number to shot.videos.size)
    }

    /** Remove a wrong line from the credit log ([CreditLogRow.index]). */
    fun removeCredit(index: Int) {
        val project = session.open?.project ?: return
        val log = project.flowCredits.manualLog
        if (index !in log.indices) return
        val gone = log[index]
        val updated = project.copy(flowCredits = FlowCredits(manualLog = log.filterIndexed { i, _ -> i != index }))
        when (val r = session.save(updated)) {
            is CoreResult.Ok -> _message.value = "Removed ${gone.credits} credits (${gone.date})."
            is CoreResult.Err -> _message.value = "Save failed: ${r.error.message}"
        }
    }

    /**
     * Small JPEG of shot [number]'s chosen reference image (`.cache/thumbs/<id>.jpg`, made once by ffmpeg). Without
     * ffmpeg the image itself is returned when it is small enough to decode safely; null when there is none.
     */
    suspend fun refThumbnail(number: Int): ByteArray? {
        val id = shotOf(number)?.chosenRef ?: return null
        return thumbnail(id, atMs = 0) ?: withContext(Dispatchers.Default) {
            (session.artifacts()?.readBytes(id) as? CoreResult.Ok)?.value?.takeIf { it.size <= MAX_RAW_THUMB_BYTES }
        }
    }

    /** Small JPEG of a frame near the start of the video shown on shot [number]'s card; null without ffmpeg. */
    suspend fun videoThumbnail(number: Int): ByteArray? {
        val shot = shotOf(number) ?: return null
        val id = shot.acceptedVideo ?: shot.videos.lastOrNull() ?: return null
        return thumbnail(id, atMs = VIDEO_THUMB_MS) ?: thumbnail(id, atMs = 0)
    }

    /** Opens the video shown on shot [number]'s card in the phone's / computer's video player. */
    fun playVideo(number: Int) {
        val shot = shotOf(number) ?: return
        val id = shot.acceptedVideo ?: shot.videos.lastOrNull() ?: run { _message.value = "No video imported for Shot $number"; return }
        openArtifact(id, "video")
    }

    /** Opens shot [number]'s chosen reference image full size. */
    fun openRefImage(number: Int) {
        val id = shotOf(number)?.chosenRef ?: return
        openArtifact(id, "image")
    }

    private fun openArtifact(id: ArtifactId, type: String) {
        val path = artifactPath(id) ?: run { _message.value = "The file for this shot is missing"; return }
        val share = fileShare ?: run { _message.value = "Opening files is not available here"; return }
        when (val r = share.open(path, mimeOf(path, type))) {
            is io.github.alalkipgen.autoflow.platform.PlatformResult.Ok -> Unit
            is io.github.alalkipgen.autoflow.platform.PlatformResult.Failed -> _message.value = r.reason
            is io.github.alalkipgen.autoflow.platform.PlatformResult.Unsupported -> _message.value = r.reason
        }
    }

    private fun shotOf(number: Int): Shot? = session.open?.project?.shots?.firstOrNull { it.number == number }

    private fun artifactPath(id: ArtifactId): String? {
        val dir = session.open?.dir ?: return null
        val artifact = (session.artifacts()?.get(id) as? CoreResult.Ok)?.value ?: return null
        return joinPath(joinPath(dir, ArtifactStore.ARTIFACTS_DIR), artifact.file)
    }

    /** `.cache/thumbs/<id>[-<ms>].jpg` from ffmpeg, one at a time; ids are content hashes, so the cache never goes stale. */
    private suspend fun thumbnail(id: ArtifactId, atMs: Long): ByteArray? = withContext(Dispatchers.Default) {
        val dir = session.open?.dir ?: return@withContext null
        val thumbsDir = joinPath(joinPath(dir, ProjectSession.CACHE_DIR), "thumbs")
        val file = joinPath(thumbsDir, if (atMs == 0L) "${id.value}.jpg" else "${id.value}-$atMs.jpg")
        session.readFile(file)?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        val runner = media?.runner ?: return@withContext null
        val ffmpeg = media.ffmpeg() ?: return@withContext null
        val source = artifactPath(id) ?: return@withContext null
        thumbs.withLock {
            session.readFile(file)?.takeIf { it.isNotEmpty() }?.let { return@withLock it }
            runCatching { fs?.createDirectories(thumbsDir) }.getOrElse { return@withLock null }
            when (FrameGrab(runner, ffmpeg).thumb(source, atMs, file, dir)) {
                is FrameGrab.Result.Ok -> session.readFile(file)?.takeIf { it.isNotEmpty() }
                is FrameGrab.Result.Failed -> null
            }
        }
    }

    /** Tick / untick shot [number] for the batch sheet. */
    fun toggleBatch(number: Int) {
        val b = _batch.value
        _batch.value = b.copy(unticked = if (number in b.unticked) b.unticked - number else b.unticked + number)
    }

    /**
     * Write the batch sheet (specs/flow-handoff.md): `packages/batch-<date>.html` with every ticked pending shot —
     * prompt, chosen reference image inline, Copy buttons. Handoff states do not change (Copy Prompt does that).
     */
    fun exportBatch() {
        val open = session.open ?: run { _message.value = "No project open"; return }
        val disk = fs ?: run { _message.value = "Saving files is not available here"; return }
        val project = open.project
        val artifacts = session.artifacts() ?: return
        val ticked = build(project).rows.filter { it.inBatch }.map { it.number }.toSet()
        if (ticked.isEmpty()) { _message.value = "Tick at least one pending shot for the batch sheet"; return }
        val shots = project.shots.filter { it.number in ticked }.mapNotNull { shot ->
            val ir = readPromptIr(shot) ?: return@mapNotNull null
            val ref = shot.chosenRef?.let { id -> (artifacts.get(id) as? CoreResult.Ok)?.value }
            val bytes = shot.chosenRef?.let { (artifacts.readBytes(it) as? CoreResult.Ok)?.value }
            BatchShot(
                number = shot.number,
                timecode = ShotsModel.formatMs(shot.startMs),
                durationMs = shot.durationMs,
                pass = if (shot.handoff.pass == Pass.FINAL) "Final (Quality)" else "Draft (Fast)",
                lyric = shot.lyricsMy,
                prompt = VeoFlowDialect.render(ir),
                image = bytes,
                imageExt = ref?.file?.substringAfterLast('.') ?: "png",
            )
        }
        val date = today()
        val folder = joinPath(open.dir, PACKAGES_DIR)
        val html = BatchSheet.html(project.name, project.settings.ratio.label, date, shots)
        val name = BatchSheet.fileName(date) { disk.exists(joinPath(folder, it)) }
        try {
            disk.createDirectories(folder)
            disk.writeDurably(joinPath(folder, name), html.encodeToByteArray())
        } catch (e: Exception) {
            _message.value = "Could not save the batch sheet: ${e.message}"
            return
        }
        _batch.value = _batch.value.copy(last = "$PACKAGES_DIR/$name")
        _message.value = "Batch sheet saved: $PACKAGES_DIR/$name (${shots.size} shot${if (shots.size == 1) "" else "s"}). Open it in a browser next to Flow."
    }

    /** Open the last batch sheet in a browser. */
    fun openBatch() = withBatch { path, share -> share.open(path, HTML_MIME) }

    /** Send the last batch sheet to another app or device (share sheet). */
    fun shareBatch() = withBatch { path, share -> share.share(path, HTML_MIME, "Batch sheet") }

    private fun withBatch(action: (String, FileShare) -> io.github.alalkipgen.autoflow.platform.PlatformResult<Unit>) {
        val dir = session.open?.dir ?: return
        val last = _batch.value.last ?: return
        val share = fileShare ?: run { _message.value = "Opening files is not available here"; return }
        when (val r = action(joinPath(dir, last), share)) {
            is io.github.alalkipgen.autoflow.platform.PlatformResult.Ok -> Unit
            is io.github.alalkipgen.autoflow.platform.PlatformResult.Failed -> _message.value = r.reason
            is io.github.alalkipgen.autoflow.platform.PlatformResult.Unsupported -> _message.value = r.reason
        }
    }

    private fun today(): String = io.github.alalkipgen.autoflow.ai.usage.UsageCounter.quotaDay(nowMs())

    private fun readPromptIr(shot: Shot): PromptIR? =
        shot.promptIr?.let { (session.readJson(it, PromptIR.serializer()) as? CoreResult.Ok)?.value }

    private fun build(project: Project?): FlowHandoffUiState {
        if (project == null) return FlowHandoffUiState()
        val lookup = session.lookup()
        val log = project.flowCredits.manualLog
        val skipped = _skipped.value
        val batch = _batch.value
        val rows = project.shots.map { shot ->
            val entries = log.count { it.shot == shot.number }
            val ir = readPromptIr(shot)
            val gate = WorkflowRules.canHandoff(shot)
            val gateMsg = if (gate.allowed) null else gate.blockers.firstOrNull()?.handoffMsg()
            HandoffRow(
                number = shot.number,
                timecode = ShotsModel.formatMs(shot.startMs),
                lyric = shot.lyricsMy.take(40),
                promptText = ir?.let { VeoFlowDialect.render(it) },
                pass = shot.handoff.pass,
                state = shot.handoff.state,
                status = ShotStatus.of(project, shot, lookup),
                hasRef = shot.chosenRef != null,
                refApproved = shot.approvals.ref,
                videoCount = shot.videos.size,
                accepted = shot.acceptedVideo != null,
                gateMsg = gateMsg,
                creditsLogged = log.filter { it.shot == shot.number }.sumOf { it.credits },
                askCredits = shot.videos.isNotEmpty() && entries < shot.videos.size &&
                    (skipped[shot.number] ?: -1) < shot.videos.size,
                creditDefault = if (shot.handoff.pass == Pass.FINAL) creditHintQuality else creditHintFast,
                batchable = ir != null && gate.allowed && shot.acceptedVideo == null,
                refImage = shot.chosenRef,
                shownVideo = shot.acceptedVideo ?: shot.videos.lastOrNull(),
            ).let { it.copy(inBatch = it.batchable && shot.number !in batch.unticked) }
        }
        val day = today()
        val month = day.take(7)
        return FlowHandoffUiState(
            projectName = project.name,
            rows = rows,
            selected = _selected.value,
            message = _message.value,
            flowUrl = flowUrl,
            creditHintFast = creditHintFast,
            creditHintQuality = creditHintQuality,
            creditsToday = log.filter { it.date == day }.sumOf { it.credits },
            creditsMonth = log.filter { it.date.startsWith(month) }.sumOf { it.credits },
            creditLog = log.mapIndexed { i, e -> CreditLogRow(i, e.date, e.shot, e.credits) }
                .filter { it.date.startsWith(month) }.asReversed(),
            batchCount = rows.count { it.inBatch },
            lastBatch = batch.last,
        )
    }

    companion object {
        /** Sanity limit for one entry (a Quality generation is about 100). */
        const val MAX_CREDITS = 10_000
        const val PACKAGES_DIR = "packages"
        const val HTML_MIME = "text/html"
        /** The video thumbnail is the frame at 1 s (the very first frame of a Flow clip is often dark). */
        const val VIDEO_THUMB_MS = 1_000L
        /** Without ffmpeg, a reference image larger than this is not decoded for the thumbnail (phone memory). */
        const val MAX_RAW_THUMB_BYTES = 4 * 1024 * 1024

        internal fun mimeOf(path: String, type: String): String = when (path.substringAfterLast('.').lowercase()) {
            "mov" -> "video/quicktime"
            "mp4", "m4v" -> "video/mp4"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "$type/*"
        }
    }

    private fun io.github.alalkipgen.autoflow.core.workflow.Blocker.handoffMsg(): String = when (this) {
        io.github.alalkipgen.autoflow.core.workflow.Blocker.NO_CHOSEN_REF -> "Import a reference image first (Ref A/B on the References screen)"
        io.github.alalkipgen.autoflow.core.workflow.Blocker.REF_NOT_APPROVED -> "Approve the reference image first"
        else -> "Shot not ready for handoff"
    }
}
