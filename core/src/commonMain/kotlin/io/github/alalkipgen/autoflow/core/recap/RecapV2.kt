package io.github.alalkipgen.autoflow.core.recap

import io.github.alalkipgen.autoflow.core.hash.Sha256
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * Recap data v2 (docs/specs/studio-v2.md §2, D-36). Read and written by Studio v2 screens; v1 ([Recap], [RecapScript])
 * stays readable and is converted by [RecapMigration].
 */

/** The story, an `rs` v2 artifact: each story change writes a new one. */
@Serializable
@SerialName("RecapStory")
data class RecapStory(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** [SOURCE_GEMINI], [SOURCE_MANUAL] or [SOURCE_QUICK]. */
    val source: String,
    val style: RecapStyle = RecapStyle.PLAIN,
    val register: RecapRegister = RecapRegister.SPOKEN,
    val blocks: List<RecapBlock>,
) {
    val durationMs: Long get() = blocks.sumOf { it.durationMs }

    companion object {
        const val SCHEMA_VERSION = 2
        const val SOURCE_GEMINI = "gemini"
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_QUICK = "quick"
    }
}

/** Spoken (everyday) or written (literary) Burmese for the narration (5B-7). */
@Serializable
@SerialName("RecapRegister")
enum class RecapRegister {
    @SerialName("SPOKEN") SPOKEN,
    @SerialName("WRITTEN") WRITTEN,
}

/** Hook style for the first narration block (5B-7). */
@Serializable
@SerialName("RecapHookStyle")
enum class RecapHookStyle {
    /** Open with a question that teases the main conflict. */
    @SerialName("QUESTION") QUESTION,
    /** Open with a striking quote or line of dialogue from the movie. */
    @SerialName("QUOTE") QUOTE,
    /** Open by dropping straight into the action with no setup. */
    @SerialName("ACTION") ACTION,
    /** No specific hook instruction; let the AI decide. */
    @SerialName("DEFAULT") DEFAULT,
}

/** One narration block: movie [clips] played in order; captions show [textMy], TTS speaks [speakMy] when set. */
@Serializable
@SerialName("RecapBlock")
data class RecapBlock(
    val id: String,
    val clips: List<RecapClip>,
    val textMy: String = "",
    val speakMy: String? = null,
) {
    val durationMs: Long get() = clips.sumOf { it.durationMs }
}

/** A movie range `[inMs, outMs)`. */
@Serializable
@SerialName("RecapClip")
data class RecapClip(val inMs: Long, val outMs: Long) {
    val durationMs: Long get() = outMs - inMs
}

@Serializable
@SerialName("RecapSources")
data class RecapSources(
    val subtitles: String? = null,
    val subtitleTrack: Int? = null,
    val summary: String? = null,
    val transcript: ArtifactId? = null,
)

@Serializable
@SerialName("TakeSource")
enum class TakeSource {
    @SerialName("TTS") TTS,
    @SerialName("RECORDED") RECORDED,
    @SerialName("IMPORTED") IMPORTED,
    @SerialName("POLISHED") POLISHED,
}

/**
 * A narration take of block [block]. Stale when [spokenHash] ≠ [SpokenText.hash] of the block; provider / model /
 * voice are null for takes made before the voice lock (voice unknown).
 */
@Serializable
@SerialName("BlockTake")
data class BlockTake(
    val block: String,
    val audio: ArtifactId,
    val source: TakeSource,
    val provider: String? = null,
    val model: String? = null,
    val voice: String? = null,
    val durationMs: Long,
    val spokenHash: String,
    val createdAt: Instant? = null,
)

@Serializable
@SerialName("RecapAudio")
data class RecapAudio(
    val movieVolume: Double = Recap.MOVIE_VOLUME_LOW,
    val bgm: ArtifactId? = null,
    val bgmVolume: Double = 0.18,
    val duck: Boolean = true,
    val polish: Boolean = true,
    val trimSilence: Boolean = false,
    val loudnessLufs: Double = -14.0,
    /** Off = the mix is left as it is (no `loudnorm`). */
    val loudness: Boolean = true,
)

@Serializable
@SerialName("RecapCaptions")
data class RecapCaptions(
    val burn: Boolean = true,
    val style: String = "default",
    /** x, y, w, h as 0–1 of the frame. */
    val rect: List<Double> = listOf(0.05, 0.78, 0.9, 0.17),
    /** Block id → chunk boundaries (ms into the take) the owner dragged; absent = automatic. */
    val chunkEdits: Map<String, List<Long>> = emptyMap(),
)

/** `project.recap` v2 (spec §2). [layers] and [quick] stay opaque until their tasks (5A-4, 5B-11) type them. */
@Serializable
@SerialName("RecapV2")
data class RecapV2(
    val rightsConfirmed: Boolean = false,
    val movie: RecapMovie? = null,
    val targetMs: Long = Recap.DEFAULT_TARGET_MS,
    val scenes: List<RecapScene> = emptyList(),
    val sceneThreshold: Int = Scenes.THRESHOLD_NORMAL,
    val sources: RecapSources = RecapSources(),
    val story: ArtifactId? = null,
    val storyApproved: Boolean = false,
    val voiceLock: VoiceLock? = null,
    val takes: List<BlockTake> = emptyList(),
    /** Block id → chosen take's audio. */
    val chosenTakes: Map<String, ArtifactId> = emptyMap(),
    /** Block id → Smart Fit choice (5B-6). */
    val fit: Map<String, String> = emptyMap(),
    /** Composited layers (spec §4, D-37). */
    val layers: List<Layer> = emptyList(),
    val audio: RecapAudio = RecapAudio(),
    val captions: RecapCaptions = RecapCaptions(),
    val looks: List<Look> = emptyList(),
    val quality: ExportQuality = ExportQuality.AUTO,
    val quick: JsonElement? = null,
    /** Picture (5B-2): blur boxes over the movie, the space around it. */
    val visual: RecapVisual = RecapVisual(),
    /** Caption appearance (colour, size, stroke). Stored here for direct access; `captions.burn` controls whether captions are burned. */
    val captionStyle: CaptionStyle = CaptionStyle(),
    /** Publish Kit text (docs/specs/studio-v2.md §9, 5B-10). */
    val publishKit: PublishKit? = null,
    /** Hook style for the first narration block (5B-7). */
    val hookStyle: RecapHookStyle = RecapHookStyle.DEFAULT,
    /** Credit / source line appended to the last block (5B-7). Empty = no credit. */
    val creditLine: String = "",
) {
    /** Convenience: whether captions are burned (delegates to [captions]). */
    val burnCaptions: Boolean get() = captions.burn
    /** Convenience: movie audio volume under narration (delegates to [audio]). */
    val movieVolume: Double get() = audio.movieVolume
    /** Alias for [story] — used by code that hasn't been updated from v1 yet. */
    val script: ArtifactId? get() = story
    /** Picked scenes (scenes with picked=true), for backward-compat with v1 UI code. */
    val picked: List<RecapScene> get() = scenes.filter { it.picked }
    /** Total ms of picked scenes. */
    val pickedMs: Long get() = picked.sumOf { it.durationMs }
    /** Whether the story is approved; alias for [storyApproved]. */
    val scriptApproved: Boolean get() = storyApproved
    /** Subtitle path from sources; backward-compat with v1 UI code. */
    val subtitles: String? get() = sources.subtitles
    val subtitleTrack: Int? get() = sources.subtitleTrack
    /** Voice source: TTS when voiceLock is set, else RECORDED. */
    val voiceSource: VoiceSource get() = if (voiceLock != null) VoiceSource.TTS else VoiceSource.RECORDED
    /** TTS voice from voiceLock, or the default. */
    val ttsVoice: String get() = voiceLock?.voice ?: Recap.DEFAULT_TTS_VOICE
    /** Script style from the story artifact — not stored in RecapV2 directly; callers set _style in RecapModel. */
    val style: RecapStyle get() = RecapStyle.PLAIN
    /** Script register — not stored in RecapV2 directly. */
    val register: RecapRegister get() = RecapRegister.SPOKEN
    /** Fit choice for segment [index] by blockId or fallback key "b_seg{index}". */
    fun fitChoice(index: Int): FitChoice? {
        val byIndex = fit["b_seg$index"] ?: fit.values.toList().getOrNull(index)
        return byIndex?.let { runCatching { FitChoice.valueOf(it) }.getOrNull() }
    }
    /** The chosen take of [blockId], else its newest. */
    fun takeFor(blockId: String): BlockTake? {
        val mine = takes.filter { it.block == blockId }
        val chosen = chosenTakes[blockId]
        return mine.lastOrNull { it.audio == chosen } ?: mine.lastOrNull()
    }
}

/** Block ids for migrated stories: `b_` + 8 hex of SHA-256("<index>:<startMs>-<endMs>"). */
fun migratedBlockId(index: Int, startMs: Long, endMs: Long): String =
    "b_" + Sha256.hex("$index:$startMs-$endMs".encodeToByteArray()).take(8)
