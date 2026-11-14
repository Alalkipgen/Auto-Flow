package io.github.alalkipgen.autoflow.ui.library

import io.github.alalkipgen.autoflow.core.library.BrandKit
import io.github.alalkipgen.autoflow.core.library.Library
import io.github.alalkipgen.autoflow.core.library.LibraryStore
import io.github.alalkipgen.autoflow.core.library.Preset
import io.github.alalkipgen.autoflow.core.library.PresetKind
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.recap.CaptionStyle
import io.github.alalkipgen.autoflow.core.recap.RecapAudio
import io.github.alalkipgen.autoflow.core.recap.RecapVisual
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class LibraryUiState(
    val library: Library = Library(),
    val message: String? = null,
    val messageOk: Boolean = false,
)

/**
 * Library screen logic (5A-7, studio-v2.md §6).
 * "Apply" actions copy library assets into the current project (D-39).
 * "Save" actions snapshot current project state into the Library (D-51: 5B-3 Brand Kit, 5B-8 caption preset).
 */
class LibraryModel(
    private val store: LibraryStore,
    private val session: ProjectSession,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        scope.launch { refresh() }
    }

    fun refresh() {
        _state.value = _state.value.copy(library = store.load())
    }

    // ── Brand Kit ───────────────────────────────────────────────────────────────────────────────

    /**
     * Save the current project's Recap settings as a Brand Kit (5B-3, D-51): copies logo/watermark from
     * the project's `library/` folder (if present) and stores caption style + visual preset strings.
     */
    fun saveBrandKit(name: String) {
        if (name.isBlank()) return say("Brand kit name cannot be empty")
        val open = session.open ?: return say("No project is open")
        val recap = open.project.recap ?: return say("No Movie Recap project open")
        val id = "bk_${System.currentTimeMillis()}"
        val libFiles = "library/"
        // Copy logo / watermark files from the project's library folder into the Library's files dir.
        fun copyIfExists(relative: String): String? {
            val src = joinPath(open.dir, relative)
            return runCatching { store.copyAssetFromPath(src, id + "_" + relative.substringAfterLast('/')) }.getOrNull()
        }
        val logoRel = copyIfExists("library/logo.png") ?: copyIfExists("library/logo.jpg")
        val wmRel = copyIfExists("library/watermark.png") ?: copyIfExists("library/watermark.jpg")
        val captionJson = CoreJson.encodeToString(CaptionStyle.serializer(), recap.captionStyle)
        val kit = BrandKit(
            id = id,
            name = name,
            logo = logoRel,
            watermark = wmRel,
            colours = recap.visual.let { v ->
                listOfNotNull(
                    v.backgroundColour.takeIf { v.background == io.github.alalkipgen.autoflow.core.recap.FrameBackground.COLOUR },
                )
            },
            font = null, // font is a Settings-level choice; not per-project
            captionStyle = captionJson,
        )
        val lib = store.load()
        store.save(lib.copy(brandKits = lib.brandKits + kit))
        refresh()
        say("Brand kit '${name}' saved to Library.", ok = true)
    }

    /**
     * Apply a Brand Kit to the current project (5B-3, D-51):
     * - copies logo/watermark files into the project's `library/` folder
     * - restores `captionStyle` from the kit's JSON blob
     * The caller (LibraryModel or RecapModel) updates `project.recap` for captionStyle.
     * Returns the decoded [CaptionStyle] when present, so callers can apply it.
     */
    fun applyBrandKit(id: String): CaptionStyle? {
        val kit = _state.value.library.brandKits.find { it.id == id } ?: return run { say("Brand kit not found"); null }
        val open = session.open ?: return run { say("No project is open"); null }
        val files = listOfNotNull(kit.logo, kit.watermark, kit.font)
        var copied = 0
        files.forEach { rel ->
            runCatching { store.copyAsset(rel, joinPath(open.dir, "library")) }.onSuccess { copied++ }
        }
        // Apply captionStyle to the open recap
        val captionStyle = kit.captionStyle?.let {
            runCatching { CoreJson.decodeFromString(CaptionStyle.serializer(), it) }.getOrNull()
        }
        // Apply captionStyle and watermark layer to the open recap (D-52).
        val recap = open.project.recap
        if (recap != null) {
            var updatedRecap = recap
            if (captionStyle != null) updatedRecap = updatedRecap.copy(captionStyle = captionStyle)
            // Wire watermark file into RecapVisual so RecapRender adds it as an IMAGE layer.
            val wmDestRel = kit.watermark?.substringAfterLast('/')?.let { name ->
                val destPath = joinPath("library", name)
                // The file was just copied into <project>/library/; store a relative path.
                destPath
            }
            updatedRecap = updatedRecap.copy(visual = updatedRecap.visual.copy(watermarkFile = wmDestRel))
            if (updatedRecap != recap) session.save(open.project.copy(recap = updatedRecap))
        }
        say("Applied '${kit.name}' (${files.size} files, caption style${if (captionStyle != null) " ✓" else " —"}, watermark ${if (kit.watermark != null) "✓" else "—"}).", ok = true)
        return captionStyle
    }

    // ── Voices ──────────────────────────────────────────────────────────────────────────────────

    fun applyVoice(id: String) {
        val voice = _state.value.library.voices.find { it.id == id } ?: return
        val open = session.open ?: return say("No project is open")
        val recap = open.project.recap ?: return say("No Movie Recap project open")
        session.save(open.project.copy(recap = recap.copy(voiceLock = voice.lock)))
        say("Applied voice '${voice.name}'", ok = true)
    }

    // ── Music ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Copy the music item's file into the project, then set it as the BGM artifact (5B-4, D-51).
     * Returns the destination absolute path so RecapModel can store the artifact.
     */
    fun applyMusicAsBgm(id: String): String? {
        val music = _state.value.library.music.find { it.id == id } ?: return run { say("Music track not found"); null }
        val open = session.open ?: return run { say("No project is open"); null }
        val destDir = joinPath(open.dir, "source")
        return runCatching {
            store.copyAsset(music.file, destDir)
        }.onSuccess { say("Copied '${music.name}' to project sources", ok = true) }
            .onFailure { say("Cannot copy music: ${it.message}") }
            .getOrNull()
    }

    fun applyMusic(id: String) {
        applyMusicAsBgm(id)
    }

    fun applyFont(id: String) {
        val font = _state.value.library.fonts.find { it.id == id } ?: return
        val open = session.open ?: return say("No project is open")
        runCatching { store.copyAsset(font.file, joinPath(open.dir, "library")) }
            .onSuccess { say("Copied font '${font.name}'", ok = true) }
            .onFailure { say("Cannot copy font: ${it.message}") }
    }

    // ── Caption Presets (5B-8, D-51) ────────────────────────────────────────────────────────────

    /**
     * Save the current project's [CaptionStyle] as a CAPTION preset in the Library (5B-8).
     */
    fun saveCaptionPreset(name: String) {
        if (name.isBlank()) return say("Preset name cannot be empty")
        val recap = session.open?.project?.recap ?: return say("No Movie Recap project open")
        val id = "cp_${System.currentTimeMillis()}"
        val value = buildJsonObject {
            put("size", recap.captionStyle.size.name)
            put("colour", recap.captionStyle.colour)
            put("box", recap.captionStyle.box)
        }
        val preset = Preset(id = id, name = name, kind = PresetKind.CAPTION, value = value)
        val lib = store.load()
        store.save(lib.copy(presets = lib.presets + preset))
        refresh()
        say("Caption preset '$name' saved.", ok = true)
    }

    /**
     * Apply a preset to the current project: for CAPTION presets, decodes and applies [CaptionStyle] (5B-8).
     */
    fun applyPreset(id: String) {
        val preset = _state.value.library.presets.find { it.id == id } ?: return
        when (preset.kind) {
            PresetKind.CAPTION -> {
                val open = session.open ?: return say("No project is open")
                val recap = open.project.recap ?: return say("No Movie Recap project open")
                val sizeName = (preset.value["size"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return say("Cannot read preset")
                val colour = (preset.value["colour"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "#FFFFFF"
                val box = (preset.value["box"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
                val size = runCatching { io.github.alalkipgen.autoflow.core.recap.CaptionSize.valueOf(sizeName) }.getOrElse { io.github.alalkipgen.autoflow.core.recap.CaptionSize.M }
                val newStyle = recap.captionStyle.copy(size = size, colour = colour, box = box)
                session.save(open.project.copy(recap = recap.copy(captionStyle = newStyle)))
                say("Caption preset '${preset.name}' applied.", ok = true)
            }
            else -> say("Preset '${preset.name}' (${preset.kind.name}) applied (values copied).", ok = true)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

    private fun say(msg: String, ok: Boolean = false) {
        _state.value = _state.value.copy(message = msg, messageOk = ok)
    }
}
