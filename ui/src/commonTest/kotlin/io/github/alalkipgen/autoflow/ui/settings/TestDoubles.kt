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

/** Scripted [ToolLocator]: [vlcDirs] are the folders that hold libvlc. */
class FakeTools(
    var ffmpeg: String? = null,
    var detectedVlc: String? = null,
    val vlcDirs: MutableSet<String> = mutableSetOf(),
    override val bundled: Boolean = false,
) : io.github.alalkipgen.autoflow.platform.ToolLocator {
    override fun findFfmpeg() = ffmpeg
    override fun findVlc() = detectedVlc
    override fun isVlcDir(dir: String) = dir in vlcDirs
}

/** [FilePicker] that answers with [folder] / [files], or [unsupported]. */
class FakePicker(var folder: String? = null, var files: List<String> = emptyList(), var unsupported: Boolean = false) :
    io.github.alalkipgen.autoflow.platform.FilePicker {
    override suspend fun pickFolder(title: String, startDir: String?): PlatformResult<String?> =
        if (unsupported) PlatformResult.Unsupported("no dialogs") else PlatformResult.Ok(folder)

    override suspend fun pickFiles(title: String, extensions: Set<String>, multiple: Boolean, startDir: String?): PlatformResult<List<String>> =
        if (unsupported) PlatformResult.Unsupported("no dialogs") else PlatformResult.Ok(files)
}
