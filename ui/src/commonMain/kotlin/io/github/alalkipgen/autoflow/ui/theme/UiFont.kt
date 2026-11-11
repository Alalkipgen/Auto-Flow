package io.github.alalkipgen.autoflow.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.alalkipgen.autoflow.media.render.FontInfo

/** Font family for UI text; [FontFamily.Default] (the system fallback) until a Settings font is found. */
val LocalUiFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Default }

/**
 * UI Burmese font (DESIGN.md §1): Pyidaungsu, else Noto Sans Myanmar, taken from the Settings fonts; none of them =
 * the system fallback (Windows: Myanmar Text). Fonts are matched on the family name inside the file, because the
 * Android picker copies a font to the cache under a numeric name.
 */
object UiFont {
    private val PREFERRED = listOf("pyidaungsu", "notosansmyanmar")

    data class Candidate(val path: String, val family: String, val weight: Int)

    /** Every file of the first preferred family present, regular first; empty = system fallback. */
    fun pick(fonts: List<Candidate>): List<Candidate> {
        for (want in PREFERRED) {
            val hits = fonts.filter { key(it.family).startsWith(want) }
            if (hits.isNotEmpty()) return hits.distinctBy { it.weight }.sortedBy { kotlin.math.abs(it.weight - 400) }
        }
        return emptyList()
    }

    /** Reads [paths] with [readBytes] (null = unreadable, skipped) and builds the UI family, or null for the fallback. */
    fun load(paths: List<String>, readBytes: (String) -> ByteArray?): FontFamily? {
        val bytes = paths.associateWith { readBytes(it) }
        val candidates = bytes.mapNotNull { (path, data) ->
            data ?: return@mapNotNull null
            val info = FontInfo.parse(data) ?: return@mapNotNull null
            Candidate(path, info.family, FontInfo.weightClass(data) ?: 400)
        }
        val fonts = pick(candidates).mapNotNull { platformFont(it.path, bytes.getValue(it.path)!!, FontWeight(it.weight.coerceIn(1, 1000))) }
        return if (fonts.isEmpty()) null else FontFamily(fonts)
    }

    private fun key(family: String) = family.lowercase().filter { it.isLetterOrDigit() }
}

/** A font from a file the platform can load, or null when it rejects the data. */
internal expect fun platformFont(path: String, bytes: ByteArray, weight: FontWeight): Font?
