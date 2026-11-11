package io.github.alalkipgen.autoflow.ui.projects

import io.github.alalkipgen.autoflow.core.CoreError
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.getOrNull
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.workflow.ArtifactLookup
import kotlin.time.Clock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer

/**
 * File access for the open project ([CurrentProject]): lyrics in `source/lyrics.txt`, the song in
 * `source/song.<ext>` (specs/project-format.md), artifacts, and saves that also refresh the Dashboard row. Every method answers [noProject] when nothing is open.
 */
class ProjectSession(
    private val fs: FileSystem,
    private val store: ProjectStore,
    private val index: ProjectIndex,
    private val current: CurrentProject,
    private val clock: Clock = Clock.System,
) {
    val open: OpenProject? get() = current.state.value

    /** The open project; screens rebuild from it after every save. */
    val state: StateFlow<OpenProject?> get() = current.state

    fun artifacts(): ArtifactStore? = open?.let { ArtifactStore(fs, it.dir, clock) }

    /** Sidecar lookup for stale checks; `null` for missing or unreadable sidecars. */
    fun lookup(): ArtifactLookup {
        val artifacts = artifacts()
        return ArtifactLookup { id -> artifacts?.get(id)?.getOrNull() }
    }

    fun readLyrics(): String = open?.let {
        val path = lyricsPath(it.dir)
        try {
            if (fs.exists(path)) fs.read(path).decodeToString() else ""
        } catch (e: Exception) {
            ""
        }
    } ?: ""

    fun writeLyrics(text: String): CoreResult<Unit> {
        val dir = open?.dir ?: return noProject()
        val path = lyricsPath(dir)
        return try {
            fs.createDirectories(joinPath(dir, SOURCE_DIR))
            fs.writeDurably("$path.tmp", text.encodeToByteArray())
            fs.atomicMove("$path.tmp", path)
            CoreResult.Ok(Unit)
        } catch (e: Exception) {
            CoreResult.Err(CoreError.Io(path, e.message ?: e.toString()))
        }
    }

    /** The imported song (`source/song.<ext>`), or null when there is none. */
    fun songPath(): String? {
        val src = joinPath(open?.dir ?: return null, SOURCE_DIR)
        return try {
            fs.list(src).firstOrNull(::isSongName)?.let { joinPath(src, it) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Copies the audio file at [from] to `source/song.<ext>` (temp file + atomic rename) and returns its path. A song
     * with another extension is moved to `.cache/replaced/` so exactly one song stays in `source/` (the app never
     * deletes files).
     */
    fun importSong(from: String): CoreResult<String> {
        val dir = open?.dir ?: return noProject()
        val ext = from.substringAfterLast('.', "").lowercase()
        if (ext !in SONG_EXTENSIONS) {
            return CoreResult.Err(CoreError.InvalidInput("Not a song file (use ${SONG_EXTENSIONS.joinToString(", ")})"))
        }
        val src = joinPath(dir, SOURCE_DIR)
        val path = joinPath(src, "$SONG_NAME.$ext")
        return try {
            val bytes = fs.read(from)
            if (bytes.isEmpty()) return CoreResult.Err(CoreError.InvalidInput("The song file is empty"))
            fs.createDirectories(src)
            fs.writeDurably("$path.tmp", bytes)
            val others = fs.list(src).filter { isSongName(it) && it != "$SONG_NAME.$ext" }
            if (others.isNotEmpty()) {
                val replaced = joinPath(joinPath(dir, CACHE_DIR), "replaced")
                fs.createDirectories(replaced)
                others.forEach { fs.atomicMove(joinPath(src, it), joinPath(replaced, it)) }
            }
            fs.atomicMove("$path.tmp", path)
            CoreResult.Ok(path)
        } catch (e: Exception) {
            CoreResult.Err(CoreError.Io(path, e.message ?: e.toString()))
        }
    }

    /** Saves [project], makes it current and refreshes its Dashboard row (progress) without reordering the list. */
    fun save(project: Project): CoreResult<Project> {
        val dir = open?.dir ?: return noProject()
        return when (val r = store.save(dir, project)) {
            is CoreResult.Ok -> r.also {
                current.set(OpenProject(dir, r.value))
                index.record(dir, r.value, opened = false)
            }
            is CoreResult.Err -> r
        }
    }

    /** Reads a JSON artifact (analysis, storyboard, spec) with [CoreJson]. */
    fun <T> readJson(id: ArtifactId, serializer: KSerializer<T>): CoreResult<T> {
        val bytes = when (val r = artifacts()?.readBytes(id) ?: return noProject()) {
            is CoreResult.Ok -> r.value
            is CoreResult.Err -> return r
        }
        return try {
            CoreResult.Ok(CoreJson.decodeFromString(serializer, bytes.decodeToString()))
        } catch (e: IllegalArgumentException) {
            CoreResult.Err(CoreError.Corrupt(id.value, e.message ?: "invalid JSON"))
        }
    }

    private fun noProject() = CoreResult.Err(CoreError.InvalidInput("No project is open"))

    companion object {
        const val SOURCE_DIR = "source"
        const val CACHE_DIR = ".cache"
        const val SONG_NAME = "song"
        val SONG_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac")

        fun isSongName(name: String) =
            name.substringBeforeLast('.', "") == SONG_NAME && name.substringAfterLast('.').lowercase() in SONG_EXTENSIONS
        fun lyricsPath(dir: String) = joinPath(joinPath(dir, SOURCE_DIR), "lyrics.txt")
    }
}
