package io.github.alalkipgen.autoflow.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoicePartsTest {
    @Test fun sentencesArePackedUpToTheLimitInOrder() {
        val text = "မင်္ဂလာပါ။ ဒီနေ့ ရာသီဥတု ကောင်းတယ်။ Price is 3.5 dollars. Really?\nNew line here"
        assertEquals(listOf("မင်္ဂလာပါ။ ဒီနေ့ ရာသီဥတု ကောင်းတယ်။ Price is 3.5 dollars. Really? New line here"), VoiceParts.split(text, 500))
        assertEquals(listOf("မင်္ဂလာပါ။", "ဒီနေ့ ရာသီဥတု ကောင်းတယ်။", "Price is 3.5 dollars.", "Really? New line here"), VoiceParts.split(text, 25))
    }

    @Test fun aLongSentenceIsCutAtASpaceElseAtTheLimit() {
        assertEquals(listOf("aaaa bbbb", "cccc"), VoiceParts.split("aaaa bbbb cccc", 10))
        assertEquals(listOf("abcdefghij", "klm"), VoiceParts.split("abcdefghijklm", 10))
        val long = (1..300).joinToString(" ") { "word$it" }
        val parts = VoiceParts.split(long, 500)
        assertTrue(parts.all { it.length <= 500 })
        assertEquals(long, parts.joinToString(" "))
    }

    @Test fun blankTextHasNoParts() {
        assertEquals(emptyList(), VoiceParts.split("  \n\n  ", 500))
        assertEquals(emptyList(), VoiceParts.split("", 500))
        assertEquals(listOf("One.", "Two."), VoiceParts.split("One.\r\n\r\nTwo.", 4))
    }
}
