package io.github.alalkipgen.autoflow.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.alalkipgen.autoflow.core.io.FileSystem
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The Android [SecureStorage] (D-25): the same `secrets.bin` as the desktop, sealed with [AesGcmCipher] under a
 * non-exportable AES-256 key in the Android Keystore. The file is left out of Android backups (manifest rules), since
 * the key never leaves this phone.
 */
fun androidSecureStorage(dirs: AppDirs, fs: FileSystem = JvmFs()): SecureStorage = FileSecureStorage(
    fs = fs,
    path = dirs.secrets,
    cipher = AesGcmCipher(AndroidKeystoreKeys()),
    unsupportedReason = "The Android Keystore is not available on this phone",
)

/** AES-256-GCM key [alias] in `AndroidKeyStore`: encrypt + decrypt only, random IVs required, no user auth. */
class AndroidKeystoreKeys(private val alias: String = ALIAS) : AesGcmCipher.KeySource {
    private fun store(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun existing(): SecretKey? = (store().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    /** Generating under an existing alias replaces that key. */
    override fun create(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "io.github.alalkipgen.autoflow.secrets.v1"
    }
}
