package io.github.alalkipgen.autoflow.core.subtitles

import io.github.alalkipgen.autoflow.core.recap.SrtCue

/** The text encoding an imported `.srt` was read with; the UI says which one was used (specs/quick-tools.md §5). */
enum class SrtEncoding { UTF8, UTF8_BOM, UTF16LE_BOM, UTF16BE_BOM, WINDOWS_1252 }

sealed interface SrtImportResult {
    /** [zawgyiLikely]: the Burmese looks like Zawgyi; the UI warns and converts nothing (Q-44). */
    data class Ok(val cues: List<SrtCue>, val encoding: SrtEncoding, val zawgyiLikely: Boolean) : SrtImportResult
    data object Empty : SrtImportResult
    /** Block [cueNumber] (its own number when it has one, else its position) cannot be read; nothing is saved. */
    data class Broken(val cueNumber: Int) : SrtImportResult
}

/** Strict `.srt` import for the Subtitles tool: decode, then every block must parse (Recap keeps its lenient reader). */
object SrtImport {
    private val TIME = Regex("""^\s*(\d{1,3}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,3}):(\d{2}):(\d{2})[,.](\d{1,3})(\s.*)?$""")
    private val TAG = Regex("""<[^>]+>|\{\\[^}]*\}""")

    fun read(bytes: ByteArray): SrtImportResult {
        val (text, encoding) = decode(bytes)
        return when (val parsed = parse(text)) {
            is SrtImportResult.Ok -> parsed.copy(encoding = encoding)
            else -> parsed
        }
    }

    /** UTF-8 (± BOM), UTF-16 with BOM, else UTF-8 when valid, else Windows-1252. */
    fun decode(bytes: ByteArray): Pair<String, SrtEncoding> {
        fun b(i: Int) = bytes.getOrNull(i)?.toInt()?.and(0xFF)
        return when {
            b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF -> bytes.copyOfRange(3, bytes.size).decodeToString() to SrtEncoding.UTF8_BOM
            b(0) == 0xFF && b(1) == 0xFE -> utf16(bytes, 2, littleEndian = true) to SrtEncoding.UTF16LE_BOM
            b(0) == 0xFE && b(1) == 0xFF -> utf16(bytes, 2, littleEndian = false) to SrtEncoding.UTF16BE_BOM
            else -> try {
                bytes.decodeToString(throwOnInvalidSequence = true) to SrtEncoding.UTF8
            } catch (e: CharacterCodingException) {
                windows1252(bytes) to SrtEncoding.WINDOWS_1252
            }
        }
    }

    fun parse(text: String): SrtImportResult {
        val blocks = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n[ \t]*\n"))
            .map { it.trim('\n') }.filter { it.isNotBlank() }
        if (blocks.isEmpty()) return SrtImportResult.Empty
        val cues = ArrayList<SrtCue>(blocks.size)
        blocks.forEachIndexed { k, block ->
            val lines = block.split('\n')
            val numbered = lines.first().trim().toIntOrNull()
            val number = numbered ?: (k + 1)
            val timeLine = if (numbered != null) lines.getOrNull(1) else lines.first()
            val m = timeLine?.let { TIME.find(it) } ?: return SrtImportResult.Broken(number)
            val g = m.groupValues
            val start = ms(g[1], g[2], g[3], g[4])
            val end = ms(g[5], g[6], g[7], g[8])
            if (end <= start) return SrtImportResult.Broken(number)
            val body = lines.drop(if (numbered != null) 2 else 1).joinToString("\n") { it.replace(TAG, "").trim() }.trim()
            cues += SrtCue(start, end, body)
        }
        return SrtImportResult.Ok(cues.sortedWith(compareBy({ it.startMs }, { it.endMs })), SrtEncoding.UTF8,
            Zawgyi.likely(cues.joinToString("\n") { it.text }))
    }

    private fun ms(h: String, m: String, s: String, f: String): Long =
        h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1000 + f.padEnd(3, '0').toLong()

    private fun utf16(bytes: ByteArray, from: Int, littleEndian: Boolean): String {
        val sb = StringBuilder((bytes.size - from) / 2)
        var i = from
        while (i + 1 < bytes.size) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt() and 0xFF
            sb.append((if (littleEndian) (hi shl 8) or lo else (lo shl 8) or hi).toChar())
            i += 2
        }
        return sb.toString()
    }

    /** Windows-1252 = Latin-1 except 0x80–0x9F; undefined bytes become U+FFFD. */
    private val CP1252_HIGH = charArrayOf(
        '\u20AC', '\uFFFD', '\u201A', '\u0192', '\u201E', '\u2026', '\u2020', '\u2021', '\u02C6', '\u2030', '\u0160', '\u2039', '\u0152', '\uFFFD', '\u017D', '\uFFFD',
        '\uFFFD', '\u2018', '\u2019', '\u201C', '\u201D', '\u2022', '\u2013', '\u2014', '\u02DC', '\u2122', '\u0161', '\u203A', '\u0153', '\uFFFD', '\u017E', '\u0178',
    )

    private fun windows1252(bytes: ByteArray): String = buildString(bytes.size) {
        for (byte in bytes) {
            val v = byte.toInt() and 0xFF
            append(if (v in 0x80..0x9F) CP1252_HIGH[v - 0x80] else v.toChar())
        }
    }
}

/**
 * A small in-house Zawgyi check (Q-44): counts patterns that are impossible or unusual in Unicode Burmese but normal
 * in Zawgyi (vowel sign E or medial RA typed before the consonant, a visible-killer U+1039 not followed by a
 * consonant, Zawgyi-only glyph code points U+105A and U+1060–U+1097). It never converts.
 */
object Zawgyi {
    private fun consonant(c: Char) = c in '\u1000'..'\u1021'

    fun likely(text: String): Boolean {
        var zawgyi = 0
        var unicode = 0
        for (i in text.indices) {
            val c = text[i]
            val prev = text.getOrNull(i - 1)
            val next = text.getOrNull(i + 1)
            when {
                (c == '\u1031' || c == '\u103B') && next != null && consonant(next) && (prev == null || !consonant(prev) && prev !in '\u103B'..'\u103E') -> zawgyi++
                c == '\u1039' && (next == null || !consonant(next)) -> zawgyi++
                c == '\u105A' || c in '\u1060'..'\u1097' -> zawgyi++
                c == '\u103A' && prev != null && consonant(prev) -> unicode++
                c == '\u1031' && prev != null && (consonant(prev) || prev in '\u103B'..'\u103E') -> unicode++
            }
        }
        return zawgyi >= 2 && zawgyi > unicode
    }
}
