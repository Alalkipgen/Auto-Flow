package io.github.alalkipgen.autoflow.core.text

/**
 * Where a Burmese syllable starts. One rule for the UI's line breaks (`My.breakSyllables` puts a zero-width space there)
 * and for core text cuts (`PublishKitText.chapterTitle`), so a cut never leaves a vowel sign or an asat without its
 * consonant (P9-G2-13).
 */
object BurmeseSyllables {
    /** A character of the Myanmar block (U+1000–U+109F). */
    fun isBurmese(c: Char): Boolean = c in '\u1000'..'\u109F'

    /**
     * [text] has a syllable starting at [i]: a consonant or independent vowel (or ဿ) that is not stacked under the one
     * before it (virama) and is not a final killed by asat (e.g. the န် of ကန်).
     */
    fun startsAt(text: CharSequence, i: Int): Boolean {
        if (i !in text.indices) return false
        val c = text[i]
        if (c !in '\u1000'..'\u102A' && c != '\u103F') return false
        if (i > 0 && text[i - 1] == '\u1039') return false
        var j = i + 1
        while (j < text.length && isMark(text[j])) {
            if (text[j] == '\u103A' || text[j] == '\u1039') return false
            if (text[j] in '\u102B'..'\u1035') return true // a vowel: a later asat belongs to it (ပေါ်, ကော်)
            j++
        }
        return true
    }

    private fun isMark(c: Char) =
        isBurmese(c) && (c.category == CharCategory.NON_SPACING_MARK || c.category == CharCategory.COMBINING_SPACING_MARK)
}
