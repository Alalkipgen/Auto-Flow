package io.github.alalkipgen.autoflow.ui.media

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A project's thumbnail source (DESIGN.md §9.5): Movie Recap = its movie, Subtitles / Dubbing = the source video,
 * Voice & Audio = the picked video (not an audio-only pick). Music Video has none yet, so its rows show the
 * placeholder. The revision is the recorded size + duration; the duration is the recorded one (badge), never a guess.
 */
fun Project.thumbSource(dir: String): MediaThumbnails.Source? {
    recap?.movie?.let { return source(dir, it.file, it.sizeBytes, it.durationMs) }
    val video: SourceVideo = subtitles?.video ?: voice?.source?.takeIf { it.width > 0 } ?: return null
    return source(dir, video.file, video.sizeBytes, video.durationMs)
}

private fun source(dir: String, file: String, size: Long, durationMs: Long) =
    MediaThumbnails.Source(dir, file, "$size:$durationMs", durationMs.takeIf { it > 0 })

/** Reads `project.json` (never writes it) off the main thread; null when the project cannot be read. */
suspend fun ProjectStore.thumbSource(dir: String): MediaThumbnails.Source? = withContext(Dispatchers.Default) {
    (open(dir) as? CoreResult.Ok)?.value?.thumbSource(dir)
}
