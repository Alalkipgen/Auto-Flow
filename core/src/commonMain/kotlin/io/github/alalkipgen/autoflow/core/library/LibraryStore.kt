package io.github.alalkipgen.autoflow.core.library

import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Loads and saves the [Library] from `<libraryRoot>/library.json` (5A-7, studio-v2.md §6).
 *
 * [libraryRoot] = `<app data>/library`.
 * File assets sit in `<libraryRoot>/files/`.
 * Projects never reference library paths — Apply copies each file into the project (D-39).
 */
class LibraryStore(
    private val fs: FileSystem,
    private val libraryRoot: String,
) {
    private val jsonPath get() = joinPath(libraryRoot, "library.json")

    private val _lexicon = MutableStateFlow(Lexicon(readLibrary().lexicon.toList()))
    /** The last successfully loaded/saved pronunciation snapshot, shared by speech, jobs and rendering. */
    val lexicon: StateFlow<Lexicon> = _lexicon.asStateFlow()

    fun load(): Library = readLibrary().also { _lexicon.value = Lexicon(it.lexicon.toList()) }

    private fun readLibrary(): Library {
        if (!fs.exists(jsonPath)) return Library()
        return runCatching {
            CoreJson.decodeFromString(Library.serializer(), fs.read(jsonPath).decodeToString())
        }.getOrDefault(Library())
    }

    fun save(lib: Library) {
        fs.createDirectories(libraryRoot)
        val tmp = "$jsonPath.tmp"
        fs.writeDurably(tmp, CoreJson.encodeToString(Library.serializer(), lib).encodeToByteArray())
        fs.atomicMove(tmp, jsonPath)
        _lexicon.value = Lexicon(lib.lexicon.toList())
    }

    /** Path to a library file asset, given its relative path (e.g. `files/logo.png`). */
    fun assetPath(relative: String): String = joinPath(libraryRoot, relative)

    /**
     * Copies a library asset into [destDir] using the filename component.
     * Returns the destination path.
     */
    fun copyAsset(relative: String, destDir: String): String {
        val src = assetPath(relative)
        val name = relative.substringAfterLast('/')
        val dest = joinPath(destDir, name)
        fs.copyDurably(src, dest)
        return dest
    }

    /**
     * Copies an arbitrary file [srcAbsolute] into the Library's `files/` directory under [destName].
     * Returns the relative path (`files/<destName>`) for storage in a [BrandKit] or similar.
     * Silently returns null when [srcAbsolute] does not exist.
     */
    fun copyAssetFromPath(srcAbsolute: String, destName: String): String? {
        if (!fs.exists(srcAbsolute)) return null
        val filesDir = joinPath(libraryRoot, "files")
        fs.createDirectories(filesDir)
        val dest = joinPath(filesDir, destName)
        fs.copyDurably(srcAbsolute, dest)
        return "files/$destName"
    }
}
