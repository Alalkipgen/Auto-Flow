package io.github.alalkipgen.autoflow.ui.jobs

import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.CoreJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Hides terminal render rows only. The render queue, output, sidecar and Export UI keep their own state. */
class RenderHistory(private val fs: FileSystem, private val root: String) {
    private val path = joinPath(root, "removed-render-history.json")
    private val removed = MutableStateFlow(runCatching { read() }.getOrDefault(emptySet()))
    val removedIds = removed.asStateFlow()

    fun remove(identity: String) {
        fs.createDirectories(root)
        fs.withFolderLock(root) {
            val next = read() + identity
            fs.writeDurably("$path.tmp", CoreJson.encodeToString(next).encodeToByteArray())
            fs.atomicMove("$path.tmp", path)
            removed.value = next
        }
    }

    private fun read(): Set<String> =
        if (fs.exists(path)) CoreJson.decodeFromString(fs.read(path).decodeToString()) else emptySet()
}