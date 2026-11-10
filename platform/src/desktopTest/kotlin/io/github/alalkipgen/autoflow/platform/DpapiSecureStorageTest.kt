package io.github.alalkipgen.autoflow.platform

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Real DPAPI on Windows (the windows-latest CI job sets AUTOFLOW_EXPECT_DPAPI=1 so this cannot silently take the
 * non-Windows branch there); elsewhere every call must be `Unsupported`.
 */
class DpapiSecureStorageTest {
    private val tmp: Path = Files.createTempDirectory("autoflow-secrets")
    private val dirs = AppDirs(tmp.resolve(".autoflow").toString())
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    private val key = "test-key-not-real-456"

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @AfterTest
    fun cleanUp() = tmp.deleteRecursively()

    @Test
    fun dpapiRoundTripOnWindowsOtherwiseUnsupported() {
        if (System.getenv("AUTOFLOW_EXPECT_DPAPI") == "1") assertTrue(isWindows, "DPAPI job is not on Windows")
        val storage = desktopSecureStorage(dirs)
        if (!isWindows) {
            assertIs<PlatformResult.Unsupported>(storage.get(SecretNames.GEMINI_API_KEY))
            assertIs<PlatformResult.Unsupported>(storage.put(SecretNames.GEMINI_API_KEY, key))
            assertFalse(Files.exists(Path.of(dirs.secrets)))
            return
        }
        assertEquals(PlatformResult.Ok(null), storage.get(SecretNames.GEMINI_API_KEY))
        assertEquals(PlatformResult.Ok(Unit), storage.put(SecretNames.GEMINI_API_KEY, key))
        assertEquals(PlatformResult.Ok(key), desktopSecureStorage(dirs).get(SecretNames.GEMINI_API_KEY))
        val onDisk = Path.of(dirs.secrets).readBytes()
        assertTrue(String(onDisk, Charsets.ISO_8859_1).startsWith("AFS1"))
        assertFalse(String(onDisk, Charsets.ISO_8859_1).contains(key), "plain text on disk")
        assertEquals(PlatformResult.Ok(Unit), storage.remove(SecretNames.GEMINI_API_KEY))
        assertEquals(PlatformResult.Ok(null), storage.get(SecretNames.GEMINI_API_KEY))
    }

    @Test
    fun aBlobSealedWithoutOurEntropyIsUnreadableThenReplaceable() {
        if (!isWindows) return
        Files.createDirectories(Path.of(dirs.root))
        val foreign = com.sun.jna.platform.win32.Crypt32Util.cryptProtectData("{\"gemini.apiKey\":\"x\"}".encodeToByteArray())
        Files.write(Path.of(dirs.secrets), "AFS1".encodeToByteArray() + foreign)
        val storage = desktopSecureStorage(dirs)
        assertIs<PlatformResult.Failed>(storage.get(SecretNames.GEMINI_API_KEY))
        assertEquals(PlatformResult.Ok(Unit), storage.put(SecretNames.GEMINI_API_KEY, key))
        assertEquals(PlatformResult.Ok(key), storage.get(SecretNames.GEMINI_API_KEY))
    }
}
