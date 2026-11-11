package io.github.alalkipgen.autoflow.platform

import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/** D-25 cipher with a software AES key standing in for the Android Keystore (same JCE calls, other provider). */
class AesGcmCipherTest {
    private class SoftKeys : AesGcmCipher.KeySource {
        var key: SecretKey? = null
        var created = 0

        override fun existing(): SecretKey? = key

        override fun create(): SecretKey {
            created++
            return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
        }
    }

    private val keys = SoftKeys()
    private val cipher = AesGcmCipher(keys)
    private val plain = """{"gemini.apiKey":"test-key-not-real-789"}""".encodeToByteArray()

    @Test
    fun sealsWithAFreshIvAndOpensAgain() {
        val a = cipher.seal(plain)
        val b = cipher.seal(plain)
        assertEquals(1, keys.created, "one key, created on the first seal")
        assertEquals(1.toByte(), a[0])
        assertEquals(1 + 12 + plain.size + 16, a.size)
        assertNotEquals(a.copyOfRange(1, 13).toList(), b.copyOfRange(1, 13).toList(), "every seal gets a new IV")
        assertContentEquals(plain, cipher.open(a))
        assertContentEquals(plain, AesGcmCipher(keys).open(b))
    }

    @Test
    fun tamperedOrForeignBlobsDoNotOpen() {
        val sealed = cipher.seal(plain)
        val flipped = sealed.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertFailsWith<GeneralSecurityException> { cipher.open(flipped) }
        assertFailsWith<GeneralSecurityException> { cipher.open(sealed.copyOf(sealed.size - 1)) }
        assertFailsWith<GeneralSecurityException> { cipher.open(byteArrayOf(2) + sealed.copyOfRange(1, sealed.size)) }
        assertFailsWith<GeneralSecurityException> { AesGcmCipher(SoftKeys().apply { create() }).open(sealed) }
    }

    @Test
    fun aMissingKeyFailsToOpenAndTheNextSealMakesANewOne() {
        val sealed = cipher.seal(plain)
        keys.key = null
        assertFailsWith<GeneralSecurityException> { cipher.open(sealed) }
        assertEquals(1, keys.created, "opening never creates a key")
        assertContentEquals(plain, cipher.open(cipher.seal(plain)))
        assertEquals(2, keys.created)
    }

    @Test
    fun aKeyTheProviderRejectsIsReplacedOnSeal() {
        keys.key = SecretKeySpec(ByteArray(7), "AES")
        assertContentEquals(plain, cipher.open(cipher.seal(plain)))
        assertEquals(1, keys.created)
    }

    @Test
    fun fileSecureStorageKeepsTheKeyOutOfThePlainFileAndRecoversFromALostKey() {
        val fs = InMemoryFileSystem()
        val path = "/data/files/autoflow/secrets.bin"
        val storage = FileSecureStorage(fs, path, cipher)
        val secret = "test-key-not-real-789"
        assertEquals(PlatformResult.Ok(null), storage.get(SecretNames.GEMINI_API_KEY))
        assertEquals(PlatformResult.Ok(Unit), storage.put(SecretNames.GEMINI_API_KEY, secret))
        assertEquals(PlatformResult.Ok(secret), FileSecureStorage(fs, path, AesGcmCipher(keys)).get(SecretNames.GEMINI_API_KEY))
        assertFalse(fs.read(path).decodeToString().contains(secret))

        // App data restored onto another phone: the file is there, the Keystore key is not.
        keys.key = null
        val lost = storage.get(SecretNames.GEMINI_API_KEY)
        assertIs<PlatformResult.Failed>(lost)
        assertFalse(lost.reason.contains(secret))
        assertEquals(PlatformResult.Ok(Unit), storage.put(SecretNames.GEMINI_API_KEY, "test-key-not-real-000"))
        assertEquals(PlatformResult.Ok("test-key-not-real-000"), storage.get(SecretNames.GEMINI_API_KEY))
        assertEquals(PlatformResult.Ok(Unit), storage.remove(SecretNames.GEMINI_API_KEY))
        assertEquals(PlatformResult.Ok(null), storage.get(SecretNames.GEMINI_API_KEY))
    }
}
