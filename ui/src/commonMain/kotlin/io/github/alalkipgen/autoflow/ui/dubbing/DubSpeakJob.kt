package io.github.alalkipgen.autoflow.ui.dubbing

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.TtsProvider
import io.github.alalkipgen.autoflow.ai.VoiceRef
import io.github.alalkipgen.autoflow.ai.retry.isBusy
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.dubbing.DubRules
import io.github.alalkipgen.autoflow.core.hash.Sha256
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.DubTake
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.recap.AiItemPaused
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.core.recap.AiTimingStage
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.core.recap.timedAiStage
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.media.audio.PcmWav
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.subtitles.SubtitleVersions
import io.github.alalkipgen.autoflow.ui.subtitles.VideoTranscriber
import io.github.alalkipgen.autoflow.ui.voice.VoiceSpeakJob
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/** One line Speak sends: its cue id, take key and the exact text after the Lexicon. */
@Serializable
data class DubSpeakLine(val id: String, val key: String, val spoken: String)

/** What a DUB_TTS job speaks, fixed at Start (`.cache/jobs/<job>/request.json`). */
@Serializable
data class DubSpeakRequest(val lock: VoiceLock, val tone: String? = null, val style: String? = null, val lines: List<DubSpeakLine>)

/** A spoken line, saved before it joins the project (`<item>.json`), so it is never sent again. */
@Serializable
data class DubSpokenLine(val take: String, val key: String, val ms: Long, val leadMs: Long)

/**
 * Dub voices (specs/dubbing.md §5, D-2): a resumable DUB_TTS job of the project folder, one item per line (cue id),
 * with the project's voice lock (exact model) and tone. Nothing is planned or sent unless `DubRules.canSpeak` allows
 * it (approved, unchanged script); each answer is an `au` artifact measured for its voice (silence trimmed) and
 * becomes that line's take only while the line still has the key it was spoken for — the other lines are untouched.
 */
class DubSpeakJob(
    private val session: ProjectSession,
    private val fs: FileSystem,
    private val tts: TtsProvider?,
    private val config: ToolsConfig = ToolsConfig(),
    private val lexicon: () -> Lexicon = { Lexicon.EMPTY },
    private val clock: Clock = Clock.System,
    /** `tts.edge.rpm` (F-3): an Edge lock's pace. */
    private val edgeRpm: Int = io.github.alalkipgen.autoflow.ai.config.EdgeTtsConfig().rpm,
) {
    private val versions = SubtitleVersions(session, fs, clock)
    val available: Boolean get() = tts != null

    /** Requests per minute a job with [lock] sends: Edge's own pace, else Gemini's [VoiceSpeakJob.RPM]. */
    fun rpm(lock: VoiceLock?): Int = io.github.alalkipgen.autoflow.ui.voice.EdgeVoices.rpm(lock, edgeRpm)

    /** The project at [dir] and its current `st`, when the script may be voiced (lock set, `DubRules.canSpeak`). */
    private fun speakable(dir: String): Pair<Project, SubtitleDoc>? {
        val p = session.projectAt(dir) ?: return null
        val doc = p.subtitles?.current?.let { versions.read(dir, it) } ?: return null
        if (p.dubbing?.lock == null) return null
        return if (DubRules.canSpeak(p.subtitles?.video?.durationMs, config.dubbing.maxSourceMs, p.dubbing, doc) == null) p to doc else null
    }

    /** What Speak would send now: the lines without a current take, or exactly [only] (↻ one line). Null when blocked. */
    fun plan(dir: String, only: Set<String>? = null): DubSpeakRequest? {
        val (p, doc) = speakable(dir) ?: return null
        val d = p.dubbing!!
        val lock = d.lock!!
        val lex = lexicon()
        val ids = only?.let { o -> doc.cues.map { it.id }.filter { it in o } } ?: DubRules.toSpeak(d, doc, lex)
        val lines = doc.cues.filter { it.id in ids }.map { DubSpeakLine(it.id, DubRules.takeKey(it, lex, lock, d.tone), DubRules.sayText(it, lex)) }
            .filter { it.spoken.isNotEmpty() }
        return DubSpeakRequest(lock, d.tone, config.tts.style(d.tone), lines)
    }

    suspend fun enqueue(runner: AiJobRunner, dir: String, request: DubSpeakRequest): String {
        val project = session.projectAt(dir) ?: error("The project in $dir cannot be read")
        val id = "dv-${project.id}-${clock.now().toEpochMilliseconds()}-${kotlin.random.Random.nextInt(0x1000000).toString(16)}"
        fs.createDirectories(VideoTranscriber.jobDir(dir, id))
        fs.writeDurably(joinPath(VideoTranscriber.jobDir(dir, id), VideoTranscriber.REQUEST),
            CoreJson.encodeToString(DubSpeakRequest.serializer(), request).encodeToByteArray())
        val key = Sha256.hex((listOf(project.id, request.lock.toString(), request.tone.orEmpty()) + request.lines.map { "${it.id}:${it.key}" })
            .joinToString("|").encodeToByteArray())
        return runner.enqueue(AiJob(id, AiJobKind.DUB_TTS, dir, request.lines.map { it.id }, rpm = rpm(request.lock), inputKey = key))
    }

    /** One line of job [jobId]: [AiItemPaused] when the model is busy, an exception when the request failed (Resume retries it). */
    suspend fun runItem(dir: String, jobId: String, item: String) {
        val folder = VideoTranscriber.jobDir(dir, jobId)
        val request = runCatching {
            CoreJson.decodeFromString(DubSpeakRequest.serializer(), fs.read(joinPath(folder, VideoTranscriber.REQUEST)).decodeToString())
        }.getOrNull() ?: error("This dub voice job's request is missing in $dir")
        val line = request.lines.firstOrNull { it.id == item } ?: error("This dub voice job has an unknown line: $item")
        val path = joinPath(folder, "$item.json")
        val saved = runCatching { CoreJson.decodeFromString(DubSpokenLine.serializer(), fs.read(path).decodeToString()) }.getOrNull()
        val (project, doc) = speakable(dir) ?: return // not approved any more: no voice is made, none is attached
        // The line changed or went away since Start: nothing is sent for it.
        if (saved == null && keyOf(project, doc, item) != line.key) return
        val spoken = saved
            ?: speak(dir, request, line).also {
                timedAiStage(AiTimingStage.SAVE) {
                    fs.writeDurably("$path.tmp", CoreJson.encodeToString(DubSpokenLine.serializer(), it).encodeToByteArray())
                    fs.atomicMove("$path.tmp", path)
                }
            }
        session.update(dir, project.id) { p ->
            val now = p.subtitles?.current?.let { versions.read(dir, it) }
            if (now == null || keyOf(p, now, item) != spoken.key) p
            else p.copy(dubbing = p.dubbing?.let { it.copy(takes = it.takes + (item to DubTake(ArtifactId(spoken.take), spoken.key, spoken.ms, spoken.leadMs))) })
        }
    }

    private fun keyOf(p: Project, doc: SubtitleDoc, id: String): String? {
        val d = p.dubbing ?: return null
        val cue = doc.cues.firstOrNull { it.id == id } ?: return null
        return d.lock?.let { DubRules.takeKey(cue, lexicon(), it, d.tone) }
    }

    private suspend fun speak(dir: String, request: DubSpeakRequest, line: DubSpeakLine): DubSpokenLine {
        val t = tts ?: error(VoiceSpeakJob.NO_TTS)
        val lock = request.lock
        val model = lock.model.takeIf { it.isNotBlank() }
        return when (val r = t.speak(line.spoken, VoiceRef(lock.voice), lock.lang, request.style, model)) {
            is AiResult.Ok -> {
                val wav = timedAiStage(AiTimingStage.PARSE) { PcmWav.parse(r.value) } ?: error("The speech audio cannot be read")
                val (lead, ms) = wav.voicedMs() ?: error("The speech audio is silent")
                when (val put = ArtifactStore(fs, dir, clock).put(r.value, ArtifactKind.AUDIO, "wav", provider = lock.provider,
                    model = r.usage?.model?.takeIf { it.isNotBlank() } ?: model, note = "dub ${line.id} voice ${lock.voice}")) {
                    is CoreResult.Ok -> DubSpokenLine(put.value.id.value, line.key, ms, lead)
                    is CoreResult.Err -> error(put.error.message)
                }
            }
            is AiResult.RateLimited -> throw AiItemPaused("${model ?: io.github.alalkipgen.autoflow.ui.voice.EdgeVoices.ttsName(lock)} is busy (${r.message.ifBlank { "rate limited" }}). Resume to go on.")
            is AiResult.Failed -> if (r.isBusy()) throw AiItemPaused("${model ?: io.github.alalkipgen.autoflow.ui.voice.EdgeVoices.ttsName(lock)} is busy (${r.msg}). Resume to go on.")
                else error("${io.github.alalkipgen.autoflow.ui.voice.EdgeVoices.ttsName(lock)}: ${r.msg}")
            is AiResult.Deferred -> error(r.instruction)
        }
    }
}
