package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.ai.AiResult
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
import io.github.alalkipgen.autoflow.core.recap.Recap
import io.github.alalkipgen.autoflow.core.recap.RecapMovie
import io.github.alalkipgen.autoflow.core.recap.RecapRules
import io.github.alalkipgen.autoflow.core.recap.RecapScene
import io.github.alalkipgen.autoflow.core.recap.RecapScript
import io.github.alalkipgen.autoflow.core.recap.RecapSegment
import io.github.alalkipgen.autoflow.core.recap.RecapStyle
import io.github.alalkipgen.autoflow.core.recap.Srt
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
import kotlinx.coroutines.withContext

/** What the Movie Recap screen shows besides the project itself. [job] = a long step is running ([progress] 0…1 when known). */
data class RecapUiState(
    val projectDir: String? = null,
    val recap: Recap? = null,
    val movieName: String? = null,
    val job: String? = null,
    val progress: Double? = null,
    val message: String? = null,
    val messageOk: Boolean = false,
    /** The current `rs` artifact, null when none or unreadable. */
    val script: RecapScript? = null,
    /** `source/summary.txt`. */
    val summary: String = "",
) {
    val busy: Boolean get() = job != null
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
            script = recap?.script?.let { id -> (session.readJson(id, RecapScript.serializer()) as? CoreResult.Ok)?.value },
            summary = if (open == null) "" else session.readSource(SUMMARY_FILE),
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
        RecapRules.canImportMovie(recap).blockers.firstOrNull()?.let { say(it.text()); return null }
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
            val saved = update { it.copy(movie = movie, scenes = emptyList(), script = null, scriptApproved = false, takes = emptyList()) }
            done(if (saved) "Movie ready: ${clock(movie.durationMs)}. Next: Find scenes." else null, ok = saved)
        }
    }

    /** Runs `scdet` over the movie ([threshold]: [Scenes.THRESHOLD_MORE] … [Scenes.THRESHOLD_FEWER]). */
    fun findScenes(threshold: Int = Scenes.THRESHOLD_NORMAL): Job? {
        val recap = recap() ?: return null
        RecapRules.canFindScenes(recap).blockers.firstOrNull()?.let { say(it.text()); return null }
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
                    val saved = update { it.copy(scenes = scenes, sceneThreshold = threshold, scriptApproved = false) }
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
            if (update { it.copy(subtitles = "${ProjectSession.SOURCE_DIR}/$SUBTITLES_FILE") }) {
                done("Subtitles ready: ${cues.size} lines.", ok = true)
            }
        }
    }

    fun removeSubtitles() {
        update { it.copy(subtitles = null) }
    }

    fun setStyle(style: RecapStyle) {
        update { it.copy(style = style) }
    }

    /** ✨ Write script (Gemini): one narration text per picked scene; the new `rs` replaces the current one, not approved. */
    fun writeScript(): Job? {
        val recap = recap() ?: return null
        val summary = _state.value.summary
        val cues = recap.subtitles?.let { Srt.parse(session.readSource(SUBTITLES_FILE)) }.orEmpty()
        val gate = RecapRules.canGenerateScript(recap, summary, if (cues.isEmpty()) "" else "srt")
        gate.blockers.firstOrNull()?.let { say(it.text()); return null }
        val gen = generator ?: run { say("Gemini is not available here"); return null }
        if (_state.value.busy) return null
        val brief = RecapBrief(
            title = project()?.name.orEmpty(),
            summary = summary,
            style = recap.style,
            scenes = recap.picked.map { SceneBrief(it.startMs, it.endMs, Srt.textIn(cues, it.startMs, it.endMs, RecapPrompts.DIALOGUE_CHARS)) },
        )
        busy(WRITING)
        return scope.launch {
            try {
                when (val r = gen.write(brief)) {
                    is AiResult.Ok -> {
                        val segments = recap.picked.zip(r.value) { scene, text -> RecapSegment(scene.startMs, scene.endMs, text) }
                        val saved = saveScript(RecapScript(source = RecapScript.SOURCE_GEMINI, style = recap.style, segments = segments))
                        done(if (saved) "✨ Script written: ${segments.size} segments. Read and fix them, then ✓ Approve." else null, ok = saved)
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

    /** ✎ Write it myself: a script with one empty segment per picked scene. */
    fun writeManually(): Boolean {
        val recap = recap() ?: return false
        RecapRules.canWriteManually(recap).blockers.firstOrNull()?.let { say(it.text()); return false }
        val segments = recap.picked.map { RecapSegment(it.startMs, it.endMs) }
        return saveScript(RecapScript(source = RecapScript.SOURCE_MANUAL, style = recap.style, segments = segments))
    }

    /** Saves segment [index] with [text] as a new `rs`; approval is cleared. */
    fun saveSegment(index: Int, text: String): Boolean {
        val script = _state.value.script ?: return false
        if (index !in script.segments.indices) return false
        val segments = script.segments.mapIndexed { i, s -> if (i == index) s.copy(textMy = text.trim()) else s }
        return saveScript(script.copy(segments = segments))
    }

    /** ✓ Approve script ([RecapRules.canApproveScript]). */
    fun approveScript(): Boolean {
        val recap = recap() ?: return false
        RecapRules.canApproveScript(recap, _state.value.script).blockers.firstOrNull()?.let { say(it.text()); return false }
        return update { it.copy(scriptApproved = true) }
    }

    private fun saveScript(script: RecapScript): Boolean {
        val store = session.artifacts() ?: return false
        val bytes = CoreJson.encodeToString(RecapScript.serializer(), script).encodeToByteArray()
        return when (val r = store.put(bytes, ArtifactKind.RECAP_SCRIPT, "json", provider = script.source)) {
            is CoreResult.Ok -> update { it.copy(script = r.value.id, scriptApproved = false) }
            is CoreResult.Err -> { say(r.error.message); false }
        }
    }

    fun dismissMessage() = say(null)

    // --- helpers -----------------------------------------------------------------------------------------------

    private fun recap(): Recap? = session.open?.project?.recap

    private fun Recap?.orEmptyScenes(): List<RecapScene> = this?.scenes.orEmpty()

    /** Applies [change] to `project.recap` and saves; false (with the message shown) when saving failed. */
    internal fun update(change: (Recap) -> Recap): Boolean {
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
