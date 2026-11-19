package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.storyboard.notice
import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.retry.isBusy
import io.github.alalkipgen.autoflow.ai.TtsProvider
import io.github.alalkipgen.autoflow.ai.VoiceRef
import io.github.alalkipgen.autoflow.ai.recap.RecapBrief
import io.github.alalkipgen.autoflow.ai.recap.RecapPrompts
import io.github.alalkipgen.autoflow.ai.recap.RecapScriptGenerator
import io.github.alalkipgen.autoflow.ai.recap.SceneBrief
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.hash.Sha256
import io.github.alalkipgen.autoflow.core.recap.AiTimingStage
import io.github.alalkipgen.autoflow.core.recap.timedAiStage
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.recap.AiItemPaused
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.recap.Look
import io.github.alalkipgen.autoflow.media.audio.SilenceTrim
import io.github.alalkipgen.autoflow.core.recap.Recap
import io.github.alalkipgen.autoflow.core.recap.RecapMovie
import io.github.alalkipgen.autoflow.core.recap.BlockTake
import io.github.alalkipgen.autoflow.core.recap.RecapRules
import io.github.alalkipgen.autoflow.core.recap.RecapRulesV2
import io.github.alalkipgen.autoflow.core.recap.RecapStory
import io.github.alalkipgen.autoflow.core.recap.TakeSource
import io.github.alalkipgen.autoflow.core.recap.RecapScene
import io.github.alalkipgen.autoflow.core.recap.RecapScript
import io.github.alalkipgen.autoflow.core.recap.RecapSegment
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import io.github.alalkipgen.autoflow.core.recap.SpokenText
import io.github.alalkipgen.autoflow.core.recap.RecapRegister
import io.github.alalkipgen.autoflow.core.recap.RecapStyle
import io.github.alalkipgen.autoflow.core.recap.Srt
import io.github.alalkipgen.autoflow.core.recap.VoiceSource
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.media.audio.PcmWav
import io.github.alalkipgen.autoflow.platform.VoiceRecorder
import io.github.alalkipgen.autoflow.core.recap.Scenes
import io.github.alalkipgen.autoflow.media.probe.FrameGrab
import io.github.alalkipgen.autoflow.media.probe.MediaProbe
import io.github.alalkipgen.autoflow.media.scene.SceneDetect
import io.github.alalkipgen.autoflow.platform.FilePicker
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.storyboard.text
import io.github.alalkipgen.autoflow.core.recap.AiRunTiming
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.alalkipgen.autoflow.core.library.LibraryStore
import io.github.alalkipgen.autoflow.ui.library.LibraryModel
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** What the Movie Recap screen shows besides the project itself. [job] = a long step is running ([progress] 0…1 when known). */
data class RecapUiState(
    val projectDir: String? = null,
    val recap: io.github.alalkipgen.autoflow.core.recap.RecapV2? = null,
    val movieName: String? = null,
    val job: String? = null,
    val progress: Double? = null,
    val message: String? = null,
    val messageOk: Boolean = false,
    val notice: UiNotice? = null,
    val jobNotice: UiNotice? = null,
    /** Foreground projection of a runner job, not a separate Jobs/Home row. */
    val runnerJobId: String? = null,
    val scriptRunId: String? = null,
    /** The current `rs` artifact, null when none or unreadable. */
    val script: RecapScript? = null,
    /**
     * The story (v2) [script] is shown from: rules — current take (chosen, else newest), stale, fit, caption timing —
     * read it by block id (audit F05 / F07), never the v1 shape.
     */
    val story: RecapStory? = null,
    /** The same Library pronunciation snapshot used by synthesis and export; captions remain written. */
    val lexicon: Lexicon = Lexicon.EMPTY,
    /** `source/summary.txt`. */
    val summary: String = "",
    /** Segment being recorded now. */
    val recording: Int? = null,
    /** Audio file of each segment's newest take (for ▶). */
    val takeFiles: Map<Int, String> = emptyMap(),
    /** File name of the background music (`recap.audio.bgm`), for the Sound controls. */
    val bgmName: String? = null,
    /**
     * ETA card before "Speak all" starts (5A-5, D-51): non-null = show "N scenes, ~T s — Start / Cancel".
     * Null = no pending ETA card.
     */
    val speakAllEta: SpeakAllEta? = null,
    /** Subtitle tracks found in the imported movie (5B-1). Populated after movie import or on demand. */
    val movieSubtitleTracks: List<io.github.alalkipgen.autoflow.core.recap.SubtitleTrack> = emptyList(),
    /** Transient probe state: an unopened movie is not evidence of zero tracks. */
    val movieSubtitleTracksRead: Boolean = false,
    /** "Transcribe picked scenes" (5B-1): what is left, the confirm card and the project's last unfinished job. */
    val transcribe: TranscribeUi = TranscribeUi(),
) {
    val busy: Boolean get() = job != null
}

/**
 * The Script card's Transcribe section (5B-1, D-44). [todoScenes] picked scenes have no `.srt` line and no
 * transcript yet ([requests] requests); [lines] / [transcribedScenes] are in the project's transcript. [confirm] =
 * the card shown before anything is sent; [job] = this project's TRANSCRIBE job that is running, paused or failed
 * (Cancel / Retry), null when none.
 */
data class TranscribeUi(
    val available: Boolean = false,
    val todoScenes: Int = 0,
    val requests: Int = 0,
    val transcribedScenes: Int = 0,
    val lines: Int = 0,
    val confirm: TranscribeConfirm? = null,
    val job: AiJob? = null,
)

/** Shown before Transcribe sends anything: [scenes] scenes, [requests] requests, [audioMs] of sound, [usedToday] requests so far today (null = not known). */
data class TranscribeConfirm(val scenes: Int, val requests: Int, val audioMs: Long, val usedToday: Int? = null)

/** ETA shown before Speak All starts (5A-5). */
data class SpeakAllEta(val count: Int, val etaSeconds: Long?)

/** The take that plays for story block [index] — chosen, else newest, when not stale (audit F06 / F07); null = none. */
fun RecapUiState.currentTake(index: Int): BlockTake? {
    val r = recap ?: return null
    val block = story?.blocks?.getOrNull(index) ?: return null
    return RecapRulesV2.currentTake(r, block, lexicon)
}

/** Block [index] has a take, but for another text (⚠ Stale). */
fun RecapUiState.isStale(index: Int): Boolean {
    val r = recap ?: return false
    val block = story?.blocks?.getOrNull(index) ?: return false
    return RecapRulesV2.isStale(r, block, lexicon)
}

/**
 * Block [index]'s caption window in take time (audit F05): the owner's `captions.chunkEdits` (first … last edge),
 * else automatic — 0 … the take (or, before a take, the block). Null without such a block.
 */
fun RecapUiState.captionWindow(index: Int): Pair<Long, Long>? {
    val block = story?.blocks?.getOrNull(index) ?: return null
    val edges = recap?.captions?.chunkEdits?.get(block.id)?.takeIf { it.size >= 2 }
    if (edges != null) return edges.first() to edges.last()
    return 0L to (recap?.takeFor(block.id)?.durationMs ?: block.durationMs)
}

/** True when block [index] has the owner's caption timing (not automatic). */
fun RecapUiState.hasCaptionTiming(index: Int): Boolean {
    val block = story?.blocks?.getOrNull(index) ?: return false
    return recap?.captions?.chunkEdits?.containsKey(block.id) == true
}

/**
 * Movie Recap logic (docs/specs/recap.md, D-33): movie import, scenes and picks; script, voice and fit are added
 * by the later slices. A Koin single, so a long step (copying, finding scenes) keeps going when the user leaves the
 * screen. Every rule comes from [RecapRules]; every change is saved to `project.recap` at once.
 */
class RecapModel(
    private val session: ProjectSession,
    private val fs: FileSystem,
    private val media: MediaTools?,
    private val picker: FilePicker?,
    private val scope: CoroutineScope,
    private val generator: RecapScriptGenerator? = null,
    private val tts: TtsProvider? = null,
    private val recorder: VoiceRecorder? = null,
    /** Initial pronunciation snapshot for callers without a Library (e.g. isolated tests). */
    private val lexicon: Lexicon = Lexicon.EMPTY,
    /** Library for Brand Kit save/apply and BGM picker (D-51: 5B-3, 5B-4, 5B-8). */
    private val library: LibraryModel? = null,
    /** Exact frames from the export compiler (D-42, 5A-8); null = this app cannot render stills. */
    private val frames: io.github.alalkipgen.autoflow.ui.export.RecapFrames? = null,
    /** Take times (`BlockTake.createdAt`) and artifact times. */
    private val clock: Clock = Clock.System,
    /**
     * The app's AI job runner (5A-5, audit F11): Speak All becomes a persistent, paced, resumable TTS_ALL job there.
     * null only where no runner exists (tests): Speak All then runs the same per-block step in this model's scope.
     */
    private val jobs: AiJobRunner? = null,
    /** Production supplies LibraryStore.lexicon; each request captures its value before suspending. */
    private val lexicons: StateFlow<Lexicon>? = null,
    modelConfig: io.github.alalkipgen.autoflow.ai.config.ModelConfig? = null,
    /** 5B-1 scene transcription (Gemini audio input, D-44); null = not offered here. */
    transcriber: io.github.alalkipgen.autoflow.ai.TranscribeProvider? = null,
    /** Today's request counts, shown on the Transcribe confirm card. */
    private val usage: io.github.alalkipgen.autoflow.ai.usage.UsageCounter? = null,
    /** Cuts the scenes' sound for Transcribe; null = ffmpeg from [media]. */
    sceneAudio: SceneAudioCutter? = null,
    private val uiLanguage: () -> UiLanguage = { UiLanguage.ENGLISH },
    /** Rebuildable app cache outside every project; null keeps time-only rows in hosts without a cache. */
    thumbnailCacheDir: String? = null,
) {
    private val pronunciation: Lexicon get() = lexicons?.value ?: lexicon
    private val scenesTx = SceneTranscriber(session, fs, media, transcriber, sceneAudio, clock)
    private val _state = MutableStateFlow(RecapUiState())
    val state: StateFlow<RecapUiState> = _state.asStateFlow()
    internal val runnerJobs: StateFlow<List<AiJob>> = jobs?.jobs ?: MutableStateFlow(emptyList())
    internal fun cancelVoiceRun(id: String) = scope.launch {
        val job = runnerJobs.value.firstOrNull { it.id == id && it.project == session.open?.dir && it.kind == AiJobKind.TTS_ALL }
        if (job?.state in setOf(AiJobState.QUEUED, AiJobState.RUNNING)) jobs?.cancel(id)
    }
    internal fun resumeVoiceRun(id: String) = scope.launch {
        val job = runnerJobs.value.firstOrNull { it.id == id && it.project == session.open?.dir && it.kind == AiJobKind.TTS_ALL }
        if (job != null && job.items.isNotEmpty() && job.state in setOf(AiJobState.PAUSED, AiJobState.FAILED)) jobs?.enqueue(job)
    }
    val voiceSettings = RecapVoiceSettings(session, modelConfig, jobs, ::refresh)
    val layerEditor = RecapLayerEditor(session, fs, picker, scope, uiLanguage = uiLanguage, changed = ::refresh)
    val blockEditor = RecapBlockEditor(session, fs, changed = ::refresh)
    val voiceInspector = RecapVoiceInspector(session, fs, { pronunciation }) { speak(it) }
    val previewSource = RecapPreviewSource(session, fs, media)
    internal val sceneThumbnails = RecapThumbnails(fs, media, thumbnailCacheDir)
    internal val takeWaveforms = RecapTakeWaveforms(fs, media, thumbnailCacheDir?.let { joinPath(it, "take-waveforms-v1") })
    internal fun thumbnailSource(state: RecapUiState): RecapThumbnails.Source? = session.open?.takeIf {
        it.dir == state.projectDir && it.project.recap == state.recap
    }?.let { open -> open.project.recap?.movie?.let {
        RecapThumbnails.Source(open.dir, open.project.id, open.project.updatedAt.toString(), it)
    } }
    internal fun thumbnailIsCurrent(source: RecapThumbnails.Source): Boolean =
        thumbnailSource(_state.value) == source
    private val thumbs = Mutex()
    private var scriptJob: Job? = null

    /**
     * Where the running recording belongs (audit F03): fixed when it started, not when it stops. While it runs it
     * stays on screen (and can be stopped) even when another project is opened; it is saved to its own project.
     */
    private var recordingInto: Pair<VoiceTarget, String>? = null

    init {
        refresh()
        jobs?.let { runner -> scope.launch {
            runner.jobs.collect { list ->
                val snapshot = _state.value
                val job = currentTranscribe(list, snapshot.projectDir)
                if (snapshot.transcribe.job != job) setTranscribe { it.copy(job = job) }
            }
        } }
        lexicons?.let { changes -> scope.launch { changes.collect { refresh() } } }
    }

    /** Re-reads the open project (the screen calls it when it opens). A running job of the same project stays. */
    fun refresh() {
        val open = session.open
        val keep = _state.value.takeIf { it.projectDir == open?.dir }
        val recap = open?.project?.recap
        val story = recap?.story?.let { id -> session.readJson(id, RecapStory.serializer()) }
        _state.value = RecapUiState(
            projectDir = open?.dir,
            recap = recap,
            lexicon = pronunciation,
            script = story?.let(::storyToScript),
            story = (story as? CoreResult.Ok)?.value,
            summary = if (open == null) "" else session.readSource(SUMMARY_FILE),
            recording = if (recordingInto != null) _state.value.recording else keep?.recording,
            takeFiles = takeFiles(open?.dir, recap, (story as? CoreResult.Ok)?.value),
            bgmName = recap?.audio?.bgm?.let { id -> session.artifacts()?.get(id)?.let { (it as? CoreResult.Ok)?.value?.inputs?.get(BGM_NAME_INPUT) } },
            movieName = session.moviePath()?.substringAfterLast('/'),
            // Probe metadata is UI-only. Keep it through same-movie saves/done(), never across projects/movies.
            movieSubtitleTracks = keep?.takeIf { it.recap?.movie == recap?.movie }?.movieSubtitleTracks.orEmpty(),
            movieSubtitleTracksRead = keep?.takeIf { it.recap?.movie == recap?.movie }?.movieSubtitleTracksRead ?: false,
            transcribe = transcribeUi(open?.dir, recap, keep?.transcribe),
            job = keep?.job,
            progress = keep?.progress,
            message = keep?.message,
            messageOk = keep?.messageOk ?: false,
            notice = keep?.notice,
            jobNotice = keep?.jobNotice,
            runnerJobId = keep?.runnerJobId,
            scriptRunId = keep?.scriptRunId,
        )
    }

    /** Picks a movie file and streams it into `source/` (spec §2); clears scenes, script and takes. */
    fun importMovie(): Job? {
        val recap = recap() ?: return null
        RecapRulesV2.canImportMovie(recap).blockers.firstOrNull()?.let { sayNotice(it.notice()); return null }
        val p = picker ?: run { sayNotice(UiNotice(UiText.CHOOSE_FILES_UNAVAILABLE)); return null }
        if (_state.value.busy) return null
        return scope.launch {
            val from = when (val r = p.pickFiles(UiText.PICK_RECAP_MOVIE.text(uiLanguage()), ProjectSession.MOVIE_EXTENSIONS)) {
                is PlatformResult.Ok -> r.value.firstOrNull() ?: return@launch
                is PlatformResult.Failed -> return@launch say(r.reason)
                is PlatformResult.Unsupported -> return@launch say(r.reason)
            }
            busyNotice(UiNotice(UiText.MOVIE_COPYING))
            val path = when (val r = withContext(Dispatchers.Default) { session.importMovie(from) }) {
                is CoreResult.Ok -> r.value
                is CoreResult.Err -> return@launch done(r.error.message)
            }
            val info = probe(path) ?: return@launch doneNotice(UiNotice(UiText.MOVIE_UNREADABLE))
            if (!info.hasVideo || info.durationMs <= 0) return@launch doneNotice(UiNotice(UiText.MOVIE_NO_PICTURE))
            val dir = session.open?.dir ?: return@launch done(null)
            val movie = RecapMovie(
                file = path.removePrefix(dir.trimEnd('/') + "/"),
                sizeBytes = runCatching { fs.size(path) }.getOrDefault(0L),
                durationMs = info.durationMs,
                width = info.width ?: 0,
                height = info.height ?: 0,
                hasAudio = info.hasAudio,
            )
            val saved = update { it.copy(movie = movie, scenes = emptyList(), story = null, storyApproved = false, takes = emptyList()) }
            if (saved) _state.value = _state.value.copy(movieSubtitleTracks = info.subtitleTracks, movieSubtitleTracksRead = true)
            val trackHint = if (info.subtitleTracks.isNotEmpty()) UiNotice(UiText.MOVIE_TRACK_HINT, "n" to info.subtitleTracks.size) else ""
            doneNotice(if (saved) UiNotice(UiText.MOVIE_READY, "duration" to clock(movie.durationMs), "tracks" to trackHint) else null, ok = saved)
        }
    }

    /** Runs `scdet` over the movie ([threshold]: [Scenes.THRESHOLD_MORE] … [Scenes.THRESHOLD_FEWER]). */
    fun findScenes(threshold: Int = Scenes.THRESHOLD_NORMAL): Job? {
        val recap = recap() ?: return null
        RecapRulesV2.canFindScenes(recap).blockers.firstOrNull()?.let { sayNotice(it.notice()); return null }
        val movie = recap.movie ?: return null
        val runner = media?.runner
        val ffmpeg = media?.ffmpeg()
        val dir = session.open?.dir ?: return null
        if (runner == null || ffmpeg == null) { sayNotice(UiNotice(UiText.MOVIE_FFMPEG_UNAVAILABLE)); return null }
        if (_state.value.busy) return null
        return scope.launch {
            busyNotice(UiNotice(UiText.SCENES_FINDING), 0.0)
            val r = SceneDetect(runner, ffmpeg).cuts(joinPath(dir, movie.file), movie.durationMs, threshold, dir) { f ->
                _state.value = _state.value.copy(progress = f.coerceIn(0.0, 1.0))
            }
            when (r) {
                is SceneDetect.Result.Failed -> doneNotice(UiNotice(UiText.SCENES_FIND_FAILED, "detail" to r.reason))
                is SceneDetect.Result.Ok -> {
                    val scenes = Scenes.fromCuts(r.cutsMs, movie.durationMs, recap().orEmptyScenes())
                    val saved = update { it.copy(scenes = scenes, sceneThreshold = threshold, storyApproved = false) }
                    doneNotice(if (saved) UiNotice(UiText.SCENES_FOUND, "n" to scenes.size) else null, ok = saved)
                }
            }
        }
    }

    fun togglePick(index: Int) {
        update { r -> r.copy(scenes = r.scenes.mapIndexed { i, s -> if (i == index) s.copy(picked = !s.picked) else s }) }
    }

    fun clearPicks() {
        update { r -> r.copy(scenes = r.scenes.map { it.copy(picked = false) }) }
    }

    fun setTarget(ms: Long) {
        update { it.copy(targetMs = ms) }
    }

    /**
     * JPEG of the frame in the middle of [scene] (`.cache/thumbs/<movie size>-<startMs>.jpg`, made once, one ffmpeg
     * at a time); null without ffmpeg or when it fails.
     */
    /** The movie file on disk (the Recap preview plays it under the boxes on Android, 5A-8), or null. */
    fun moviePath(): String? {
        val movie = recap()?.movie ?: return null
        val dir = session.open?.dir ?: return null
        return joinPath(dir, movie.file)
    }

    suspend fun thumbnail(scene: RecapScene): ByteArray? = withContext(Dispatchers.Default) {
        val movie = recap()?.movie ?: return@withContext null
        val dir = session.open?.dir ?: return@withContext null
        val thumbsDir = joinPath(joinPath(dir, ProjectSession.CACHE_DIR), "thumbs")
        val file = joinPath(thumbsDir, "${movie.sizeBytes}-${scene.startMs}.jpg")
        session.readFile(file)?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        val runner = media?.runner ?: return@withContext null
        val ffmpeg = media.ffmpeg() ?: return@withContext null
        thumbs.withLock {
            session.readFile(file)?.takeIf { it.isNotEmpty() }?.let { return@withLock it }
            runCatching { fs.createDirectories(thumbsDir) }.getOrElse { return@withLock null }
            val mid = scene.startMs + scene.durationMs / 2
            when (FrameGrab(runner, ffmpeg).thumb(joinPath(dir, movie.file), mid, file, dir)) {
                is FrameGrab.Result.Ok -> session.readFile(file)
                is FrameGrab.Result.Failed -> null
            }
        }
    }

    // --- 3 · Script (spec §4) ------------------------------------------------------------------------------------

    /** Saves the typed story summary (`source/summary.txt`) at once. */
    fun setSummary(text: String) {
        if (session.open == null) return
        _state.value = _state.value.copy(summary = text)
        (session.writeSource(SUMMARY_FILE, text) as? CoreResult.Err)?.let { say(it.error.message) }
    }

    /** Picks a `.srt` file and keeps it as `source/subtitles.srt` (UTF-8). */
    fun importSubtitles(): Job? {
        recap() ?: return null
        val p = picker ?: run { sayNotice(UiNotice(UiText.CHOOSE_FILES_UNAVAILABLE)); return null }
        if (_state.value.busy) return null
        return scope.launch {
            val from = when (val r = p.pickFiles(UiText.PICK_RECAP_SUBTITLES.text(uiLanguage()), setOf("srt"))) {
                is PlatformResult.Ok -> r.value.firstOrNull() ?: return@launch
                is PlatformResult.Failed -> return@launch say(r.reason)
                is PlatformResult.Unsupported -> return@launch say(r.reason)
            }
            val text = withContext(Dispatchers.Default) { session.readFile(from)?.decodeToString()?.removePrefix("\uFEFF") }
                ?: return@launch sayNotice(UiNotice(UiText.CANNOT_READ_FILE))
            val cues = Srt.parse(text)
            if (cues.isEmpty()) return@launch sayNotice(UiNotice(UiText.SUBTITLES_NONE))
            (session.writeSource(SUBTITLES_FILE, text) as? CoreResult.Err)?.let { return@launch say(it.error.message) }
            if (update { it.copy(sources = it.sources.copy(subtitles = "${ProjectSession.SOURCE_DIR}/$SUBTITLES_FILE")) }) {
                doneNotice(UiNotice(UiText.SUBTITLES_READY, "n" to cues.size), ok = true)
            }
        }
    }

    fun removeSubtitles() {
        update { it.copy(sources = it.sources.copy(subtitles = null, subtitleTrack = null)) }
    }

    /** Sets the selected subtitle stream track index (5B-1). */
    fun setSubtitleTrack(index: Int?) {
        update { it.copy(sources = it.sources.copy(subtitleTrack = index)) }
    }

    /** Read-only UI metadata refresh for a reopened movie; the existing probe remains authoritative. */
    fun readMovieSubtitleTracks(): Job? {
        val open = session.open ?: return null
        val movie = open.project.recap?.movie ?: return null
        if (_state.value.busy) return null
        val runner = media?.runner
        val ffprobe = media?.ffprobe()
        if (runner == null || ffprobe == null) { sayNotice(UiNotice(UiText.MOVIE_FFMPEG_UNAVAILABLE)); return null }
        return scope.launch {
            val notice = UiNotice(UiText.V3_READ_TRACKS)
            busyNotice(notice)
            val path = joinPath(open.dir, movie.file)
            val result = withContext(Dispatchers.Default) { MediaProbe(runner, ffprobe).probe(path, MediaTools.parentOf(path)) }
            val current = session.open
            if (current?.dir != open.dir || current.project.id != open.project.id || current.project.updatedAt != open.project.updatedAt) {
                if (_state.value.projectDir == open.dir && _state.value.jobNotice == notice) done(null)
                return@launch
            }
            doneNotice(if (result is MediaProbe.Result.Failed) UiNotice(UiText.V3_TRACKS_UNREADABLE, "detail" to result.reason) else null)
            if (result is MediaProbe.Result.Ok) _state.value = _state.value.copy(movieSubtitleTracks = result.info.subtitleTracks, movieSubtitleTracksRead = true)
        }
    }

    /**
     * Extracts subtitle track [track] from the imported movie via ffmpeg (5B-1).
     * Writes `source/subtitles.srt` and sets `recap.sources.subtitles`.
     */
    fun extractSubtitleTrack(track: io.github.alalkipgen.autoflow.core.recap.SubtitleTrack): Job? {
        val recap = recap() ?: return null
        val movie = recap.movie ?: run { sayNotice(UiNotice(UiText.IMPORT_MOVIE_FIRST)); return null }
        val runner = media?.runner
        val ffmpeg = media?.ffmpeg()
        val dir = session.open?.dir ?: return null
        if (runner == null || ffmpeg == null) { sayNotice(UiNotice(UiText.MOVIE_FFMPEG_UNAVAILABLE)); return null }
        if (_state.value.busy) return null
        return scope.launch {
            busyNotice(UiNotice(UiText.SUBTITLE_EXTRACTING, "n" to track.index))
            val moviePath = io.github.alalkipgen.autoflow.core.io.joinPath(dir, movie.file)
            val sourceDir = io.github.alalkipgen.autoflow.core.io.joinPath(dir, ProjectSession.SOURCE_DIR)
            val outPath = io.github.alalkipgen.autoflow.core.io.joinPath(sourceDir, SUBTITLES_FILE)
            fs.createDirectories(sourceDir)
            // The track's own stream index from the probe (audit F14).
            val args = listOf(ffmpeg) + io.github.alalkipgen.autoflow.media.probe.MediaProbe.extractSubtitleArgs(moviePath, track.index, outPath)
            val r = withContext(kotlinx.coroutines.Dispatchers.Default) {
                runner.run(args, dir, 120_000L, keepOutput = true) {}
            }
            if (r is io.github.alalkipgen.autoflow.media.tool.ToolExec.Done && r.exitCode == 0 && fs.exists(outPath)) {
                val srtText = fs.read(outPath).decodeToString()
                val cues = Srt.parse(srtText)
                if (cues.isEmpty()) return@launch doneNotice(UiNotice(UiText.SUBTITLE_TRACK_EMPTY))
                (session.writeSource(SUBTITLES_FILE, srtText) as? CoreResult.Err)?.let { return@launch say(it.error.message) }
                if (update { it.copy(sources = it.sources.copy(subtitles = "${ProjectSession.SOURCE_DIR}/$SUBTITLES_FILE", subtitleTrack = track.index)) }) {
                    doneNotice(UiNotice(UiText.SUBTITLE_EXTRACTED, "n" to cues.size, "track" to track.index), ok = true)
                }
            } else {
                val msg = (r as? io.github.alalkipgen.autoflow.media.tool.ToolExec.Done)?.tail?.lastOrNull() ?: "ffmpeg failed"
                doneNotice(UiNotice(UiText.SUBTITLE_EXTRACT_FAILED, "detail" to msg))
            }
        }
    }

    // --- Transcribe picked scenes (5B-1, D-44, spec §7) ----------------------------------------------------------

    private fun currentTranscribe(list: List<AiJob>, dir: String?): AiJob? = list
        .filter { it.project == dir && it.kind == AiJobKind.TRANSCRIBE && it.state !in FINISHED_JOB_STATES }
        .maxWithOrNull(compareBy<AiJob> { it.state in ACTIVE_JOB_STATES }.thenBy { it.id })

    private fun transcribeUi(dir: String?, recap: io.github.alalkipgen.autoflow.core.recap.RecapV2?, keep: TranscribeUi?): TranscribeUi {
        if (dir == null || recap == null) return TranscribeUi(available = scenesTx.available)
        val transcript = scenesTx.transcriptOf(dir, recap)
        val plan = scenesTx.plan(dir, recap)
        val unfinished = currentTranscribe(jobs?.jobs?.value.orEmpty(), dir)
        return TranscribeUi(
            available = scenesTx.available,
            todoScenes = plan?.scenes?.size ?: 0,
            requests = plan?.requests ?: 0,
            transcribedScenes = transcript?.scenes?.size ?: 0,
            lines = transcript?.lines?.size ?: 0,
            confirm = keep?.confirm,
            job = unfinished,
        )
    }

    private fun setTranscribe(change: (TranscribeUi) -> TranscribeUi) {
        _state.value = _state.value.copy(transcribe = change(_state.value.transcribe))
    }

    /**
     * "Transcribe picked scenes": the picked scenes without `.srt` lines and not transcribed yet. First call shows the
     * confirm card (scenes, requests, today's usage); call again with [confirmed] = true after Start.
     *
     * With the app's [jobs] runner this is a TRANSCRIBE job of this project (audit F11, spec §5): one item per request,
     * paced, resumable, cancelled from here or from Jobs; the lines are saved into this project even when another one
     * is opened, and a scene already transcribed is never sent again. Without a runner the same items run here.
     */
    fun transcribeScenes(confirmed: Boolean = false): Job? {
        val open = session.open ?: return null
        val recap = open.project.recap ?: return null
        if (!scenesTx.available) { sayNotice(UiNotice(UiText.TRANSCRIPTION_UNAVAILABLE)); return null }
        val movie = recap.movie ?: run { sayNotice(UiNotice(UiText.IMPORT_MOVIE_FIRST)); return null }
        if (!movie.hasAudio) { sayNotice(UiNotice(UiText.MOVIE_NO_SOUND_TO_TRANSCRIBE)); return null }
        if (_state.value.busy || _state.value.recording != null) return null
        val plan = scenesTx.plan(open.dir, recap) ?: return null
        if (plan.scenes.isEmpty()) {
            sayNotice(UiNotice(if (RecapRulesV2.pickedScenes(recap).isEmpty()) UiText.PICK_SCENES_FIRST else UiText.SCENES_ALREADY_TRANSCRIBED))
            return null
        }
        if (!confirmed) {
            setTranscribe { it.copy(confirm = TranscribeConfirm(plan.scenes.size, plan.requests, plan.audioMs)) }
            val counter = usage ?: return null
            return scope.launch {
                val today = runCatching { counter.today().values.sum() }.getOrNull()
                setTranscribe { t -> t.copy(confirm = t.confirm?.copy(usedToday = today)) }
            }
        }
        setTranscribe { it.copy(confirm = null) }
        val runner = jobs
        busyNotice(UiNotice(UiText.TRANSCRIBING_PICKED), runnerJobId = runner?.let { PENDING_RUNNER_JOB })
        val items = plan.items
        return scope.launch {
            try {
                if (runner != null) {
                    val job = AiJob(
                        id = "tx-${open.project.id}-${clock.now().toEpochMilliseconds()}-${kotlin.random.Random.nextInt(0x1000000).toString(16)}",
                        kind = AiJobKind.TRANSCRIBE, project = open.dir, items = items, rpm = TRANSCRIBE_RPM,
                        inputKey = jobInputKey(open.project.id, items),
                    )
                    return@launch followTranscribeJob(runner, open.dir, open.project.name, job)
                }
                for ((n, item) in items.withIndex()) {
                    if (session.open?.dir != open.dir) return@launch doneNotice(UiNotice(UiText.TRANSCRIBE_PROJECT_CHANGED, "n" to n, "total" to items.size))
                    _state.value = _state.value.copy(job = "Transcribing: request ${n + 1} of ${items.size}…", jobNotice = UiNotice(UiText.TRANSCRIBE_PROGRESS, "n" to n + 1, "total" to items.size), progress = n.toDouble() / items.size)
                    try {
                        scenesTx.runItem(open.dir, item)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        refresh()
                        return@launch done(e.message)
                    }
                }
                refresh()
                doneNotice(transcribedNotice(), ok = true)
            } finally {
                if (_state.value.projectDir == open.dir && _state.value.job != null) done(null)
            }
        }.also { scriptJob = it }
    }

    /** Closes the Transcribe confirm card; nothing is sent. */
    fun dismissTranscribe() = setTranscribe { it.copy(confirm = null) }

    /** Stops Transcribe: the job is cancelled (scenes already saved stay in the project). */
    fun cancelTranscribe() {
        val runner = jobs
        val id = _state.value.transcribe.job?.id
        if (runner != null && id != null) {
            scope.launch { runner.cancel(id) }
        } else if (_state.value.job?.startsWith(TRANSCRIBING_PREFIX) == true) {
            scriptJob?.cancel()
            scriptJob = null
            refresh()
            doneNotice(UiNotice(UiText.TRANSCRIBE_STOPPED_KEEP))
        }
    }

    /** Retry / Resume this project's paused or failed Transcribe job: only its remaining requests are sent. */
    fun retryTranscribe(): Job? {
        val open = session.open ?: return null
        val runner = jobs ?: return null
        val job = _state.value.transcribe.job?.takeIf { it.project == open.dir && (it.state == AiJobState.FAILED || it.state == AiJobState.PAUSED) } ?: return null
        if (_state.value.busy || _state.value.recording != null) return null
        busyNotice(UiNotice(UiText.TRANSCRIBING_PICKED), runnerJobId = job.id)
        return scope.launch {
            try {
                followTranscribeJob(runner, open.dir, open.project.name, job)
            } finally {
                if (_state.value.projectDir == open.dir && _state.value.job != null) done(null)
            }
        }.also { scriptJob = it }
    }

    /** Enqueues (or resumes) [job] and shows its progress here until it ends or another project is opened. */
    private suspend fun followTranscribeJob(runner: AiJobRunner, dir: String, name: String, job: AiJob) {
        val id = runner.enqueue(job)
        if (session.open?.dir != dir) return
        _state.value = _state.value.copy(runnerJobId = id)
        if (id != job.id && runner.jobs.value.any { it.id == id && it.state in ACTIVE_JOB_STATES }) {
            sayNotice(UiNotice(UiText.JOB_ALREADY_RUNNING))
        }
        var seen: AiJob? = null
        var left = false
        runner.jobs.first { list ->
            val j = list.firstOrNull { it.id == id }
            if (session.open?.dir != dir) { left = true; return@first true }
            if (j != null) {
                seen = j
                setTranscribe { it.copy(job = j) }
            }
            (j == null && seen != null) || j?.state in ENDED_JOB_STATES
        }
        val end = runner.jobs.value.firstOrNull { it.id == id }
        if (left) {
            if (!_state.value.busy) sayNotice(UiNotice(UiText.TRANSCRIBE_BACKGROUND, "name" to name))
            return
        }
        setTranscribe { it.copy(job = end?.takeIf { e -> e.state !in FINISHED_JOB_STATES }) }
        refresh()
        when {
            end == null || end.state == AiJobState.CANCELLED -> doneNotice(UiNotice(UiText.TRANSCRIBE_CANCELLED_KEEP))
            end.state == AiJobState.DONE -> doneNotice(transcribedNotice(), ok = true)
            end.state == AiJobState.PAUSED -> doneNotice(end.error?.let(::diagnostic) ?: UiNotice(UiText.TRANSCRIBE_RETRY_BUSY))
            else -> doneNotice(UiNotice(UiText.TRANSCRIBE_RETRY_DETAIL, "detail" to (end.error?.let(::diagnostic) ?: UiNotice(UiText.TRANSCRIBE_STOPPED))))
        }
    }

    private fun transcribedNotice(): UiNotice {
        val t = _state.value.transcribe
        return UiNotice(UiText.TRANSCRIBE_FINISHED, "lines" to t.lines, "scenes" to t.transcribedScenes)
    }

    /** One TRANSCRIBE job item of the project in [dir] ([RecapJobProcessor], both platforms); see [SceneTranscriber.runItem]. */
    suspend fun transcribeJobItem(dir: String, item: String) {
        scenesTx.runItem(dir, item)
        if (session.open?.dir == dir) timedAiStage(AiTimingStage.UI_UPDATE) { refresh() }
    }

    /** Whether [item]'s scenes are already in the transcript of the project in [dir] (no request then). */
    fun isTranscribeItemDone(dir: String, item: String): Boolean = scenesTx.isItemDone(dir, item)

    // style/register are now per-story; kept as local state until writeScript() is called
    private var _style: RecapStyle = RecapStyle.PLAIN
    private var _register: RecapRegister = RecapRegister.SPOKEN

    fun setStyle(style: RecapStyle) {
        _style = style
    }

    /** Spoken (casual) vs Written (formal) Burmese register for TTS (5B-7). */
    fun setRegister(register: RecapRegister) {
        _register = register
    }

    /** Hook style for the first narration block (5B-7). Persisted to [RecapV2.hookStyle]. */
    fun setHookStyle(hook: io.github.alalkipgen.autoflow.core.recap.RecapHookStyle) {
        update { it.copy(hookStyle = hook) }
    }

    /** Credit / source line appended to the last block (5B-7). Persisted to [RecapV2.creditLine]. */
    fun setCreditLine(credit: String) {
        update { it.copy(creditLine = credit) }
    }

    /** ✨ Write script (Gemini): one narration text per picked scene; the new `rs` replaces the current one, not approved. */
    fun writeScript(): Job? {
        val origin = session.open ?: return null
        val recap = origin.project.recap ?: return null
        val summary = _state.value.summary
        val subtitlePath = recap.sources.subtitles
        val cues = subtitlePath?.let { Srt.parse(session.readSource(SUBTITLES_FILE)) }.orEmpty()
        // 5B-1: transcribed scene lines are used like `.srt` lines (spec §7, ≤ 600 characters per scene).
        val transcript = session.open?.dir?.let { scenesTx.transcriptOf(it, recap) }?.takeIf { it.lines.isNotEmpty() }
        val gate = RecapRulesV2.canGenerateScript(recap, summary, if (cues.isEmpty() && transcript == null) "" else "srt")
        gate.blockers.firstOrNull()?.let { sayNotice(it.notice()); return null }
        val gen = generator ?: run { sayNotice(UiNotice(UiText.GEMINI_UNAVAILABLE)); return null }
        if (_state.value.busy) return null
        val picked = RecapRulesV2.pickedScenes(recap)
        val characters = project()?.characters.orEmpty()
        val names = characters.mapNotNull { c ->
            if (c.nameMy.isBlank()) null else "${c.nameMy} (${c.token})"
        }
        val brief = RecapBrief(
            title = project()?.name.orEmpty(),
            summary = summary,
            style = _style,
            register = _register,
            scenes = picked.map { s ->
                val dialogue = Srt.textIn(cues, s.startMs, s.endMs, RecapPrompts.DIALOGUE_CHARS)
                SceneBrief(s.startMs, s.endMs, dialogue.ifEmpty { transcript?.textIn(s.startMs, s.endMs, RecapPrompts.DIALOGUE_CHARS).orEmpty() })
            },
            names = names,
            hookStyle = recap.hookStyle,
            creditLine = recap.creditLine,
        )
        val run = AiJob("script-${clock.now().toEpochMilliseconds()}-${kotlin.random.Random.nextInt().toUInt()}", AiJobKind.SCRIPT, origin.dir)
        val trace = AiRunTiming(id = run.id)
        var terminal = AiJobState.CANCELLED
        busyNotice(UiNotice(UiText.SCRIPT_WRITING))
        _state.value = _state.value.copy(scriptRunId = run.id)
        return scope.launch {
            try {
                trace.stage(AiTimingStage.RESPONSE)
                val r = withContext(trace) { gen.write(brief) }
                val current = session.open
                if (current?.dir != origin.dir || current.project.id != origin.project.id || _state.value.scriptRunId != run.id) return@launch
                when (r) {
                    is AiResult.Ok -> {
                        val blocks = picked.zip(r.value) { scene, text ->
                            io.github.alalkipgen.autoflow.core.recap.RecapBlock(
                                id = io.github.alalkipgen.autoflow.core.recap.migratedBlockId(picked.indexOf(scene), scene.startMs, scene.endMs),
                                clips = listOf(io.github.alalkipgen.autoflow.core.recap.RecapClip(scene.startMs, scene.endMs)),
                                textMy = text,
                            )
                        }
                        val saved = withContext(trace) { timedAiStage(AiTimingStage.SAVE) {
                            saveStory(RecapStory(source = RecapStory.SOURCE_GEMINI, style = brief.style, register = brief.register, blocks = blocks))
                        } }
                        terminal = if (saved) AiJobState.DONE else AiJobState.FAILED
                        doneNotice(if (saved) UiNotice(UiText.SCRIPT_WRITTEN, "n" to blocks.size) else null, ok = saved)
                    }
                    is AiResult.RateLimited -> { terminal = AiJobState.PAUSED; done(RATE_LIMITED) }
                    is AiResult.Failed -> { terminal = AiJobState.FAILED; doneNotice(UiNotice(UiText.GEMINI_DETAIL, "detail" to r.msg)) }
                    is AiResult.Deferred -> { terminal = AiJobState.PAUSED; done(r.instruction) }
                }
            } finally {
                trace.clear()
                if (_state.value.scriptRunId == run.id) done(null)
                withContext(NonCancellable) {
                    jobs?.recordForeground(run.copy(state = terminal, timings = trace.samples, timingsUpdatedAt = clock.now().toEpochMilliseconds()))
                }
            }
        }.also { scriptJob = it }
    }

    /** Stops ✨ Write script; nothing changes. */
    fun cancelScript() {
        scriptJob?.cancel()
        scriptJob = null
        doneNotice(UiNotice(UiText.SCRIPT_STOPPED_UNCHANGED))
    }

    /** ✎ Write it myself: a story with one empty block per picked scene. */
    fun writeManually(): Boolean {
        val recap = recap() ?: return false
        RecapRulesV2.canWriteManually(recap).blockers.firstOrNull()?.let { sayNotice(it.notice()); return false }
        val picked = RecapRulesV2.pickedScenes(recap)
        val blocks = picked.mapIndexed { i, scene ->
            io.github.alalkipgen.autoflow.core.recap.RecapBlock(
                id = io.github.alalkipgen.autoflow.core.recap.migratedBlockId(i, scene.startMs, scene.endMs),
                clips = listOf(io.github.alalkipgen.autoflow.core.recap.RecapClip(scene.startMs, scene.endMs)),
            )
        }
        return saveStory(RecapStory(source = RecapStory.SOURCE_MANUAL, style = _style, register = _register, blocks = blocks))
    }

    /** Saves block [index] with [text]; approval is cleared. Updates v2 RecapStory. */
    fun saveSegment(index: Int, text: String): Boolean {
        val story = currentStory() ?: run {
            // Fallback: v1 RecapScript path (legacy)
            val script = _state.value.script ?: return false
            if (index !in script.segments.indices) return false
            val segments = script.segments.mapIndexed { i, s -> if (i == index) s.copy(textMy = text.trim()) else s }
            return saveScript(script.copy(segments = segments))
        }
        if (index !in story.blocks.indices) return false
        val blocks = story.blocks.mapIndexed { i, b -> if (i == index) b.copy(textMy = text.trim()) else b }
        return saveStory(story.copy(blocks = blocks))
    }

    /**
     * Caption timing of block [index] (per-chunk caption timing, D-53; audit F05): saves
     * `recap.captions.chunkEdits[block id] = [startMs, endMs]` — ms into the block's voice take (studio-v2 §2) — the
     * window its caption chunks share (burned in and in `.srt`, the same cues). The story is not touched: its clips
     * (the movie cuts), text and approval stay as they are. With a take, [endMs] must lie inside it.
     */
    fun saveSegmentTiming(index: Int, startMs: Long, endMs: Long): Boolean {
        if (_state.value.busy) return false
        val recap = recap() ?: return false
        val block = currentStory()?.blocks?.getOrNull(index) ?: return false
        val start = startMs.coerceAtLeast(0L)
        if (endMs <= start) { sayNotice(UiNotice(UiText.CAPTION_END_AFTER_START)); return false }
        val take = recap.takeFor(block.id)
        if (take != null && endMs > take.durationMs) { sayNotice(UiNotice(UiText.CAPTION_END_INSIDE_VOICE, "duration" to seconds(take.durationMs))); return false }
        val saved = update { it.copy(captions = it.captions.copy(chunkEdits = it.captions.chunkEdits + (block.id to listOf(start, endMs)))) }
        if (saved) sayNotice(UiNotice(UiText.CAPTION_TIMING_SAVED))
        return saved
    }

    /** Block [index]'s captions back to automatic timing (its chunk edges are removed, audit F05). */
    fun resetCaptionTiming(index: Int): Boolean {
        if (_state.value.busy) return false
        val block = currentStory()?.blocks?.getOrNull(index) ?: return false
        if (recap()?.captions?.chunkEdits?.containsKey(block.id) != true) return true
        return update { it.copy(captions = it.captions.copy(chunkEdits = it.captions.chunkEdits - block.id)) }
    }

    /** ✓ Approve script. */
    fun approveScript(): Boolean {
        val recap = recap() ?: return false
        val story = currentStory()
        RecapRulesV2.canApproveScript(recap, story).blockers.firstOrNull()?.let { sayNotice(it.notice()); return false }
        return update { it.copy(storyApproved = true) }
    }

    private fun currentStory(): RecapStory? =
        recap()?.story?.let { id -> (session.readJson(id, RecapStory.serializer()) as? CoreResult.Ok)?.value }

    /** Converts a [CoreResult] from reading a RecapStory artifact to a v1-compatible RecapScript for UI backward compat. */
    private fun storyToScript(result: CoreResult<RecapStory>): RecapScript? {
        val story = (result as? CoreResult.Ok)?.value ?: return null
        return RecapScript(
            source = story.source,
            style = story.style,
            segments = story.blocks.mapIndexed { i, b ->
                val clip = b.clips.firstOrNull()
                RecapSegment(startMs = clip?.inMs ?: (i * 10_000L), endMs = clip?.outMs ?: ((i + 1) * 10_000L), textMy = b.textMy)
            },
        )
    }

    private fun saveStory(story: RecapStory): Boolean {
        val store = session.artifacts() ?: return false
        val bytes = CoreJson.encodeToString(RecapStory.serializer(), story).encodeToByteArray()
        return when (val r = store.put(bytes, ArtifactKind.RECAP_SCRIPT, "json", provider = story.source)) {
            is CoreResult.Ok -> update { it.copy(story = r.value.id, storyApproved = false) }
            is CoreResult.Err -> { say(r.error.message); false }
        }
    }

    private fun saveScript(script: RecapScript): Boolean {
        val store = session.artifacts() ?: return false
        val bytes = CoreJson.encodeToString(RecapScript.serializer(), script).encodeToByteArray()
        return when (val r = store.put(bytes, ArtifactKind.RECAP_SCRIPT, "json", provider = script.source)) {
            is CoreResult.Ok -> update { it.copy(story = r.value.id, storyApproved = false) }
            is CoreResult.Err -> { say(r.error.message); false }
        }
    }

    // --- 4 · Voice (spec §5, D-34) -----------------------------------------------------------------------------

    val canRecord: Boolean get() = recorder != null

    fun setVoiceSource(source: VoiceSource) {
        if (source == VoiceSource.REPLICATED) return // hidden: needs a paid tier (D-34)
        // v2: voiceSource is implicit from voiceLock; this is a no-op for now
    }

    fun setTtsVoice(voice: String) {
        // v2: voice is stored in voiceLock
        update { r -> r.copy(voiceLock = r.voiceLock?.copy(voice = voice) ?: io.github.alalkipgen.autoflow.core.recap.VoiceLock(provider = "gemini", model = "", voice = voice, lang = "my")) }
    }

    /** ● Record segment [index] into the project's `.cache/` (stopped with [stopRecording]). */
    fun startRecording(index: Int): Job? {
        val rec = recorder ?: run { sayNotice(UiNotice(UiText.RECORDING_UNAVAILABLE)); return null }
        if (_state.value.busy || _state.value.recording != null) return null
        val target = voiceTarget(index) ?: return null
        val path = cachePath(target.dir, "rec-${target.blockId}.${rec.extension}") ?: return null
        return scope.launch {
            when (val r = rec.start(path)) {
                is PlatformResult.Ok -> {
                    recordingInto = target to path
                    _state.value = _state.value.copy(recording = index, message = "● Recording #${index + 1}… read: ${target.spoken}", notice = UiNotice(UiText.RECORDING_READ, "n" to index + 1, "text" to target.spoken), messageOk = true)
                }
                is PlatformResult.Failed -> say(r.reason)
                is PlatformResult.Unsupported -> say(r.reason)
            }
        }
    }

    /** ■ Stop: the recording becomes the segment's newest take (of the project and block it was started for). */
    fun stopRecording(): Job? {
        val index = _state.value.recording ?: return null
        val rec = recorder ?: return null
        val (target, path) = recordingInto ?: return null
        return scope.launch {
            _state.value = _state.value.copy(recording = null)
            recordingInto = null
            val ms = when (val r = rec.stop()) {
                is PlatformResult.Ok -> r.value
                is PlatformResult.Failed -> return@launch say(r.reason)
                is PlatformResult.Unsupported -> return@launch say(r.reason)
            }
            val bytes = withContext(Dispatchers.Default) { session.readFile(path) }?.takeIf { it.isNotEmpty() }
                ?: return@launch sayNotice(UiNotice(UiText.RECORDING_EMPTY))
            val duration = durationOf(path, bytes) ?: ms
            saveTake(target, bytes, rec.extension, TakeSource.RECORDED, duration, Provenance.UNKNOWN)
                ?.let { say(it) } ?: sayNotice(UiNotice(UiText.VOICE_SAVED, "n" to index + 1, "duration" to seconds(duration))).also { ok() }
        }
    }

    fun cancelRecording() {
        recorder?.cancel()
        recordingInto = null
        _state.value = _state.value.copy(recording = null, message = null, notice = null)
    }

    /** Import file: any audio file becomes the segment's newest take. */
    fun importTake(index: Int): Job? {
        val p = picker ?: run { sayNotice(UiNotice(UiText.CHOOSE_FILES_UNAVAILABLE)); return null }
        if (_state.value.busy || _state.value.recording != null) return null
        val target = voiceTarget(index) ?: return null
        return scope.launch {
            val from = when (val r = p.pickFiles(UiText.PICK_RECAP_NARRATION.text(uiLanguage(), "n" to index + 1), AUDIO_EXTENSIONS)) {
                is PlatformResult.Ok -> r.value.firstOrNull() ?: return@launch
                is PlatformResult.Failed -> return@launch say(r.reason)
                is PlatformResult.Unsupported -> return@launch say(r.reason)
            }
            val bytes = withContext(Dispatchers.Default) { session.readFile(from) }?.takeIf { it.isNotEmpty() }
                ?: return@launch sayNotice(UiNotice(UiText.CANNOT_READ_FILE))
            val duration = durationOf(from, bytes) ?: return@launch sayNotice(UiNotice(UiText.AUDIO_UNREADABLE))
            val ext = from.substringAfterLast('.', "m4a").lowercase().takeIf { it in AUDIO_EXTENSIONS } ?: "m4a"
            saveTake(target, bytes, ext, TakeSource.RECORDED, duration, Provenance.UNKNOWN)
                ?.let { say(it) } ?: sayNotice(UiNotice(UiText.VOICE_SAVED, "n" to index + 1, "duration" to seconds(duration))).also { ok() }
        }
    }

    /**
     * One item of a TTS_ALL job (audit F11, spec §5; [RecapJobProcessor] on both platforms): speaks block [blockId]
     * of the project in [dir] — that project as it is on disk, whichever project is open now — with that project's
     * voice lock and style, and stores the take there. Returns when the take is saved or there is nothing to do (the
     * block is gone or has no text, or it already has a current take: [isJobItemDone]). Throws [AiItemPaused] when the
     * locked model is busy (429 / 5xx after retries: the job pauses, never another model, spec §3), otherwise an
     * exception with the reason (the job fails).
     */
    suspend fun speakJobItem(dir: String, blockId: String) {
        if (isJobItemDone(dir, blockId)) return
        val (target, lock, style) = jobTarget(dir, blockId) ?: return
        val msg = speakInto(target, lock, style) ?: return
        if (msg == BLOCK_GONE) return
        if (lastSpeakBusy) throw AiItemPaused(msg)
        error(msg)
    }

    /**
     * Whether block [blockId] of the project in [dir] needs no voice now (spec §5: "finished items are found from the
     * project"): it has a current take (its spoken text has not changed since), no text, or is no longer in the story.
     * False when the project cannot be read (the item then fails with the reason).
     */
    fun isJobItemDone(dir: String, blockId: String): Boolean {
        val recap = session.projectAt(dir)?.recap ?: return false
        val story = storyOf(dir, recap) ?: return false
        val block = story.blocks.firstOrNull { it.id == blockId } ?: return true
        val lexicon = pronunciation
        if (SpokenText.of(block, lexicon).isEmpty()) return true
        return recap.takeFor(blockId)?.let {
            !SpokenText.isStale(it, block, lexicon) && it.voiceStatus(recap.voiceLock) !in setOf(TakeVoiceStatus.DIFFERENT, TakeVoiceStatus.UNKNOWN)
        } == true
    }

    /** The voice target of a job item, from the project on disk; null = the block is gone or has no text. */
    internal fun hasSavedJobClip(dir: String, blockId: String): Boolean {
        val recap = session.projectAt(dir)?.recap ?: return false
        val block = storyOf(dir, recap)?.blocks?.firstOrNull { it.id == blockId } ?: return false
        if (SpokenText.of(block, pronunciation).isBlank()) return false
        val take = recap.takeFor(blockId) ?: return false
        val artifact = (ArtifactStore(fs, dir).get(take.audio) as? CoreResult.Ok)?.value ?: return false
        return fs.exists(joinPath(joinPath(dir, ArtifactStore.ARTIFACTS_DIR), artifact.file)) && isJobItemDone(dir, blockId)
    }

    private fun jobTarget(dir: String, blockId: String): Triple<VoiceTarget, io.github.alalkipgen.autoflow.core.recap.VoiceLock?, RecapStyle>? {
        val project = session.projectAt(dir) ?: error("The project in $dir cannot be read")
        val recap = project.recap ?: error("${project.name} is not a Movie Recap")
        val story = storyOf(dir, recap)
        RecapRulesV2.canVoice(recap, story).blockers.firstOrNull()?.let { error("${project.name}: ${it.text()}") }
        val block = story!!.blocks.firstOrNull { it.id == blockId } ?: return null
        val spoken = SpokenText.of(block, pronunciation).takeIf { it.isNotEmpty() } ?: return null
        val target = VoiceTarget(dir, project.id, block.id, spoken, RecapRules.textHash(spoken), recap.audio.trimSilence)
        return Triple(target, recap.voiceLock, story.style)
    }

    /** True when the last [speakInto] failure was a busy model (job pauses) rather than a hard failure. */
    private var lastSpeakBusy = false

    /** 🔊 one segment with Gemini TTS. */
    fun speak(index: Int): Job? {
        if (tts == null) { sayNotice(UiNotice(UiText.TTS_UNAVAILABLE)); return null }
        if (_state.value.busy || _state.value.recording != null) return null
        val target = voiceTarget(index) ?: return null
        val lock = recap()?.voiceLock
        val style = currentStory()?.style ?: _style
        busyNotice(UiNotice(UiText.VOICE_SPEAKING_ONE, "n" to index + 1))
        return scope.launch {
            try {
                val msg = speakInto(target, lock, style)
                doneNotice(msg?.let(::diagnostic) ?: UiNotice(UiText.VOICE_SPOKEN_ONE, "n" to index + 1), ok = msg == null)
            } finally {
                if (_state.value.job != null) done(null)
            }
        }
    }

    /**
     * 🔊 Speak all: every scene with text and without a current take — none, or one whose spoken text has changed
     * since ([SpokenText.isStale], audit F10) — in order.
     * Shows an ETA card ("N scenes, ~T s — Start / Cancel") before beginning (5A-5, D-51).
     * Call again with [confirmed]=true after the user taps Start.
     *
     * Audit F11: with the app's [jobs] runner this is a TTS_ALL job of this project and these block ids (spec §5):
     * saved, paced per model, resumed after a restart, cancelled from Jobs, each take stored in this project even
     * when another one is opened. The returned coroutine follows the job until it ends (or another project is
     * opened: the job goes on, see Jobs). Without a runner the same per-block step ([speakJobItem]) runs here,
     * stopping at the first failure.
     */
    fun speakAll(confirmed: Boolean = false, includeDifferentVoice: Boolean = false): Job? {
        val open = session.open ?: return null
        val recap = open.project.recap ?: return null
        val story = currentStory()
        RecapRulesV2.canVoice(recap, story).blockers.firstOrNull()?.let { sayNotice(it.notice()); return null }
        if (tts == null) { sayNotice(UiNotice(UiText.TTS_UNAVAILABLE)); return null }
        if (_state.value.busy || _state.value.recording != null) return null
        val lexicon = pronunciation
        val todo = story!!.blocks.withIndex().filter { (_, b) ->
            SpokenText.of(b, lexicon).isNotEmpty() && recap.takeFor(b.id).let {
                it == null || SpokenText.isStale(it, b, lexicon) ||
                    (includeDifferentVoice && it.voiceStatus(recap.voiceLock) in setOf(TakeVoiceStatus.DIFFERENT, TakeVoiceStatus.UNKNOWN))
            }
        }.map { it.index }
        if (todo.isEmpty()) { sayNotice(UiNotice(UiText.VOICE_ALL_READY)); return null }
        // Confirm only the real request count; a configured pacing ceiling is not a runtime ETA.
        if (!confirmed) {
            _state.value = _state.value.copy(speakAllEta = SpeakAllEta(todo.size, null))
            return null
        }
        _state.value = _state.value.copy(speakAllEta = null)
        val runner = jobs
        busyNotice(UiNotice(UiText.VOICE_SPEAKING), runnerJobId = runner?.let { PENDING_RUNNER_JOB })
        val blocks = todo.map { story.blocks[it].id }
        return scope.launch {
            try {
                if (runner != null) return@launch followSpeakAllJob(runner, open.dir, open.project, blocks)
                for ((n, id) in blocks.withIndex()) {
                    if (session.open?.dir != open.dir) return@launch doneNotice(UiNotice(UiText.VOICE_PROJECT_CHANGED, "n" to n, "total" to blocks.size))
                    _state.value = _state.value.copy(job = "Speaking #${todo[n] + 1} (${n + 1} of ${blocks.size})…", jobNotice = UiNotice(UiText.VOICE_PROGRESS, "scene" to todo[n] + 1, "n" to n + 1, "total" to blocks.size), progress = n.toDouble() / blocks.size)
                    try {
                        speakJobItem(open.dir, id)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        return@launch done(e.message)
                    }
                }
                doneNotice(UiNotice(UiText.VOICE_SPOKEN_COUNT, "n" to blocks.size), ok = true)
            } finally {
                if (_state.value.projectDir == open.dir && _state.value.job != null) done(null)
            }
        }.also { scriptJob = it }
    }

    /** Enqueues Speak All as a TTS_ALL job and shows its progress here until it ends (see [speakAll]). */
    private suspend fun followSpeakAllJob(runner: AiJobRunner, dir: String, project: Project, blocks: List<String>) {
        val job = AiJob(
            id = "tts-${project.id}-${clock.now().toEpochMilliseconds()}-${kotlin.random.Random.nextInt(0x1000000).toString(16)}",
            kind = AiJobKind.TTS_ALL, project = dir,
            items = blocks, rpm = TTS_RPM,
            inputKey = jobInputKey(project.id, blocks + listOf(project.recap?.story.toString(),
                project.recap?.voiceLock.toString(), project.recap?.audio?.trimSilence.toString(), pronunciation.toString())),
        )
        val id = runner.enqueue(job)
        if (session.open?.dir != dir) return
        _state.value = _state.value.copy(runnerJobId = id)
        if (id != job.id && runner.jobs.value.any { it.id == id && it.state in ACTIVE_JOB_STATES }) {
            sayNotice(UiNotice(UiText.JOB_ALREADY_RUNNING))
        }
        var seen: AiJob? = null
        var left = false
        runner.jobs.first { list ->
            val j = list.firstOrNull { it.id == id }
            if (j != null) seen = j
            if (session.open?.dir != dir) { left = true; return@first true }
            (j == null && seen != null) || j?.state in ENDED_JOB_STATES
        }
        val end = runner.jobs.value.firstOrNull { it.id == id }
        when {
            left -> if (!_state.value.busy) sayNotice(UiNotice(UiText.VOICE_BACKGROUND, "name" to project.name))
            end == null -> doneNotice(UiNotice(UiText.VOICE_CANCELLED))
            end.state == AiJobState.DONE -> doneNotice(UiNotice(UiText.VOICE_SPOKEN_COUNT, "n" to blocks.size), ok = true)
            else -> doneNotice(end.error?.let(::diagnostic) ?: UiNotice(UiText.VOICE_STOPPED, "state" to end.state))
        }
    }

    /** Dismiss the Speak All ETA card without starting. */
    fun cancelSpeakAll() {
        _state.value = _state.value.copy(speakAllEta = null)
    }

    /** Speaks [target] with Gemini TTS under [lock] and stores the take in [target]'s project. null = saved. */
    private suspend fun speakInto(target: VoiceTarget, lock: io.github.alalkipgen.autoflow.core.recap.VoiceLock?, storyStyle: RecapStyle): String? {
        val provider = tts ?: return "Text to speech is not available here"
        val style = when (storyStyle) {
            io.github.alalkipgen.autoflow.core.recap.RecapStyle.PLAIN -> "calm, clear movie recap narrator"
            io.github.alalkipgen.autoflow.core.recap.RecapStyle.COMMENTARY -> "lively movie recap narrator with feeling"
        }
        // Voice lock (D-35, spec §3): pass the locked model so the provider never switches. A lock made by a voice
        // chip before any take has model "" — that means "no model yet" (the provider uses the default lock), never
        // a model called "".
        val lockedModel = lock?.model?.takeIf { it.isNotBlank() }
        val ttsVoice = lock?.voice ?: "Kore"
        // The speak-as text with the lexicon (SpokenText, audit F10): the same text the take's hash is made of.
        val r = provider.speak(target.spoken, VoiceRef(ttsVoice), "my", style, lockedModel)
        lastSpeakBusy = r.isBusy()
        return when (r) {
            is AiResult.Ok -> {
                val ms = timedAiStage(AiTimingStage.PARSE) { PcmWav.parse(r.value)?.durationMs } ?: return "The speech audio cannot be read"
                // Provenance of this answer (F10): the model that answered (the locked one when the provider does not
                // say), the voice it spoke with, now. The first TTS take sets the lock if it has no model yet.
                val answered = r.usage?.model?.takeIf { it.isNotBlank() }
                val made = Provenance(
                    provider = lock?.provider?.takeIf { it.isNotBlank() } ?: TTS_PROVIDER,
                    model = answered ?: lockedModel,
                    voice = ttsVoice,
                    lockIfNone = if (lockedModel == null && answered != null) {
                        io.github.alalkipgen.autoflow.core.recap.VoiceLock(TTS_PROVIDER, answered, ttsVoice, "my")
                    } else null,
                )
                timedAiStage(AiTimingStage.SAVE) { saveTake(target, r.value, "wav", TakeSource.TTS, ms, made) }
            }
            is AiResult.RateLimited -> "${lockedModel ?: "Gemini TTS"} is busy · Resume (${RATE_LIMITED})"
            is AiResult.Failed -> if (lastSpeakBusy) "${lockedModel ?: "Gemini TTS"} is busy · Resume (${r.msg})"
                else "Gemini TTS: ${r.msg}"
            is AiResult.Deferred -> r.instruction
        }
    }

    /**
     * Where a voice take belongs, fixed before any work suspends (audit F03): the folder and id of the project, the
     * block, and the exact text it speaks ([SpokenText]: the speak-as text, else the written text, with the
     * lexicon, F10). The user may open another project or edit the script while TTS, a recording, an import or a
     * clean runs: the result then still goes to this project — or is dropped when this block is gone — and never
     * into whatever project happens to be open.
     */
    private data class VoiceTarget(
        val dir: String,
        val projectId: String,
        val blockId: String,
        val spoken: String,
        val hash: String,
        val trimSilence: Boolean,
    )

    /** Who made a take (F10). [UNKNOWN] for recordings / imports: no provider, model or voice is invented. */
    private data class Provenance(
        val provider: String?,
        val model: String?,
        val voice: String?,
        val lockIfNone: io.github.alalkipgen.autoflow.core.recap.VoiceLock? = null,
    ) {
        companion object {
            val UNKNOWN = Provenance(null, null, null)
        }
    }

    /** The target for scene [index] when voicing is allowed now (else the blocker is shown and null returned). */
    private fun voiceTarget(index: Int): VoiceTarget? {
        val open = session.open ?: return null
        val recap = open.project.recap ?: return null
        val story = currentStory()
        RecapRulesV2.canVoice(recap, story).blockers.firstOrNull()?.let { sayNotice(it.notice()); return null }
        val block = story!!.blocks.getOrNull(index) ?: run { sayNotice(UiNotice(UiText.SCENE_NOT_IN_SCRIPT, "n" to index + 1)); return null }
        val spoken = SpokenText.of(block, pronunciation)
        if (spoken.isEmpty()) { sayNotice(UiNotice(UiText.SCENE_NO_NARRATION, "n" to index + 1)); return null }
        return VoiceTarget(open.dir, open.project.id, block.id, spoken, RecapRules.textHash(spoken), recap.audio.trimSilence)
    }

    /** A new take: long pauses cut first when Sound → "Cut long pauses" is on (5B-4), then saved. null = saved. */
    private suspend fun saveTake(t: VoiceTarget, bytes: ByteArray, ext: String, source: TakeSource, durationMs: Long, made: Provenance): String? {
        val (b, e, ms) = trimPauses(t, bytes, ext, durationMs)
        return storeTake(t, b, e, source, ms, made)
    }

    /**
     * The take with every pause longer than 0.35 s cut to 0.2 s ([SilenceTrim], audit F09) as a WAV, when the
     * target asks for it; the take as it was when it does not, no pause is that long, ffmpeg is missing or fails, or
     * the result is empty. Works in the target project's `.cache/`.
     */
    private suspend fun trimPauses(t: VoiceTarget, bytes: ByteArray, ext: String, durationMs: Long): Triple<ByteArray, String, Long> {
        val asIs = Triple(bytes, ext, durationMs)
        if (!t.trimSilence) return asIs
        val runner = media?.runner ?: return asIs
        val ffmpeg = media.ffmpeg() ?: return asIs
        val src = cachePath(t.dir, "trim-in-${t.blockId}.$ext") ?: return asIs
        val out = cachePath(t.dir, "trim-out-${t.blockId}.wav") ?: return asIs
        return withContext(Dispatchers.Default) {
            runCatching { fs.writeDurably(src, bytes) }.getOrElse { return@withContext asIs }
            val r = SilenceTrim(runner, ffmpeg).trim(src, out, t.dir, TRIM_TIMEOUT_MS)
            val trimmed = if (r is SilenceTrim.Result.Trimmed) session.readFile(out) else null
            val ms = trimmed?.let { PcmWav.parse(it)?.durationMs }
            if (trimmed == null || ms == null || ms < MIN_TRIMMED_MS) asIs else Triple(trimmed, "wav", ms)
        }
    }

    /**
     * Stores the audio in the target project's artifacts and appends the take to that project (audit F03 / F06):
     * read-modify-write of the target folder under its lock, so takes already there — older recordings, other
     * generations — stay; the new take becomes the block's chosen one; the same audio twice is one take. Dropped
     * (message, nothing referenced) when the block is no longer in that project's story. null = saved.
     */
    private fun storeTake(t: VoiceTarget, bytes: ByteArray, ext: String, source: TakeSource, durationMs: Long, made: Provenance): String? {
        if (!hasBlock(t.dir, t.projectId, t.blockId)) return BLOCK_GONE
        val inputs = mapOf("block" to t.blockId, "textHash" to t.hash)
        val audio = when (val r = ArtifactStore(fs, t.dir, clock).put(
            bytes, ArtifactKind.AUDIO, ext, inputs = inputs, provider = made.provider ?: source.name.lowercase(), model = made.model,
        )) {
            is CoreResult.Ok -> r.value.id
            is CoreResult.Err -> return r.error.message
        }
        val take = BlockTake(
            block = t.blockId, audio = audio, source = source, provider = made.provider, model = made.model,
            voice = made.voice, durationMs = durationMs, spokenHash = t.hash, createdAt = clock.now(),
        )
        var gone = false
        val r = session.update(t.dir, t.projectId) { p ->
            val recap = p.recap
            if (recap == null || storyOf(t.dir, recap)?.blocks?.any { it.id == t.blockId } != true) {
                gone = true
                return@update p
            }
            val takes = if (recap.takes.any { it.block == t.blockId && it.audio == audio }) recap.takes else recap.takes + take
            val lock = made.lockIfNone?.takeIf { recap.voiceLock?.model.isNullOrBlank() } ?: recap.voiceLock
            p.copy(recap = recap.copy(takes = takes, chosenTakes = recap.chosenTakes + (t.blockId to audio), voiceLock = lock))
        }
        if (gone) return BLOCK_GONE
        return when (r) {
            is CoreResult.Ok -> { if (session.open?.dir == t.dir) refresh(); null }
            is CoreResult.Err -> r.error.message
        }
    }

    /** Whether block [blockId] is in the story of project [projectId] in [dir] as it is on disk now. */
    private fun hasBlock(dir: String, projectId: String, blockId: String): Boolean {
        val project = session.projectAt(dir)?.takeIf { it.id == projectId } ?: return false
        val recap = project.recap ?: return false
        return storyOf(dir, recap)?.blocks?.any { it.id == blockId } == true
    }

    private fun storyOf(dir: String, recap: io.github.alalkipgen.autoflow.core.recap.RecapV2): RecapStory? {
        val id = recap.story ?: return null
        val bytes = (ArtifactStore(fs, dir, clock).readBytes(id) as? CoreResult.Ok)?.value ?: return null
        return runCatching { CoreJson.decodeFromString(RecapStory.serializer(), bytes.decodeToString()) }.getOrNull()
    }

    private fun cachePath(dir: String, name: String): String? {
        val cache = joinPath(dir, ProjectSession.CACHE_DIR)
        return try {
            fs.createDirectories(cache)
            joinPath(cache, name)
        } catch (e: Exception) {
            null
        }
    }

    // --- fit check (card 5, spec §6) --------------------------------------------------------------------------

    /** The movie's sound under the narration: [MOVIE_VOLUMES] (Off 0 · Low 0.2 · Full 1.0). */
    fun setMovieVolume(volume: Double) {
        if (_state.value.busy) return
        update { it.copy(audio = it.audio.copy(movieVolume = volume.coerceIn(0.0, 1.0))) }
    }

    /** Changes the sound settings (5B-4); used when the video is rendered. */
    fun setAudio(change: (io.github.alalkipgen.autoflow.core.recap.RecapAudio) -> io.github.alalkipgen.autoflow.core.recap.RecapAudio) {
        if (_state.value.busy) return
        update { it.copy(audio = change(it.audio)) }
    }

    /** Picks a music file and keeps it as the background music (an `au` artifact; the old one stays on disk). */
    fun importBgm(): Job? {
        val p = picker ?: run { sayNotice(UiNotice(UiText.CHOOSE_FILES_UNAVAILABLE)); return null }
        if (_state.value.busy || _state.value.recording != null) return null
        return scope.launch {
            val from = when (val r = p.pickFiles(UiText.PICK_RECAP_MUSIC.text(uiLanguage()), AUDIO_EXTENSIONS)) {
                is PlatformResult.Ok -> r.value.firstOrNull() ?: return@launch
                is PlatformResult.Failed -> return@launch say(r.reason)
                is PlatformResult.Unsupported -> return@launch say(r.reason)
            }
            val bytes = withContext(Dispatchers.Default) { session.readFile(from) }?.takeIf { it.isNotEmpty() }
                ?: return@launch sayNotice(UiNotice(UiText.CANNOT_READ_FILE))
            val ext = from.substringAfterLast('.', "m4a").lowercase().takeIf { it in AUDIO_EXTENSIONS } ?: "m4a"
            val store = session.artifacts() ?: return@launch sayNotice(UiNotice(UiText.NO_PROJECT_IS_OPEN))
            val name = from.substringAfterLast('/').substringAfterLast('\\')
            when (val r = store.put(bytes, io.github.alalkipgen.autoflow.core.model.ArtifactKind.AUDIO, ext, inputs = mapOf("role" to "bgm", BGM_NAME_INPUT to name), provider = "imported")) {
                is CoreResult.Ok -> if (update { it.copy(audio = it.audio.copy(bgm = r.value.id)) }) { sayNotice(UiNotice(UiText.BGM_SET, "name" to name)); ok() }
                is CoreResult.Err -> say(r.error.message)
            }
        }
    }

    /** Changes the picture settings (5B-2): blur boxes, the space around the movie, captions on a box. */
    fun setVisual(change: (io.github.alalkipgen.autoflow.core.recap.RecapVisual) -> io.github.alalkipgen.autoflow.core.recap.RecapVisual) {
        if (_state.value.busy) return
        update { it.copy(visual = change(it.visual)) }
    }

    /**
     * Puts the blur box of [preset] at [rect] (0–1 of the movie picture) — the end of a drag / pinch on the live
     * preview (5A-8); kept inside the picture, saved at once.
     */
    fun setMaskRect(preset: io.github.alalkipgen.autoflow.core.recap.MaskPreset, rect: List<Float>) {
        setVisual { io.github.alalkipgen.autoflow.core.recap.RecapVisuals.setRect(it, preset, rect) }
    }

    /**
     * "Exact frame" (D-42): the still the export will show at [atMs] (default: the first caption's middle), made by
     * the export compiler — blur boxes, background, looks, watermark and burned-in captions exactly as rendered.
     */
    suspend fun exactFrame(atMs: Long? = null): io.github.alalkipgen.autoflow.ui.export.RecapFrames.Result {
        val f = frames ?: return io.github.alalkipgen.autoflow.ui.export.RecapFrames.Result.Failed(io.github.alalkipgen.autoflow.ui.export.RecapFrames.NO_FFMPEG)
        val t = atMs ?: withContext(Dispatchers.Default) { f.firstTime() }
            ?: return io.github.alalkipgen.autoflow.ui.export.RecapFrames.Result.Failed("Import the movie and write the script first.")
        return withContext(Dispatchers.Default) { f.frame(t) }
    }

    /** Changes the caption look (5B-8): size, colour, box. */
    fun setCaptionStyle(change: (io.github.alalkipgen.autoflow.core.recap.CaptionStyle) -> io.github.alalkipgen.autoflow.core.recap.CaptionStyle) {
        if (_state.value.busy) return
        update { it.copy(captionStyle = change(it.captionStyle)) }
    }

    /** Turns one look on or off; the order stays Mirror, Zoom, Fill, Warm. */
    fun toggleLook(look: Look) {
        if (_state.value.busy) return
        update { r -> r.copy(looks = Look.entries.filter { if (it == look) it !in r.looks else it in r.looks }) }
    }

    fun setBurnCaptions(on: Boolean) {
        if (_state.value.busy) return
        update { it.copy(captions = it.captions.copy(burn = on)) }
    }

    /** Sets the Smart Fit choice of story block [blockId] (5A-8, studio-v2 §8; by block id, audit F07). */
    fun setFitChoice(blockId: String, choice: io.github.alalkipgen.autoflow.core.recap.FitChoice) {
        if (_state.value.busy) return
        if (currentStory()?.blocks?.any { it.id == blockId } != true) return
        update { it.copy(fit = it.fit + (blockId to choice.name)) }
    }

    // --- Brand Kit (5B-3, D-51) -------------------------------------------------------------------------

    /**
     * Save the current Recap settings as a Library Brand Kit with [name] (5B-3).
     * Copies any logo/watermark from the project's `library/` folder.
     */
    fun saveBrandKit(name: String) {
        library?.saveBrandKit(name) ?: sayNotice(UiNotice(UiText.LIBRARY_UNAVAILABLE))
    }

    /**
     * Apply a Library Brand Kit to this project: copies files and restores caption style (5B-3).
     */
    fun applyBrandKit(id: String) {
        library?.applyBrandKit(id) ?: sayNotice(UiNotice(UiText.LIBRARY_UNAVAILABLE))
        refresh()
    }

    // --- BGM from Library (5B-4, D-51) ------------------------------------------------------------------

    /**
     * Pick a music item from the Library and set it as the BGM for this project (5B-4).
     * Copies the file into the project's `source/` folder and stores it as an `au` artifact.
     */
    fun setBgmFromLibrary(musicId: String): Job? {
        val lib = library ?: run { sayNotice(UiNotice(UiText.LIBRARY_UNAVAILABLE)); return null }
        if (_state.value.busy || _state.value.recording != null) return null
        return scope.launch {
            val path = lib.applyMusicAsBgm(musicId) ?: return@launch
            val bytes = withContext(Dispatchers.Default) { session.readFile(path) }?.takeIf { it.isNotEmpty() }
                ?: return@launch sayNotice(UiNotice(UiText.MUSIC_UNREADABLE))
            val name = path.substringAfterLast('/')
            val store = session.artifacts() ?: return@launch sayNotice(UiNotice(UiText.NO_PROJECT_IS_OPEN))
            when (val r = store.put(bytes, io.github.alalkipgen.autoflow.core.model.ArtifactKind.AUDIO, path.substringAfterLast('.', "m4a"),
                inputs = mapOf("role" to "bgm", BGM_NAME_INPUT to name), provider = "library")) {
                is io.github.alalkipgen.autoflow.core.CoreResult.Ok ->
                    if (update { it.copy(audio = it.audio.copy(bgm = r.value.id)) }) sayNotice(UiNotice(UiText.BGM_SET, "name" to name))
                is io.github.alalkipgen.autoflow.core.CoreResult.Err -> say(r.error.message)
            }
        }
    }

    // --- Background image (5B-2, D-51) ------------------------------------------------------------------

    /**
     * Pick an image file from the file picker and use it as the background image (FrameBackground.IMAGE).
     * Copies the file into the project's `library/` folder and sets `recap.visual.backgroundImageFile`.
     */
    fun importBackgroundImage(): Job? {
        val p = picker ?: run { sayNotice(UiNotice(UiText.CHOOSE_FILES_UNAVAILABLE)); return null }
        if (_state.value.busy || _state.value.recording != null) return null
        return scope.launch {
            val from = when (val r = p.pickFiles(UiText.PICK_RECAP_BACKGROUND.text(uiLanguage()), IMAGE_EXTENSIONS)) {
                is io.github.alalkipgen.autoflow.platform.PlatformResult.Ok -> r.value.firstOrNull() ?: return@launch
                is io.github.alalkipgen.autoflow.platform.PlatformResult.Failed -> return@launch say(r.reason)
                is io.github.alalkipgen.autoflow.platform.PlatformResult.Unsupported -> return@launch say(r.reason)
            }
            val bytes = withContext(Dispatchers.Default) { session.readFile(from) }?.takeIf { it.isNotEmpty() }
                ?: return@launch sayNotice(UiNotice(UiText.CANNOT_READ_FILE))
            val dir = session.open?.dir ?: return@launch sayNotice(UiNotice(UiText.NO_PROJECT_IS_OPEN))
            val libDir = io.github.alalkipgen.autoflow.core.io.joinPath(dir, "library")
            val name = from.substringAfterLast('/').substringAfterLast('\\')
            val dest = io.github.alalkipgen.autoflow.core.io.joinPath(libDir, name)
            runCatching { fs.createDirectories(libDir); fs.writeDurably(dest, bytes) }
                .onFailure { return@launch sayNotice(UiNotice(UiText.IMAGE_SAVE_FAILED, "detail" to it.message)) }
            // Store path relative to project dir
            val rel = "library/$name"
            update { it.copy(visual = it.visual.copy(
                background = io.github.alalkipgen.autoflow.core.recap.FrameBackground.IMAGE,
                backgroundImageFile = dest,
            )) }
            sayNotice(UiNotice(UiText.BACKGROUND_SET, "name" to name))
        }
    }

    // --- Clean tab (D-54) ---------------------------------------------------------------------------

    /**
     * Polish a take: cuts its long pauses ([trimPauses], whatever the Sound switch says — this is the explicit
     * "clean" action) and adds the result as a new take of the same block, keeping the original take, its spoken
     * hash and provenance (audit F03 / F06: bound to the project and block it was started for).
     */
    fun cleanTake(index: Int): Job? {
        if (_state.value.busy || _state.value.recording != null) return null
        val recap = recap() ?: return null
        val open = session.open ?: return null
        val block = currentStory()?.blocks?.getOrNull(index) ?: return sayNotice(UiNotice(UiText.SCENE_NOT_IN_SCRIPT, "n" to index + 1)).let { null }
        val take = RecapRulesV2.currentTakeFor(recap, block.id)
            ?: return sayNotice(UiNotice(UiText.BLOCK_NO_TAKE)).let { null }
        val artifactFile = (ArtifactStore(fs, open.dir).get(take.audio) as? CoreResult.Ok)?.value?.file
            ?: return sayNotice(UiNotice(UiText.TAKE_MISSING)).let { null }
        val file = joinPath(joinPath(open.dir, ArtifactStore.ARTIFACTS_DIR), artifactFile)
        if (!fs.exists(file)) return sayNotice(UiNotice(UiText.TAKE_MISSING)).let { null }
        val target = VoiceTarget(open.dir, open.project.id, block.id, SpokenText.of(block, pronunciation), take.spokenHash, trimSilence = true)
        val made = Provenance(take.provider, take.model, take.voice)
        return scope.launch {
            busyNotice(UiNotice(UiText.TAKE_CLEANING, "n" to index + 1))
            val bytes = withContext(Dispatchers.Default) { runCatching { fs.read(file) }.getOrNull() }
                ?: return@launch doneNotice(UiNotice(UiText.TAKE_UNREADABLE))
            val ext = file.substringAfterLast('.', "wav")
            val (cleaned, cleanExt, cleanMs) = trimPauses(target, bytes, ext, take.durationMs)
            if (cleaned === bytes) return@launch doneNotice(UiNotice(UiText.TAKE_NOT_TRIMMED))
            val source = if (take.source == TakeSource.RECORDED) TakeSource.RECORDED else TakeSource.POLISHED
            storeTake(target, cleaned, cleanExt, source, cleanMs, made)?.let { return@launch done(it) }
            doneNotice(UiNotice(UiText.TAKE_CLEANED, "n" to index + 1), ok = true)
        }
    }

    // --- Watermark image (5B-3, D-54) ------------------------------------------------------------------

    /**
     * Pick a PNG/JPG image, copy it to `library/watermark.<ext>` and set [RecapVisual.watermarkFile].
     * The file is also picked up by [saveBrandKit] the next time the Brand Kit is saved.
     */
    fun importWatermark(): Job? {
        val p = picker ?: run { sayNotice(UiNotice(UiText.CHOOSE_FILES_UNAVAILABLE)); return null }
        if (_state.value.busy || _state.value.recording != null) return null
        return scope.launch {
            val from = when (val r = p.pickFiles(UiText.PICK_RECAP_WATERMARK.text(uiLanguage()), IMAGE_EXTENSIONS)) {
                is io.github.alalkipgen.autoflow.platform.PlatformResult.Ok -> r.value.firstOrNull() ?: return@launch
                is io.github.alalkipgen.autoflow.platform.PlatformResult.Failed -> return@launch say(r.reason)
                is io.github.alalkipgen.autoflow.platform.PlatformResult.Unsupported -> return@launch say(r.reason)
            }
            val bytes = withContext(Dispatchers.Default) { session.readFile(from) }?.takeIf { it.isNotEmpty() }
                ?: return@launch sayNotice(UiNotice(UiText.CANNOT_READ_FILE))
            val dir = session.open?.dir ?: return@launch sayNotice(UiNotice(UiText.NO_PROJECT_IS_OPEN))
            val libDir = io.github.alalkipgen.autoflow.core.io.joinPath(dir, "library")
            val ext = from.substringAfterLast('.', "png").lowercase().let { if (it in setOf("png","jpg","jpeg")) it else "png" }
            val destName = "watermark.$ext"
            val dest = io.github.alalkipgen.autoflow.core.io.joinPath(libDir, destName)
            runCatching { fs.createDirectories(libDir); fs.writeDurably(dest, bytes) }
                .onFailure { return@launch sayNotice(UiNotice(UiText.WATERMARK_SAVE_FAILED, "detail" to it.message)) }
            val rel = "library/$destName"
            update { it.copy(visual = it.visual.copy(watermarkFile = rel)) }
            sayNotice(UiNotice(UiText.WATERMARK_SET, "name" to destName))
        }
    }

    /** Remove the watermark from this project. */
    fun setWatermarkWidth(percent: Int) {
        val width = percent.coerceIn(io.github.alalkipgen.autoflow.core.recap.WatermarkSize.MIN, io.github.alalkipgen.autoflow.core.recap.WatermarkSize.MAX)
        setVisual { it.copy(watermarkWidthPercent = width.takeUnless { n -> n == io.github.alalkipgen.autoflow.core.recap.WatermarkSize.MEDIUM }) }
    }

    fun removeWatermark() = setVisual { it.copy(watermarkFile = null) }

    /** Set watermark anchor position (D-56). */
    fun setWatermarkAnchor(anchor: io.github.alalkipgen.autoflow.core.recap.WatermarkAnchor) =
        setVisual { it.copy(watermarkAnchor = anchor) }

    /** The video's shape: the Recap's own choice, else the project's (the preview draws the captions for it). */
    fun outputRatio(recap: io.github.alalkipgen.autoflow.core.recap.RecapV2): io.github.alalkipgen.autoflow.core.model.Ratio =
        recap.visual.outputRatio ?: session.open?.project?.settings?.ratio ?: io.github.alalkipgen.autoflow.core.model.Ratio.R9_16

    /** Set the output video ratio for Movie Recap (D-56). Null = follow project setting (default 9:16). */
    fun setOutputRatio(ratio: io.github.alalkipgen.autoflow.core.model.Ratio?) =
        setVisual { it.copy(outputRatio = ratio) }

    // --- Caption custom colour / size (D-56) ----------------------------------------------------------

    /** Set the preset caption colour (D-57). Clears customColour. */
    fun setCaptionColour(hex: String) = update { recap ->
        recap.copy(captionStyle = recap.captionStyle.copy(colour = hex, customColour = null))
    }

    /** Set a free hex colour for captions (D-56). Pass null to clear and use the preset [colour]. */
    fun setCaptionCustomColour(hex: String?) = update { recap ->
        val h = hex?.trim()?.let { if (it.startsWith("#")) it else "#$it" }
            ?.takeIf { it.matches(Regex("#[0-9A-Fa-f]{6}")) }
        recap.copy(captionStyle = recap.captionStyle.copy(customColour = h))
    }

    /** Set a custom size factor for captions (D-56), 0.5–3.0. Pass null to use the preset [size]. */
    fun setCaptionCustomSize(factor: Float?) = update { recap ->
        val f = factor?.coerceIn(0.5f, 3.0f)
        recap.copy(captionStyle = recap.captionStyle.copy(customSizeFactor = f))
    }

    /** Set caption outline/stroke width (D-57). 0 = no custom stroke (auto). */
    fun setCaptionStrokeWidth(px: Int) = update { recap ->
        recap.copy(captionStyle = recap.captionStyle.copy(strokeWidth = px.coerceIn(0, 8)))
    }

    /** Set caption outline/stroke colour (D-57). `#RRGGBB`. */
    fun setCaptionStrokeColour(hex: String) {
        val h = hex.trim().let { if (it.startsWith("#")) it else "#$it" }
            .takeIf { it.matches(Regex("#[0-9A-Fa-f]{6}")) } ?: return
        update { recap -> recap.copy(captionStyle = recap.captionStyle.copy(strokeColour = h)) }
    }

    // --- Caption Preset (5B-8, D-51) ------------------------------------------------------------------

    /**
     * Save the current caption style as a Library preset (5B-8).
     */
    fun saveCaptionPreset(name: String) {
        library?.saveCaptionPreset(name) ?: sayNotice(UiNotice(UiText.LIBRARY_UNAVAILABLE))
    }

    private suspend fun durationOf(path: String, bytes: ByteArray): Long? =
        PcmWav.parse(bytes)?.durationMs ?: probe(path)?.takeIf { it.hasAudio && it.durationMs > 0 }?.durationMs

    /** Scene index → file of its current take (chosen, else newest; audit F06: the take list is a history). */
    private fun takeFiles(dir: String?, recap: io.github.alalkipgen.autoflow.core.recap.RecapV2?, story: RecapStory?): Map<Int, String> {
        if (dir == null || recap == null || story == null || recap.takes.isEmpty()) return emptyMap()
        val store = ArtifactStore(fs, dir)
        return story.blocks.withIndex().mapNotNull { (i, b) ->
            val take = recap.takeFor(b.id) ?: return@mapNotNull null
            (store.get(take.audio) as? CoreResult.Ok)?.value?.file?.let { i to joinPath(joinPath(dir, ArtifactStore.ARTIFACTS_DIR), it) }
        }.toMap()
    }

    private fun ok() {
        _state.value = _state.value.copy(messageOk = true)
    }

    fun dismissMessage() = say(null)

    // --- helpers -----------------------------------------------------------------------------------------------

    private fun recap(): io.github.alalkipgen.autoflow.core.recap.RecapV2? = session.open?.project?.recap

    private fun io.github.alalkipgen.autoflow.core.recap.RecapV2?.orEmptyScenes(): List<RecapScene> = this?.scenes.orEmpty()

    /**
     * Applies [change] to `project.recap` of the open project and saves; false (with the message shown) when saving
     * failed — e.g. [io.github.alalkipgen.autoflow.core.CoreError.Conflict] when another window saved this project
     * since it was opened here (audit F04; nothing is overwritten).
     */
    internal fun update(change: (io.github.alalkipgen.autoflow.core.recap.RecapV2) -> io.github.alalkipgen.autoflow.core.recap.RecapV2): Boolean {
        val project = session.open?.project ?: return false
        val recap = project.recap ?: return false
        return when (val r = session.save(project.copy(recap = change(recap)))) {
            is CoreResult.Ok -> { refresh(); true }
            is CoreResult.Err -> { say(r.error.message); false }
        }
    }

    internal fun project(): Project? = session.open?.project

    private suspend fun probe(path: String) = withContext(Dispatchers.Default) {
        val runner = media?.runner ?: return@withContext null
        val ffprobe = media.ffprobe() ?: return@withContext null
        (MediaProbe(runner, ffprobe).probe(path, MediaTools.parentOf(path)) as? MediaProbe.Result.Ok)?.info
    }

    private fun diagnostic(detail: String): UiNotice = UiNotice(UiText.RESULT_DETAIL, "detail" to detail)

    internal fun busy(job: String, progress: Double? = null) = busyNotice(diagnostic(job), progress)
    internal fun busyNotice(notice: UiNotice, progress: Double? = null, runnerJobId: String? = null) {
        _state.value = _state.value.copy(job = notice.text(UiLanguage.ENGLISH), jobNotice = notice, progress = progress, runnerJobId = runnerJobId, message = null, notice = null)
    }

    internal fun done(message: String?, ok: Boolean = false) = doneNotice(message?.let(::diagnostic), ok)
    internal fun doneNotice(notice: UiNotice?, ok: Boolean = false) {
        refresh()
        _state.value = _state.value.copy(job = null, jobNotice = null, progress = null, runnerJobId = null, scriptRunId = null,
            message = notice?.text(UiLanguage.ENGLISH) ?: _state.value.message,
            notice = notice ?: _state.value.notice, messageOk = ok && notice != null)
    }

    internal fun say(message: String?) = sayNotice(message?.let(::diagnostic))
    internal fun sayNotice(notice: UiNotice?) {
        _state.value = _state.value.copy(message = notice?.text(UiLanguage.ENGLISH), notice = notice, messageOk = false)
    }

    companion object {
        const val NO_FFMPEG = "ffmpeg is not available, so the movie cannot be read."
        const val SUMMARY_FILE = "summary.txt"
        const val SUBTITLES_FILE = "subtitles.srt"
        const val WRITING = "Gemini is writing the script…"
        const val RATE_LIMITED = "The free-tier limit is reached; try again later"
        /** The provider of every TTS take and voice lock this app makes (Gemini TTS, spec §3). */
        const val TTS_PROVIDER = "gemini"
        const val BLOCK_GONE = "This scene was removed or replaced while its voice was made; the take was not saved"
        /** TTS requests per minute per model (spec §3 `tts.rpm`): the Speak All job's pacing and its ETA. */
        const val TTS_RPM = 3
        private const val PENDING_RUNNER_JOB = ""
        private val ACTIVE_JOB_STATES = setOf(AiJobState.QUEUED, AiJobState.RUNNING)
        private fun jobInputKey(projectId: String, inputs: List<String>) = Sha256.hex(
            (listOf(projectId) + inputs).joinToString("") { "${it.length}:$it" }.encodeToByteArray(),
        )
        private val ENDED_JOB_STATES = setOf(AiJobState.DONE, AiJobState.PAUSED, AiJobState.FAILED, AiJobState.CANCELLED)
        private val FINISHED_JOB_STATES = setOf(AiJobState.DONE, AiJobState.CANCELLED)
        private const val TRANSCRIBING_PREFIX = "Transcribing"
        const val TRANSCRIBING = "$TRANSCRIBING_PREFIX picked scenes…"
        /** Requests per minute for TRANSCRIBE jobs (text model with audio input). */
        const val TRANSCRIBE_RPM = 10
        /** Movie sound chips (spec §6). */
        val MOVIE_VOLUMES = listOf("Off" to 0.0, "Low" to Recap.MOVIE_VOLUME_LOW, "Full" to 1.0)

        /** Background music level chips (5B-4). */
        val BGM_VOLUMES = listOf("Quiet" to 0.1, "Medium" to 0.18, "Loud" to 0.3)
        const val BGM_NAME_INPUT = "name"
        const val TRIM_TIMEOUT_MS = 60_000L
        /** A trimmed take shorter than this is treated as a failed trim (kept as it was). */
        const val MIN_TRIMMED_MS = 200L

        /** Look chips (spec §6). */
        val LOOKS = listOf(Look.MIRROR to "Mirror", Look.ZOOM to "Zoom 10 %", Look.FILL to "Fill frame", Look.WARM to "Warm")
        val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "wav", "aac", "ogg", "opus")
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

        /** Gemini prebuilt voices offered as chips (name to Gemini's own description). */
        val TTS_VOICES = listOf("Charon" to "Informative", "Kore" to "Firm", "Puck" to "Upbeat", "Zephyr" to "Bright", "Fenrir" to "Excitable", "Aoede" to "Breezy")

        fun seconds(ms: Long): String = "${ms / 1000}.${(ms % 1000) / 100} s"

        /** `1:05:09` / `4:05`. */
        fun clock(ms: Long): String {
            val s = ms / 1000
            val h = s / 3600
            val m = s / 60 % 60
            val sec = (s % 60).toString().padStart(2, '0')
            return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
        }
    }
}
