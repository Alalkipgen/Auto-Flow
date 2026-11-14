package io.github.alalkipgen.autoflow.core.recap

import io.github.alalkipgen.autoflow.core.model.ArtifactId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Movie Recap state in `project.recap` (docs/specs/recap.md §2, D-33). Only `MOVIE_RECAP` projects have one. */
@Serializable
@SerialName("Recap")
data class Recap(
    /** The rights tick of New Movie Recap (spec §1). */
    val rightsConfirmed: Boolean = false,
    val movie: RecapMovie? = null,
    /** The length the owner aims for; a guide for picking scenes. */
    val targetMs: Long = DEFAULT_TARGET_MS,
    val scenes: List<RecapScene> = emptyList(),
    /** `scdet` threshold the scenes were found with. */
    val sceneThreshold: Int = Scenes.THRESHOLD_NORMAL,
    /** `source/subtitles.srt` when imported. */
    val subtitles: String? = null,
    /** Selected subtitle track stream index (ffprobe 0-based, 5B-1). */
    val subtitleTrack: Int? = null,
    /** Transcript artifact id (`tx_…`, schemaVersion 2, 5B-1). */
    val transcript: ArtifactId? = null,
    val style: RecapStyle = RecapStyle.PLAIN,
    /** Spoken (casual) vs Written (formal) Burmese register for TTS (5B-7, D-36). */
    val register: RecapRegister = RecapRegister.SPOKEN,
    /** Current `rs` artifact ([RecapScript]). */
    val script: ArtifactId? = null,
    val scriptApproved: Boolean = false,
    val voiceSource: VoiceSource = VoiceSource.RECORDED,
    val ttsVoice: String = DEFAULT_TTS_VOICE,
    /**
     * Voice lock for TTS consistency (voice lock, D-35): null until the user picks a voice or the
     * first TTS take is recorded. Every subsequent TTS call must use exactly these settings.
     */
    val voiceLock: VoiceLock? = null,
    /** Newest narration per segment. */
    val takes: List<VoiceTake> = emptyList(),
    val looks: List<Look> = emptyList(),
    /** The movie's own sound under the narration: 0 (off) … 1 (full). */
    val movieVolume: Double = MOVIE_VOLUME_LOW,
    val burnCaptions: Boolean = true,
    /** Export size (5B-9, studio-v2 §9). Preview renders stay 540p. */
    val quality: ExportQuality = ExportQuality.AUTO,
    /** Publish Kit text (5B-10, studio-v2 §9): title, description, hashtags, chapters. */
    val publishKit: PublishKit? = null,
    /** Quick Recap wizard state (5B-11, D-47); null = not a Quick Recap. Feature-flagged by `quickRecap`. */
    val quick: QuickRecap? = null,
    /**
     * Smart Fit choices per segment (5A-8, studio-v2.md §8): segment index → [FitChoice].
     * Absent key = default EXTEND. Applied when take + 0.4 s > scene length.
     */
    val fit: Map<Int, FitChoice> = emptyMap(),
    /**
     * Sound (5B-4): voice polish, background music (`bgm` = an `au` artifact), ducking, loudness. Its `movieVolume`
     * is not used in v1 ([movieVolume] is); the migration copies [movieVolume] into it.
     */
    val audio: RecapAudio = RecapAudio(),
) {
    val picked: List<RecapScene> get() = scenes.filter { it.picked }
    val pickedMs: Long get() = picked.sumOf { it.durationMs }

    companion object {
        const val DEFAULT_TARGET_MS = 300_000L
        const val DEFAULT_TTS_VOICE = "Charon"
        const val MOVIE_VOLUME_LOW = 0.2
        val TARGETS_MS = listOf(60_000L, 180_000L, 300_000L, 600_000L)
    }
}

/** The imported movie, `source/movie.<ext>` relative to the project folder (not an artifact: too large to hash). */
@Serializable
@SerialName("RecapMovie")
data class RecapMovie(
    val file: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int = 0,
    val height: Int = 0,
    val hasAudio: Boolean = true,
)

/** A scene in movie time, `[startMs, endMs)`. */
@Serializable
@SerialName("RecapScene")
data class RecapScene(
    val startMs: Long,
    val endMs: Long,
    val picked: Boolean = false,
) {
    val durationMs: Long get() = endMs - startMs
}

@Serializable
@SerialName("RecapStyle")
enum class RecapStyle {
    /** Neutral storytelling. */
    @SerialName("PLAIN") PLAIN,

    /** Adds short reactions and opinions, recap-channel style. */
    @SerialName("COMMENTARY") COMMENTARY,
}

/** Where the narration comes from (D-34). [REPLICATED] is hidden unless models.json says it is available. */
@Serializable
@SerialName("VoiceSource")
enum class VoiceSource {
    @SerialName("RECORDED") RECORDED,
    @SerialName("TTS") TTS,
    @SerialName("REPLICATED") REPLICATED,
}

/** Picture changes for the movie clips (spec §6); any combination. */
@Serializable
@SerialName("Look")
enum class Look {
    @SerialName("MIRROR") MIRROR,
    @SerialName("ZOOM") ZOOM,
    @SerialName("FILL") FILL,
    @SerialName("WARM") WARM,
}

/**
 * The narration of segment [segment] (0-based): an `au` artifact.
 * Stale when [textHash] ≠ the segment's text hash.
 * [model] and [voice] are null for takes recorded before the voice lock (D-35); Export pre-flight warns about these.
 */
@Serializable
@SerialName("VoiceTake")
data class VoiceTake(
    val segment: Int,
    val audio: ArtifactId,
    val source: VoiceSource,
    val durationMs: Long,
    val textHash: String,
    /** Gemini model id used for this take; null = recorded by user or pre-lock TTS take (voice unknown). */
    val model: String? = null,
    /** Gemini voice name used for this take; null = recorded or pre-lock (voice unknown). */
    val voice: String? = null,
    /** Provider id: "gemini", "recorded", etc.; null = pre-lock take. */
    val provider: String? = null,
) {
    /** True when this take came from TTS and the voice is known and matches [lock]. */
    fun matchesLock(lock: VoiceLock): Boolean =
        provider == lock.provider && model == lock.model && voice == lock.voice
}


/**
 * Voice lock for one Recap project (voice lock, D-35): every TTS call uses exactly this
 * provider / model / voice / lang — no silent fallback to another model or voice.
 * Set on the Voice step (or by the first TTS take if still empty).
 */
@Serializable
@SerialName("VoiceLock")
data class VoiceLock(
    val provider: String,
    val model: String,
    val voice: String,
    val lang: String,
)

/** The narration script, an `rs` artifact (spec §4): one segment per picked scene, in movie order. */
@Serializable
@SerialName("RecapScript")
data class RecapScript(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** `gemini` or `manual`. */
    val source: String,
    val style: RecapStyle = RecapStyle.PLAIN,
    val segments: List<RecapSegment>,
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val SOURCE_GEMINI = "gemini"
        const val SOURCE_MANUAL = "manual"
    }
}

@Serializable
@SerialName("RecapSegment")
data class RecapSegment(
    val startMs: Long,
    val endMs: Long,
    val textMy: String = "",
) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * Export quality (docs/specs/studio-v2.md §9): [AUTO] = the movie's short side, at most 1080 (1080 when unknown);
 * [P1440] only when picked. The value is the output's short side in px.
 */
@Serializable
@SerialName("ExportQuality")
enum class ExportQuality(val label: String) {
    @SerialName("AUTO") AUTO("Auto"),
    @SerialName("720") P720("720p"),
    @SerialName("1080") P1080("1080p"),
    @SerialName("1440") P1440("1440p"),
    ;

    /** Short side of the export in px (even, for yuv420p). */
    fun shortSidePx(movie: RecapMovie?): Int = when (this) {
        P720 -> 720
        P1080 -> 1080
        P1440 -> 1440
        AUTO -> {
            val movieShort = movie?.let { minOf(it.width, it.height) } ?: 0
            if (movieShort <= 0) AUTO_MAX else movieShort.coerceIn(AUTO_MIN, AUTO_MAX).let { it - it % 2 }
        }
    }

    companion object {
        const val AUTO_MAX = 1080
        /** Below this an AUTO export is still made at 360p, so captions stay readable. */
        const val AUTO_MIN = 360
    }
}
