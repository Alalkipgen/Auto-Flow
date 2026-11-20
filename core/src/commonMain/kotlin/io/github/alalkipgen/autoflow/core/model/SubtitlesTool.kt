package io.github.alalkipgen.autoflow.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The copied source video of a Phase 7 tool project (specs/quick-tools.md §3): `RecapMovie` plus [fps]. */
@Serializable
@SerialName("SourceVideo")
data class SourceVideo(
    val file: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Double? = null,
    val hasAudio: Boolean = true,
)

/** `project.subtitles` of a [Mode.SUBTITLES] project (D-77). */
@Serializable
@SerialName("SubtitlesTool")
data class SubtitlesTool(
    val schemaVersion: Int = 1,
    val video: SourceVideo? = null,
    /** The current `st` version (D-79); null until a source is chosen. Saves move only this pointer. */
    val current: ArtifactId? = null,
)
