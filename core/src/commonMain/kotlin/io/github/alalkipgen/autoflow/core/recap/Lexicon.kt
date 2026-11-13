package io.github.alalkipgen.autoflow.core.recap

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Pronunciation fixes applied before TTS (5B-7, D-39): each [LexiconEntry.written] in the text is spoken as
 * [LexiconEntry.spoken]. Captions always show the written text.
 */
@Serializable
@SerialName("Lexicon")
data class Lexicon(val entries: List<LexiconEntry> = emptyList()) {
    /**
     * [text] with every entry replaced, scanning left to right; at one position the longest matching entry wins, and
     * replaced text is not scanned again (so `A → AB` cannot loop). Blank entries are ignored.
     */
    fun apply(text: String): String {
        val usable = entries.filter { it.written.isNotEmpty() }.sortedByDescending { it.written.length }
        if (usable.isEmpty() || text.isEmpty()) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val hit = usable.firstOrNull { text.startsWith(it.written, i) }
            if (hit == null) {
                out.append(text[i]); i++
            } else {
                out.append(hit.spoken); i += hit.written.length
            }
        }
        return out.toString()
    }

    companion object {
        val EMPTY = Lexicon()
    }
}

@Serializable
@SerialName("LexiconEntry")
data class LexiconEntry(val written: String, val spoken: String)

/** What TTS speaks for a block, and the hash that marks its takes stale (studio-v2 §2). */
object SpokenText {
    /** `lexicon(speakMy ?: textMy)`, trimmed; a blank [RecapBlock.speakMy] counts as unset. */
    fun of(block: RecapBlock, lexicon: Lexicon = Lexicon.EMPTY): String =
        lexicon.apply(block.speakMy?.takeIf { it.isNotBlank() } ?: block.textMy).trim()

    /** First 12 hex of SHA-256 of [of]; the same function as v1's [RecapRules.textHash], so migrated takes stay current. */
    fun hash(block: RecapBlock, lexicon: Lexicon = Lexicon.EMPTY): String = RecapRules.textHash(of(block, lexicon))

    fun isStale(take: BlockTake, block: RecapBlock, lexicon: Lexicon = Lexicon.EMPTY): Boolean =
        take.spokenHash != hash(block, lexicon)

}
