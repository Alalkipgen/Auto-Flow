package io.github.alalkipgen.autoflow.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Project mode (docs/DESIGN.md §1). */
@Serializable
@SerialName("Mode")
enum class Mode {
    @SerialName("MUSIC_VIDEO") MUSIC_VIDEO,
    @SerialName("MOVIE_RECAP") MOVIE_RECAP,
}

/** Output aspect ratio. Serialized as the human form, e.g. `"9:16"`. */
@Serializable
@SerialName("Ratio")
enum class Ratio(val width: Int, val height: Int) {
    @SerialName("16:9") R16_9(16, 9),
    @SerialName("9:16") R9_16(9, 16),
    @SerialName("1:1") R1_1(1, 1),
    @SerialName("4:5") R4_5(4, 5),
    @SerialName("21:9") R21_9(21, 9),
    ;

    /** `"9:16"`: the form used in prompts and project.json. */
    val label: String get() = "$width:$height"
}

@Serializable
@SerialName("StoryboardMode")
enum class StoryboardMode {
    @SerialName("NARRATIVE") NARRATIVE,
    @SerialName("PERFORMANCE") PERFORMANCE,
    @SerialName("ABSTRACT") ABSTRACT,
    @SerialName("MIXED") MIXED,
}

/** Song section. Same set as the S1 structured-output schema. */
@Serializable
@SerialName("Section")
enum class Section {
    @SerialName("INTRO") INTRO,
    @SerialName("VERSE") VERSE,
    @SerialName("PRECHORUS") PRECHORUS,
    @SerialName("CHORUS") CHORUS,
    @SerialName("BRIDGE") BRIDGE,
    @SerialName("OUTRO") OUTRO,
}

/** Per-shot Flow handoff state (specs/workflow-and-approval.md "Handoff state"). */
@Serializable
@SerialName("HandoffState")
enum class HandoffState {
    @SerialName("NONE") NONE,
    @SerialName("SENT_TO_FLOW") SENT_TO_FLOW,
    @SerialName("IMPORTED") IMPORTED,
    @SerialName("ACCEPTED") ACCEPTED,
}

/** Label only: Draft = Veo Fast, Final = Veo Quality (specs/workflow-and-approval.md "Pass"). */
@Serializable
@SerialName("Pass")
enum class Pass {
    @SerialName("DRAFT") DRAFT,
    @SerialName("FINAL") FINAL,
}
