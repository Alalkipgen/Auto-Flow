package io.github.alalkipgen.autoflow.core.model

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Root of `project.json` (specs/project-format.md §2). */
@Serializable
@SerialName("Project")
data class Project(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String,
    val name: String,
    val mode: Mode,
    val createdAt: Instant,
    val updatedAt: Instant,
    val settings: ProjectSettings = ProjectSettings(),
    val globalStyle: GlobalStyle = GlobalStyle(),
    val characters: List<Character> = emptyList(),
    val analysis: ArtifactId? = null,
    /** Line timings of the song (`tx`, [io.github.alalkipgen.autoflow.core.lyrics.LyricTimings]; D-30). */
    val lyricTiming: ArtifactId? = null,
    val storyboards: List<ArtifactId> = emptyList(),
    val currentStoryboard: ArtifactId? = null,
    val shots: List<Shot> = emptyList(),
    /** RenderGraph (specs/render-graph.md), typed by the PLAN 1D RenderGraph task; kept opaque so nothing is lost. */
    val timeline: JsonElement? = null,
    val flowCredits: FlowCredits = FlowCredits(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/**
 * [textModel] and [imageModel] are references into `config/models.json` (e.g. `ref:models.text.default`), never
 * literal model names (D-10).
 */
@Serializable
@SerialName("ProjectSettings")
data class ProjectSettings(
    val ratio: Ratio = Ratio.R9_16,
    val clipMs: Long = 8_000,
    val durationMs: Long = 0,
    val storyboardMode: StoryboardMode = StoryboardMode.NARRATIVE,
    val songStyle: String = "",
    val videoStyle: String = "",
    val mood: String = "",
    val textModel: String = "ref:models.text.default",
    val imageModel: String = "ref:models.image.default",
    /** Storyboard "Snap cuts to beat" (Q-12, D-32): cuts move onto the nearest beat within ±250 ms. */
    val snapToBeat: Boolean = false,
)

/** Model-bound visual style. [hash] (`gs_…`) is derived from the other fields when the project is saved. */
@Serializable
@SerialName("GlobalStyle")
data class GlobalStyle(
    val visual: String = "",
    val palette: String = "",
    val lighting: String = "",
    val camera: String = "",
    val negative: String = "",
    val hash: String = "",
)

/** [token] is referenced as `{{TOKEN}}` in `*En` fields; [descriptionEn] replaces it at compile time. */
@Serializable
@SerialName("Character")
data class Character(
    val token: String,
    val nameMy: String = "",
    val descriptionEn: String = "",
    val refImages: List<ArtifactId> = emptyList(),
)

@Serializable
@SerialName("Shot")
data class Shot(
    val number: Int,
    val startMs: Long,
    val durationMs: Long,
    val section: Section? = null,
    val lyricsMy: String = "",
    val spec: ArtifactId? = null,
    val promptIr: ArtifactId? = null,
    val refImages: List<ArtifactId> = emptyList(),
    val chosenRef: ArtifactId? = null,
    val videos: List<ArtifactId> = emptyList(),
    val acceptedVideo: ArtifactId? = null,
    val approvals: Approvals = Approvals(),
    val handoff: Handoff = Handoff(),
)

/** Per-shot approval flags (specs/workflow-and-approval.md "Approval flags"). */
@Serializable
@SerialName("Approvals")
data class Approvals(
    val storyboard: Boolean = false,
    val prompt: Boolean = false,
    val ref: Boolean = false,
    val video: Boolean = false,
)

@Serializable
@SerialName("Handoff")
data class Handoff(
    val state: HandoffState = HandoffState.NONE,
    val sentAt: Instant? = null,
    val pass: Pass = Pass.DRAFT,
    val note: String? = null,
)

/** Flow credits are logged by hand; costs are never computed in code (D-10). */
@Serializable
@SerialName("FlowCredits")
data class FlowCredits(
    val manualLog: List<CreditEntry> = emptyList(),
)

@Serializable
@SerialName("CreditEntry")
data class CreditEntry(
    /** ISO calendar date, `YYYY-MM-DD`. */
    val date: String,
    val credits: Int,
    val shot: Int? = null,
)
