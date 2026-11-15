package io.github.alalkipgen.autoflow.platform

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.recap.Recap
import io.github.alalkipgen.autoflow.core.recap.RecapMigration
import io.github.alalkipgen.autoflow.core.recap.RecapScript
import io.github.alalkipgen.autoflow.core.recap.RecapStory
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import kotlinx.serialization.json.*

/**
 * Explicit, offline recovery of an already-lossy migration. Never calls the in-place restore path.
 * Close the app first: source is read-only, hashed before/after copying, but not locked or changed.
 * Like ProjectZip.restore, publish a checked staging directory using a no-replace move.
 */
object V1ProjectCopy {
    const val CURRENT_COPY = "project.before-v1-recovery.json"

    sealed interface Result {
        data class Recovered(
            val destination: String, val copiedFiles: Int, val artifacts: Int,
            val blocks: Int, val takes: Int, val approved: Boolean,
        ) : Result
        data class Refused(val reason: String) : Result
    }

    /** Destination must not exist, even as an empty directory or a link. No guessed model/provenance is added. */
    fun recover(source: File, destination: File): Result = try {
        recoverChecked(source, destination)
    } catch (e: Exception) {
        Result.Refused(e.message ?: e.javaClass.simpleName)
    }

    private fun recoverChecked(source: File, destination: File): Result.Recovered {
        require(!Files.isSymbolicLink(source.toPath())) { "The source must not be a symbolic link" }
        val root = source.toPath().toRealPath()
        require(Files.isDirectory(root)) { "The source is not a project folder" }
        val target = destination.absoluteFile.parentFile.toPath().toRealPath().resolve(destination.name)
        require(destination.name.endsWith(".autoflow") && destination.name != ".autoflow") { "Choose a new Name.autoflow destination" }
        require(!target.startsWith(root)) { "The destination must be outside the source" }
        require(!Files.exists(target, NOFOLLOW_LINKS)) { "The destination already exists; nothing was replaced" }
        val before = snapshot(root)
        require(RecapMigration.BACKUP_FILE in before) { "No original ${RecapMigration.BACKUP_FILE} is available" }
        require(CURRENT_COPY !in before) { "The source already contains $CURRENT_COPY; choose an original source" }

        val staging = Files.createTempDirectory(target.parent, ".recover-v1-")
        try {
            // Copy every non-cache file, including unreferenced immutable artifacts and the original backup.
            for ((relative, hash) in before) {
                val copied = staging.resolve(if (relative == "project.json") CURRENT_COPY else relative)
                Files.createDirectories(copied.parent)
                Files.copy(root.resolve(relative), copied, NOFOLLOW_LINKS) // no REPLACE_EXISTING, no link traversal
                require(Files.isRegularFile(copied, NOFOLLOW_LINKS)) { "The source changed to a non-regular file: $relative" }
                check(digest(copied) == hash) { "The copy of $relative did not verify" }
            }
            val raw = CoreJson.parseToJsonElement(Files.readString(staging.resolve(RecapMigration.BACKUP_FILE))).jsonObject
            require(RecapMigration.schemaVersion(raw) == 1 && raw["mode"]?.jsonPrimitive?.content == Mode.MOVIE_RECAP.name) {
                "The backup is not a v1 Movie Recap project"
            }
            val v1 = CoreJson.decodeFromJsonElement(Recap.serializer(), raw["recap"] ?: error("The backup has no recap"))
            reference(v1.script, ArtifactKind.RECAP_SCRIPT)
            reference(v1.transcript, ArtifactKind.TRANSCRIPT)
            reference(v1.audio.bgm, ArtifactKind.AUDIO)
            v1.takes.forEach { reference(it.audio, ArtifactKind.AUDIO) }
            val fs = JvmFs()
            val dir = staging.toString().replace('\\', '/')
            val store = ArtifactStore(fs, dir)
            validateReferences(staging, raw)
            val script = v1.script?.let {
                CoreJson.decodeFromString(RecapScript.serializer(), store.readBytes(it).required().decodeToString())
            }
            // The normal migration drops orphan indices. Recovery refuses instead: it must not silently lose a take.
            val indices = script?.segments?.indices ?: IntRange.EMPTY
            require(v1.takes.all { it.segment in indices } && v1.fit.keys.all { it in indices }) {
                "The backup has orphan take/fit indices; cannot invent their missing script blocks"
            }
            require(!v1.scriptApproved || script != null) { "An approved script is missing" }
            listOfNotNull(v1.movie?.file, v1.subtitles, v1.visual.backgroundImageFile, v1.visual.watermarkFile)
                .forEach { requiredFile(staging, it) }
            v1.movie?.takeIf { it.sizeBytes > 0 }?.let {
                require(Files.size(requiredFile(staging, it.file)) == it.sizeBytes) { "The movie size does not match the backup" }
            }
            val (migrated, project) = RecapMigration.migrateInFolder(fs, dir, raw, burmeseTtsModel = null)
            validateRootReferences(project)
            val recap = requireNotNull(project.recap)
            val story = recap.story?.let { CoreJson.decodeFromString(RecapStory.serializer(), store.readBytes(it).required().decodeToString()) }
            check(story == script?.let { RecapMigration.storyFrom(it, v1.register) }) { "The recovered story differs from the original script" }
            check(recap == RecapMigration.recapV2(v1, story, recap.story, null)) { "The migrated fields did not verify" }
            check(recap.takes.size == v1.takes.size && recap.storyApproved == v1.scriptApproved) { "Takes or approval did not verify" }
            validateReferences(staging, migrated)
            Files.writeString(staging.resolve("project.json"), CoreJson.encodeToString(JsonObject.serializer(), migrated), CREATE_NEW)
            // Recheck every copied byte after migration, and the source again before publishing.
            for ((relative, hash) in before) {
                val copied = staging.resolve(if (relative == "project.json") CURRENT_COPY else relative)
                check(digest(copied) == hash) { "Recovery changed an original file: $relative" }
            }
            check(snapshot(root) == before) { "The source changed during recovery; close the app and retry" }
            val artifactCount = before.keys.count { it.startsWith("artifacts/") && it.endsWith(".meta.json") }
            Files.move(staging, target) // deliberately NOT ATOMIC_MOVE (which may replace); same-parent staging
            return Result.Recovered(target.toString(), before.size, artifactCount, story?.blocks?.size ?: 0, recap.takes.size, recap.storyApproved)
        } finally {
            // Only our unpublished staging directory is removed. Never the source or the destination.
            if (Files.exists(staging, NOFOLLOW_LINKS)) {
                Files.walk(staging).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
            }
        }
    }

    /** ArtifactId's inline serializer accepts any string: never silently skip a malformed typed pointer. */
    private fun reference(id: ArtifactId?, expected: ArtifactKind) {
        require(id == null || id.isWellFormed && id.kind == expected) { "Invalid artifact reference: $id (expected ${expected.code})" }
    }

    private fun validateRootReferences(project: Project) {
        reference(project.analysis, ArtifactKind.ANALYSIS)
        reference(project.lyricTiming, ArtifactKind.TRANSCRIPT)
        reference(project.currentStoryboard, ArtifactKind.STORYBOARD)
        project.storyboards.forEach { reference(it, ArtifactKind.STORYBOARD) }
        project.characters.forEach { c -> c.refImages.forEach { reference(it, ArtifactKind.IMAGE) } }
        project.shots.forEach { s ->
            reference(s.spec, ArtifactKind.SHOT_SPEC)
            reference(s.promptIr, ArtifactKind.PROMPT_IR)
            reference(s.chosenRef, ArtifactKind.IMAGE)
            reference(s.acceptedVideo, ArtifactKind.VIDEO)
            s.refImages.forEach { reference(it, ArtifactKind.IMAGE) }
            s.videos.forEach { reference(it, ArtifactKind.VIDEO) }
        }
    }

    /** Validate transitive artifact references, sidecar identity, content hashes and JSON readability. */
    private fun validateReferences(root: Path, raw: JsonElement) {
        val store = ArtifactStore(JvmFs(), root.toString().replace('\\', '/'))
        val pending = ArrayDeque<ArtifactId>()
        fun collect(json: JsonElement) {
            when (json) {
                is JsonObject -> json.values.forEach { collect(it) }
                is JsonArray -> json.forEach { collect(it) }
                is JsonPrimitive -> json.contentOrNull?.let { ArtifactId(it) }?.takeIf { it.isWellFormed }?.let { pending.add(it) }
            }
        }
        collect(raw)
        val seen = mutableSetOf<ArtifactId>()
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (!seen.add(id)) continue
            val meta = store.get(id).required()
            require(meta.kind == id.kind && meta.file.substringBefore('.') == id.value && '/' !in meta.file && '\\' !in meta.file) {
                "Invalid sidecar for $id"
            }
            val file = requiredFile(root, "artifacts/${meta.file}")
            require(digest(file).take(ArtifactId.HASH_LENGTH) == id.value.substringAfter('_')) { "Corrupt artifact $id" }
            meta.inputs.values.map(::ArtifactId).filter { it.isWellFormed }.forEach { pending.add(it) }
            if (meta.file.endsWith(".json")) collect(CoreJson.parseToJsonElement(Files.readString(file)))
        }
    }

    private fun requiredFile(root: Path, relative: String): Path {
        require(relative.isNotBlank() && '\\' !in relative && ':' !in relative) { "Unsafe project reference: $relative" }
        val path = root.resolve(relative).normalize()
        require(!File(relative).isAbsolute && path.startsWith(root) && Files.isRegularFile(path, NOFOLLOW_LINKS)) {
            "Missing or unsafe project reference: $relative"
        }
        return path
    }

    private fun snapshot(root: Path): Map<String, String> = buildMap {
        Files.walk(root).use { paths ->
            paths.forEach { path ->
                val relative = root.relativize(path)
                if (relative.any { it.toString() == ".cache" }) return@forEach
                require(!Files.isSymbolicLink(path)) { "Symbolic links are not supported in a recovery source: $relative" }
                if (Files.isDirectory(path, NOFOLLOW_LINKS)) return@forEach
                require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Not a regular project file: $relative" }
                put(relative.toString().replace('\\', '/'), digest(path))
            }
        }
    }

    private fun digest(path: Path): String {
        val hash = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                hash.update(buffer, 0, n)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private fun <T> CoreResult<T>.required(): T = when (this) {
        is CoreResult.Ok -> value
        is CoreResult.Err -> error("A required artifact is missing or unreadable: $error")
    }
}
