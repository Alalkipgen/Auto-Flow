package io.github.alalkipgen.autoflow.core.library

import io.github.alalkipgen.autoflow.core.recap.LexiconEntry
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Library store root (5A-7, studio-v2.md §6).
 * Stored at `<app data>/library/library.json`.
 */
@Serializable
@SerialName("Library")
data class Library(
    val schemaVersion: Int = SCHEMA_VERSION,
    val brandKits: List<BrandKit> = emptyList(),
    val voices: List<SavedVoice> = emptyList(),
    /** Shared lexicon — whole-word replacements, longest `written` first. */
    val lexicon: List<LexiconEntry> = emptyList(),
    val music: List<MusicItem> = emptyList(),
    val fonts: List<FontItem> = emptyList(),
    val presets: List<Preset> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val DIR = "library"
        const val FILE = "library/library.json"
        const val FILES_DIR = "library/files"
    }
}

/** Brand Kit (studio-v2.md §6). */
@Serializable
@SerialName("BrandKit")
data class BrandKit(
    val id: String,
    val name: String,
    /** Relative path inside library: `files/…png`. */
    val logo: String? = null,
    val watermark: String? = null,
    val colours: List<String> = emptyList(),
    val font: String? = null,
    val captionStyle: String? = null,
    val layers: List<String> = emptyList(),
    val intro: String? = null,
    val outro: String? = null,
)

/** Saved voice preset (studio-v2.md §6). */
@Serializable
@SerialName("SavedVoice")
data class SavedVoice(
    val id: String,
    val name: String,
    val lock: VoiceLock,
)

/** Background music item (studio-v2.md §6). */
@Serializable
@SerialName("MusicItem")
data class MusicItem(
    val id: String,
    val name: String,
    /** Relative path inside library: `files/…m4a`. */
    val file: String,
    val durationMs: Long = 0L,
)

/** Custom font item (studio-v2.md §6). */
@Serializable
@SerialName("FontItem")
data class FontItem(
    val id: String,
    val name: String,
    /** Relative path inside library: `files/…ttf`. */
    val file: String,
)

/** Project preset (studio-v2.md §6): capture of CAPTION / STYLE / EXPORT settings. */
@Serializable
@SerialName("Preset")
data class Preset(
    val id: String,
    val name: String,
    val kind: PresetKind,
    /** Arbitrary JSON blob; shape depends on [kind]. */
    val value: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
)

@Serializable
@SerialName("PresetKind")
enum class PresetKind {
    @SerialName("CAPTION") CAPTION,
    @SerialName("STYLE") STYLE,
    @SerialName("EXPORT") EXPORT,
}
