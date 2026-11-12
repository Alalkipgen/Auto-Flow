package io.github.alalkipgen.autoflow.platform

/**
 * Microphone recording for Movie Recap narration (D-34, docs/specs/recap.md §5). One recording at a time:
 * [start] writes to a file (Android asks for the microphone permission the first time), [stop] finishes it and
 * returns its length in ms. [extension] is the file type written (`m4a` on Android, `wav` on desktop).
 */
interface VoiceRecorder {
    val extension: String

    suspend fun start(path: String): PlatformResult<Unit>

    suspend fun stop(): PlatformResult<Long>

    /** Stops and deletes the file being written; nothing when not recording. */
    fun cancel()
}
