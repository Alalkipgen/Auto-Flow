package io.github.alalkipgen.autoflow.core.recap

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A v1 project that cannot be migrated without losing saved work (audit F02): its recap is unreadable, or the script
 * it references is missing or damaged. Nothing has been written when this is thrown.
 */
class MigrationException(message: String) : Exception(message)

/**
 * Project migration 1 → 2 for Movie Recap (docs/specs/studio-v2.md §2, D-36). Works on the `project.json` tree so
 * every non-recap key passes through untouched. Running it on a v2 project changes nothing. The Dashboard runs it on
 * open (backup first, then the migrated file); v0.2.0 ran it too early, which [restoreV1Backup] undid.
 */
object RecapMigration {
    const val BACKUP_FILE = "project.v1.bak.json"
    const val TO_VERSION = 2
    const val V2_COPY_FILE = "project.v2.json"
    /** Where [recoverFromBackup] keeps an unreadable `project.json` it replaced (`project.damaged-2.json`… after). */
    const val DAMAGED_FILE = "project.damaged.json"
    private const val PROJECT_FILE = "project.json"

    fun schemaVersion(project: JsonObject): Int = project["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 1

    fun needsMigration(project: JsonObject): Boolean = schemaVersion(project) < TO_VERSION

    /** Step 1: copies `project.json` → [BACKUP_FILE] (via `.tmp` + atomic rename); never overwrites an existing backup. */
    fun backup(fs: FileSystem, projectDir: String) {
        val backup = joinPath(projectDir, BACKUP_FILE)
        if (fs.exists(backup)) return
        val tmp = "$backup.tmp"
        fs.copyDurably(joinPath(projectDir, "project.json"), tmp)
        fs.atomicMove(tmp, backup)
    }

    /**
     * Undo for a project migrated before the app can read v2: when `project.json` has a schemaVersion above
     * [supportedVersion] and [BACKUP_FILE] is a v1 project, keeps the v2 file as [V2_COPY_FILE] and puts the backup
     * back as `project.json`. Returns true when it restored. Never throws on a bad file; it just does nothing.
     */
    fun restoreV1Backup(fs: FileSystem, projectDir: String, supportedVersion: Int): Boolean {
        val main = joinPath(projectDir, "project.json")
        val backup = joinPath(projectDir, BACKUP_FILE)
        if (!fs.exists(main) || !fs.exists(backup)) return false
        val current = versionOf(fs, main) ?: return false
        if (current <= supportedVersion) return false
        val old = versionOf(fs, backup) ?: return false
        if (old > supportedVersion) return false
        val copy = joinPath(projectDir, V2_COPY_FILE)
        if (!fs.exists(copy)) {
            fs.copyDurably(main, "$copy.tmp")
            fs.atomicMove("$copy.tmp", copy)
        }
        fs.copyDurably(backup, "$main.tmp")
        fs.atomicMove("$main.tmp", main)
        return true
    }

    private fun versionOf(fs: FileSystem, path: String): Int? = try {
        schemaVersion(kotlinx.serialization.json.Json.parseToJsonElement(fs.read(path).decodeToString()).jsonObject)
    } catch (_: Exception) {
        null
    }

    /** Step 2: the v1 script as an `rs` v2 story, one block per segment; [register] is the v1 recap's. */
    fun storyFrom(script: RecapScript, register: RecapRegister = RecapRegister.SPOKEN): RecapStory = RecapStory(
        source = script.source,
        style = script.style,
        register = register,
        blocks = script.segments.mapIndexed { i, s ->
            RecapBlock(
                id = migratedBlockId(i, s.startMs, s.endMs),
                clips = listOf(RecapClip(s.startMs, s.endMs)),
                textMy = s.textMy,
                speakMy = null,
            )
        },
    )

    /**
     * Steps 3–5: [v1] as `project.recap` v2. [story] / [storyId] are the migrated story and its new `rs` id (null when
     * the project had no script); [burmeseTtsModel] = the first `tts.candidates` model that speaks `my`.
     * Takes and fit choices whose segment has no block are dropped from the list (audio artifacts stay on disk).
     * Every other v1 field the v2 schema has is carried over (audit F02): subtitle track, transcript, fit (segment
     * index → block id), caption style, publish kit, Quick Recap state, quality, looks, picture and sound.
     */
    fun recapV2(v1: Recap, story: RecapStory?, storyId: ArtifactId?, burmeseTtsModel: String?): RecapV2 {
        val blocks = story?.blocks.orEmpty()
        val lock = v1.voiceLock ?: if (v1.voiceSource == VoiceSource.TTS && burmeseTtsModel != null) {
            VoiceLock(provider = "gemini", model = burmeseTtsModel, voice = v1.ttsVoice, lang = "my")
        } else null
        return RecapV2(
            rightsConfirmed = v1.rightsConfirmed,
            movie = v1.movie,
            targetMs = v1.targetMs,
            scenes = v1.scenes,
            sceneThreshold = v1.sceneThreshold,
            sources = RecapSources(subtitles = v1.subtitles, subtitleTrack = v1.subtitleTrack, transcript = v1.transcript),
            story = storyId,
            storyApproved = storyId != null && v1.scriptApproved,
            voiceLock = lock,
            takes = v1.takes.mapNotNull { t ->
                val block = blocks.getOrNull(t.segment) ?: return@mapNotNull null
                BlockTake(
                    block = block.id,
                    audio = t.audio,
                    source = when (t.source) {
                        VoiceSource.RECORDED -> TakeSource.RECORDED
                        VoiceSource.TTS, VoiceSource.REPLICATED -> TakeSource.TTS
                    },
                    provider = t.provider,
                    model = t.model,
                    voice = t.voice,
                    durationMs = t.durationMs,
                    spokenHash = t.textHash,
                )
            },
            fit = v1.fit.mapNotNull { (segment, choice) -> blocks.getOrNull(segment)?.let { it.id to choice.name } }.toMap(),
            audio = v1.audio.copy(movieVolume = v1.movieVolume),
            captions = RecapCaptions(burn = v1.burnCaptions),
            looks = v1.looks,
            quality = v1.quality,
            quick = v1.quick?.let { CoreJson.encodeToJsonElement(QuickRecap.serializer(), it) },
            visual = v1.visual,
            captionStyle = v1.captionStyle,
            publishKit = v1.publishKit,
        )
    }

    /**
     * Steps 2–6 on the `project.json` tree. [readScript] loads the v1 `rs` (null or a throw = missing / damaged);
     * [writeStory] stores the v2 story and returns its id. A v2 [project] (or one without a recap) only gets
     * `schemaVersion` = 2. A recap without a `script` migrates without a story; a recap whose `script` cannot be read
     * throws [MigrationException] before [writeStory] runs, so the caller keeps the v1 file as it is (audit F02).
     */
    fun migrate(
        project: JsonObject,
        readScript: (ArtifactId) -> RecapScript?,
        writeStory: (RecapStory) -> ArtifactId,
        burmeseTtsModel: String?,
    ): JsonObject {
        if (!needsMigration(project)) return project
        val out = project.toMutableMap()
        out["schemaVersion"] = JsonPrimitive(TO_VERSION)
        val recapJson = project["recap"]
        if (recapJson != null && recapJson is JsonObject) {
            val v1 = try {
                CoreJson.decodeFromJsonElement(Recap.serializer(), recapJson.jsonObject)
            } catch (e: IllegalArgumentException) {
                throw MigrationException("The recap in project.json is unreadable (${e.message?.lineSequence()?.first()})")
            }
            val script = v1.script?.let { id ->
                val read = try {
                    readScript(id)
                } catch (e: MigrationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                read ?: throw MigrationException("The script $id that this project uses is missing or damaged")
            }
            val story = script?.let { storyFrom(it, v1.register) }
            val storyId = story?.let(writeStory)
            out["recap"] = CoreJson.encodeToJsonElement(RecapV2.serializer(), recapV2(v1, story, storyId, burmeseTtsModel))
        }
        return JsonObject(out)
    }

    /**
     * [raw] (a `project.json` tree of [projectDir]) migrated to v2 and decoded, with project.json NOT written: reads
     * the v1 script from the folder's artifacts and stores the new story as an `rs` artifact (additive, content
     * addressed). Throws [MigrationException] when that would lose saved work or the result is not a valid project.
     */
    fun migrateInFolder(fs: FileSystem, projectDir: String, raw: JsonObject, burmeseTtsModel: String?): Pair<JsonObject, Project> {
        val artifacts = ArtifactStore(fs, projectDir)
        val migrated = migrate(
            project = raw,
            readScript = { id ->
                val bytes = (artifacts.readBytes(id) as? CoreResult.Ok)?.value
                    ?: throw MigrationException("The script $id that this project uses is missing")
                try {
                    CoreJson.decodeFromString(RecapScript.serializer(), bytes.decodeToString())
                } catch (e: IllegalArgumentException) {
                    throw MigrationException("The script $id that this project uses is damaged")
                }
            },
            writeStory = { story ->
                val bytes = CoreJson.encodeToString(RecapStory.serializer(), story).encodeToByteArray()
                when (val r = artifacts.put(bytes, ArtifactKind.RECAP_SCRIPT, "json")) {
                    is CoreResult.Ok -> r.value.id
                    is CoreResult.Err -> throw MigrationException("Cannot save the migrated story: ${r.error}")
                }
            },
            burmeseTtsModel = burmeseTtsModel,
        )
        val project = try {
            CoreJson.decodeFromJsonElement(Project.serializer(), migrated)
        } catch (e: IllegalArgumentException) {
            throw MigrationException("The migrated project is not valid (${e.message?.lineSequence()?.first()})")
        }
        return migrated to project
    }

    /**
     * Migrates `project.json` of [projectDir] in place when it is v1: everything is read and checked first
     * ([migrateInFolder]), then [backup] (only if none exists), then the new file goes in via `.tmp` + atomic rename.
     * Returns false when nothing needed doing (absent, unreadable — [recoverFromBackup] handles those — or already
     * v2). Throws [MigrationException] (project.json unchanged) when it cannot be migrated without losing work.
     */
    fun migrateFolder(fs: FileSystem, projectDir: String, burmeseTtsModel: String?): Boolean {
        val main = joinPath(projectDir, PROJECT_FILE)
        if (!fs.exists(main)) return false
        val raw = parse(fs.read(main)) ?: return false
        if (!needsMigration(raw)) return false
        val (migrated, _) = migrateInFolder(fs, projectDir, raw, burmeseTtsModel)
        backup(fs, projectDir)
        val tmp = "$main.mig.tmp"
        fs.writeDurably(tmp, CoreJson.encodeToString(JsonObject.serializer(), migrated).encodeToByteArray())
        fs.atomicMove(tmp, main)
        return true
    }

    /** What [recoverFromBackup] did. */
    sealed interface Recovery {
        /** There is no [BACKUP_FILE]; nothing was changed. */
        data object NoBackup : Recovery

        /** [BACKUP_FILE] cannot give a valid project either ([reason]); nothing was changed. */
        data class Failed(val reason: String) : Recovery

        /** project.json now holds [project] (migrated from [BACKUP_FILE] when it was v1); a damaged main was kept as [kept]. */
        data class Recovered(val project: Project, val kept: String?) : Recovery
    }

    /**
     * Recovery when `project.json` is missing or cannot be opened (audit F13): [BACKUP_FILE] is read, migrated when it
     * is v1 ([migrateInFolder]) and decoded *before* anything is replaced. Only then a present main file is kept as
     * [DAMAGED_FILE] (never overwriting an earlier one) and the recovered project goes in via `.tmp` + atomic rename.
     * The backup itself is never changed, so a later open can recover again.
     */
    fun recoverFromBackup(fs: FileSystem, projectDir: String, burmeseTtsModel: String?): Recovery {
        val main = joinPath(projectDir, PROJECT_FILE)
        val backupPath = joinPath(projectDir, BACKUP_FILE)
        if (!fs.exists(backupPath)) return Recovery.NoBackup
        val backupBytes = try {
            fs.read(backupPath)
        } catch (e: Exception) {
            return Recovery.Failed("The backup $BACKUP_FILE cannot be read (${e.message})")
        }
        val raw = parse(backupBytes) ?: return Recovery.Failed("The backup $BACKUP_FILE is damaged too")
        val (bytes, project) = if (needsMigration(raw)) {
            try {
                migrateInFolder(fs, projectDir, raw, burmeseTtsModel).let { (json, p) ->
                    CoreJson.encodeToString(JsonObject.serializer(), json).encodeToByteArray() to p
                }
            } catch (e: MigrationException) {
                return Recovery.Failed("The backup $BACKUP_FILE cannot be used: ${e.message}")
            }
        } else {
            val p = try {
                CoreJson.decodeFromJsonElement(Project.serializer(), raw)
            } catch (e: IllegalArgumentException) {
                return Recovery.Failed("The backup $BACKUP_FILE is not a valid project either")
            }
            if (schemaVersion(raw) > Project.SCHEMA_VERSION) return Recovery.Failed("The backup $BACKUP_FILE is from a newer Auto Flow")
            backupBytes to p
        }
        val kept = if (fs.exists(main)) {
            val name = generateSequence(1) { it + 1 }
                .map { if (it == 1) DAMAGED_FILE else DAMAGED_FILE.replace(".json", "-$it.json") }
                .first { !fs.exists(joinPath(projectDir, it)) }
            val keep = joinPath(projectDir, name)
            fs.copyDurably(main, "$keep.tmp")
            fs.atomicMove("$keep.tmp", keep)
            name
        } else null
        val tmp = "$main.rec.tmp"
        fs.writeDurably(tmp, bytes)
        fs.atomicMove(tmp, main)
        return Recovery.Recovered(project, kept)
    }

    private fun parse(bytes: ByteArray): JsonObject? = try {
        kotlinx.serialization.json.Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
    } catch (_: Exception) {
        null
    }
}
