package io.github.alalkipgen.autoflow.ui.settings

import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.platform.PlatformResult
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsStoreTest {
    private val fs = InMemoryFileSystem()
    private val path = "/home/a/.autoflow/settings.json"

    @Test
    fun firstRunIsFakeModeWithDefaults() {
        val store = SettingsStore(fs, path)
        assertEquals(AppSettings(), store.state.value)
        assertTrue(store.state.value.useFakeProviders)
        assertNull(store.problem)
    }

    @Test
    fun changesArePersistedAtomically() {
        val store = SettingsStore(fs, path)
        assertEquals(PlatformResult.Ok(Unit), store.update { it.copy(useFakeProviders = false, defaultRatio = Ratio.R16_9) })
        assertEquals(listOf("write $path.tmp", "move $path.tmp -> $path"), fs.log.filter { !it.startsWith("mkdirs") })
        assertEquals(store.state.value, SettingsStore(fs, path).state.value)
        assertEquals(Ratio.R16_9, SettingsStore(fs, path).state.value.defaultRatio)
    }

    @Test
    fun unreadableFileGivesDefaultsAndIsLeftUntilTheNextChange() {
        fs.createDirectories("/home/a/.autoflow")
        fs.writeDurably(path, "{not json".encodeToByteArray())
        val store = SettingsStore(fs, path)
        assertEquals(AppSettings(), store.state.value)
        assertNotNull(store.problem)
        assertContentEquals("{not json".encodeToByteArray(), fs.read(path))
        store.update { it.copy(ffmpegPath = "C:/ffmpeg/bin/ffmpeg.exe") }
        assertNull(store.problem)
        assertEquals("C:/ffmpeg/bin/ffmpeg.exe", SettingsStore(fs, path).state.value.ffmpegPath)
    }

    @Test
    fun unknownKeysAndBadValuesFromANewerVersionAreTolerated() {
        fs.createDirectories("/home/a/.autoflow")
        fs.writeDurably(path, """{"useFakeProviders": false, "defaultRatio": "7:3", "futureThing": 1}""".encodeToByteArray())
        val s = SettingsStore(fs, path).state.value
        assertEquals(false, s.useFakeProviders)
        assertEquals(Ratio.R9_16, s.defaultRatio)
    }

    @Test
    fun failedSaveKeepsTheOldState() {
        val store = SettingsStore(fs, path)
        fs.failWhen = { op, _ -> op == "move" }
        assertIs<PlatformResult.Failed>(store.update { it.copy(useFakeProviders = false) })
        assertTrue(store.state.value.useFakeProviders)
    }
}

class ModelConfigSourceTest {
    private val fs = InMemoryFileSystem()
    private val bundled = BundledConfig { TEST_MODELS_JSON }

    @Test
    fun bundledFileByDefault() {
        val loaded = loadModelConfig(fs, null, bundled)
        assertEquals(listOf("text-a", "text-b"), loaded.config.text.candidates)
        assertEquals(LoadedModelConfig.BUNDLED, loaded.source)
        assertEquals(TEST_MODELS_JSON, loaded.text)
        assertNull(loaded.problem)
    }

    @Test
    fun overrideWinsWhenValidElseBundledWithAProblem() {
        fs.createDirectories("/cfg")
        fs.writeDurably("/cfg/models.json", """{"text": {"candidates": ["mine"]}}""".encodeToByteArray())
        assertEquals(listOf("mine"), loadModelConfig(fs, "/cfg/models.json", bundled).config.text.candidates)

        fs.writeDurably("/cfg/bad.json", "[1,".encodeToByteArray())
        val bad = loadModelConfig(fs, "/cfg/bad.json", bundled)
        assertEquals(listOf("text-a", "text-b"), bad.config.text.candidates)
        assertNotNull(bad.problem)

        val missing = loadModelConfig(fs, "/cfg/missing.json", bundled)
        assertEquals(LoadedModelConfig.BUNDLED, missing.source)
        assertTrue(missing.problem!!.contains("/cfg/missing.json"))
    }
}

const val TEST_MODELS_JSON = """{"text": {"candidates": ["text-a", "text-b"]}, "image": {"provider": "manual"}}"""
