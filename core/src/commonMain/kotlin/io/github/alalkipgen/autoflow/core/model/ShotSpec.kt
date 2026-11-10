package io.github.alalkipgen.autoflow.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One storyboard shot (specs/prompt-compiler.md §1, Stage B). `*My` fields are for humans and `*En` fields go to
 * models; they are never translated into each other (D-12). Stored as an `sp` artifact.
 */
@Serializable
@SerialName("ShotSpec")
data class ShotSpec(
    val schemaVersion: Int = SCHEMA_VERSION,
    val shot: Int,
    val sceneMy: String = "",
    val lyricsMy: String = "",
    val subjectEn: String = "",
    val actionEn: String = "",
    val settingEn: String = "",
    val cameraEn: String = "",
    val moodEn: String = "",
    val lightingEn: String = "",
    /** Character tokens, e.g. `HERO`. */
    val characters: List<String> = emptyList(),
    val motifEn: String = "",
    val transitionEn: String = "",
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** Stage A result (specs/prompt-compiler.md §1). Stored as an `an` artifact. */
@Serializable
@SerialName("SongAnalysis")
data class SongAnalysis(
    val schemaVersion: Int = SCHEMA_VERSION,
    val themeMy: String = "",
    val arcMy: String = "",
    val sections: List<AnalysisSection> = emptyList(),
    val motifsEn: List<String> = emptyList(),
    val paletteEn: String = "",
    val charactersMy: List<String> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
@SerialName("AnalysisSection")
data class AnalysisSection(
    val type: Section,
    val startMs: Long,
    val endMs: Long,
    val lyricsMy: String = "",
)
