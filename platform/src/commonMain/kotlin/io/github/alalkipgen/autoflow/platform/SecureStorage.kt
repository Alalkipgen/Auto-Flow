package io.github.alalkipgen.autoflow.platform

import io.github.alalkipgen.autoflow.core.io.FileSystem
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Small secrets (the Gemini API key), encrypted at rest for the current OS user (D-19). Values never appear in
 * results, messages or logs. On desktop use `desktopSecureStorage(appDirs)`.
 */
interface SecureStorage {
    /** `Ok(null)` = not set. */
    fun get(name: String): PlatformResult<String?>

    fun put(name: String, value: String): PlatformResult<Unit>

    fun remove(name: String): PlatformResult<Unit>
}

object SecretNames {
    const val GEMINI_API_KEY = "gemini.apiKey"
}

/** Encrypts and decrypts for the current user only. Implementations may throw. */
interface SecretCipher {
    fun seal(plain: ByteArray): ByteArray

    fun open(sealed: ByteArray): ByteArray
}

/**
 * [SecureStorage] as one file at [path]: `AFS1` + [cipher]-sealed JSON object of name → value, written to `.tmp`
 * and renamed into place. [cipher] null = this OS has no supported cipher ([unsupportedReason]).
 *
 * A file that cannot be decrypted (copied from another Windows account, damaged) reads as `Failed`; the next [put]
 * replaces it, so the user can always enter the key again. An I/O error never causes an overwrite.
 */
class FileSecureStorage(
    private val fs: FileSystem,
    private val path: String,
    private val cipher: SecretCipher?,
    private val unsupportedReason: String = "Secure storage is not available on this system",
) : SecureStorage {
    override fun get(name: String): PlatformResult<String?> = when (val r = load()) {
        is Loaded.Ok -> PlatformResult.Ok(r.secrets[name])
        is Loaded.Unreadable -> PlatformResult.Failed(UNREADABLE)
        is Loaded.Error -> r.result
    }

    override fun put(name: String, value: String): PlatformResult<Unit> {
        if (value.isBlank()) return PlatformResult.Failed("The value is empty")
        val current = when (val r = load()) {
            is Loaded.Ok -> r.secrets
            is Loaded.Unreadable -> emptyMap()
            is Loaded.Error -> return r.result
        }
        return store(current + (name to value))
    }

    override fun remove(name: String): PlatformResult<Unit> = when (val r = load()) {
        is Loaded.Ok -> if (name in r.secrets) store(r.secrets - name) else PlatformResult.Ok(Unit)
        is Loaded.Unreadable -> store(emptyMap())
        is Loaded.Error -> r.result
    }

    private sealed interface Loaded {
        data class Ok(val secrets: Map<String, String>) : Loaded
        data object Unreadable : Loaded
        data class Error(val result: PlatformResult<Nothing>) : Loaded
    }

    private fun load(): Loaded {
        val c = cipher ?: return Loaded.Error(PlatformResult.Unsupported(unsupportedReason))
        val bytes = try {
            if (!fs.exists(path)) return Loaded.Ok(emptyMap())
            fs.read(path)
        } catch (e: Exception) {
            return Loaded.Error(PlatformResult.Failed("Cannot read the secrets file: ${e::class.simpleName}"))
        }
        if (bytes.size < MAGIC.size || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return Loaded.Unreadable
        return try {
            val plain = c.open(bytes.copyOfRange(MAGIC.size, bytes.size))
            try {
                Loaded.Ok(Json.decodeFromString(SECRETS, plain.decodeToString()))
            } finally {
                plain.fill(0)
            }
        } catch (_: LinkageError) {
            Loaded.Error(PlatformResult.Unsupported(unsupportedReason))
        } catch (_: Exception) {
            // Never include the exception message: a JSON error quotes the decrypted text.
            Loaded.Unreadable
        }
    }

    private fun store(secrets: Map<String, String>): PlatformResult<Unit> {
        val c = cipher ?: return PlatformResult.Unsupported(unsupportedReason)
        val plain = Json.encodeToString(SECRETS, secrets).encodeToByteArray()
        return try {
            val sealed = MAGIC + c.seal(plain)
            val dir = path.substringBeforeLast('/', "")
            if (dir.isNotEmpty()) fs.createDirectories(dir)
            val tmp = "$path.tmp"
            fs.writeDurably(tmp, sealed)
            fs.atomicMove(tmp, path)
            PlatformResult.Ok(Unit)
        } catch (_: LinkageError) {
            PlatformResult.Unsupported(unsupportedReason)
        } catch (e: Exception) {
            PlatformResult.Failed("Cannot save the secrets file: ${e::class.simpleName}")
        } finally {
            plain.fill(0)
        }
    }

    private companion object {
        val MAGIC = "AFS1".encodeToByteArray()
        val SECRETS = MapSerializer(String.serializer(), String.serializer())
        const val UNREADABLE = "The saved API key cannot be read on this account. Enter it again."
    }
}
