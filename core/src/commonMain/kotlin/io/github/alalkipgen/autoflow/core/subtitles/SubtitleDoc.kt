package io.github.alalkipgen.autoflow.core.subtitles

import io.github.alalkipgen.autoflow.core.hash.Sha256
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One immutable version of a subtitle document — artifact kind `st` (D-79, specs/quick-tools.md §4). Cue ids never
 * change; a save writes a new version and only `project.subtitles.current` moves.
 */
@Serializable
@SerialName("SubtitleDoc")
data class SubtitleDoc(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** `track:<stream index>` · `srt` · `tx:<tx id>` · `edit`. */
    val source: String,
    /** The previous `st` version, or null for the first one. */
    val derivedFrom: ArtifactId? = null,
    /** Spoken/original language when known (`en`, `my`, …). */
    val lang: String? = null,
    /** The next free cue number: new cues get `c<nextId>`. */
    val nextId: Int,
    val cues: List<SubtitleCue>,
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val SOURCE_SRT = "srt"
        const val SOURCE_EDIT = "edit"
        fun trackSource(streamIndex: Int) = "track:$streamIndex"
        fun transcriptSource(tx: ArtifactId) = "tx:${tx.value}"
    }
}

/** One line on screen. Times are integer milliseconds of the source video. */
@Serializable
@SerialName("SubtitleCue")
data class SubtitleCue(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    /** Original text; never replaced by a translation (rule 4). */
    val text: String,
    val textMy: String? = null,
    /** [myHash] of [text] when [textMy] was made; a different value means the original changed since. */
    val myFrom: String? = null,
    /** The owner typed [textMy]; translation never replaces it unless asked (D-81). */
    val myEdited: Boolean = false,
    /** Dubbing only (D-83). */
    val spokenMy: String? = null,
    /** Reserved, empty in v1. */
    val speaker: String? = null,
)

/** First 8 hex of SHA-256 of [text] — the `myFrom` stamp. */
fun myHash(text: String): String = Sha256.hex(text.encodeToByteArray()).take(8)
