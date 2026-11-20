package io.github.alalkipgen.autoflow.ui.jobs

import io.github.alalkipgen.autoflow.core.recap.AiItemProcessor
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.UnsupportedAiJob
import io.github.alalkipgen.autoflow.ui.recap.RecapModel
import io.github.alalkipgen.autoflow.ui.subtitles.VideoTranscriber

/**
 * The app's [AiItemProcessor] (audit F11), the same on desktop and Android: a TTS_ALL item is a block id of the job's
 * own project folder (`job.project`), spoken and stored there by [RecapModel.speakJobItem] — never "the open
 * project" or a scene index. Items already voiced in that project are reported done without a request. A TRANSCRIBE
 * item (5B-1) is one request's movie ranges of the job's project ([RecapModel.transcribeJobItem]); ranges already in
 * that project's transcript are done without a request. A MEDIA_TRANSCRIBE item (S-5) is one chunk of a Subtitles
 * project's video ([VideoTranscriber.runItem]); a SUB_TRANSLATE item one batch of its lines (S-6); a TEXT_TTS item one
 * part of a Voice & Audio project's text (V-1e); a DUB_TTS item one line of a Dubbing project (D-2). Other job kinds FAIL with [UnsupportedAiJob]. [recap] is
 * looked up when an item runs (the model and the runner need each
 * other in DI); none → the item fails.
 */
class RecapJobProcessor(
    private val recap: () -> RecapModel?,
    private val video: () -> VideoTranscriber?,
    private val translate: () -> io.github.alalkipgen.autoflow.ui.subtitles.SubtitleTranslateJob? = { null },
    private val speak: () -> io.github.alalkipgen.autoflow.ui.voice.VoiceSpeakJob? = { null },
    private val dub: () -> io.github.alalkipgen.autoflow.ui.dubbing.DubSpeakJob? = { null },
) : AiItemProcessor {
    constructor(recap: () -> RecapModel?) : this(recap, { null })

    override suspend fun process(job: AiJob, item: String) = when (job.kind) {
        AiJobKind.TTS_ALL -> model().speakJobItem(job.project, item)
        AiJobKind.TRANSCRIBE -> model().transcribeJobItem(job.project, item)
        AiJobKind.MEDIA_TRANSCRIBE -> transcriber().runItem(job.project, job.id, item)
        AiJobKind.SUB_TRANSLATE -> (translate() ?: error("Translation is not available here")).runItem(job.project, job.id, item)
        AiJobKind.TEXT_TTS -> (speak() ?: error("Text to voice is not available here")).runItem(job.project, job.id, item)
        AiJobKind.DUB_TTS -> (dub() ?: error("Dub voices are not available here")).runItem(job.project, job.id, item)
        else -> throw UnsupportedAiJob(job.kind)
    }

    override suspend fun alreadyDone(job: AiJob, item: String): Boolean = when (job.kind) {
        AiJobKind.TTS_ALL -> model().isJobItemDone(job.project, item)
        AiJobKind.TRANSCRIBE -> model().isTranscribeItemDone(job.project, item)
        // A saved chunk is skipped by runItem itself, which then joins all chunks when it was the last one.
        else -> false
    }

    override fun hasSavedClip(job: AiJob, item: String): Boolean =
        job.kind == AiJobKind.TTS_ALL && model().hasSavedJobClip(job.project, item)

    private fun model(): RecapModel = recap() ?: error("the Movie Recap screen is not available")
    private fun transcriber(): VideoTranscriber = video() ?: error("Transcription is not available here")
}
