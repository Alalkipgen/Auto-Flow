package io.github.alalkipgen.autoflow.ui.library

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.core.library.BrandKit
import io.github.alalkipgen.autoflow.core.library.Library
import io.github.alalkipgen.autoflow.core.library.LibraryStore
import io.github.alalkipgen.autoflow.core.library.Preset
import io.github.alalkipgen.autoflow.core.library.PresetKind
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.recap.CaptionStyle
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class LibraryUiState(
    val library: Library = Library(),
    val message: String? = null,
    val messageOk: Boolean = false,
    val notice: UiNotice? = null,
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
    private val fs: FileSystem? = null,
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
        if (name.isBlank()) return say(UiText.V3_LIBRARY_NAME_REQUIRED)
        val open = session.open ?: return say(UiText.NO_PROJECT_IS_OPEN)
        val recap = open.project.recap ?: return say(UiText.V3_LIBRARY_NEEDS_RECAP)
        val id = "bk_${System.currentTimeMillis()}"
        // Copy logo / watermark files from the project's library folder into the Library's files dir.
        fun copyIfExists(relative: String): String? {
            val src = joinPath(open.dir, relative)
            return runCatching { store.copyAssetFromPath(src, id + "_" + relative.substringAfterLast('/')) }.getOrNull()
        }
        val logoRel = copyIfExists("library/logo.png") ?: copyIfExists("library/logo.jpg")
        // Also try the watermark file from recap.visual.watermarkFile (D-54)
        val wmFromVisual = recap.visual.watermarkFile?.substringAfterLast('/')
            ?.let { copyIfExists("library/$it") }
        val wmRel = wmFromVisual ?: copyIfExists("library/watermark.png") ?: copyIfExists("library/watermark.jpg")
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
            watermarkWidthPercent = recap.visual.watermarkWidthPercent,
        )
        val lib = store.load()
        saveLibrary(lib.copy(brandKits = lib.brandKits + kit), UiNotice(UiText.V3_LIBRARY_SAVED, "name" to name))
    }

    /**
     * Apply a Brand Kit to the current project (5B-3, D-51):
     * - copies logo/watermark files into the project's `library/` folder
     * - restores `captionStyle` from the kit's JSON blob
     * The caller (LibraryModel or RecapModel) updates `project.recap` for captionStyle.
     * Returns the decoded [CaptionStyle] when present, so callers can apply it.
     */
    /** Delete a Brand Kit by id (D-58). */
    fun deleteBrandKit(id: String) {
        val lib = store.load()
        saveLibrary(lib.copy(brandKits = lib.brandKits.filter { it.id != id }), UiNotice(UiText.V3_LIBRARY_DELETED))
    }

    fun applyBrandKit(id: String): CaptionStyle? {
        val kit = _state.value.library.brandKits.find { it.id == id } ?: return run { say(UiText.V3_LIBRARY_NOT_FOUND); null }
        val open = session.open ?: return run { say(UiText.NO_PROJECT_IS_OPEN); null }
        val files = listOfNotNull(kit.logo, kit.watermark, kit.font)
        var copied = 0
        files.forEach { rel ->
            runCatching {
                val dest = joinPath(open.dir, "library")
                fs?.createDirectories(dest) // Existing UI-owned destination, as in RecapModel watermark import.
                store.copyAsset(rel, dest)
            }.onSuccess { copied++ }
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
            updatedRecap = updatedRecap.copy(visual = updatedRecap.visual.copy(
                watermarkFile = wmDestRel, watermarkWidthPercent = kit.watermarkWidthPercent))
            if (updatedRecap != recap) {
                val result = session.save(open.project.copy(recap = updatedRecap))
                if (result is CoreResult.Err) { say(UiNotice(UiText.SAVE_FAILED, "detail" to result.error.message)); return captionStyle }
            }
        }
        val complete = copied == files.size && (kit.captionStyle == null || captionStyle != null)
        val key = if (!complete) UiText.V3_LIBRARY_PARTIAL else if (recap != null) UiText.V3_LIBRARY_KIT_APPLIED else UiText.V3_LIBRARY_KIT_COPIED
        say(UiNotice(key, "name" to kit.name, "done" to copied, "total" to files.size), ok = complete && (recap != null || copied > 0))
        return captionStyle
    }

    // ── Voices ──────────────────────────────────────────────────────────────────────────────────

    fun applyVoice(id: String) {
        val voice = _state.value.library.voices.find { it.id == id } ?: return say(UiText.V3_LIBRARY_NOT_FOUND)
        val open = session.open ?: return say(UiText.NO_PROJECT_IS_OPEN)
        val recap = open.project.recap ?: return say(UiText.V3_LIBRARY_NEEDS_RECAP)
        when (val result = session.save(open.project.copy(recap = recap.copy(voiceLock = voice.lock)))) {
            is CoreResult.Ok -> say(UiNotice(UiText.V3_LIBRARY_APPLIED, "name" to voice.name), ok = true)
            is CoreResult.Err -> say(UiNotice(UiText.SAVE_FAILED, "detail" to result.error.message))
        }
    }

    // ── Music ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Copy the music file only. The existing Recap picker separately calls setBgmFromLibrary to
     * store/wire the BGM artifact (D-51); the standalone Library must not claim that step happened.
     */
    fun applyMusicAsBgm(id: String): String? {
        val music = _state.value.library.music.find { it.id == id } ?: return run { say(UiText.V3_LIBRARY_NOT_FOUND); null }
        val open = session.open ?: return run { say(UiText.NO_PROJECT_IS_OPEN); null }
        val destDir = joinPath(open.dir, "source")
        return runCatching {
            store.copyAsset(music.file, destDir)
        }.onSuccess { say(UiNotice(UiText.V3_LIBRARY_COPIED, "name" to music.name), ok = true) }
            .onFailure { say(UiNotice(UiText.COPY_FAILED, "detail" to it.message)) }
            .getOrNull()
    }

    fun applyMusic(id: String) {
        applyMusicAsBgm(id)
    }

    fun applyFont(id: String) {
        val font = _state.value.library.fonts.find { it.id == id } ?: return say(UiText.V3_LIBRARY_NOT_FOUND)
        val open = session.open ?: return say(UiText.NO_PROJECT_IS_OPEN)
        runCatching {
            val dest = joinPath(open.dir, "library")
            fs?.createDirectories(dest)
            store.copyAsset(font.file, dest)
        }
            .onSuccess { say(UiNotice(UiText.V3_LIBRARY_COPIED, "name" to font.name), ok = true) }
            .onFailure { say(UiNotice(UiText.COPY_FAILED, "detail" to it.message)) }
    }

    // ── Caption Presets (5B-8, D-51) ────────────────────────────────────────────────────────────

    /**
     * Save the current project's [CaptionStyle] as a CAPTION preset in the Library (5B-8).
     */
    fun saveCaptionPreset(name: String) {
        if (name.isBlank()) return say(UiText.V3_LIBRARY_NAME_REQUIRED)
        val recap = session.open?.project?.recap ?: return say(UiText.V3_LIBRARY_NEEDS_RECAP)
        val id = "cp_${System.currentTimeMillis()}"
        val value = buildJsonObject {
            put("size", recap.captionStyle.size.name)
            put("colour", recap.captionStyle.colour)
            put("box", recap.captionStyle.box)
        }
        val preset = Preset(id = id, name = name, kind = PresetKind.CAPTION, value = value)
        val lib = store.load()
        saveLibrary(lib.copy(presets = lib.presets + preset), UiNotice(UiText.V3_LIBRARY_SAVED, "name" to name))
    }

    /**
     * Apply a preset to the current project: for CAPTION presets, decodes and applies [CaptionStyle] (5B-8).
     */
    fun applyPreset(id: String) {
        val preset = _state.value.library.presets.find { it.id == id } ?: return say(UiText.V3_LIBRARY_NOT_FOUND)
        when (preset.kind) {
            PresetKind.CAPTION -> {
                val open = session.open ?: return say(UiText.NO_PROJECT_IS_OPEN)
                val recap = open.project.recap ?: return say(UiText.V3_LIBRARY_NEEDS_RECAP)
                val sizeName = (preset.value["size"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return say(UiText.V3_LIBRARY_PRESET_INVALID)
                val colour = (preset.value["colour"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "#FFFFFF"
                val box = (preset.value["box"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
                val size = runCatching { io.github.alalkipgen.autoflow.core.recap.CaptionSize.valueOf(sizeName) }.getOrElse { io.github.alalkipgen.autoflow.core.recap.CaptionSize.M }
                val newStyle = recap.captionStyle.copy(size = size, colour = colour, box = box)
                when (val result = session.save(open.project.copy(recap = recap.copy(captionStyle = newStyle)))) {
                    is CoreResult.Ok -> say(UiNotice(UiText.V3_LIBRARY_APPLIED, "name" to preset.name), ok = true)
                    is CoreResult.Err -> say(UiNotice(UiText.SAVE_FAILED, "detail" to result.error.message))
                }
            }
            else -> say(UiText.V3_LIBRARY_PRESET_UNSUPPORTED)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

    private fun saveLibrary(library: Library, success: UiNotice) {
        runCatching { store.save(library); refresh() }
            .onSuccess { say(success, ok = true) }
            .onFailure { say(UiNotice(UiText.SAVE_FAILED, "detail" to it.message)) }
    }

    private fun say(key: UiText) = say(UiNotice(key))
    private fun say(notice: UiNotice, ok: Boolean = false) {
        _state.value = _state.value.copy(message = notice.text(UiLanguage.ENGLISH), notice = notice, messageOk = ok)
    }
}
