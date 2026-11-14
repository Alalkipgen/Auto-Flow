package io.github.alalkipgen.autoflow.core.recap

import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.CoreJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Project migration 1 → 2 for Movie Recap (docs/specs/studio-v2.md §2, D-36). Works on the `project.json` tree so
 * every non-recap key passes through untouched. Running it on a v2 project changes nothing.
 *
 * Not called by the app yet: the Studio v2 Recap screen (5A-2 UI part) switches it on together with the v2 reader.
 * v0.2.0 ran it on open too early (the app still reads recap v1), so [restoreV1Backup] undoes that.
 */
object RecapMigration {
    const val BACKUP_FILE = "project.v1.bak.json"
    const val TO_VERSION = 2
    const val V2_COPY_FILE = "project.v2.json"

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

    /** Step 2: the v1 script as an `rs` v2 story, one block per segment. */
    fun storyFrom(script: RecapScript): RecapStory = RecapStory(
        source = script.source,
        style = script.style,
        register = RecapRegister.SPOKEN,
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
     * Takes whose segment has no block are dropped from the list (their audio artifacts stay on disk).
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
            sources = RecapSources(subtitles = v1.subtitles),
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
            audio = RecapAudio(movieVolume = v1.movieVolume),
            captions = RecapCaptions(burn = v1.burnCaptions),
            looks = v1.looks,
            quality = v1.quality,
        )
    }

    /**
     * Steps 2–6 on the `project.json` tree. [readScript] loads the v1 `rs`; [writeStory] stores the v2 story and returns
     * its id. A v2 [project] (or one without a recap) only gets `schemaVersion` = 2.
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
            val v1 = CoreJson.decodeFromJsonElement(Recap.serializer(), recapJson.jsonObject)
            val story = v1.script?.let(readScript)?.let(::storyFrom)
            val storyId = story?.let(writeStory)
            out["recap"] = CoreJson.encodeToJsonElement(RecapV2.serializer(), recapV2(v1, story, storyId, burmeseTtsModel))
        }
        return JsonObject(out)
    }
}
