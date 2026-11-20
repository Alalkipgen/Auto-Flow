package io.github.alalkipgen.autoflow.ui.voice

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.TtsProvider
import io.github.alalkipgen.autoflow.ai.VoiceRef
import io.github.alalkipgen.autoflow.ai.retry.isBusy
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.hash.Sha256
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.VoiceTool
import io.github.alalkipgen.autoflow.core.recap.AiItemPaused
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.core.recap.AiTimingStage
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.core.recap.timedAiStage
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.voice.VoiceTakes
import io.github.alalkipgen.autoflow.media.audio.PcmWav
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.subtitles.VideoTranscriber
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/** One part Speak sends: its id, take key and the exact text after the Lexicon. */
@Serializable
data class VoiceSpeakPart(val id: String, val hash: String, val spoken: String)

/** What a TEXT_TTS job speaks, fixed at Start (`.cache/jobs/<job>/request.json`). */
@Serializable
data class VoiceSpeakRequest(val lock: VoiceLock, val tone: String? = null, val style: String? = null, val parts: List<VoiceSpeakPart>)

/** A spoken part, saved before it joins the project (`<item>.json`), so it is never sent again. */
@Serializable
data class VoiceSpokenPart(val take: String, val hash: String, val durationMs: Long)

/**
 * Text → voice Speak (specs/quick-tools.md §6–§7, V-1e): a resumable TEXT_TTS job of the project folder, one item per
 * part (`p<n>`), with the project's voice lock (exact model, never another one) and tone style. Each answer is stored
 * as an `au` artifact and becomes the part's take only while the part still has the key it was spoken for.
 */
class VoiceSpeakJob(
    private val session: ProjectSession,
    private val fs: FileSystem,
    private val tts: TtsProvider?,
    private val config: ToolsConfig = ToolsConfig(),
    private val lexicon: () -> Lexicon = { Lexicon.EMPTY },
    private val clock: Clock = Clock.System,
    /** `tts.edge.rpm` (F-3): an Edge lock's pace. */
    private val edgeRpm: Int = io.github.alalkipgen.autoflow.ai.config.EdgeTtsConfig().rpm,
) {
    val available: Boolean get() = tts != null

    /** Requests per minute a job with [lock] sends: Edge's own pace, else Gemini's [RPM]. */
    fun rpm(lock: VoiceLock?): Int = EdgeVoices.rpm(lock, edgeRpm)

    /**
     * What Speak would send now: the parts without a current take, or exactly [only] (Speak this part again). The
     * project's parts are re-planned first, so ids and keys match what is saved. Null without a voice lock.
     */
    fun plan(dir: String, only: Set<String>? = null): VoiceSpeakRequest? {
        val p = session.projectAt(dir) ?: return null
        val voice = p.voice ?: return null
        val lock = voice.lock ?: return null
        val max = config.tts.maxCharsPerPart
        val lexicon = lexicon()
        val planned = VoiceTakes.plan(voice, max, lexicon)
        if (planned != voice) session.update(dir, p.id) { it.copy(voice = it.voice?.let { v -> VoiceTakes.plan(v, max, lexicon) }) }
        val parts = planned.parts.zip(VoiceTakes.texts(voice, max)).filter { (part, _) -> only?.contains(part.id) ?: (part.take == null) }
            .map { (part, text) -> VoiceSpeakPart(part.id, part.hash, VoiceTakes.spoken(text, lexicon)) }.filter { it.spoken.isNotEmpty() }
        return VoiceSpeakRequest(lock, voice.tone, config.tts.style(voice.tone), parts)
    }

    suspend fun enqueue(runner: AiJobRunner, dir: String, request: VoiceSpeakRequest): String {
        val project = session.projectAt(dir) ?: error("The project in $dir cannot be read")
        val id = "va-${project.id}-${clock.now().toEpochMilliseconds()}-${kotlin.random.Random.nextInt(0x1000000).toString(16)}"
        fs.createDirectories(VideoTranscriber.jobDir(dir, id))
        fs.writeDurably(joinPath(VideoTranscriber.jobDir(dir, id), VideoTranscriber.REQUEST),
            CoreJson.encodeToString(VoiceSpeakRequest.serializer(), request).encodeToByteArray())
        val key = Sha256.hex((listOf(project.id, request.lock.toString(), request.tone.orEmpty()) + request.parts.map { "${it.id}:${it.hash}" })
            .joinToString("|").encodeToByteArray())
        return runner.enqueue(AiJob(id, AiJobKind.TEXT_TTS, dir, request.parts.map { it.id }, rpm = rpm(request.lock), inputKey = key))
    }

    /** One part of job [jobId]: [AiItemPaused] when the model is busy, an exception when the request failed (Resume retries it). */
    suspend fun runItem(dir: String, jobId: String, item: String) {
        val folder = VideoTranscriber.jobDir(dir, jobId)
        val request = runCatching {
            CoreJson.decodeFromString(VoiceSpeakRequest.serializer(), fs.read(joinPath(folder, VideoTranscriber.REQUEST)).decodeToString())
        }.getOrNull() ?: error("This Speak job's request is missing in $dir")
        val part = request.parts.firstOrNull { it.id == item } ?: error("This Speak job has an unknown part: $item")
        val path = joinPath(folder, "$item.json")
        val project = session.projectAt(dir) ?: error("The project in $dir cannot be read")
        val saved = runCatching { CoreJson.decodeFromString(VoiceSpokenPart.serializer(), fs.read(path).decodeToString()) }.getOrNull()
        // The part changed or went away since Start: nothing is sent for it.
        if (saved == null && project.voice?.parts?.none { it.id == item && it.hash == part.hash } != false) return
        val spoken = saved
            ?: speak(dir, request, part).also {
                timedAiStage(AiTimingStage.SAVE) {
                    fs.writeDurably("$path.tmp", CoreJson.encodeToString(VoiceSpokenPart.serializer(), it).encodeToByteArray())
                    fs.atomicMove("$path.tmp", path)
                }
            }
        session.update(dir, project.id) { p -> p.copy(voice = p.voice?.let { attach(it, item, spoken) }) }
    }

    private suspend fun speak(dir: String, request: VoiceSpeakRequest, part: VoiceSpeakPart): VoiceSpokenPart {
        val t = tts ?: error(NO_TTS)
        val lock = request.lock
        val model = lock.model.takeIf { it.isNotBlank() }
        return when (val r = t.speak(part.spoken, VoiceRef(lock.voice), lock.lang, request.style, model)) {
            is AiResult.Ok -> {
                val ms = timedAiStage(AiTimingStage.PARSE) { PcmWav.parse(r.value)?.durationMs } ?: error("The speech audio cannot be read")
                when (val put = ArtifactStore(fs, dir, clock).put(r.value, ArtifactKind.AUDIO, "wav", provider = lock.provider,
                    model = r.usage?.model?.takeIf { it.isNotBlank() } ?: model, note = "voice ${lock.voice}")) {
                    is CoreResult.Ok -> VoiceSpokenPart(put.value.id.value, part.hash, ms)
                    is CoreResult.Err -> error(put.error.message)
                }
            }
            is AiResult.RateLimited -> throw AiItemPaused("${model ?: io.github.alalkipgen.autoflow.ui.voice.EdgeVoices.ttsName(lock)} is busy (${r.message.ifBlank { "rate limited" }}). Resume to go on.")
            is AiResult.Failed -> if (r.isBusy()) throw AiItemPaused("${model ?: io.github.alalkipgen.autoflow.ui.voice.EdgeVoices.ttsName(lock)} is busy (${r.msg}). Resume to go on.")
                else error("${io.github.alalkipgen.autoflow.ui.voice.EdgeVoices.ttsName(lock)}: ${r.msg}")
            is AiResult.Deferred -> error(r.instruction)
        }
    }

    companion object {
        /** Gemini TTS requests per minute, as Recap's Speak all. */
        const val RPM = 3
        const val NO_TTS = "Text to speech is not set up, so the parts cannot be spoken. Add a key in Settings."

        /** [voice] with [spoken] as the take of part [item] when that part still has the key it was spoken for. */
        fun attach(voice: VoiceTool, item: String, spoken: VoiceSpokenPart): VoiceTool {
            val take = ArtifactId(spoken.take)
            if (voice.parts.none { it.id == item && it.hash == spoken.hash && it.take != take }) return voice
            return voice.copy(parts = voice.parts.map { if (it.id == item && it.hash == spoken.hash) it.copy(take = take) else it }, joined = null)
        }
    }
}
