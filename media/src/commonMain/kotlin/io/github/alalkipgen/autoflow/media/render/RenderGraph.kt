package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.Layer
import io.github.alalkipgen.autoflow.core.recap.Look
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What one render produces (specs/render-graph.md): an ordered clip strip, an optional music track, text overlays
 * and the output format. Written next to every render as `exports/<name>.rendergraph.json` (artifact kind `rg`)
 * and kept as `project.timeline`. The same graph compiles to the same ffmpeg command on desktop and Android (D-23).
 */
@Serializable
@SerialName("RenderGraph")
data class RenderGraph(
    val schemaVersion: Int = SCHEMA_VERSION,
    val clips: List<Clip>,
    val audio: AudioTrack? = null,
    val overlays: List<Overlay> = emptyList(),
    val output: Output,
    /** Narration takes over the clips' own sound (Movie Recap, D-33); not used together with [audio]. */
    val voice: VoiceTrack? = null,
    /** Picture changes for every [Clip.source] clip (Movie Recap looks, docs/specs/recap.md §6). */
    val looks: List<Look> = emptyList(),
    /** Composited layers (spec §4, D-37): BLUR, IMAGE, TEXT, BORDER, BACKGROUND drawn in list order. */
    val layers: List<Layer> = emptyList(),
) {
    /** Output length: the clip strip minus crossfade overlaps. */
    val durationMs: Long
        get() = clips.withIndex().sumOf { (i, c) -> c.durationMs - if (i > 0) c.crossfadeMs else 0 }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/**
 * One shot on the strip. [artifact] null = placeholder card ("#NN — pending", [label]) for a shot without an
 * accepted video, shown for [trimOutMs] − [trimInMs]. [transition] is the change from the previous clip into this one.
 * [source] (used when [artifact] is null) is a project file such as the recap movie; past its end the last frame holds.
 */
@Serializable
@SerialName("Clip")
data class Clip(
    val artifact: ArtifactId?,
    val trimInMs: Long = 0,
    val trimOutMs: Long,
    val transition: Transition = Transition.CUT,
    val transitionMs: Int = 0,
    val label: String = "",
    val source: String? = null,
) {
    val durationMs: Long get() = trimOutMs - trimInMs
    val crossfadeMs: Long get() = if (transition == Transition.CROSSFADE) transitionMs.toLong() else 0
}

@Serializable
@SerialName("Transition")
enum class Transition {
    @SerialName("CUT") CUT,
    @SerialName("CROSSFADE") CROSSFADE,
}

/**
 * The song. [offsetMs] > 0 starts it that late in the video, < 0 skips that much of the song. With [muteClips] the
 * clips' own sound is dropped; otherwise it plays under the song at [clipsVolume].
 */
@Serializable
@SerialName("AudioTrack")
data class AudioTrack(
    val file: String,
    val offsetMs: Long = 0,
    val fadeInMs: Int = 0,
    val fadeOutMs: Int = 0,
    val muteClips: Boolean = true,
    val clipsVolume: Double = 0.3,
)

/**
 * Narration: each cue's file starts at [VoiceCue.startMs] of the output; the clips' own sound plays under it at
 * [clipsVolume] (0 = off).
 */
@Serializable
@SerialName("VoiceTrack")
data class VoiceTrack(val cues: List<VoiceCue>, val clipsVolume: Double = 0.2)

/** [file] is relative to the project folder (an `au` artifact) or absolute. */
@Serializable
@SerialName("VoiceCue")
data class VoiceCue(val file: String, val startMs: Long)

/**
 * A text overlay (lyrics, title card), drawn by libass from the fonts dir (S4). [font] is the family name inside a
 * font file of that dir, [sizePx] the em size in output pixels (as in the Compose preview), [color] `#RRGGBB` or
 * `#AARRGGBB`. Long Burmese lines are broken by the caller with `\n` (no auto-wrap, specs/render-graph.md).
 */
@Serializable
@SerialName("Overlay")
data class Overlay(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val font: String,
    val sizePx: Int,
    val color: String = "#FFFFFF",
    val position: Pos = Pos.BOTTOM,
    val anim: Anim = Anim.NONE,
)

@Serializable
@SerialName("Pos")
enum class Pos {
    @SerialName("TOP") TOP,
    @SerialName("CENTER") CENTER,
    @SerialName("BOTTOM") BOTTOM,
}

@Serializable
@SerialName("Anim")
enum class Anim {
    @SerialName("NONE") NONE,
    @SerialName("FADE") FADE,
}

/** Encoder settings of a [RenderPreset]: Preview = fast proxy, Export = final quality (specs/render-graph.md). */
@Serializable
@SerialName("Quality")
enum class Quality(val x264Preset: String, val crf: Int, val audioBitrate: String) {
    @SerialName("PREVIEW") PREVIEW("ultrafast", 23, "128k"),
    @SerialName("EXPORT") EXPORT("slow", 18, "192k"),
}

@Serializable
@SerialName("Output")
data class Output(
    val ratio: Ratio,
    val width: Int,
    val height: Int,
    val fps: Int,
    val quality: Quality,
    val preset: String,
)
