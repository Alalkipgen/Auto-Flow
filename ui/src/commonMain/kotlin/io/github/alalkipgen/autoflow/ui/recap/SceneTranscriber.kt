package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.SceneSpan
import io.github.alalkipgen.autoflow.ai.TranscribeProvider
import io.github.alalkipgen.autoflow.ai.TranscriptSegment
import io.github.alalkipgen.autoflow.ai.retry.isBusy
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.recap.AiTimingStage
import io.github.alalkipgen.autoflow.core.recap.timedAiStage
import io.github.alalkipgen.autoflow.core.hash.Sha256
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.recap.AiItemPaused
import io.github.alalkipgen.autoflow.core.recap.AudioChunk
import io.github.alalkipgen.autoflow.core.recap.RecapScene
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.core.recap.SceneAudioChunks
import io.github.alalkipgen.autoflow.core.recap.Srt
import io.github.alalkipgen.autoflow.core.recap.SrtCue
import io.github.alalkipgen.autoflow.core.recap.Transcript
import io.github.alalkipgen.autoflow.core.recap.TranscriptLine
import io.github.alalkipgen.autoflow.core.recap.TranscriptRange
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.media.audio.SceneAudio
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** Writes the sound of [chunk] (cut from [movie]) to [out]; null = written, else the reason. */
fun interface SceneAudioCutter {
    suspend fun cut(movie: String, chunk: AudioChunk, out: String, workDir: String): String?
}

/**
 * "Transcribe picked scenes" (5B-1, D-44, spec §7), project-bound like Speak All (audit F11): a TRANSCRIBE job item
 * is one request ([SceneAudioChunks.item]: the movie key and its ranges) of the job's own project folder. The
 * ranges' sound only is cut ([SceneAudio], `.cache/transcribe/<hash>.m4a`), sent to the [provider]'s scene mode,
 * mapped back to movie time and merged into that project's `tx` artifact (`source: "gemini-scenes"`,
 * `recap.sources.transcript`) under its folder lock — whichever project is open now. A range already in the
 * transcript is done without a request; a movie imported since the job started fails the item, never mixes movies.
 */
class SceneTranscriber(
    private val session: ProjectSession,
    private val fs: FileSystem,
    private val media: MediaTools?,
    private val provider: TranscribeProvider?,
    private val cutter: SceneAudioCutter? = null,
    private val clock: Clock = Clock.System,
) {
    /** What one "Transcribe picked scenes" would send: [scenes] packed into [chunks], one request each. */
    data class Plan(val movieKey: String, val scenes: List<RecapScene>, val chunks: List<AudioChunk>) {
        val requests: Int get() = chunks.size
        val audioMs: Long get() = chunks.sumOf { it.durationMs }
        val items: List<String> get() = chunks.map { SceneAudioChunks.item(movieKey, it) }
    }

    val available: Boolean get() = provider != null

    /** The picked scenes of [recap] (project [dir]) with no `.srt` line and no transcript yet; null without a movie. */
    fun plan(dir: String, recap: RecapV2): Plan? {
        val movie = recap.movie ?: return null
        val scenes = SceneAudioChunks.toTranscribe(recap, cuesOf(dir, recap), transcriptOf(dir, recap))
        return Plan(SceneAudioChunks.movieKey(movie), scenes, SceneAudioChunks.plan(scenes.map { TranscriptRange(it.startMs, it.endMs) }))
    }

    /** `.srt` lines of project [dir] (`recap.sources.subtitles`); none when there is no such file. */
    fun cuesOf(dir: String, recap: RecapV2): List<SrtCue> {
        val rel = recap.sources.subtitles ?: return emptyList()
        return runCatching { Srt.parse(fs.read(joinPath(dir, rel)).decodeToString().removePrefix("\uFEFF")) }.getOrDefault(emptyList())
    }

    /** The `tx` artifact of project [dir]; null when there is none or it cannot be read. */
    fun transcriptOf(dir: String, recap: RecapV2): Transcript? {
        val id = recap.sources.transcript ?: return null
        val bytes = (ArtifactStore(fs, dir, clock).readBytes(id) as? CoreResult.Ok)?.value ?: return null
        return runCatching { CoreJson.decodeFromString(Transcript.serializer(), bytes.decodeToString()) }.getOrNull()
    }

    /** Spec §5 "finished items are found from the project": every range of [item] is in [dir]'s transcript, same movie. */
    fun isItemDone(dir: String, item: String): Boolean {
        val recap = session.projectAt(dir)?.recap ?: return false
        val (key, chunk) = SceneAudioChunks.parseItem(item) ?: return false
        val movie = recap.movie ?: return false
        if (SceneAudioChunks.movieKey(movie) != key) return false
        val transcript = transcriptOf(dir, recap) ?: return false
        return chunk.scenes.all { transcript.covers(it.inMs, it.outMs) }
    }

    /**
     * One TRANSCRIBE item of project [dir]. Returns when its lines are saved (or were already there). Throws
     * [AiItemPaused] when Gemini is only busy (429 / 5xx after retries: the job pauses), otherwise an exception with
     * the reason (the job fails and keeps this item and the ones after it).
     */
    suspend fun runItem(dir: String, item: String) {
        if (isItemDone(dir, item)) return
        val p = provider ?: error(UNAVAILABLE)
        val project = session.projectAt(dir) ?: error("The project in $dir cannot be read")
        val recap = project.recap ?: error("${project.name} is not a Movie Recap")
        val (key, chunk) = SceneAudioChunks.parseItem(item) ?: error("This Transcribe job has an unreadable item: $item")
        val movie = recap.movie ?: error("${project.name} has no movie")
        if (SceneAudioChunks.movieKey(movie) != key) error(MOVIE_CHANGED)
        if (!movie.hasAudio) error(NO_SOUND)
        // A given [cutter] runs in the caller's context (it picks its own threads); ffmpeg and the file work run on
        // Dispatchers.Default.
        val audio = timedAiStage(AiTimingStage.MEDIA_PREP) {
            cutter?.let { soundOf(it, dir, movie.file, item, chunk) }
                ?: withContext(Dispatchers.Default) { soundOf(ffmpegCutter() ?: error(RecapModel.NO_FFMPEG), dir, movie.file, item, chunk) }
        }
        val spans = chunk.scenes.map { SceneSpan(it.offsetMs, it.endOffsetMs) }
        when (val r = p.transcribeScenes(audio, SceneAudio.MIME, spans, lang = null)) {
            is AiResult.Ok -> timedAiStage(AiTimingStage.SAVE) { save(dir, project.id, key, chunk, r.value) }
            is AiResult.RateLimited -> throw AiItemPaused("Gemini is busy (${r.message.ifBlank { "rate limited" }}). Resume to go on.")
            is AiResult.Failed -> if (r.isBusy()) throw AiItemPaused("Gemini is busy (${r.msg}). Resume to go on.") else error("Gemini: ${r.msg}")
            is AiResult.Deferred -> error(r.instruction)
        }
    }

    /** Cuts [chunk] of the movie [movieFile] into `.cache/transcribe/<hash>.m4a` of project [dir] and reads it. */
    private suspend fun soundOf(cut: SceneAudioCutter, dir: String, movieFile: String, item: String, chunk: AudioChunk): ByteArray {
        val cache = joinPath(joinPath(dir, ProjectSession.CACHE_DIR), "transcribe")
        fs.createDirectories(cache)
        val out = joinPath(cache, "${Sha256.hex(item.encodeToByteArray()).take(16)}.m4a")
        cut.cut(joinPath(dir, movieFile), chunk, out, dir)?.let { error("Cannot cut the scenes' sound: $it") }
        return runCatching { fs.read(out) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: error("Cannot cut the scenes' sound: no audio was written")
    }

    /** Merges the lines of [chunk] into the project's transcript (locked read-modify-write; same movie only). */
    private fun save(dir: String, projectId: String, key: String, chunk: AudioChunk, perScene: List<List<TranscriptSegment>>) {
        if (perScene.size != chunk.scenes.size) error("Gemini answered for ${perScene.size} of ${chunk.scenes.size} scenes")
        val lines = chunk.scenes.zip(perScene).flatMap { (scene, segments) ->
            segments.map { TranscriptLine(scene.toMovieMs(it.startMs), scene.toMovieMs(it.endMs), it.text) }
        }
        var problem: String? = null
        val r = session.update(dir, projectId) { p ->
            val recap = p.recap
            val movie = recap?.movie
            if (recap == null || movie == null || SceneAudioChunks.movieKey(movie) != key) {
                problem = MOVIE_CHANGED
                return@update p
            }
            val old = transcriptOf(dir, recap) ?: Transcript(source = Transcript.SOURCE_GEMINI_SCENES, lang = Transcript.LANG_UNKNOWN)
            val next = old.withScenes(chunk.ranges, lines)
            val bytes = CoreJson.encodeToString(Transcript.serializer(), next).encodeToByteArray()
            when (val put = ArtifactStore(fs, dir, clock).put(bytes, ArtifactKind.TRANSCRIPT, "json", provider = Transcript.SOURCE_GEMINI_SCENES)) {
                is CoreResult.Ok -> p.copy(recap = recap.copy(sources = recap.sources.copy(transcript = put.value.id)))
                is CoreResult.Err -> { problem = put.error.message; p }
            }
        }
        problem?.let { error(it) }
        (r as? CoreResult.Err)?.let { error(it.error.message) }
    }

    private fun ffmpegCutter(): SceneAudioCutter? {
        val runner = media?.runner ?: return null
        val ffmpeg = media.ffmpeg() ?: return null
        return SceneAudioCutter { movie, chunk, out, workDir -> SceneAudio(runner, ffmpeg).cut(movie, chunk, out, workDir) }
    }

    companion object {
        const val UNAVAILABLE = "Transcription is not available here"
        const val MOVIE_CHANGED = "The movie changed since these scenes were picked. Transcribe them again."
        const val NO_SOUND = "This movie has no sound to transcribe."
    }
}
