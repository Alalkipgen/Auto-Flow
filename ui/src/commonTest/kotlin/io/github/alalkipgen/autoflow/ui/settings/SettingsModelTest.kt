package io.github.alalkipgen.autoflow.ui.settings

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.Usage
import io.github.alalkipgen.autoflow.ai.usage.UsageCounter
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.platform.SecretNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SettingsModelTest {
    private val fs = InMemoryFileSystem()
    private val settings = SettingsStore(fs, "/a/settings.json")
    private val secrets = MemorySecureStorage()
    private val status = ProviderStatusModel(settings, secrets)
    private val usage = UsageCounter(fs, "/a/usage.json")
    private val tools = FakeTools()
    private val picker = FakePicker()
    private val opened = mutableListOf<String>()
    private var testResult: AiResult<Unit> = AiResult.Ok(Unit, Usage(model = "gemini-x", latencyMs = 1))

    private fun model(storage: MemorySecureStorage = secrets) = SettingsModel(
        settings, storage, ProviderStatusModel(settings, storage), usage, loadModelConfig(fs, null) { TEST_MODELS_JSON },
        tools, picker, { opened += it; PlatformResult.Ok(Unit) }, { testResult },
    )

    @Test
    fun bundledToolsReportWhetherTheApkHasFfmpeg() {
        assertEquals(false, model().state.value.toolsBundled)
        val apk = FakeTools(ffmpeg = "/lib/libffmpeg.so", bundled = true)
        fun bundled() = SettingsModel(settings, secrets, status, usage, loadModelConfig(fs, null) { TEST_MODELS_JSON }, apk, picker,
            { PlatformResult.Ok(Unit) }, { testResult }).state.value
        assertEquals(true, bundled().toolsBundled)
        assertEquals(true, bundled().ffmpegFound)
        apk.ffmpeg = null
        assertEquals(false, bundled().ffmpegFound)
    }

    @Test
    fun keyIsSavedMaskedAndRemoved() {
        val m = SettingsModel(settings, secrets, status, usage, loadModelConfig(fs, null) { TEST_MODELS_JSON }, tools, picker,
            { PlatformResult.Ok(Unit) }, { testResult })
        m.saveKey("   ")
        assertEquals("Enter a key first", m.state.value.message)
        assertEquals(KeyState.MISSING, m.state.value.keyState)

        m.saveKey("  test-key-not-real ")
        assertEquals("test-key-not-real", secrets.values[SecretNames.GEMINI_API_KEY])
        assertEquals(KeyState.SET, m.state.value.keyState)
        assertEquals("••••real", m.state.value.maskedKey)
        m.setUseFakes(false)
        assertEquals("Gemini API ● Free tier · Flow ● Manual", status.current(), "title bar refreshed")

        m.removeKey()
        assertEquals(KeyState.MISSING, m.state.value.keyState)
        assertNull(m.state.value.maskedKey)
        assertEquals("Gemini API ● No key · Flow ● Manual", status.current())
    }

    @Test
    fun keyOnNonWindowsExplainsWhy() {
        val m = model(MemorySecureStorage(unsupported = true))
        m.saveKey("test-key-not-real")
        assertEquals("needs Windows", m.state.value.message)
        assertEquals(KeyState.UNSUPPORTED, m.state.value.keyState)
    }

    @Test
    fun testTtsNamesTheModelAndKeepsTheSample() = runTest {
        var reply: io.github.alalkipgen.autoflow.ai.AiResult<ByteArray> =
            io.github.alalkipgen.autoflow.ai.AiResult.Ok(byteArrayOf(1, 2), io.github.alalkipgen.autoflow.ai.Usage("gemini-3.8-flash-tts"))
        val saved = mutableListOf<ByteArray>()
        val m = SettingsModel(
            settings, secrets, ProviderStatusModel(settings, secrets), usage, loadModelConfig(fs, null) { TEST_MODELS_JSON },
            tools, picker, { PlatformResult.Ok(Unit) }, { testResult },
            testTtsCall = { reply }, saveTtsSample = { saved += it; "/h/tts-test.wav" },
        )
        assertTrue(m.canTestTts)
        m.testTts()
        assertEquals("Gemini TTS works (gemini-3.8-flash-tts). Tap ▶ to hear the Burmese sentence.", m.state.value.message)
        assertEquals("/h/tts-test.wav", m.state.value.ttsSample)
        assertEquals(1, saved.size)
        reply = io.github.alalkipgen.autoflow.ai.AiResult.Failed("model_unavailable", "no TTS model answered")
        m.testTts()
        assertEquals("Gemini TTS: no TTS model answered", m.state.value.message)
        assertEquals(null, m.state.value.ttsSample)
    }

    @Test
    fun testKeyReportsEachOutcomeAndRefreshesUsage() = runTest {
        val m = model()
        usage.record("gemini-x")
        m.testKey()
        assertEquals("Key works (gemini-x)", m.state.value.message)
        assertEquals(mapOf("gemini-x" to 1), m.state.value.usageToday)
        assertFalse(m.state.value.testing)

        testResult = AiResult.RateLimited(retryAfterMs = null)
        m.testKey()
        assertEquals("Key accepted, but the free-tier limit is reached for now", m.state.value.message)
        testResult = AiResult.Failed("no_api_key", "Add a Gemini API key in Settings")
        m.testKey()
        assertEquals("Add a Gemini API key in Settings", m.state.value.message)
    }

    @Test
    fun modeAndDefaultsArePersisted() {
        val m = model()
        m.setUseFakes(false)
        m.setDefaultRatio(Ratio.R16_9)
        m.setDefaultClipMs(10_000)
        val saved = SettingsStore(fs, "/a/settings.json").state.value
        assertEquals(AppSettings(useFakeProviders = false, defaultRatio = Ratio.R16_9, defaultClipMs = 10_000), saved)
    }

    @Test
    fun ffmpegCheckShowsVersionAndOverlayBackend() = runTest {
        val ran = mutableListOf<Pair<List<String>, String>>()
        var libass = true
        val runner = ToolRunner { cmd, dir, _, _, _ ->
            ran += cmd to dir
            val out = when (cmd.last()) {
                "-version" -> "ffmpeg version 7.0.2-static\nconfiguration: --enable-gpl" + if (libass) " --enable-libass" else ""
                "-encoders" -> " V....D libx264              libx264 H.264\n A....D aac                  AAC"
                else -> ""
            }
            ToolExec.Done(0, out, emptyList(), 5, false)
        }
        fun m() = SettingsModel(settings, secrets, status, usage, loadModelConfig(fs, null) { TEST_MODELS_JSON }, tools, picker,
            { PlatformResult.Ok(Unit) }, { testResult }, ffmpegRunner = runner)
        val model = m()
        model.checkFfmpeg()
        assertEquals("ffmpeg not found: set its path or press Detect.", model.state.value.ffmpegCheck)
        assertTrue(model.state.value.ffmpegCheckWarn)

        model.setFfmpegPath("C:/ffmpeg/bin/ffmpeg.exe")
        assertNull(model.state.value.ffmpegCheck)
        model.checkFfmpeg()
        assertEquals("ffmpeg 7.0.2-static · libass ✓ · libx264 ✓ · aac ✓ → text overlays: libass", model.state.value.ffmpegCheck)
        assertFalse(model.state.value.ffmpegCheckWarn)
        assertEquals(listOf("C:/ffmpeg/bin/ffmpeg.exe", "-hide_banner", "-version") to "C:/ffmpeg/bin", ran.first())

        libass = false
        model.checkFfmpeg()
        assertTrue(model.state.value.ffmpegCheck!!.endsWith("text overlays: PNG fallback (no libass)"))
        assertTrue(model.state.value.ffmpegCheckWarn)
        model.setDefaultClipMs(6_000) // other changes keep the result
        assertTrue(model.state.value.ffmpegCheck!!.contains("libass ✗"))

        val none = model()
        none.checkFfmpeg() // no runner: nothing to show
        assertNull(none.state.value.ffmpegCheck)
    }

    @Test
    fun ffmpegDetectAndBrowse() = runTest {
        val m = model()
        m.detectFfmpeg()
        assertEquals("ffmpeg was not found on PATH. Choose ffmpeg.exe instead.", m.state.value.message)
        tools.ffmpeg = "C:/ffmpeg/bin/ffmpeg.exe"
        m.detectFfmpeg()
        assertEquals("C:/ffmpeg/bin/ffmpeg.exe", m.state.value.settings.ffmpegPath)
        picker.files = listOf("D:/tools/ffmpeg.exe")
        m.browseFfmpeg()
        assertEquals("D:/tools/ffmpeg.exe", m.state.value.settings.ffmpegPath)
        picker.files = emptyList() // cancelled
        m.browseFfmpeg()
        assertEquals("D:/tools/ffmpeg.exe", m.state.value.settings.ffmpegPath)
        picker.unsupported = true
        m.browseFfmpeg()
        assertEquals("no dialogs", m.state.value.message)
    }

    @Test
    fun vlcFolderMustHoldLibvlcAndFallsBackToDetection() = runTest {
        tools.detectedVlc = "C:/Program Files/VideoLAN/VLC"
        val m = model()
        assertEquals("C:/Program Files/VideoLAN/VLC", m.state.value.vlcDir)
        m.setVlcPath("D:/empty")
        assertEquals("No VLC (libvlc) in D:/empty", m.state.value.message)
        assertNull(m.state.value.settings.vlcPath)

        tools.vlcDirs += "D:/vlc"
        picker.folder = "D:/vlc"
        m.browseVlc()
        assertEquals("D:/vlc", m.state.value.settings.vlcPath)
        assertEquals("D:/vlc", m.state.value.vlcDir)

        tools.detectedVlc = null
        m.setVlcPath(null)
        assertNull(m.state.value.vlcDir)
        m.openVlcDownload()
        assertEquals(listOf(SettingsModel.VLC_DOWNLOAD_URL), opened)
    }

    @Test
    fun fontsAndModelConfigOverride() = runTest {
        val m = model()
        picker.files = listOf("C:/f/Padauk.ttf", "C:/f/Pyidaungsu.ttf")
        m.addFonts()
        m.addFonts()
        assertEquals(listOf("C:/f/Padauk.ttf", "C:/f/Pyidaungsu.ttf"), m.state.value.settings.fontPaths)
        m.removeFont("C:/f/Padauk.ttf")
        assertEquals(listOf("C:/f/Pyidaungsu.ttf"), m.state.value.settings.fontPaths)

        picker.files = listOf("D:/cfg/models.json")
        m.browseModelsConfig()
        assertEquals("D:/cfg/models.json", m.state.value.settings.modelsConfigPath)
        assertEquals("Restart Auto Flow to load the model config", m.state.value.message)
        assertEquals(LoadedModelConfig.BUNDLED, m.state.value.models.source)
    }
}

class SettingsModelFeatureTest {
    private val fs = io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem()
    private val settings = SettingsStore(fs, "/a/settings.json")
    private val secrets = MemorySecureStorage()
    private val status = ProviderStatusModel(settings, secrets)
    private val usage = io.github.alalkipgen.autoflow.ai.usage.UsageCounter(fs, "/a/usage.json")
    private val tools = FakeTools()
    private val picker = FakePicker()

    private val bundledFeaturesJson = """{"schemaVersion":1,"studioV2":false,"quickRecap":false}"""

    private fun model(bundled: String? = bundledFeaturesJson) = SettingsModel(
        settings, secrets, status, usage, loadModelConfig(fs, null) { TEST_MODELS_JSON },
        tools, picker, { io.github.alalkipgen.autoflow.platform.PlatformResult.Ok(Unit) },
        { io.github.alalkipgen.autoflow.ai.AiResult.Ok(Unit, io.github.alalkipgen.autoflow.ai.Usage("x")) },
        bundledFeatures = bundled,
    )

    @Test
    fun featuresDefaultOffWithNoBundledFile() {
        val m = model(bundled = null)
        val flags = m.state.value.features
        assertEquals(false, flags.studioV2)
        assertEquals(false, flags.quickRecap)
    }

    @Test
    fun bundledFeaturesAreLoaded() {
        val json = """{"schemaVersion":1,"studioV2":true,"quickRecap":false}"""
        val m = model(bundled = json)
        assertEquals(true, m.state.value.features.studioV2)
        assertEquals(false, m.state.value.features.quickRecap)
    }

    @Test
    fun setFeatureAddsOverrideAndTogglesClearIt() {
        val m = model()
        // bundled studioV2=false; turning it on stores an override
        m.setFeature("studioV2", true)
        assertEquals(true, m.state.value.features.studioV2)
        assertEquals(mapOf("studioV2" to true), m.state.value.settings.featureOverrides)

        // turning it back to the bundled default removes the override
        m.setFeature("studioV2", false)
        assertEquals(false, m.state.value.features.studioV2)
        assertEquals(emptyMap(), m.state.value.settings.featureOverrides)
    }

    @Test
    fun overridesArePersisted() {
        val m = model()
        m.setFeature("quickRecap", true)
        val m2 = model()
        // The settings store persisted the override; the new model should read it
        assertEquals(true, m2.state.value.features.quickRecap)
    }
}
