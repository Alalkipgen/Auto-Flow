package io.github.alalkipgen.autoflow.core.model

import kotlin.jvm.JvmInline
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Artifact kinds and their id prefixes (specs/project-format.md §1). */
@Serializable
@SerialName("ArtifactKind")
enum class ArtifactKind(val code: String) {
    @SerialName("an") ANALYSIS("an"),
    @SerialName("sb") STORYBOARD("sb"),
    @SerialName("sp") SHOT_SPEC("sp"),
    @SerialName("pr") PROMPT_IR("pr"),
    @SerialName("im") IMAGE("im"),
    @SerialName("vd") VIDEO("vd"),
    @SerialName("au") AUDIO("au"),
    @SerialName("tx") TRANSCRIPT("tx"),
    @SerialName("rg") RENDER_GRAPH("rg"),
    @SerialName("rs") RECAP_SCRIPT("rs"),
    ;

    companion object {
        fun fromCode(code: String): ArtifactKind? = entries.firstOrNull { it.code == code }
    }
}

/** `<kind>_<first 12 hex of sha256(bytes)>`, e.g. `im_3fa9c01b22de`. Serialized as a plain string. */
@Serializable
@JvmInline
value class ArtifactId(val value: String) {
    val kind: ArtifactKind? get() = ArtifactKind.fromCode(value.substringBefore('_', ""))

    /** True when the id has the `<kind>_<12 lowercase hex>` shape. */
    val isWellFormed: Boolean get() = kind != null && ID_HASH.matches(value.substringAfter('_'))

    override fun toString(): String = value

    companion object {
        const val HASH_LENGTH = 12
        private val ID_HASH = Regex("[0-9a-f]{$HASH_LENGTH}")

        fun of(kind: ArtifactKind, sha256Hex: String): ArtifactId =
            ArtifactId("${kind.code}_${sha256Hex.take(HASH_LENGTH)}")
    }
}

/**
 * Sidecar record of one immutable artifact file (ARCHITECTURE.md §4).
 * [inputs] maps an input name (e.g. `spec`, `globalStyle`, `promptIr`, `ref`) to the artifact id or hash it was
 * derived from; stale detection compares these with the current project pointers.
 */
@Serializable
@SerialName("Artifact")
data class Artifact(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: ArtifactId,
    val kind: ArtifactKind,
    val file: String,
    val shot: Int? = null,
    val inputs: Map<String, String> = emptyMap(),
    val provider: String? = null,
    val model: String? = null,
    val createdAt: Instant,
    val note: String? = null,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}
