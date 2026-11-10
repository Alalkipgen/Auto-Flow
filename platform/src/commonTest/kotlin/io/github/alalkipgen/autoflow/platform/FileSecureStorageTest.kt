package io.github.alalkipgen.autoflow.platform

import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileSecureStorageTest {
    private val fs = InMemoryFileSystem()
    private val path = "/home/a/.autoflow/secrets.bin"
    private val key = "test-key-not-real-123"
    private val storage = FileSecureStorage(fs, path, XorCipher)

    @Test
    fun missingFileMeansNotSet() {
        assertEquals(PlatformResult.Ok(null), storage.get(SecretNames.GEMINI_API_KEY))
    }

    @Test
    fun roundTripsAndNeverWritesPlainText() {
        assertEquals(PlatformResult.Ok(Unit), storage.put(SecretNames.GEMINI_API_KEY, key))
        assertEquals(PlatformResult.Ok(key), FileSecureStorage(fs, path, XorCipher).get(SecretNames.GEMINI_API_KEY))
        val onDisk = fs.read(path)
        assertTrue(onDisk.decodeToString().startsWith("AFS1"))
        assertFalse(key in onDisk.decodeToString(), "plain text on disk")
    }

    @Test
    fun writesAtomicallyThroughATempFile() {
        storage.put(SecretNames.GEMINI_API_KEY, key)
        assertEquals(listOf("write $path.tmp", "move $path.tmp -> $path"), fs.log.filter { !it.startsWith("mkdirs") })
    }

    @Test
    fun removeKeepsOtherSecrets() {
        storage.put(SecretNames.GEMINI_API_KEY, key)
        storage.put("other", "x")
        assertEquals(PlatformResult.Ok(Unit), storage.remove(SecretNames.GEMINI_API_KEY))
        assertEquals(PlatformResult.Ok(null), storage.get(SecretNames.GEMINI_API_KEY))
        assertEquals(PlatformResult.Ok("x"), storage.get("other"))
    }

    @Test
    fun undecryptableFileFailsWithoutLeakingAndIsReplacedByTheNextPut() {
        fs.createDirectories("/home/a/.autoflow")
        // Decrypts, but to broken JSON that contains the secret: the message must not quote it.
        fs.writeDurably(path, "AFS1".encodeToByteArray() + XorCipher.seal("{\"gemini.apiKey\":\"$key".encodeToByteArray()))
        val failed = assertIs<PlatformResult.Failed>(storage.get(SecretNames.GEMINI_API_KEY))
        assertFalse(key in failed.reason)

        fs.writeDurably(path, "not ours".encodeToByteArray())
        assertIs<PlatformResult.Failed>(storage.get(SecretNames.GEMINI_API_KEY))
        assertEquals(PlatformResult.Ok(Unit), storage.put(SecretNames.GEMINI_API_KEY, key))
        assertEquals(PlatformResult.Ok(key), storage.get(SecretNames.GEMINI_API_KEY))
    }

    @Test
    fun readErrorNeverOverwrites() {
        storage.put(SecretNames.GEMINI_API_KEY, key)
        fs.failWhen = { op, _ -> op == "read" }
        fs.log.clear()
        assertIs<PlatformResult.Failed>(storage.put(SecretNames.GEMINI_API_KEY, "new"))
        assertTrue(fs.log.isEmpty(), "nothing written: ${fs.log}")
    }

    @Test
    fun noCipherIsUnsupportedEverywhere() {
        val none = FileSecureStorage(fs, path, cipher = null, unsupportedReason = "needs Windows")
        assertEquals(PlatformResult.Unsupported("needs Windows"), none.get(SecretNames.GEMINI_API_KEY))
        assertEquals(PlatformResult.Unsupported("needs Windows"), none.put(SecretNames.GEMINI_API_KEY, key))
        assertEquals(PlatformResult.Unsupported("needs Windows"), none.remove(SecretNames.GEMINI_API_KEY))
        assertFalse(fs.exists(path))
    }

    @Test
    fun blankValueIsRejected() {
        assertIs<PlatformResult.Failed>(storage.put(SecretNames.GEMINI_API_KEY, "  "))
        assertFalse(fs.exists(path))
    }

    private object XorCipher : SecretCipher {
        override fun seal(plain: ByteArray) = ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }
        override fun open(sealed: ByteArray) = seal(sealed)
    }
}
