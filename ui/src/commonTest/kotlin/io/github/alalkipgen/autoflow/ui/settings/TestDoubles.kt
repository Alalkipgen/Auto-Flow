package io.github.alalkipgen.autoflow.ui.settings

import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.platform.SecureStorage

/** Map-backed [SecureStorage]; [unsupported] mimics a non-Windows desktop. */
class MemorySecureStorage(private val unsupported: Boolean = false) : SecureStorage {
    val values = mutableMapOf<String, String>()

    override fun get(name: String): PlatformResult<String?> =
        if (unsupported) PlatformResult.Unsupported("needs Windows") else PlatformResult.Ok(values[name])

    override fun put(name: String, value: String): PlatformResult<Unit> =
        if (unsupported) PlatformResult.Unsupported("needs Windows") else PlatformResult.Ok(Unit).also { values[name] = value }

    override fun remove(name: String): PlatformResult<Unit> =
        if (unsupported) PlatformResult.Unsupported("needs Windows") else PlatformResult.Ok(Unit).also { values -= name }
}
