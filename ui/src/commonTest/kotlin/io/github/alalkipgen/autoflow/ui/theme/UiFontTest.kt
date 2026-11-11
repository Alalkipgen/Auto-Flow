package io.github.alalkipgen.autoflow.ui.theme

import io.github.alalkipgen.autoflow.ui.theme.UiFont.Candidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiFontTest {
    private val noto = Candidate("/c/1.ttf", "Noto Sans Myanmar", 400)
    private val notoBold = Candidate("/c/2.ttf", "Noto Sans Myanmar", 700)
    private val pyidaungsu = Candidate("/c/3.ttf", "Pyidaungsu", 400)
    private val other = Candidate("/c/4.ttf", "Padauk", 400)

    @Test
    fun prefersPyidaungsuThenNotoSansMyanmar() {
        assertEquals(listOf(pyidaungsu), UiFont.pick(listOf(other, noto, pyidaungsu)))
        assertEquals(listOf(noto), UiFont.pick(listOf(other, noto)))
    }

    @Test
    fun otherFontsGiveTheSystemFallback() {
        assertTrue(UiFont.pick(listOf(other)).isEmpty())
        assertTrue(UiFont.pick(emptyList()).isEmpty())
    }

    @Test
    fun keepsEveryWeightOfTheFamilyRegularFirst() {
        assertEquals(listOf(noto, notoBold), UiFont.pick(listOf(notoBold, noto, noto.copy(path = "/c/dup.ttf"))))
        val upper = Candidate("/p.ttf", "PYIDAUNGSU", 400)
        assertEquals(listOf(upper), UiFont.pick(listOf(upper)))
        assertEquals("/c/5.ttf", UiFont.pick(listOf(Candidate("/c/5.ttf", "Noto Sans Myanmar UI", 400))).single().path)
    }

    @Test
    fun unreadableOrDamagedFilesFallBack() {
        assertNull(UiFont.load(listOf("/missing.ttf")) { null })
        assertNull(UiFont.load(listOf("/bad.ttf")) { ByteArray(10) })
    }
}
