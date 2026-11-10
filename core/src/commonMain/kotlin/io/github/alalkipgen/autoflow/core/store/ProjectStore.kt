package io.github.alalkipgen.autoflow.core.store

import io.github.alalkipgen.autoflow.core.CoreError
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.hash.withComputedHash
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Project folder create/open and atomic `project.json` writes (specs/project-format.md).
 * Save = serialize → `project.json.tmp` (fsync) → copy current to `project.json.bak` → rename tmp over `project.json`.
 * A crash at any step leaves a readable `project.json` (old or new) in place.
 */
class ProjectStore(
    private val fs: FileSystem,
    private val clock: Clock = Clock.System,
    private val newId: () -> String = ::randomId,
) {
    /** Creates the project folder [dir] itself (callers suggest `<parent>/<name>.autoflow`). [dir] must be empty or absent. */
    fun create(dir: String, name: String, mode: Mode, settings: ProjectSettings = ProjectSettings()): CoreResult<Project> {
        if (name.isBlank()) return CoreResult.Err(CoreError.InvalidInput("Project name is empty"))
        return io(dir) {
            if (fs.exists(dir) && fs.list(dir).isNotEmpty()) return CoreResult.Err(CoreError.AlreadyExists(dir))
            SUBFOLDERS.forEach { fs.createDirectories(joinPath(dir, it)) }
            val now = clock.now()
            val project = Project(
                id = newId(), name = name.trim(), mode = mode, createdAt = now, updatedAt = now, settings = settings,
            )
            save(dir, project)
        }
    }

    fun open(dir: String): CoreResult<Project> = read(dir, PROJECT_FILE)

    /** Opens `project.json.bak` (the previous save) without changing anything on disk. */
    fun openBackup(dir: String): CoreResult<Project> = read(dir, BACKUP_FILE)

    /** Saves [project] into [dir]; sets `updatedAt` and recomputes `globalStyle.hash`. Returns what was written. */
    fun save(dir: String, project: Project): CoreResult<Project> {
        val main = joinPath(dir, PROJECT_FILE)
        return io(main) {
            val saved = project.copy(updatedAt = clock.now(), globalStyle = project.globalStyle.withComputedHash())
            val tmp = joinPath(dir, "$PROJECT_FILE.tmp")
            fs.writeDurably(tmp, CoreJson.encodeToString(saved).encodeToByteArray())
            if (fs.exists(main)) {
                val bakTmp = joinPath(dir, "$BACKUP_FILE.tmp")
                fs.writeDurably(bakTmp, fs.read(main))
                fs.atomicMove(bakTmp, joinPath(dir, BACKUP_FILE))
            }
            fs.atomicMove(tmp, main)
            CoreResult.Ok(saved)
        }
    }

    private fun read(dir: String, fileName: String): CoreResult<Project> {
        val path = joinPath(dir, fileName)
        return io(path) {
            if (!fs.exists(path)) return CoreResult.Err(CoreError.NotFound(path))
            decode(path, fs.read(path).decodeToString(), backupAvailable = fileName == PROJECT_FILE &&
                fs.exists(joinPath(dir, BACKUP_FILE)))
        }
    }

    private fun decode(path: String, text: String, backupAvailable: Boolean): CoreResult<Project> {
        val version = try {
            Json.parseToJsonElement(text).jsonObject["schemaVersion"]?.jsonPrimitive?.int
        } catch (e: IllegalArgumentException) {
            return CoreResult.Err(CoreError.Corrupt(path, e.message ?: "invalid JSON", backupAvailable))
        } ?: return CoreResult.Err(CoreError.Corrupt(path, "missing schemaVersion", backupAvailable))
        if (version > Project.SCHEMA_VERSION) {
            return CoreResult.Err(CoreError.UnsupportedSchema(path, version, Project.SCHEMA_VERSION))
        }
        return try {
            CoreResult.Ok(CoreJson.decodeFromString<Project>(text))
        } catch (e: IllegalArgumentException) { // SerializationException is an IllegalArgumentException
            CoreResult.Err(CoreError.Corrupt(path, e.message ?: "invalid project", backupAvailable))
        }
    }

    private inline fun <T> io(path: String, block: () -> CoreResult<T>): CoreResult<T> = try {
        block()
    } catch (e: Exception) {
        CoreResult.Err(CoreError.Io(path, e.message ?: e.toString()))
    }

    companion object {
        const val PROJECT_FILE = "project.json"
        const val BACKUP_FILE = "project.json.bak"
        val SUBFOLDERS = listOf("source", "artifacts", "inbox", "exports", "packages", ".cache")
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun randomId(): String = Uuid.random().toString()
