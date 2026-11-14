package io.github.alalkipgen.autoflow.core.library

import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.recap.LexiconEntry
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryStoreTest {

    private fun makeStore(): LibraryStore {
        return LibraryStore(InMemoryFileSystem(), "/library")
    }

    @Test
    fun load_missingFile_returnsDefaults() {
        val store = makeStore()
        val lib = store.load()
        assertEquals(Library.SCHEMA_VERSION, lib.schemaVersion)
        assertTrue(lib.brandKits.isEmpty())
        assertTrue(lib.voices.isEmpty())
        assertTrue(lib.lexicon.isEmpty())
    }

    @Test
    fun saveAndLoad_roundTrip() {
        val store = makeStore()
        val lib = Library(
            brandKits = listOf(BrandKit(id = "k_01", name = "My Brand", colours = listOf("#FF5500"))),
            voices = listOf(SavedVoice(id = "v_01", name = "Charon", lock = VoiceLock("gemini", "flash-tts", "Charon", "my"))),
            lexicon = listOf(LexiconEntry("ကျနော်", "ကျောင်")),
            music = listOf(MusicItem(id = "m_01", name = "Beat", file = "files/beat.m4a", durationMs = 60_000L)),
            fonts = listOf(FontItem(id = "f_01", name = "NotoMyanmar", file = "files/noto.ttf")),
        )
        store.save(lib)
        val loaded = store.load()
        assertEquals(lib, loaded)
    }

    @Test
    fun save_twice_overwritesFirst() {
        val store = makeStore()
        store.save(Library(voices = listOf(SavedVoice("v_01", "A", VoiceLock("gemini", "m", "v", "my")))))
        store.save(Library(voices = listOf(SavedVoice("v_02", "B", VoiceLock("gemini", "m", "w", "my")))))
        val loaded = store.load()
        assertEquals(1, loaded.voices.size)
        assertEquals("B", loaded.voices.first().name)
    }

    @Test
    fun copyAsset_copiesFileToDestination() {
        val fs = InMemoryFileSystem()
        val store = LibraryStore(fs, "/library")
        // Write a fake asset
        fs.createDirectories("/library/files")
        fs.writeDurably("/library/files/logo.png", byteArrayOf(0x89.toByte(), 0x50))
        fs.createDirectories("/project")
        val dest = store.copyAsset("files/logo.png", "/project")
        assertEquals("/project/logo.png", dest)
        assertEquals(2, fs.read(dest).size)
    }

    @Test
    fun presets_roundTrip() {
        val store = makeStore()
        val preset = Preset(
            id = "p_01",
            name = "1080p Export",
            kind = PresetKind.EXPORT,
            value = kotlinx.serialization.json.buildJsonObject { put("quality", kotlinx.serialization.json.JsonPrimitive("1080")) },
        )
        store.save(Library(presets = listOf(preset)))
        val loaded = store.load()
        assertEquals(1, loaded.presets.size)
        assertEquals("1080p Export", loaded.presets.first().name)
        assertEquals(PresetKind.EXPORT, loaded.presets.first().kind)
    }
}
