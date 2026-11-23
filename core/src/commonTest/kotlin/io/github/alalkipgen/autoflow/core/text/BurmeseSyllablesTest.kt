package io.github.alalkipgen.autoflow.core.text

import kotlin.test.Test
import kotlin.test.assertEquals

/** The syllable rule shared by `My.breakSyllables` and `PublishKitText.chapterTitle` (P9-G2-13). */
class BurmeseSyllablesTest {
    private fun starts(text: String) = text.indices.filter { BurmeseSyllables.startsAt(text, it) }

    @Test
    fun syllablesStartAtConsonantsThatAreNotFinalsOrStacked() {
        assertEquals(listOf(0, 3), starts("ကန်တော့"))      // န် is a final (asat): ကန် · တော့
        assertEquals(listOf(0, 4), starts("ပေါ်မှာ"))       // the asat after ော belongs to the vowel: ပေါ် · မှာ
        assertEquals(listOf(0, 5), starts("ကမ္ဘာမြေ"))     // ဘ is stacked under မ (virama): ကမ္ဘာ · မြေ
        assertEquals(listOf(0, 4, 6), starts("မြန်မာစာ"))   // မြန် · မာ · စာ
        assertEquals(emptyList<Int>(), starts("Cooper 12"))
        assertEquals(false, BurmeseSyllables.startsAt("က", 5))
    }
}
