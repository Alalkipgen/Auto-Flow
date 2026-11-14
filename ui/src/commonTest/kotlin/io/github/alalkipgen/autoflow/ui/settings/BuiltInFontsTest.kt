package io.github.alalkipgen.autoflow.ui.settings

import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuiltInFontsTest {
    private fun source(files: Map<String, ByteArray>) = object : BundledConfig {
        var reads = 0
        override fun modelsJson(): String = "{}"
        override fun bundledFont(name: String): ByteArray? = files[name]?.also { reads++ }
    }

    @Test
    fun copiesShippedFontsOnceAndListsThem() {
        val fs = InMemoryFileSystem()
        val src = source(mapOf("Pyidaungsu-Regular.ttf" to byteArrayOf(1, 2, 3), "NotoSansMyanmar-Regular.ttf" to byteArrayOf(4)))
        val fonts = BuiltInFonts(fs, "/app/fonts", src)
        assertEquals(listOf("/app/fonts/Pyidaungsu-Regular.ttf", "/app/fonts/NotoSansMyanmar-Regular.ttf"), fonts.paths)
        assertEquals(listOf<Byte>(1, 2, 3), fs.read("/app/fonts/Pyidaungsu-Regular.ttf").toList())
        // A second start with the same files keeps them.
        assertEquals(fonts.paths, BuiltInFonts(fs, "/app/fonts", src).paths)
    }

    @Test
    fun rewritesADamagedCopy() {
        val fs = InMemoryFileSystem()
        fs.createDirectories("/app/fonts")
        fs.writeDurably("/app/fonts/Pyidaungsu-Regular.ttf", byteArrayOf(9))
        BuiltInFonts(fs, "/app/fonts", source(mapOf("Pyidaungsu-Regular.ttf" to byteArrayOf(1, 2)))).paths
        assertEquals(listOf<Byte>(1, 2), fs.read("/app/fonts/Pyidaungsu-Regular.ttf").toList())
    }

    @Test
    fun noFolderOrNoFontsMeansNone() {
        assertTrue(BuiltInFonts(InMemoryFileSystem(), null, source(mapOf("Pyidaungsu-Regular.ttf" to byteArrayOf(1)))).paths.isEmpty())
        assertTrue(BuiltInFonts(InMemoryFileSystem(), "/app/fonts", source(emptyMap())).paths.isEmpty())
    }

    @Test
    fun settingsFontsComeFirstWithoutDuplicates() {
        val fs = InMemoryFileSystem()
        val fonts = BuiltInFonts(fs, "/f", source(mapOf("Pyidaungsu-Regular.ttf" to byteArrayOf(1))))
        assertEquals(listOf("/mine.ttf", "/f/Pyidaungsu-Regular.ttf"), BuiltInFonts.withBuiltIns(listOf("/mine.ttf", "/f/Pyidaungsu-Regular.ttf"), fonts))
        assertEquals(listOf("/mine.ttf"), BuiltInFonts.withBuiltIns(listOf("/mine.ttf"), null))
    }
}
