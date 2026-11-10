package io.github.alalkipgen.autoflow.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One storyboard version, stored as an `sb` artifact: the analysis it came from and every shot with its timing and
 * spec. `project.storyboards` lists the versions; switching back re-puts these specs as `sp` artifacts (same bytes,
 * same ids), so a version can always be restored.
 */
@Serializable
@SerialName("StoryboardVersion")
data class StoryboardVersion(
    val schemaVersion: Int = SCHEMA_VERSION,
    val analysis: ArtifactId? = null,
    val shots: List<StoryboardShot>,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
@SerialName("StoryboardShot")
data class StoryboardShot(
    val number: Int,
    val startMs: Long,
    val durationMs: Long,
    val section: Section? = null,
    val spec: ShotSpec,
)
