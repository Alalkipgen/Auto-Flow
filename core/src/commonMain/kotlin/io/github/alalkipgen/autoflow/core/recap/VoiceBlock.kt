package io.github.alalkipgen.autoflow.core.recap

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Voice & Audio block context (5B-5, studio-v2.md Voice step).
 * Represents one block's voice actions: Speak, Record (teleprompter), Clean, Clone (Labs).
 * The active tab is stored transiently in the UI; not persisted.
 */
enum class VoiceTab {
    SPEAK, RECORD, CLEAN, CLONE
}

/**
 * Teleprompter recording state (5B-5): the owner sees the current segment text large,
 * the next segment muted. Recording captures audio for one [blockId].
 */
@Serializable
@SerialName("TeleprompterState")
data class TeleprompterState(
    /** Block being recorded. */
    val blockId: String,
    /** 0-based index of the current segment within the script. */
    val segmentIndex: Int = 0,
    /** Whether the teleprompter is currently recording. */
    val recording: Boolean = false,
)

/**
 * "Speak all" request (5B-5): triggers a TTS_ALL job for all blocks in the project.
 * [blocksRemaining] is the item list; each = block id.
 * [estimatedRpm] comes from the voice lock model's RPM limit.
 */
@Serializable
@SerialName("SpeakAllRequest")
data class SpeakAllRequest(
    val projectId: String,
    val blocksRemaining: List<String>,
    val estimatedRpm: Int = 60,
)
