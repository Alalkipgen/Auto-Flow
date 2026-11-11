package io.github.alalkipgen.autoflow.platform

import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM [SecretCipher] (D-25). On Android the key lives in the Android Keystore and never leaves it; tests use
 * a plain JCE key. Sealed bytes: version `1` · 12-byte IV · ciphertext + 16-byte tag, with [AAD] bound in, and a
 * fresh random IV for every seal (the provider picks it).
 *
 * No key on [open] (app data restored onto another phone, Keystore cleared) throws, so `FileSecureStorage` reports
 * the file as unreadable; the next save then creates a new key. A key the provider rejects is replaced on seal.
 */
class AesGcmCipher(private val keys: KeySource) : SecretCipher {
    /** Where the key comes from: [existing] null = not created yet; [create] makes (or replaces) it. */
    interface KeySource {
        fun existing(): SecretKey?

        fun create(): SecretKey
    }

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = try {
            encryptor(keys.existing() ?: keys.create())
        } catch (_: InvalidKeyException) {
            encryptor(keys.create())
        }
        val iv = cipher.iv
        if (iv == null || iv.size != IV_BYTES) throw GeneralSecurityException("Unexpected GCM IV")
        return byteArrayOf(VERSION) + iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < HEADER + TAG_BYTES || sealed[0] != VERSION) throw GeneralSecurityException("Not an AES-GCM secrets blob")
        val key = keys.existing() ?: throw GeneralSecurityException("The secrets key is gone")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, sealed, 1, IV_BYTES))
        cipher.updateAAD(AAD)
        return cipher.doFinal(sealed, HEADER, sealed.size - HEADER)
    }

    private fun encryptor(key: SecretKey): Cipher = Cipher.getInstance(TRANSFORMATION).apply {
        init(Cipher.ENCRYPT_MODE, key)
        updateAAD(AAD)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val HEADER = 1 + IV_BYTES
        val AAD = "io.github.alalkipgen.autoflow/secrets/v1".encodeToByteArray()
    }
}
