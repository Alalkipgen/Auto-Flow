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
) {
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
