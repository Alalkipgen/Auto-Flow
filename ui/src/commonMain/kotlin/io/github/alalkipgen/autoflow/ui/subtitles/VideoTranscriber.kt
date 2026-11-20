package io.github.alalkipgen.autoflow.ui.subtitles

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.SceneSpan
import io.github.alalkipgen.autoflow.ai.TranscribeProvider
import io.github.alalkipgen.autoflow.ai.retry.isBusy
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.hash.Sha256
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.SubtitlesTool
import io.github.alalkipgen.autoflow.core.recap.AiItemPaused
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.core.recap.AiTimingStage
import io.github.alalkipgen.autoflow.core.recap.AudioChunk
import io.github.alalkipgen.autoflow.core.recap.ChunkScene
import io.github.alalkipgen.autoflow.core.recap.SrtCue
import io.github.alalkipgen.autoflow.core.recap.Transcript
import io.github.alalkipgen.autoflow.core.recap.TranscriptLine
import io.github.alalkipgen.autoflow.core.recap.TranscriptRange
import io.github.alalkipgen.autoflow.core.recap.timedAiStage
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.subtitles.MediaChunk
import io.github.alalkipgen.autoflow.core.subtitles.MediaChunks
import io.github.alalkipgen.autoflow.core.subtitles.MediaTranscribeRequest
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleOps
import io.github.alalkipgen.autoflow.media.audio.AudioFilters
import io.github.alalkipgen.autoflow.media.audio.SceneAudio
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.recap.RecapModel
import io.github.alalkipgen.autoflow.ui.recap.SceneAudioCutter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * Whole-video transcription of a Subtitles project (S-5, D-80, spec §7–§9): only the sound is sent, in chunks of at
 * most `transcribe.chunkMaxMs` cut at a pause ([MediaChunks.plan] over `silencedetect`), as a resumable
 * MEDIA_TRANSCRIBE job of the project folder. Each item cuts its chunk's sound ([SceneAudio], mono 16 kHz AAC), sends
 * it as one scene, and saves the lines in source time to `.cache/jobs/<job>/k<start>.json`; a saved chunk is never
 * sent again (Resume sends only the rest). When the last chunk is saved, all are joined ([MediaChunks.merge]) into a
 * `tx` (`gemini-media`) and a new `st` (`tx:<id>`, after the current version) that becomes `subtitles.current`.
 */
class VideoTranscriber(
    private val session: ProjectSession,
    private val fs: FileSystem,
    private val media: MediaTools?,
    private val provider: TranscribeProvider?,
    private val config: ToolsConfig = ToolsConfig(),
    private val cutter: SceneAudioCutter? = null,
    private val pauses: (suspend (file: String, dir: String) -> List<MediaChunks.Pause>?)? = null,
    private val clock: Clock = Clock.System,
) {
    /** What Transcribe would send: [chunks], one request each, [audioMs] of sound. */
    data class Plan(val chunks: List<MediaChunk>) {
        val requests: Int get() = chunks.size
        val audioMs: Long get() = chunks.sumOf { it.durationMs }
    }

    val available: Boolean get() = provider != null

    /** The chunks of [video] in project [dir]; pauses that cannot be found give hard cuts with overlap (still ≤ max). */
    suspend fun plan(dir: String, video: SourceVideo): Plan {
        val t = config.transcribe
        val found = (pauses ?: ::findPauses)(video.file, dir).orEmpty()
        return Plan(MediaChunks.plan(video.durationMs, found, t.chunkMaxMs, t.silenceWindowMs, t.overlapMs))
    }

    /** Queues the job of [plan] for project [dir] ([lang] = a `transcribe.languages` entry, `auto` = not given). */
    suspend fun enqueue(runner: AiJobRunner, dir: String, plan: Plan, lang: String): String {
        val project = session.projectAt(dir) ?: error("The project in $dir cannot be read")
        val video = project.subtitles?.video ?: error("${project.name} has no video")
        val id = "mt-${project.id}-${clock.now().toEpochMilliseconds()}-${kotlin.random.Random.nextInt(0x1000000).toString(16)}"
        val request = MediaTranscribeRequest(videoKey(video), lang, plan.chunks.map { TranscriptRange(it.startMs, it.endMs) })
        fs.createDirectories(jobDir(dir, id))
        fs.writeDurably(joinPath(jobDir(dir, id), REQUEST), CoreJson.encodeToString(MediaTranscribeRequest.serializer(), request).encodeToByteArray())
        val items = plan.chunks.map { it.item }
        val key = Sha256.hex((listOf(project.id, videoKey(video), lang) + items).joinToString("|").encodeToByteArray())
        return runner.enqueue(AiJob(id, AiJobKind.MEDIA_TRANSCRIBE, dir, items, rpm = RecapModel.TRANSCRIBE_RPM, inputKey = key))
    }

    /** The chunk's lines are saved (spec §7: found from the project folder, not only from the job file). */
    fun isItemDone(dir: String, jobId: String, item: String): Boolean =
        MediaChunk.parse(item)?.let { fs.exists(joinPath(jobDir(dir, jobId), it.file)) } ?: false

    /**
     * One item of job [jobId]. Throws [AiItemPaused] when Gemini is only busy (the job pauses, Resume goes on), else an
     * exception with the reason (the job fails and keeps this chunk and the rest; finished chunks stay saved).
     */
    suspend fun runItem(dir: String, jobId: String, item: String) {
        val chunk = MediaChunk.parse(item) ?: error("This Transcribe job has an unreadable item: $item")
        val request = request(dir, jobId) ?: error("This Transcribe job's request is missing in $dir")
        if (!isItemDone(dir, jobId, item)) {
            val p = provider ?: error(NO_GEMINI)
            val video = video(dir, request)
            val audio = timedAiStage(AiTimingStage.MEDIA_PREP) {
                cutter?.let { sound(it, dir, video.file, chunk) }
                    ?: withContext(Dispatchers.Default) { sound(ffmpegCutter() ?: error(NO_FFMPEG), dir, video.file, chunk) }
            }
            val lang = request.lang.takeUnless { it == AUTO }
            when (val r = p.transcribeScenes(audio, SceneAudio.MIME, listOf(SceneSpan(0, chunk.durationMs)), lang)) {
                is AiResult.Ok -> timedAiStage(AiTimingStage.SAVE) {
                    val lines = r.value.firstOrNull().orEmpty().map { TranscriptLine(chunk.startMs + it.startMs, chunk.startMs + it.endMs, it.text) }
                    val part = Transcript(source = Transcript.SOURCE_GEMINI_MEDIA, lang = lang ?: Transcript.LANG_UNKNOWN, lines = lines,
                        scenes = listOf(TranscriptRange(chunk.startMs, chunk.endMs)))
                    val path = joinPath(jobDir(dir, jobId), chunk.file)
                    fs.writeDurably("$path.tmp", CoreJson.encodeToString(Transcript.serializer(), part).encodeToByteArray())
                    fs.atomicMove("$path.tmp", path)
                }
                is AiResult.RateLimited -> throw AiItemPaused("Gemini is busy (${r.message.ifBlank { "rate limited" }}). Resume to go on.")
                is AiResult.Failed -> if (r.isBusy()) throw AiItemPaused("Gemini is busy (${r.msg}). Resume to go on.") else error("Gemini: ${r.msg}")
                is AiResult.Deferred -> error(r.instruction)
            }
        }
        finish(dir, jobId, request)
    }

    /** Once every chunk is saved (and not yet joined): `tx` + new `st` → `subtitles.current`; `result.json` marks it done. */
    private fun finish(dir: String, jobId: String, request: MediaTranscribeRequest) {
        val folder = jobDir(dir, jobId)
        if (fs.exists(joinPath(folder, RESULT))) return
        val parts = request.parts.map { c ->
            val text = runCatching { fs.read(joinPath(folder, c.file)).decodeToString() }.getOrNull() ?: return
            c to CoreJson.decodeFromString(Transcript.serializer(), text).lines
        }
        val lines = MediaChunks.merge(parts)
        val lang = request.lang.takeUnless { it == AUTO } ?: Transcript.LANG_UNKNOWN
        val tx = Transcript(source = Transcript.SOURCE_GEMINI_MEDIA, lang = lang, lines = lines, scenes = request.chunks)
        val store = ArtifactStore(fs, dir, clock)
        val txId = when (val r = store.put(CoreJson.encodeToString(Transcript.serializer(), tx).encodeToByteArray(), ArtifactKind.TRANSCRIPT, "json",
            provider = Transcript.SOURCE_GEMINI_MEDIA)) {
            is CoreResult.Ok -> r.value.id
            is CoreResult.Err -> error(r.error.message)
        }
        val project = session.projectAt(dir) ?: error("The project in $dir cannot be read")
        var problem: String? = null
        val updated = session.update(dir, project.id) { p ->
            val tool = p.subtitles ?: SubtitlesTool()
            if (tool.video?.let(::videoKey) != request.videoKey) { problem = VIDEO_CHANGED; return@update p }
            val doc = SubtitleOps.fromCues(lines.map { SrtCue(it.startMs, it.endMs, it.text) }, SubtitleDoc.transcriptSource(txId), lang.takeUnless { it == Transcript.LANG_UNKNOWN })
                .copy(derivedFrom = tool.current)
            when (val put = store.put(CoreJson.encodeToString(SubtitleDoc.serializer(), doc).encodeToByteArray(), ArtifactKind.SUBTITLES, "json")) {
                is CoreResult.Ok -> p.copy(subtitles = tool.copy(current = put.value.id))
                is CoreResult.Err -> { problem = put.error.message; p }
            }
        }
        problem?.let { error(it) }
        (updated as? CoreResult.Err)?.let { error(it.error.message) }
        fs.writeDurably(joinPath(folder, RESULT), """{"tx":"${txId.value}","lines":${lines.size}}""".encodeToByteArray())
    }

    private fun request(dir: String, jobId: String): MediaTranscribeRequest? =
        runCatching { CoreJson.decodeFromString(MediaTranscribeRequest.serializer(), fs.read(joinPath(jobDir(dir, jobId), REQUEST)).decodeToString()) }.getOrNull()

    private fun video(dir: String, request: MediaTranscribeRequest): SourceVideo {
        val project = session.projectAt(dir) ?: error("The project in $dir cannot be read")
        val video = project.subtitles?.video ?: error("${project.name} has no video")
        if (videoKey(video) != request.videoKey) error(VIDEO_CHANGED)
        if (!video.hasAudio) error(NO_SOUND)
        return video
    }

    private suspend fun sound(cut: SceneAudioCutter, dir: String, file: String, chunk: MediaChunk): ByteArray {
        val cache = joinPath(joinPath(dir, ProjectSession.CACHE_DIR), "transcribe")
        fs.createDirectories(cache)
        val out = joinPath(cache, "k${chunk.startMs}-${chunk.endMs}.m4a")
        cut.cut(joinPath(dir, file), AudioChunk(listOf(ChunkScene(chunk.startMs, chunk.endMs, 0))), out, dir)?.let { error("Cannot cut the sound: $it") }
        return runCatching { fs.read(out) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: error("Cannot cut the sound: no audio was written")
    }

    private fun ffmpegCutter(): SceneAudioCutter? {
        val runner = media?.runner ?: return null
        val ffmpeg = media.ffmpeg() ?: return null
        return SceneAudioCutter { movie, chunk, out, workDir -> SceneAudio(runner, ffmpeg).cut(movie, chunk, out, workDir) }
    }

    /** `silencedetect` over the sound (spec §9: −35 dB, 0.4 s); null when ffmpeg is missing or fails. */
    private suspend fun findPauses(file: String, dir: String): List<MediaChunks.Pause>? = withContext(Dispatchers.Default) {
        val runner = media?.runner ?: return@withContext null
        val ffmpeg = media.ffmpeg() ?: return@withContext null
        val r = runner.run(listOf(ffmpeg) + AudioFilters.silenceDetectArgs(file, PAUSE_MS, PAUSE_DB), dir, PAUSE_TIMEOUT_MS, keepOutput = true) {}
        if (r !is ToolExec.Done || r.exitCode != 0) return@withContext null
        AudioFilters.parseSilences(r.output).map { s ->
            MediaChunks.Pause((s.start * 1000).toLong(), ((s.end ?: Double.MAX_VALUE / 2000) * 1000).toLong())
        }
    }

    companion object {
        const val AUTO = "auto"
        const val REQUEST = "request.json"
        const val RESULT = "result.json"
        const val NO_GEMINI = "Gemini is not set up, so the video cannot be transcribed. Add a key in Settings."
        const val NO_SOUND = "This video has no sound to transcribe."
        const val NO_FFMPEG = "ffmpeg is not available, so the video's sound cannot be cut."
        const val VIDEO_CHANGED = "The video changed since this transcription started. Transcribe again."
        private const val PAUSE_MS = 400L
        private const val PAUSE_DB = -35
        private const val PAUSE_TIMEOUT_MS = 30 * 60_000L

        fun videoKey(video: SourceVideo): String = "${video.sizeBytes}-${video.durationMs}"
        fun jobDir(dir: String, jobId: String): String = joinPath(joinPath(joinPath(dir, ProjectSession.CACHE_DIR), "jobs"), jobId)
    }
}
