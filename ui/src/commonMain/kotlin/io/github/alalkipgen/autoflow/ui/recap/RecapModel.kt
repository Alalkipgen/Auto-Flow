package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.ai.AiResult
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
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.recap.Look
import io.github.alalkipgen.autoflow.media.audio.AudioFilters
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
import io.github.alalkipgen.autoflow.core.recap.RecapRegister
import io.github.alalkipgen.autoflow.core.recap.RecapStyle
import io.github.alalkipgen.autoflow.core.recap.Srt
import io.github.alalkipgen.autoflow.core.recap.VoiceSource
import io.github.alalkipgen.autoflow.core.recap.VoiceTake
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.alalkipgen.autoflow.core.library.LibraryStore
import io.github.alalkipgen.autoflow.ui.library.LibraryModel
import kotlinx.coroutines.withContext

/** What the Movie Recap screen shows besides the project itself. [job] = a long step is running ([progress] 0…1 when known). */
data class RecapUiState(
    val projectDir: String? = null,
    val recap: io.github.alalkipgen.autoflow.core.recap.RecapV2? = null,
    val movieName: String? = null,
    val job: String? = null,
    val progress: Double? = null,
    val message: String? = null,
    val messageOk: Boolean = false,
    /** The current `rs` artifact, null when none or unreadable. */
    val script: RecapScript? = null,
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
) {
    val busy: Boolean get() = job != null
}

/** ETA shown before Speak All starts (5A-5). */
data class SpeakAllEta(val count: Int, val etaSeconds: Long?)

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
    /** Pronunciation fixes applied before TTS (5B-7); empty until Library is wired (5A-7). */
    private val lexicon: Lexicon = Lexicon.EMPTY,
    /** Library for Brand Kit save/apply and BGM picker (D-51: 5B-3, 5B-4, 5B-8). */
    private val library: LibraryModel? = null,
) {
    private val _state = MutableStateFlow(RecapUiState())
    val state: StateFlow<RecapUiState> = _state.asStateFlow()
    private val thumbs = Mutex()
    private var scriptJob: Job? = null

    init {
        refresh()
    }

    /** Re-reads the open project (the screen calls it when it opens). A running job of the same project stays. */
    fun refresh() {
        val open = session.open
        val keep = _state.value.takeIf { it.projectDir == open?.dir }
        val recap = open?.project?.recap
        _state.value = RecapUiState(
            projectDir = open?.dir,
            recap = recap,
            script = recap?.story?.let { id -> storyToScript(session.readJson(id, io.github.alalkipgen.autoflow.core.recap.RecapStory.serializer())) },
            summary = if (open == null) "" else session.readSource(SUMMARY_FILE),
            recording = keep?.recording,
            takeFiles = takeFiles(open?.dir, recap),
            bgmName = recap?.audio?.bgm?.let { id -> session.artifacts()?.get(id)?.let { (it as? CoreResult.Ok)?.value?.inputs?.get(BGM_NAME_INPUT) } },
            movieName = session.moviePath()?.substringAfterLast('/'),
            job = keep?.job,
            progress = keep?.progress,
            message = keep?.message,
            messageOk = keep?.messageOk ?: false,
        )
    }

    /** Picks a movie file and streams it into `source/` (spec §2); clears scenes, script and takes. */
    fun importMovie(): Job? {
        val recap = recap() ?: return null
        RecapRulesV2.canImportMovie(recap).blockers.firstOrNull()?.let { say(it.text()); return null }
        val p = picker ?: run { say("Choosing files is not available here"); return null }
        if (_state.value.busy) return null
        return scope.launch {
            val from = when (val r = p.pickFiles("Choose the movie", ProjectSession.MOVIE_EXTENSIONS)) {
                is PlatformResult.Ok -> r.value.firstOrNull() ?: return@launch
                is PlatformResult.Failed -> return@launch say(r.reason)
                is PlatformResult.Unsupported -> return@launch say(r.reason)
            }
            busy("Copying the movie into the project…")
            val path = when (val r = withContext(Dispatchers.Default) { session.importMovie(from) }) {
                is CoreResult.Ok -> r.value
                is CoreResult.Err -> return@launch done(r.error.message)
            }
            val info = probe(path) ?: return@launch done("The movie was copied, but it cannot be read (ffprobe). Is it a video file?")
            if (!info.hasVideo || info.durationMs <= 0) return@launch done("This file has no video picture.")
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
            done(if (saved) "Movie ready: ${clock(movie.durationMs)}. Next: Find scenes." else null, ok = saved)
        }
    }

    /** Runs `scdet` over the movie ([threshold]: [Scenes.THRESHOLD_MORE] … [Scenes.THRESHOLD_FEWER]). */
    fun findScenes(threshold: Int = Scenes.THRESHOLD_NORMAL): Job? {
        val recap = recap() ?: return null
        RecapRulesV2.canFindScenes(recap).blockers.firstOrNull()?.let { say(it.text()); return null }
        val movie = recap.movie ?: return null
        val runner = media?.runner
        val ffmpeg = media?.ffmpeg()
        val dir = session.open?.dir ?: return null
        if (runner == null || ffmpeg == null) { say(NO_FFMPEG); return null }
        if (_state.value.busy) return null
        return scope.launch {
            busy("Finding scenes…", 0.0)
            val r = SceneDetect(runner, ffmpeg).cuts(joinPath(dir, movie.file), movie.durationMs, threshold, dir) { f ->
                _state.value = _state.value.copy(progress = f.coerceIn(0.0, 1.0))
            }
            when (r) {
                is SceneDetect.Result.Failed -> done("Cannot find scenes: ${r.reason}")
                is SceneDetect.Result.Ok -> {
                    val scenes = Scenes.fromCuts(r.cutsMs, movie.durationMs, recap().orEmptyScenes())
                    val saved = update { it.copy(scenes = scenes, sceneThreshold = threshold, storyApproved = false) }
                    done(if (saved) "Found ${scenes.size} scenes. Tick the ones to tell the story with." else null, ok = saved)
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
        val p = picker ?: run { say("Choosing files is not available here"); return null }
        if (_state.value.busy) return null
        return scope.launch {
            val from = when (val r = p.pickFiles("Choose the subtitles (.srt)", setOf("srt"))) {
                is PlatformResult.Ok -> r.value.firstOrNull() ?: return@launch
                is PlatformResult.Failed -> return@launch say(r.reason)
                is PlatformResult.Unsupported -> return@launch say(r.reason)
            }
            val text = withContext(Dispatchers.Default) { session.readFile(from)?.decodeToString()?.removePrefix("\uFEFF") }
                ?: return@launch say("Cannot read this file")
            val cues = Srt.parse(text)
            if (cues.isEmpty()) return@launch say("No subtitle lines were found in this file. Is it an .srt file?")
            (session.writeSource(SUBTITLES_FILE, text) as? CoreResult.Err)?.let { return@launch say(it.error.message) }
            if (update { it.copy(sources = it.sources.copy(subtitles = "${ProjectSession.SOURCE_DIR}/$SUBTITLES_FILE")) }) {
                done("Subtitles ready: ${cues.size} lines.", ok = true)
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

    /** ✨ Write script (Gemini): one narration text per picked scene; the new `rs` replaces the current one, not approved. */
    fun writeScript(): Job? {
        val recap = recap() ?: return null
        val summary = _state.value.summary
        val subtitlePath = recap.sources.subtitles
        val cues = subtitlePath?.let { Srt.parse(session.readSource(SUBTITLES_FILE)) }.orEmpty()
        val gate = RecapRulesV2.canGenerateScript(recap, summary, if (cues.isEmpty()) "" else "srt")
        gate.blockers.firstOrNull()?.let { say(it.text()); return null }
        val gen = generator ?: run { say("Gemini is not available here"); return null }
        if (_state.value.busy) return null
        val picked = RecapRulesV2.pickedScenes(recap)
        val brief = RecapBrief(
            title = project()?.name.orEmpty(),
            summary = summary,
            style = _style,
            register = _register,
            scenes = picked.map { SceneBrief(it.startMs, it.endMs, Srt.textIn(cues, it.startMs, it.endMs, RecapPrompts.DIALOGUE_CHARS)) },
        )
        busy(WRITING)
        return scope.launch {
            try {
                when (val r = gen.write(brief)) {
                    is AiResult.Ok -> {
                        val blocks = picked.zip(r.value) { scene, text ->
                            io.github.alalkipgen.autoflow.core.recap.RecapBlock(
                                id = io.github.alalkipgen.autoflow.core.recap.migratedBlockId(picked.indexOf(scene), scene.startMs, scene.endMs),
                                clips = listOf(io.github.alalkipgen.autoflow.core.recap.RecapClip(scene.startMs, scene.endMs)),
                                textMy = text,
                            )
                        }
                        val saved = saveStory(RecapStory(source = RecapStory.SOURCE_GEMINI, style = _style, register = _register, blocks = blocks))
                        done(if (saved) "✨ Script written: ${blocks.size} segments. Read and fix them, then ✓ Approve." else null, ok = saved)
                    }
                    is AiResult.RateLimited -> done(RATE_LIMITED)
                    is AiResult.Failed -> done("Gemini: ${r.msg}")
                    is AiResult.Deferred -> done(r.instruction)
                }
            } finally {
                if (_state.value.job == WRITING) done(null)
            }
        }.also { scriptJob = it }
    }

    /** Stops ✨ Write script; nothing changes. */
    fun cancelScript() {
        scriptJob?.cancel()
        scriptJob = null
        done("Stopped. The script did not change.")
    }

    /** ✎ Write it myself: a story with one empty block per picked scene. */
    fun writeManually(): Boolean {
        val recap = recap() ?: return false
        RecapRulesV2.canWriteManually(recap).blockers.firstOrNull()?.let { say(it.text()); return false }
        val picked = RecapRulesV2.pickedScenes(recap)
        val blocks = picked.mapIndexed { i, scene ->
            io.github.alalkipgen.autoflow.core.recap.RecapBlock(
                id = io.github.alalkipgen.autoflow.core.recap.migratedBlockId(i, scene.startMs, scene.endMs),
                clips = listOf(io.github.alalkipgen.autoflow.core.recap.RecapClip(scene.startMs, scene.endMs)),
            )
        }
        return saveStory(RecapStory(source = RecapStory.SOURCE_MANUAL, style = _style, register = _register, blocks = blocks))
    }

    /** Saves block [index] with [text]; approval is cleared. */
    fun saveSegment(index: Int, text: String): Boolean {
        val script = _state.value.script ?: return false
        if (index !in script.segments.indices) return false
        val segments = script.segments.mapIndexed { i, s -> if (i == index) s.copy(textMy = text.trim()) else s }
        return saveScript(script.copy(segments = segments))
    }

    /**
     * Edit the timing of segment [index] (per-chunk caption timing, D-52): shifts [startMs] and [endMs].
     */
    fun saveSegmentTiming(index: Int, startMs: Long, endMs: Long): Boolean {
        val script = _state.value.script ?: return false
        if (index !in script.segments.indices) return false
        if (endMs <= startMs) { say("End must be after start"); return false }
        val clamped = startMs.coerceAtLeast(0L)
        val segments = script.segments.mapIndexed { i, s -> if (i == index) s.copy(startMs = clamped, endMs = endMs) else s }
        return if (saveScript(script.copy(segments = segments))) { say("Timing saved"); true } else false
    }

    /** ✓ Approve script. */
    fun approveScript(): Boolean {
        val recap = recap() ?: return false
        val story = currentStory()
        RecapRulesV2.canApproveScript(recap, story).blockers.firstOrNull()?.let { say(it.text()); return false }
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

    /** ● Record segment [index] into `.cache/` (stopped with [stopRecording]). */
    fun startRecording(index: Int): Job? {
        val text = voiceText(index) ?: return null
        val rec = recorder ?: run { say("Recording is not available here; use Import file"); return null }
        if (_state.value.busy || _state.value.recording != null) return null
        val path = session.cacheFile("rec-$index.${rec.extension}") ?: return null
        return scope.launch {
            when (val r = rec.start(path)) {
                is PlatformResult.Ok -> _state.value = _state.value.copy(recording = index, message = "● Recording #${index + 1}… read: $text", messageOk = true)
                is PlatformResult.Failed -> say(r.reason)
                is PlatformResult.Unsupported -> say(r.reason)
            }
        }
    }

    /** ■ Stop: the recording becomes the segment's newest take. */
    fun stopRecording(): Job? {
        val index = _state.value.recording ?: return null
        val rec = recorder ?: return null
        val path = session.cacheFile("rec-$index.${rec.extension}") ?: return null
        return scope.launch {
            _state.value = _state.value.copy(recording = null)
            val ms = when (val r = rec.stop()) {
                is PlatformResult.Ok -> r.value
                is PlatformResult.Failed -> return@launch say(r.reason)
                is PlatformResult.Unsupported -> return@launch say(r.reason)
            }
            val bytes = withContext(Dispatchers.Default) { session.readFile(path) }?.takeIf { it.isNotEmpty() }
                ?: return@launch say("The recording is empty")
            val duration = durationOf(path, bytes) ?: ms
            if (saveTake(index, bytes, rec.extension, VoiceSource.RECORDED, duration)) say("Saved #${index + 1}: ${seconds(duration)}.").also { ok() }
        }
    }

    fun cancelRecording() {
        recorder?.cancel()
        _state.value = _state.value.copy(recording = null, message = null)
    }

    /** Import file: any audio file becomes the segment's newest take. */
    fun importTake(index: Int): Job? {
        voiceText(index) ?: return null
        val p = picker ?: run { say("Choosing files is not available here"); return null }
        if (_state.value.busy || _state.value.recording != null) return null
        return scope.launch {
            val from = when (val r = p.pickFiles("Choose the narration for scene ${index + 1}", AUDIO_EXTENSIONS)) {
                is PlatformResult.Ok -> r.value.firstOrNull() ?: return@launch
                is PlatformResult.Failed -> return@launch say(r.reason)
                is PlatformResult.Unsupported -> return@launch say(r.reason)
            }
            val bytes = withContext(Dispatchers.Default) { session.readFile(from) }?.takeIf { it.isNotEmpty() }
                ?: return@launch say("Cannot read this file")
            val duration = durationOf(from, bytes) ?: return@launch say("This file has no sound that can be read (ffprobe).")
            val ext = from.substringAfterLast('.', "m4a").lowercase().takeIf { it in AUDIO_EXTENSIONS } ?: "m4a"
            if (saveTake(index, bytes, ext, VoiceSource.RECORDED, duration)) say("Saved #${index + 1}: ${seconds(duration)}.").also { ok() }
        }
    }

    /**
     * Called by [AiWorker] (5A-5, D-51) to speak one segment from a WorkManager AI job.
     * Throws on failure so the worker can mark the job FAILED.
     */
    suspend fun speakForJob(index: Int) {
        val msg = speakOne(index)
        if (msg != null) error(msg)
    }

    /** 🔊 one segment with Gemini TTS. */
    fun speak(index: Int): Job? {
        voiceText(index) ?: return null
        if (tts == null) { say("Text to speech is not available here"); return null }
        if (_state.value.busy || _state.value.recording != null) return null
        busy("Speaking #${index + 1}…")
        return scope.launch {
            try {
                val msg = speakOne(index)
                done(msg ?: "Spoken #${index + 1}.", ok = msg == null)
            } finally {
                if (_state.value.job != null) done(null)
            }
        }
    }

    /** 🔊 Speak all: every segment without a current take, in order; stops at the first failure. */
    /**
     * Shows an ETA card ("N scenes, ~T s — Start / Cancel") before beginning (5A-5, D-51).
     * Call again with [confirmed]=true after the user taps Start.
     */
    fun speakAll(confirmed: Boolean = false): Job? {
        val recap = recap() ?: return null
        val script = _state.value.script ?: return null
        RecapRulesV2.canVoice(recap, currentStory()).blockers.firstOrNull()?.let { say(it.text()); return null }
        if (tts == null) { say("Text to speech is not available here"); return null }
        if (_state.value.busy || _state.value.recording != null) return null
        val todo = script.segments.indices.filter { i ->
            val blockId = recap.story?.let { storyId -> (session.readJson(storyId, RecapStory.serializer()) as? CoreResult.Ok)?.value?.blocks?.getOrNull(i)?.id }
            (if (blockId != null) RecapRulesV2.currentTakeFor(recap, blockId) else null) == null && script.segments[i].textMy.isNotBlank()
        }
        if (todo.isEmpty()) { say("Every scene already has its voice."); return null }
        // Show ETA card first; user confirms before the loop starts (5A-5).
        if (!confirmed) {
            val rpm = 3L // free-tier TTS is ~3 req/min; shown as a rough ETA
            val etaSec = todo.size * 60L / rpm
            _state.value = _state.value.copy(speakAllEta = SpeakAllEta(todo.size, etaSec))
            return null
        }
        _state.value = _state.value.copy(speakAllEta = null)
        busy("Speaking…", 0.0)
        return scope.launch {
            try {
                for ((n, i) in todo.withIndex()) {
                    _state.value = _state.value.copy(job = "Speaking #${i + 1} (${n + 1} of ${todo.size})…", progress = n.toDouble() / todo.size)
                    speakOne(i)?.let { return@launch done(it) }
                }
                done("🔊 Spoken ${todo.size} scenes.", ok = true)
            } finally {
                if (_state.value.job != null) done(null)
            }
        }.also { scriptJob = it }
    }

    /** Dismiss the Speak All ETA card without starting. */
    fun cancelSpeakAll() {
        _state.value = _state.value.copy(speakAllEta = null)
    }

    /** null when done, else the message. */
    private suspend fun speakOne(index: Int): String? {
        val recap = recap() ?: return "No project is open"
        val text = voiceText(index) ?: return _state.value.message
        val provider = tts ?: return "Text to speech is not available here"
        val story = currentStory()
        val style = when (story?.style ?: _style) {
            io.github.alalkipgen.autoflow.core.recap.RecapStyle.PLAIN -> "calm, clear movie recap narrator"
            io.github.alalkipgen.autoflow.core.recap.RecapStyle.COMMENTARY -> "lively movie recap narrator with feeling"
        }
        // Voice lock enforcement (D-35, audit): pass the locked model so the provider never switches.
        val lockedModel = recap.voiceLock?.model
        val ttsVoice = recap.voiceLock?.voice ?: "Kore"
        return when (val r = provider.speak(text, VoiceRef(ttsVoice), "my", style, lockedModel)) {
            is AiResult.Ok -> {
                val ms = PcmWav.parse(r.value)?.durationMs ?: return "The speech audio cannot be read"
                if (saveTake(index, r.value, "wav", VoiceSource.TTS, ms)) null else _state.value.message ?: "Cannot save the voice"
            }
            is AiResult.RateLimited -> RATE_LIMITED
            is AiResult.Failed -> "Gemini TTS: ${r.msg}"
            is AiResult.Deferred -> r.instruction
        }
    }

    /** The segment's text when voicing is allowed now (else the blocker is shown and null returned). */
    private fun voiceText(index: Int): String? {
        val recap = recap() ?: return null
        val script = _state.value.script
        RecapRulesV2.canVoice(recap, currentStory()).blockers.firstOrNull()?.let { say(it.text()); return null }
        val written = script!!.segments.getOrNull(index)?.textMy?.trim().orEmpty()
        if (written.isEmpty()) { say("Scene ${index + 1} has no narration text"); return null }
        // Apply lexicon substitutions before TTS (5B-7): captions show the written text, TTS speaks the spoken form.
        return lexicon.apply(written)
    }

    /** A new take: long pauses cut first when Sound → "Cut long pauses" is on (5B-4), then saved. */
    private suspend fun saveTake(index: Int, bytes: ByteArray, ext: String, source: VoiceSource, durationMs: Long): Boolean {
        val (b, e, ms) = trimPauses(index, bytes, ext, durationMs)
        return storeTake(index, b, e, source, ms)
    }

    /**
     * The take with every pause longer than 0.35 s cut to 0.2 s ([AudioFilters.trimSilence]) as a WAV, when
     * `recap.audio.trimSilence` is on; the take as it was when it is off, ffmpeg is missing or the result is empty.
     */
    private suspend fun trimPauses(index: Int, bytes: ByteArray, ext: String, durationMs: Long): Triple<ByteArray, String, Long> {
        val asIs = Triple(bytes, ext, durationMs)
        if (recap()?.audio?.trimSilence != true) return asIs
        val runner = media?.runner ?: return asIs
        val ffmpeg = media.ffmpeg() ?: return asIs
        val dir = session.open?.dir ?: return asIs
        val src = session.cacheFile("trim-in-$index.$ext") ?: return asIs
        val out = session.cacheFile("trim-out-$index.wav") ?: return asIs
        return withContext(Dispatchers.Default) {
            runCatching { fs.writeDurably(src, bytes) }.getOrElse { return@withContext asIs }
            val r = runner.run(listOf(ffmpeg) + AudioFilters.trimArgs(src, out), dir, TRIM_TIMEOUT_MS, keepOutput = false) {}
            val ok = r is io.github.alalkipgen.autoflow.media.tool.ToolExec.Done && r.exitCode == 0 && !r.timedOut
            val trimmed = if (ok) session.readFile(out) else null
            val ms = trimmed?.let { PcmWav.parse(it)?.durationMs }
            if (trimmed == null || ms == null || ms < MIN_TRIMMED_MS) asIs else Triple(trimmed, "wav", ms)
        }
    }

    private fun storeTake(index: Int, bytes: ByteArray, ext: String, source: VoiceSource, durationMs: Long): Boolean {
        val text = _state.value.script?.segments?.getOrNull(index)?.textMy ?: return false
        val store = session.artifacts() ?: return false
        val hash = RecapRules.textHash(text)
        val story = currentStory()
        val blockId = story?.blocks?.getOrNull(index)?.id ?: "b_seg$index"
        val inputs = mapOf("segment" to index.toString(), "textHash" to hash)
        val ttsSource = when (source) {
            VoiceSource.TTS, VoiceSource.REPLICATED -> TakeSource.TTS
            VoiceSource.RECORDED -> TakeSource.RECORDED
        }
        return when (val r = store.put(bytes, io.github.alalkipgen.autoflow.core.model.ArtifactKind.AUDIO, ext, inputs = inputs, provider = source.name.lowercase())) {
            is CoreResult.Ok -> {
                val take = BlockTake(block = blockId, audio = r.value.id, source = ttsSource, durationMs = durationMs, spokenHash = hash)
                update { recap -> recap.copy(takes = recap.takes.filter { it.block != blockId } + take) }
            }
            is CoreResult.Err -> { say(r.error.message); false }
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
        val p = picker ?: run { say("Choosing files is not available here"); return null }
        if (_state.value.busy || _state.value.recording != null) return null
        return scope.launch {
            val from = when (val r = p.pickFiles("Choose the background music", AUDIO_EXTENSIONS)) {
                is PlatformResult.Ok -> r.value.firstOrNull() ?: return@launch
                is PlatformResult.Failed -> return@launch say(r.reason)
                is PlatformResult.Unsupported -> return@launch say(r.reason)
            }
            val bytes = withContext(Dispatchers.Default) { session.readFile(from) }?.takeIf { it.isNotEmpty() }
                ?: return@launch say("Cannot read this file")
            val ext = from.substringAfterLast('.', "m4a").lowercase().takeIf { it in AUDIO_EXTENSIONS } ?: "m4a"
            val store = session.artifacts() ?: return@launch say("No project is open")
            val name = from.substringAfterLast('/').substringAfterLast('\\')
            when (val r = store.put(bytes, io.github.alalkipgen.autoflow.core.model.ArtifactKind.AUDIO, ext, inputs = mapOf("role" to "bgm", BGM_NAME_INPUT to name), provider = "imported")) {
                is CoreResult.Ok -> if (update { it.copy(audio = it.audio.copy(bgm = r.value.id)) }) { say("Background music: $name"); ok() }
                is CoreResult.Err -> say(r.error.message)
            }
        }
    }

    /** Changes the picture settings (5B-2): blur boxes, the space around the movie, captions on a box. */
    fun setVisual(change: (io.github.alalkipgen.autoflow.core.recap.RecapVisual) -> io.github.alalkipgen.autoflow.core.recap.RecapVisual) {
        if (_state.value.busy) return
        update { it.copy(visual = change(it.visual)) }
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

    /** Sets the Smart Fit choice for segment [index] (5A-8, studio-v2 §8). */
    fun setFitChoice(index: Int, choice: io.github.alalkipgen.autoflow.core.recap.FitChoice) {
        if (_state.value.busy) return
        val blockId = currentStory()?.blocks?.getOrNull(index)?.id ?: "b_seg$index"
        update { it.copy(fit = it.fit + (blockId to choice.name)) }
    }

    // --- Brand Kit (5B-3, D-51) -------------------------------------------------------------------------

    /**
     * Save the current Recap settings as a Library Brand Kit with [name] (5B-3).
     * Copies any logo/watermark from the project's `library/` folder.
     */
    fun saveBrandKit(name: String) {
        library?.saveBrandKit(name) ?: say("Library is not available")
    }

    /**
     * Apply a Library Brand Kit to this project: copies files and restores caption style (5B-3).
     */
    fun applyBrandKit(id: String) {
        library?.applyBrandKit(id) ?: say("Library is not available")
        refresh()
    }

    // --- BGM from Library (5B-4, D-51) ------------------------------------------------------------------

    /**
     * Pick a music item from the Library and set it as the BGM for this project (5B-4).
     * Copies the file into the project's `source/` folder and stores it as an `au` artifact.
     */
    fun setBgmFromLibrary(musicId: String): Job? {
        val lib = library ?: run { say("Library is not available"); return null }
        if (_state.value.busy || _state.value.recording != null) return null
        return scope.launch {
            val path = lib.applyMusicAsBgm(musicId) ?: return@launch
            val bytes = withContext(Dispatchers.Default) { session.readFile(path) }?.takeIf { it.isNotEmpty() }
                ?: return@launch say("Cannot read the music file")
            val name = path.substringAfterLast('/')
            val store = session.artifacts() ?: return@launch say("No project is open")
            when (val r = store.put(bytes, io.github.alalkipgen.autoflow.core.model.ArtifactKind.AUDIO, path.substringAfterLast('.', "m4a"),
                inputs = mapOf("role" to "bgm", BGM_NAME_INPUT to name), provider = "library")) {
                is io.github.alalkipgen.autoflow.core.CoreResult.Ok ->
                    if (update { it.copy(audio = it.audio.copy(bgm = r.value.id)) }) say("Background music: $name", )
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
        val p = picker ?: run { say("Choosing files is not available here"); return null }
        if (_state.value.busy || _state.value.recording != null) return null
        return scope.launch {
            val from = when (val r = p.pickFiles("Choose the background image", IMAGE_EXTENSIONS)) {
                is io.github.alalkipgen.autoflow.platform.PlatformResult.Ok -> r.value.firstOrNull() ?: return@launch
                is io.github.alalkipgen.autoflow.platform.PlatformResult.Failed -> return@launch say(r.reason)
                is io.github.alalkipgen.autoflow.platform.PlatformResult.Unsupported -> return@launch say(r.reason)
            }
            val bytes = withContext(Dispatchers.Default) { session.readFile(from) }?.takeIf { it.isNotEmpty() }
                ?: return@launch say("Cannot read this file")
            val dir = session.open?.dir ?: return@launch say("No project is open")
            val libDir = io.github.alalkipgen.autoflow.core.io.joinPath(dir, "library")
            val name = from.substringAfterLast('/').substringAfterLast('\\')
            val dest = io.github.alalkipgen.autoflow.core.io.joinPath(libDir, name)
            runCatching { fs.createDirectories(libDir); fs.writeDurably(dest, bytes) }
                .onFailure { return@launch say("Cannot save the image: ${it.message}") }
            // Store path relative to project dir
            val rel = "library/$name"
            update { it.copy(visual = it.visual.copy(
                background = io.github.alalkipgen.autoflow.core.recap.FrameBackground.IMAGE,
                backgroundImageFile = dest,
            )) }
            say("Background image set: $name", )
        }
    }

    // --- Clean tab (D-54) ---------------------------------------------------------------------------

    /**
     * Polish a recorded take: re-runs [trimPauses] on the existing take file and saves the result.
     * Shows a busy indicator while running.
     */
    fun cleanTake(index: Int): Job? {
        if (_state.value.busy || _state.value.recording != null) return null
        val recap = recap() ?: return null
        val blockId = currentStory()?.blocks?.getOrNull(index)?.id ?: "b_seg$index"
        val take = RecapRulesV2.currentTakeFor(recap, blockId)
            ?: return say("No take for this block").let { null }
        val dir = session.open?.dir ?: return null
        val artifactStore = ArtifactStore(fs, dir)
        val artifactFile = (artifactStore.get(take.audio) as? CoreResult.Ok)?.value?.file
            ?: return say("Take file not found").let { null }
        val file = joinPath(joinPath(dir, ArtifactStore.ARTIFACTS_DIR), artifactFile)
        if (!fs.exists(file)) return say("Take file not found").let { null }
        return scope.launch {
            busy("Cleaning take ${ index + 1 }…")
            val bytes = withContext(Dispatchers.Default) { runCatching { fs.read(file) }.getOrNull() }
                ?: return@launch done("Cannot read take file")
            val ext = file.substringAfterLast('.', "wav")
            val (cleaned, cleanExt, cleanMs) = trimPauses(index, bytes, ext, take.durationMs)
            val srcVoice = if (take.source == TakeSource.RECORDED) VoiceSource.RECORDED else VoiceSource.TTS
            if (!storeTake(index, cleaned, cleanExt, srcVoice, cleanMs))
                return@launch done("Could not save the cleaned take")
            done("Take ${ index + 1 } cleaned: silence trimmed", ok = true)
        }
    }

    // --- Watermark image (5B-3, D-54) ------------------------------------------------------------------

    /**
     * Pick a PNG/JPG image, copy it to `library/watermark.<ext>` and set [RecapVisual.watermarkFile].
     * The file is also picked up by [saveBrandKit] the next time the Brand Kit is saved.
     */
    fun importWatermark(): Job? {
        val p = picker ?: run { say("Choosing files is not available here"); return null }
        if (_state.value.busy || _state.value.recording != null) return null
        return scope.launch {
            val from = when (val r = p.pickFiles("Choose a watermark / logo image", IMAGE_EXTENSIONS)) {
                is io.github.alalkipgen.autoflow.platform.PlatformResult.Ok -> r.value.firstOrNull() ?: return@launch
                is io.github.alalkipgen.autoflow.platform.PlatformResult.Failed -> return@launch say(r.reason)
                is io.github.alalkipgen.autoflow.platform.PlatformResult.Unsupported -> return@launch say(r.reason)
            }
            val bytes = withContext(Dispatchers.Default) { session.readFile(from) }?.takeIf { it.isNotEmpty() }
                ?: return@launch say("Cannot read this file")
            val dir = session.open?.dir ?: return@launch say("No project is open")
            val libDir = io.github.alalkipgen.autoflow.core.io.joinPath(dir, "library")
            val ext = from.substringAfterLast('.', "png").lowercase().let { if (it in setOf("png","jpg","jpeg")) it else "png" }
            val destName = "watermark.$ext"
            val dest = io.github.alalkipgen.autoflow.core.io.joinPath(libDir, destName)
            runCatching { fs.createDirectories(libDir); fs.writeDurably(dest, bytes) }
                .onFailure { return@launch say("Cannot save the watermark: ${it.message}") }
            val rel = "library/$destName"
            update { it.copy(visual = it.visual.copy(watermarkFile = rel)) }
            say("Watermark set: $destName", )
        }
    }

    /** Remove the watermark from this project. */
    fun removeWatermark() = setVisual { it.copy(watermarkFile = null) }

    /** Set watermark anchor position (D-56). */
    fun setWatermarkAnchor(anchor: io.github.alalkipgen.autoflow.core.recap.WatermarkAnchor) =
        setVisual { it.copy(watermarkAnchor = anchor) }

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
        library?.saveCaptionPreset(name) ?: say("Library is not available")
    }

    private suspend fun durationOf(path: String, bytes: ByteArray): Long? =
        PcmWav.parse(bytes)?.durationMs ?: probe(path)?.takeIf { it.hasAudio && it.durationMs > 0 }?.durationMs

    private fun takeFiles(dir: String?, recap: io.github.alalkipgen.autoflow.core.recap.RecapV2?): Map<Int, String> {
        if (dir == null || recap == null || recap.takes.isEmpty()) return emptyMap()
        val store = ArtifactStore(fs, dir)
        // Map block index (position in story blocks) to file path for playback
        return recap.takes.mapIndexedNotNull { i, t ->
            (store.get(t.audio) as? CoreResult.Ok)?.value?.file?.let { i to joinPath(joinPath(dir, ArtifactStore.ARTIFACTS_DIR), it) }
        }.toMap()
    }

    private fun ok() {
        _state.value = _state.value.copy(messageOk = true)
    }

    fun dismissMessage() = say(null)

    // --- helpers -----------------------------------------------------------------------------------------------

    private fun recap(): io.github.alalkipgen.autoflow.core.recap.RecapV2? = session.open?.project?.recap

    private fun io.github.alalkipgen.autoflow.core.recap.RecapV2?.orEmptyScenes(): List<RecapScene> = this?.scenes.orEmpty()

    /** Applies [change] to `project.recap` and saves; false (with the message shown) when saving failed. */
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

    internal fun busy(job: String, progress: Double? = null) {
        _state.value = _state.value.copy(job = job, progress = progress, message = null)
    }

    internal fun done(message: String?, ok: Boolean = false) {
        refresh()
        _state.value = _state.value.copy(job = null, progress = null, message = message ?: _state.value.message, messageOk = ok && message != null)
    }

    internal fun say(message: String?) {
        _state.value = _state.value.copy(message = message, messageOk = false)
    }

    companion object {
        const val NO_FFMPEG = "ffmpeg is not available, so the movie cannot be read."
        const val SUMMARY_FILE = "summary.txt"
        const val SUBTITLES_FILE = "subtitles.srt"
        const val WRITING = "Gemini is writing the script…"
        const val RATE_LIMITED = "The free-tier limit is reached; try again later"
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
