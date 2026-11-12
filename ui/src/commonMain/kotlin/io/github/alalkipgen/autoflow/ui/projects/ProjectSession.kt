package io.github.alalkipgen.autoflow.ui.projects

import io.github.alalkipgen.autoflow.core.CoreError
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.getOrNull
import io.github.alalkipgen.autoflow.core.hash.Sha256
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.lyrics.LyricTimings
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.workflow.ArtifactLookup
import io.github.alalkipgen.autoflow.core.workflow.SongBeats
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

    /** The imported movie (`source/movie.<ext>`, Movie Recap), or null. */
    fun moviePath(): String? {
        val src = joinPath(open?.dir ?: return null, SOURCE_DIR)
        return try {
            fs.list(src).firstOrNull(::isMovieName)?.let { joinPath(src, it) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Streams the movie at [from] into `source/movie.<ext>` (temp file + atomic rename; D-33: never in memory). Like
     * [importSong], a movie with another extension moves to `.cache/replaced/`. Answers the new path.
     */
    fun importMovie(from: String): CoreResult<String> {
        val dir = open?.dir ?: return noProject()
        val ext = from.substringAfterLast('.', "").lowercase()
        if (ext !in MOVIE_EXTENSIONS) {
            return CoreResult.Err(CoreError.InvalidInput("Not a movie file (use ${MOVIE_EXTENSIONS.joinToString(", ")})"))
        }
        val src = joinPath(dir, SOURCE_DIR)
        val path = joinPath(src, "$MOVIE_NAME.$ext")
        return try {
            if (fs.size(from) == 0L) return CoreResult.Err(CoreError.InvalidInput("The movie file is empty"))
            fs.createDirectories(src)
            fs.copyDurably(from, "$path.tmp")
            val others = fs.list(src).filter { isMovieName(it) && it != "$MOVIE_NAME.$ext" }
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

    /** A text file in `source/` (e.g. `summary.txt`), or "" when missing or unreadable. */
    fun readSource(name: String): String = open?.let {
        val path = joinPath(joinPath(it.dir, SOURCE_DIR), name)
        try {
            if (fs.exists(path)) fs.read(path).decodeToString() else ""
        } catch (e: Exception) {
            ""
        }
    } ?: ""

    /** Writes `source/<name>` (temp file + atomic rename). */
    fun writeSource(name: String, text: String): CoreResult<Unit> {
        val dir = open?.dir ?: return noProject()
        val path = joinPath(joinPath(dir, SOURCE_DIR), name)
        return try {
            fs.createDirectories(joinPath(dir, SOURCE_DIR))
            fs.writeDurably("$path.tmp", text.encodeToByteArray())
            fs.atomicMove("$path.tmp", path)
            CoreResult.Ok(Unit)
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

    /** Bytes of a file in or outside the project (e.g. the song); null when it cannot be read. */
    fun readFile(path: String): ByteArray? = try {
        fs.read(path)
    } catch (e: Exception) {
        null
    }

    /** `<project>/.cache/<name>` with the folder created; null without a project or when it cannot be made. */
    fun cacheFile(name: String): String? {
        val cache = joinPath(open?.dir ?: return null, CACHE_DIR)
        return try {
            fs.createDirectories(cache)
            joinPath(cache, name)
        } catch (e: Exception) {
            null
        }
    }

    /** SHA-256 hex of the song file, or null without a readable song. */
    fun songSha256(): String? = songPath()?.let(::readFile)?.takeIf { it.isNotEmpty() }?.let { Sha256.hex(it) }

    /** Cached beats (`.cache/beats.json`, D-32) when they were found for the current song, else null. */
    fun readBeats(): SongBeats? {
        val path = cacheFile(BEATS_FILE) ?: return null
        val bytes = readFile(path) ?: return null
        val beats = try {
            CoreJson.decodeFromString(SongBeats.serializer(), bytes.decodeToString())
        } catch (e: IllegalArgumentException) {
            return null
        }
        return beats.takeIf { it.songSha256 == songSha256() }
    }

    fun writeBeats(beats: SongBeats): CoreResult<Unit> {
        val path = cacheFile(BEATS_FILE) ?: return noProject()
        return try {
            fs.writeDurably("$path.tmp", CoreJson.encodeToString(SongBeats.serializer(), beats).encodeToByteArray())
            fs.atomicMove("$path.tmp", path)
            CoreResult.Ok(Unit)
        } catch (e: Exception) {
            CoreResult.Err(CoreError.Io(path, e.message ?: e.toString()))
        }
    }

    /** The saved line timings (`project.lyricTiming`), or null when there are none or they cannot be read. */
    fun readLyricTimings(): LyricTimings? {
        val id = open?.project?.lyricTiming ?: return null
        return readJson(id, LyricTimings.serializer()).getOrNull()
    }

    /** Stores [timings] as a `tx` artifact and points `project.lyricTiming` at it (D-30); null clears the pointer. */
    fun saveLyricTimings(timings: LyricTimings?): CoreResult<Project> {
        val project = open?.project ?: return noProject()
        if (timings == null) return if (project.lyricTiming == null) CoreResult.Ok(project) else save(project.copy(lyricTiming = null))
        val bytes = CoreJson.encodeToString(LyricTimings.serializer(), timings).encodeToByteArray()
        val artifact = when (val r = artifacts()!!.put(bytes, ArtifactKind.TRANSCRIPT, "json", provider = timings.source)) {
            is CoreResult.Ok -> r.value
            is CoreResult.Err -> return r
        }
        return save(project.copy(lyricTiming = artifact.id))
    }

    private fun noProject() = CoreResult.Err(CoreError.InvalidInput("No project is open"))

    companion object {
        const val SOURCE_DIR = "source"
        const val CACHE_DIR = ".cache"
        const val SONG_NAME = "song"
        const val BEATS_FILE = "beats.json"
        val SONG_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac")
        const val MOVIE_NAME = "movie"
        val MOVIE_EXTENSIONS = setOf("mp4", "mkv", "mov", "webm", "m4v")

        fun isMovieName(name: String) =
            name.substringBeforeLast('.', "") == MOVIE_NAME && name.substringAfterLast('.').lowercase() in MOVIE_EXTENSIONS

        fun isSongName(name: String) =
            name.substringBeforeLast('.', "") == SONG_NAME && name.substringAfterLast('.').lowercase() in SONG_EXTENSIONS
        fun lyricsPath(dir: String) = joinPath(joinPath(dir, SOURCE_DIR), "lyrics.txt")
    }
}
