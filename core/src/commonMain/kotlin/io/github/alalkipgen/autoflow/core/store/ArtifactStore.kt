package io.github.alalkipgen.autoflow.core.store

import io.github.alalkipgen.autoflow.core.CoreError
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.hash.Sha256
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.Artifact
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import kotlin.time.Clock

/**
 * Immutable, content-hash named artifacts under `<project>/artifacts/` (specs/project-format.md §1).
 * Data file `<kind>_<hash12>.<ext>` plus sidecar `<kind>_<hash12>.meta.json`. Files are written to a `.tmp-` name and
 * renamed into place, and an existing artifact file is never rewritten. Putting identical bytes again returns the
 * first record unchanged (dedupe).
 */
class ArtifactStore(
    private val fs: FileSystem,
    projectDir: String,
    private val clock: Clock = Clock.System,
) {
    private val dir = joinPath(projectDir, ARTIFACTS_DIR)

    fun put(
        bytes: ByteArray,
        kind: ArtifactKind,
        ext: String,
        shot: Int? = null,
        inputs: Map<String, String> = emptyMap(),
        provider: String? = null,
        model: String? = null,
        note: String? = null,
    ): CoreResult<Artifact> {
        if (!EXT.matches(ext)) return CoreResult.Err(CoreError.InvalidInput("Invalid file extension: \"$ext\""))
        val hex = Sha256.hex(bytes)
        val id = ArtifactId.of(kind, hex)
        return io(metaPath(id)) {
            fs.createDirectories(dir)
            val metaPath = metaPath(id)
            if (fs.exists(metaPath)) {
                val existing = when (val r = decodeMeta(metaPath, id)) {
                    is CoreResult.Ok -> r.value
                    is CoreResult.Err -> return r
                }
                return ensureData(joinPath(dir, existing.file), bytes, hex, id) ?: CoreResult.Ok(existing)
            }
            val file = "$id.$ext"
            ensureData(joinPath(dir, file), bytes, hex, id)?.let { return it }
            val artifact = Artifact(
                id = id, kind = kind, file = file, shot = shot, inputs = inputs, provider = provider, model = model,
                createdAt = clock.now(), note = note,
            )
            writeNew(metaPath, CoreJson.encodeToString(artifact).encodeToByteArray())
            CoreResult.Ok(artifact)
        }
    }

    fun get(id: ArtifactId): CoreResult<Artifact> = io(metaPath(id)) {
        val path = metaPath(id)
        if (!fs.exists(path)) CoreResult.Err(CoreError.NotFound(path)) else decodeMeta(path, id)
    }

    fun readBytes(id: ArtifactId): CoreResult<ByteArray> = when (val r = get(id)) {
        is CoreResult.Err -> r
        is CoreResult.Ok -> io(joinPath(dir, r.value.file)) {
            val path = joinPath(dir, r.value.file)
            if (!fs.exists(path)) CoreResult.Err(CoreError.NotFound(path)) else CoreResult.Ok(fs.read(path))
        }
    }

    /** True when both the sidecar and the data file are present. */
    fun exists(id: ArtifactId): Boolean = (get(id) as? CoreResult.Ok)?.let { fs.exists(joinPath(dir, it.value.file)) } ?: false

    /** Artifacts recorded for [shot], oldest first. A sidecar that cannot be read fails the whole call. */
    fun listByShot(shot: Int): CoreResult<List<Artifact>> = io(dir) {
        val result = mutableListOf<Artifact>()
        for (name in fs.list(dir)) {
            if (name.startsWith('.') || !name.endsWith(META_SUFFIX)) continue
            val id = ArtifactId(name.removeSuffix(META_SUFFIX))
            when (val r = decodeMeta(joinPath(dir, name), id)) {
                is CoreResult.Ok -> if (r.value.shot == shot) result += r.value
                is CoreResult.Err -> return r
            }
        }
        CoreResult.Ok(result.sortedWith(compareBy({ it.createdAt }, { it.id.value })))
    }

    /** Writes the data file if missing; returns an error if a different file already holds this id. */
    private fun ensureData(path: String, bytes: ByteArray, hex: String, id: ArtifactId): CoreResult.Err? {
        if (!fs.exists(path)) {
            writeNew(path, bytes)
            return null
        }
        return if (Sha256.hex(fs.read(path)) == hex) null else CoreResult.Err(CoreError.HashCollision(id))
    }

    private fun writeNew(path: String, bytes: ByteArray) {
        val tmp = joinPath(dir, ".tmp-" + path.substringAfterLast('/'))
        fs.writeDurably(tmp, bytes)
        fs.atomicMove(tmp, path)
    }

    private fun decodeMeta(path: String, id: ArtifactId): CoreResult<Artifact> = try {
        val artifact = CoreJson.decodeFromString<Artifact>(fs.read(path).decodeToString())
        if (artifact.id != id) {
            CoreResult.Err(CoreError.Corrupt(path, "sidecar id ${artifact.id} does not match file name"))
        } else {
            CoreResult.Ok(artifact)
        }
    } catch (e: IllegalArgumentException) {
        CoreResult.Err(CoreError.Corrupt(path, e.message ?: "invalid artifact sidecar"))
    }

    private fun metaPath(id: ArtifactId) = joinPath(dir, "$id$META_SUFFIX")

    private inline fun <T> io(path: String, block: () -> CoreResult<T>): CoreResult<T> = try {
        block()
    } catch (e: Exception) {
        CoreResult.Err(CoreError.Io(path, e.message ?: e.toString()))
    }

    companion object {
        const val ARTIFACTS_DIR = "artifacts"
        const val META_SUFFIX = ".meta.json"
        private val EXT = Regex("[a-z0-9]{1,8}")
    }
}
