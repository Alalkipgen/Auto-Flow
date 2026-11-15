package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.ai.config.ModelConfig
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.recap.BlockTake
import io.github.alalkipgen.autoflow.core.recap.RecapStory
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.core.recap.TakeSource
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession

/** Voice identity is independent of spoken-text staleness; recording/import never becomes "Different voice". */
enum class TakeVoiceStatus { CURRENT, DIFFERENT, UNKNOWN, NOT_TTS }

fun BlockTake.voiceStatus(lock: VoiceLock?): TakeVoiceStatus = when {
    source != TakeSource.TTS -> TakeVoiceStatus.NOT_TTS
    model.isNullOrBlank() || provider.isNullOrBlank() || voice.isNullOrBlank() -> TakeVoiceStatus.UNKNOWN
    lock != null && (provider != lock.provider || model != lock.model || voice != lock.voice) -> TakeVoiceStatus.DIFFERENT
    else -> TakeVoiceStatus.CURRENT
}

/** The sheet is bound to this project revision, never whichever project happens to be open on confirmation. */
data class VoiceChangeDraft(val dir: String, val project: Project, val story: RecapStory?)

/** Small UI action boundary for D-35 / studio-v2 §3; no synthesis or artifact rewriting happens here. */
class RecapVoiceSettings(
    private val session: ProjectSession,
    config: ModelConfig?,
    private val jobs: AiJobRunner?,
    private val changed: () -> Unit,
) {
    val models: List<String> = config?.tts?.chainFor("my").orEmpty()

    fun begin(): VoiceChangeDraft? = session.open?.takeIf { it.project.recap != null }
        ?.let { VoiceChangeDraft(it.dir, it.project, it.project.recap?.story?.let { id ->
            (session.readJson(id, RecapStory.serializer()) as? CoreResult.Ok)?.value
        }) }

    fun affected(recap: RecapV2, story: RecapStory?, next: VoiceLock): List<Int> =
        story?.blocks.orEmpty().mapIndexedNotNull { i, b ->
            recap.takeFor(b.id)?.takeIf {
                it.source == TakeSource.TTS && it.voiceStatus(next) in setOf(TakeVoiceStatus.DIFFERENT, TakeVoiceStatus.UNKNOWN)
            }?.let { i }
        }

    fun regenerationCount(draft: VoiceChangeDraft, next: VoiceLock, lexicon: io.github.alalkipgen.autoflow.core.recap.Lexicon): Int =
        draft.story?.blocks.orEmpty().count { block ->
            val take = draft.project.recap?.takeFor(block.id)
            io.github.alalkipgen.autoflow.core.recap.SpokenText.of(block, lexicon).isNotBlank() &&
                (take == null || io.github.alalkipgen.autoflow.core.recap.SpokenText.isStale(take, block, lexicon) ||
                    take.voiceStatus(next) in setOf(TakeVoiceStatus.DIFFERENT, TakeVoiceStatus.UNKNOWN))
        }

    /** null = committed; a reason means no project bytes were changed. Old takes and choices are retained. */
    fun apply(draft: VoiceChangeDraft, next: VoiceLock): String? {
        val open = session.open
        if (open?.dir != draft.dir || open.project.id != draft.project.id) return "Project changed — reopen Change voice."
        if (open.project != draft.project) return "Project was edited — reopen Change voice."
        if (next.provider != "gemini" || next.lang != "my" || next.model !in models ||
            next.voice !in RecapModel.TTS_VOICES.map { it.first }) return "Choose a configured Burmese-capable model and voice."
        if (jobs?.jobs?.value.orEmpty().any {
                it.project == draft.dir && it.kind == AiJobKind.TTS_ALL && it.state in setOf(AiJobState.QUEUED, AiJobState.RUNNING)
            }) return "Pause or cancel this project's voice job before changing its voice."
        val recap = draft.project.recap ?: return "Not a Recap project."
        return when (val result = session.save(draft.project.copy(recap = recap.copy(voiceLock = next)))) {
            is CoreResult.Ok -> { changed(); null }
            is CoreResult.Err -> result.error.message
        }
    }
}
