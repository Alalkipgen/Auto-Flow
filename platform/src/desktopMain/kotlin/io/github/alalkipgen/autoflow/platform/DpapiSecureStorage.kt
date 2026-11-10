package io.github.alalkipgen.autoflow.platform

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt
import io.github.alalkipgen.autoflow.core.io.FileSystem

/**
 * The desktop [SecureStorage] (D-19): `~/.autoflow/secrets.bin` sealed with Windows DPAPI for the current user.
 * Every other OS gets `Unsupported` for all calls; there is no environment-variable fallback (AGENTS.md §1), so
 * Real AI mode needs Windows.
 */
fun desktopSecureStorage(
    dirs: AppDirs,
    fs: FileSystem = JvmFs(),
    osName: String = System.getProperty("os.name").orEmpty(),
): SecureStorage = FileSecureStorage(
    fs = fs,
    path = dirs.secrets,
    cipher = if (osName.startsWith("Windows")) DpapiCipher else null,
    unsupportedReason = "Saving the API key needs Windows (DPAPI). Use Fake AI mode on this system.",
)

/** `CryptProtectData` in current-user scope, never showing UI, with app-specific entropy. */
internal object DpapiCipher : SecretCipher {
    private val entropy = "io.github.alalkipgen.autoflow/secrets/v1".encodeToByteArray()

    override fun seal(plain: ByteArray): ByteArray =
        Crypt32Util.cryptProtectData(plain, entropy, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, "Auto Flow", null)

    override fun open(sealed: ByteArray): ByteArray =
        Crypt32Util.cryptUnprotectData(sealed, entropy, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null)
}
