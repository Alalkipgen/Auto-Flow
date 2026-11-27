package io.github.alalkipgen.autoflow.core.subtitles

import io.github.alalkipgen.autoflow.core.recap.Srt

/**
 * The `.srt` export (specs/quick-tools.md §5 Export, Q-38): the saved version as SubRip text in the chosen language,
 * named after the project, the language and the version, so the same version and language is the same file.
 */
object SubtitleExport {
    const val SRT_MIME = "application/x-subrip"
    const val EXPORTS_DIR = "exports"

    /** Burmese once some line has Burmese (Q-38), else the original. */
    fun defaultChoice(doc: SubtitleDoc): TextChoice =
        if (doc.cues.any { !it.textMy.isNullOrBlank() }) TextChoice.BURMESE else TextChoice.ORIGINAL

    fun srt(doc: SubtitleDoc, choice: TextChoice): String = Srt.write(SubtitleOps.toSrt(doc, choice))

    /** `<project>-<my|orig|both>-<version>.srt`: letters, digits and Burmese kept, the rest `-`. */
    fun fileName(project: String, choice: TextChoice, version: String, ext: String = "srt"): String {
        val safe = project.map { if (it.isLetterOrDigit() || it.code in 0x1000..0x109F) it else '-' }.joinToString("")
            .replace(Regex("-+"), "-").trim('-').ifEmpty { "subtitles" }.take(60)
        val lang = when (choice) {
            TextChoice.BURMESE -> "my"
            TextChoice.ORIGINAL -> "orig"
            TextChoice.BOTH -> "both"
        }
        val id = version.filter { it.isLetterOrDigit() }.takeLast(12).ifEmpty { "draft" }
        return "$safe-$lang-$id.$ext"
    }
}
