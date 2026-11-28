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
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
        val id = savedId("bk")
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
        if (lib.brandKits.count { it.id == id } != 1) return say(UiText.V3_LIBRARY_NOT_FOUND)
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
     * Save the current project's [CaptionStyle] as a CAPTION preset in the Library (5B-8): a Movie Recap's, or a
     * Dubbing project's burned subtitles (D-106) — the same preset either tool applies. Size, colour and box as
     * before, plus the custom colour, custom size and outline when they are set.
     */
    fun saveCaptionPreset(name: String) {
        if (name.isBlank()) return say(UiText.V3_LIBRARY_NAME_REQUIRED)
        val style = session.open?.project?.let(::captionStyleOf) ?: return say(UiText.LIBRARY_NEEDS_CAPTIONS)
        val id = savedId("cp")
        val value = buildJsonObject {
            put("size", style.size.name)
            put("colour", style.colour)
            put("box", style.box)
            style.customColour?.let { put("customColour", it) }
            style.customSizeFactor?.let { put("customSizeFactor", it) }
            if (style.strokeWidth > 0) {
                put("strokeWidth", style.strokeWidth)
                put("strokeColour", style.strokeColour)
            }
        }
        val preset = Preset(id = id, name = name, kind = PresetKind.CAPTION, value = value)
        val lib = store.load()
        saveLibrary(lib.copy(presets = lib.presets + preset), UiNotice(UiText.V3_LIBRARY_SAVED, "name" to name))
    }

    /** The caption style a project burns: Movie Recap's, or a Dubbing project's (D-106); null for other tools. */
    private fun captionStyleOf(project: io.github.alalkipgen.autoflow.core.model.Project): CaptionStyle? =
        project.recap?.captionStyle ?: if (project.mode == io.github.alalkipgen.autoflow.core.model.Mode.DUBBING)
            project.dubbing?.captionStyle ?: io.github.alalkipgen.autoflow.core.model.DubbingTool.DEFAULT_CAPTIONS else null

    /** Only the saved Library entry goes away, not style already copied into any project. */
    fun deletePreset(id: String) {
        val lib = store.load()
        if (lib.presets.count { it.id == id } != 1) return say(UiText.V3_LIBRARY_NOT_FOUND)
        saveLibrary(lib.copy(presets = lib.presets.filter { it.id != id }), UiNotice(UiText.LIBRARY_PRESET_DELETED))
    }

    /**
     * Apply a preset to the current project: for CAPTION presets, decodes and applies [CaptionStyle] (5B-8).
     */
    fun applyPreset(id: String) {
        val preset = _state.value.library.presets.find { it.id == id } ?: return say(UiText.V3_LIBRARY_NOT_FOUND)
        when (preset.kind) {
            PresetKind.CAPTION -> {
                val open = session.open ?: return say(UiText.NO_PROJECT_IS_OPEN)
                val current = captionStyleOf(open.project) ?: return say(UiText.LIBRARY_NEEDS_CAPTIONS)
                fun text(key: String) = (preset.value[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
                val sizeName = text("size") ?: return say(UiText.V3_LIBRARY_PRESET_INVALID)
                val colour = text("colour") ?: "#FFFFFF"
                val box = text("box")?.toBooleanStrictOrNull() ?: false
                val size = runCatching { io.github.alalkipgen.autoflow.core.recap.CaptionSize.valueOf(sizeName) }.getOrElse { io.github.alalkipgen.autoflow.core.recap.CaptionSize.M }
                // A preset saved before D-106 has only size / colour / box: the rest of the project's style stays.
                val full = preset.value.containsKey("customColour") || preset.value.containsKey("customSizeFactor") || preset.value.containsKey("strokeWidth")
                val newStyle = current.copy(size = size, colour = colour, box = box).let { s ->
                    if (!full) s else s.copy(customColour = text("customColour"),
                        customSizeFactor = text("customSizeFactor")?.toFloatOrNull(),
                        strokeWidth = text("strokeWidth")?.toIntOrNull() ?: 0,
                        strokeColour = text("strokeColour") ?: "#000000")
                }
                val project = open.project.recap?.let { open.project.copy(recap = it.copy(captionStyle = newStyle)) }
                    ?: open.project.copy(dubbing = (open.project.dubbing ?: io.github.alalkipgen.autoflow.core.model.DubbingTool()).copy(captionStyle = newStyle))
                when (val result = session.save(project)) {
                    is CoreResult.Ok -> say(UiNotice(UiText.V3_LIBRARY_APPLIED, "name" to preset.name), ok = true)
                    is CoreResult.Err -> say(UiNotice(UiText.SAVE_FAILED, "detail" to result.error.message))
                }
            }
            else -> say(UiText.V3_LIBRARY_PRESET_UNSUPPORTED)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

    @OptIn(ExperimentalUuidApi::class)
    private fun savedId(prefix: String) = "${prefix}_${Uuid.random()}"

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
