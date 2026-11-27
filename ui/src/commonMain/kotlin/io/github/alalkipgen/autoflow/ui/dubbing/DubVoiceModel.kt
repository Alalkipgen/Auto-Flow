package io.github.alalkipgen.autoflow.ui.dubbing

import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.getOrNull
import io.github.alalkipgen.autoflow.core.dubbing.DubFit
import io.github.alalkipgen.autoflow.core.dubbing.DubRules
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.DubOriginal
import io.github.alalkipgen.autoflow.core.model.DubTake
import io.github.alalkipgen.autoflow.core.model.DubbingTool
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.core.voice.VoiceTakes
import io.github.alalkipgen.autoflow.ui.i18n.UiNotice
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One Voice row (specs/dubbing.md §4): the line, its slot, its current take (null = none or an old one) and the fit. */
data class DubVoiceRow(val n: Int, val id: String, val startMs: Long, val endMs: Long, val slotMs: Long, val spoken: String,
    val take: DubTake?, val old: Boolean, val fit: DubFit?, val source: String = "")

data class DubVoiceUi(val dir: String? = null, val confirm: DubSpeakRequest? = null, val notice: UiNotice? = null, val ok: Boolean = false)

/**
 * The Voice step (D-2b): voice lock and tone, the original sound (Lower / Mute), per-line takes with their fit, and
 * Speak — the confirm card first (lines without a current take, or the one line of ↻), then the [DubSpeakJob] on the
 * app's runner. Nothing is spoken without Start; while the job is open the voice, tone and original are read-only.
 */
class DubVoiceModel(
    private val session: ProjectSession,
    private val speak: DubSpeakJob?,
    private val runner: AiJobRunner?,
    private val scope: CoroutineScope,
    private val config: ToolsConfig = ToolsConfig(),
    private val lexicon: () -> Lexicon = { Lexicon.EMPTY },
    /** Burmese-capable TTS models (`config/models.json`), first = default. */
    val models: List<String> = emptyList(),
    /** Edge voices to offer next to the Gemini ones (D-86); null = none (not set up, or flag `labs.edgeVoices` off). */
    val edgeVoices: () -> io.github.alalkipgen.autoflow.ai.config.EdgeTtsConfig? = { null },
) {
    private val _state = MutableStateFlow(DubVoiceUi())

    /** Whole minutes Speak of [n] lines with [lock] takes at the job's pace (Edge faster than Gemini, F-3). */
    fun minutes(n: Int, lock: io.github.alalkipgen.autoflow.core.recap.VoiceLock?): Int =
        io.github.alalkipgen.autoflow.ui.voice.EdgeVoices.minutes(n, speak?.rpm(lock)
            ?: io.github.alalkipgen.autoflow.ui.voice.EdgeVoices.rpm(lock, io.github.alalkipgen.autoflow.ai.config.EdgeTtsConfig().rpm))
    val state: StateFlow<DubVoiceUi> = _state.asStateFlow()
    val jobs: StateFlow<List<AiJob>> = runner?.jobs ?: MutableStateFlow(emptyList())

    fun jobOf(dir: String?, list: List<AiJob>): AiJob? = list.lastOrNull { it.kind == AiJobKind.DUB_TTS && it.project == dir && it.state in OPEN }

    fun rows(open: OpenProject?, doc: SubtitleDoc): List<DubVoiceRow> {
        val tool = open?.project?.dubbing
        val slots = DubRules.slots(doc, open?.project?.subtitles?.video?.durationMs ?: 0, config.dubbing.minGapMs)
        val lex = lexicon()
        return doc.cues.mapIndexed { i, c ->
            val take = DubRules.currentTake(tool, c, lex)
            DubVoiceRow(i + 1, c.id, c.startMs, c.endMs, slots[i], DubRules.sayText(c, lex), take, take == null && tool?.takes?.containsKey(c.id) == true,
                take?.let { DubFit.of(it.ms, c.endMs - c.startMs, slots[i], config.dubbing.maxVoiceSpeed) }, c.text)
        }
    }

    /** A model or voice chip: saved now; lines spoken with another voice lose their current take (kept as files). */
    fun setLock(lock: VoiceLock) = change { it.copy(lock = lock) }

    /** Tone chip; the chosen one again clears it. */
    fun setTone(tone: String?) = change { it.copy(tone = tone?.takeIf { t -> t in VoiceTakes.TONES && t != it.tone }) }

    /** Original sound under the dub (D-83): Lower keeps `dubbing.originalVolume`, Mute is 0. */
    /** The original-sound volume that Lower sets (the Lower segment shows it while Mute is on, not 0 %). */
    val lowerVolume: Double get() = config.dubbing.originalVolume

    fun setOriginal(mode: String) = change {
        it.copy(original = if (mode == DubOriginal.MUTE) DubOriginal(DubOriginal.MUTE, 0.0) else DubOriginal(DubOriginal.LOWER, config.dubbing.originalVolume))
    }

    fun defaultLock(): VoiceLock? = models.firstOrNull()?.let { VoiceLock("gemini", it, "Charon", "my") }

    /** Speak n lines, or ↻ for [only]: shows the confirm card; nothing is sent yet. */
    fun ask(only: Set<String>? = null): Job? {
        val open = session.open ?: return null
        val t = speak?.takeIf { it.available && runner != null } ?: return say(open.dir, UiNotice(UiText.TTS_UNAVAILABLE))
        if (jobOf(open.dir, jobs.value) != null) return say(open.dir, UiNotice(UiText.JOB_ALREADY_RUNNING))
        if (open.project.dubbing?.lock == null) return say(open.dir, UiNotice(UiText.VA_NO_VOICE))
        val request = t.plan(open.dir, only) ?: return say(open.dir, UiNotice(UiText.DUB_LOCK_NOT_APPROVED))
        if (request.lines.isEmpty()) return say(open.dir, UiNotice(UiText.DUB_ALL_VOICED), ok = true)
        _state.value = DubVoiceUi(open.dir, confirm = request)
        return null
    }

    fun dismiss() { _state.value = DubVoiceUi(_state.value.dir) }

    fun start(): Job? {
        val c = _state.value.confirm ?: return null
        val dir = _state.value.dir ?: return null
        val t = speak ?: return null
        val r = runner ?: return null
        _state.value = DubVoiceUi(dir)
        return scope.launch { follow(r, dir, t.enqueue(r, dir, c)) }
    }

    /** Speaks only the lines not saved yet. */
    fun resume(job: AiJob): Job? {
        val r = runner ?: return null
        _state.value = DubVoiceUi(job.project)
        return scope.launch { follow(r, job.project, r.enqueue(job)) }
    }

    /** Stops the job; lines already spoken keep their take. */
    fun cancel(job: AiJob): Job? {
        val r = runner ?: return null
        return scope.launch { r.cancel(job.id); say(job.project, UiNotice(UiText.DUB_SPEAK_CANCELLED)) }
    }

    /** The file of take [take] of the open project, for ▶. */
    /** A take's loudness for the desktop waveform card (Group 4 H): null when its file is not plain 16-bit PCM. */
    fun takePeaks(take: ArtifactId): List<Float>? = session.artifacts()?.readBytes(take)?.getOrNull()?.let { wavePeaks(it) }

    fun takePath(take: ArtifactId): String? = session.artifacts()?.get(take)?.getOrNull()?.let {
        joinPath(joinPath(session.open?.dir ?: return null, ArtifactStore.ARTIFACTS_DIR), it.file)
    }

    private fun change(edit: (DubbingTool) -> DubbingTool) {
        val open = session.open ?: return
        if (jobOf(open.dir, jobs.value) != null) return
        session.update(open.dir, open.project.id) { p -> p.copy(dubbing = edit(p.dubbing ?: DubbingTool())) }
    }

    private suspend fun follow(r: AiJobRunner, dir: String, id: String) {
        val end = r.jobs.first { list -> list.firstOrNull { it.id == id }?.state?.let { it !in ACTIVE } ?: true }.firstOrNull { it.id == id }
        when (end?.state) {
            AiJobState.DONE -> say(dir, UiNotice(UiText.DUB_SPEAK_DONE, "n" to (end.initialItemCount ?: 0)), ok = true)
            null, AiJobState.CANCELLED -> say(dir, UiNotice(UiText.DUB_SPEAK_CANCELLED))
            else -> Unit // PAUSED / FAILED: the job card says why and offers Resume
        }
    }

    private fun say(dir: String, notice: UiNotice, ok: Boolean = false): Job? { _state.value = DubVoiceUi(dir, notice = notice, ok = ok); return null }

    companion object {
        private val ACTIVE = setOf(AiJobState.QUEUED, AiJobState.RUNNING)
        private val OPEN = ACTIVE + setOf(AiJobState.PAUSED, AiJobState.FAILED)
    }
}
