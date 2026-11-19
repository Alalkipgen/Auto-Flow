package io.github.alalkipgen.autoflow.ui.projects

import io.github.alalkipgen.autoflow.core.CoreError
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.getOrNull
import io.github.alalkipgen.autoflow.core.hash.Sha256
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.ProjectWrites
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

    fun writeLyrics(text: String): CoreResult<Unit> = withSourceWriter { dir ->
        val path = lyricsPath(dir)
        try {
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
    fun importSong(from: String): CoreResult<String> = withSourceWriter { dir ->
        val ext = from.substringAfterLast('.', "").lowercase()
        if (ext !in SONG_EXTENSIONS) {
            return@withSourceWriter CoreResult.Err(CoreError.InvalidInput("Not a song file (use ${SONG_EXTENSIONS.joinToString(", ")})"))
        }
        val src = joinPath(dir, SOURCE_DIR)
        val path = joinPath(src, "$SONG_NAME.$ext")
        try {
            val bytes = fs.read(from)
            if (bytes.isEmpty()) return@withSourceWriter CoreResult.Err(CoreError.InvalidInput("The song file is empty"))
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
    fun importMovie(from: String): CoreResult<String> = withSourceWriter { dir ->
        val ext = from.substringAfterLast('.', "").lowercase()
        if (ext !in MOVIE_EXTENSIONS) {
            return@withSourceWriter CoreResult.Err(CoreError.InvalidInput("Not a movie file (use ${MOVIE_EXTENSIONS.joinToString(", ")})"))
        }
        val src = joinPath(dir, SOURCE_DIR)
        val path = joinPath(src, "$MOVIE_NAME.$ext")
        try {
            if (fs.size(from) == 0L) return@withSourceWriter CoreResult.Err(CoreError.InvalidInput("The movie file is empty"))
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
    fun writeSource(name: String, text: String): CoreResult<Unit> = withSourceWriter { dir ->
        val path = joinPath(joinPath(dir, SOURCE_DIR), name)
        try {
            fs.createDirectories(joinPath(dir, SOURCE_DIR))
            fs.writeDurably("$path.tmp", text.encodeToByteArray())
            fs.atomicMove("$path.tmp", path)
            CoreResult.Ok(Unit)
        } catch (e: Exception) {
            CoreResult.Err(CoreError.Io(path, e.message ?: e.toString()))
        }
    }

    private inline fun <T> withSourceWriter(write: (String) -> CoreResult<T>): CoreResult<T> {
        val target = open ?: return noProject()
        val release = ProjectWrites.writer(fs.canonicalPath(target.dir)) ?: return noProject()
        try {
            if (store.open(target.dir).getOrNull()?.id != target.project.id) return noProject()
            return write(target.dir)
        } finally { release() }
    }

    /**
     * Saves [project], makes it current and refreshes its Dashboard row (progress) without reordering the list.
     * Refused when [project] is not the open project (a copy taken before the user switched projects must never be
     * written into the new one's folder, audit F03) or when it is older than the file ([CoreError.Conflict], F04).
     */
    fun save(project: Project): CoreResult<Project> {
        val open = open ?: return noProject()
        if (project.id != open.project.id) return CoreResult.Err(CoreError.InvalidInput(OTHER_PROJECT))
        return published(open.dir, store.save(open.dir, project))
    }

    /**
     * Read-modify-write of the project in [dir] — the folder the caller captured, which need not be the open one any
     * more (audit F03): [change] gets that folder's `project.json` as it is now, under its folder lock (F04).
     * [projectId] guards against a different project having been put in that folder. The open project is
     * refreshed only when it is that folder.
     */
    fun update(dir: String, projectId: String, change: (Project) -> Project): CoreResult<Project> {
        var other = false
        val r = store.update(dir) { p -> if (p.id != projectId) p.also { other = true } else change(p) }
        if (other) return CoreResult.Err(CoreError.InvalidInput(OTHER_PROJECT))
        return published(dir, r)
    }

    /** [ProjectStore.open] of [dir] without making it current (a project the user may have switched away from). */
    fun projectAt(dir: String): Project? = store.open(dir).getOrNull()

    private fun published(dir: String, r: CoreResult<Project>): CoreResult<Project> {
        if (r is CoreResult.Ok) {
            if (open?.dir == dir) current.set(OpenProject(dir, r.value))
            index.record(dir, r.value, opened = false)
        }
        return r
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
        const val OTHER_PROJECT = "This change belongs to another project; it was not saved"
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
