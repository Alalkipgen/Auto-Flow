package io.github.alalkipgen.autoflow.ui.library

import io.github.alalkipgen.autoflow.core.library.Library
import io.github.alalkipgen.autoflow.core.library.LibraryStore
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LibraryUiState(
    val library: Library = Library(),
    val message: String? = null,
    val messageOk: Boolean = false,
)

/**
 * Library screen logic (5A-7, studio-v2.md §6).
 * "Apply" actions copy library assets into the current project (D-39).
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

    fun applyBrandKit(id: String) {
        val kit = _state.value.library.brandKits.find { it.id == id } ?: return
        val open = session.open ?: return say("No project is open")
        // Copy logo, watermark, font if present
        val files = listOfNotNull(kit.logo, kit.watermark, kit.font)
        var copied = 0
        files.forEach { rel ->
            runCatching { store.copyAsset(rel, joinPath(open.dir, "library")) }.onSuccess { copied++ }
        }
        say("Applied brand kit '${kit.name}' ($copied files copied)", ok = true)
    }

    fun applyVoice(id: String) {
        val voice = _state.value.library.voices.find { it.id == id } ?: return
        val open = session.open ?: return say("No project is open")
        val recap = open.project.recap ?: return say("No Movie Recap project open")
        session.save(open.project.copy(recap = recap.copy(voiceLock = voice.lock)))
        say("Applied voice '${voice.name}'", ok = true)
    }

    fun applyMusic(id: String) {
        val music = _state.value.library.music.find { it.id == id } ?: return
        val open = session.open ?: return say("No project is open")
        runCatching { store.copyAsset(music.file, joinPath(open.dir, "source")) }
            .onSuccess { say("Copied '${music.name}' to project sources", ok = true) }
            .onFailure { say("Cannot copy music: ${it.message}") }
    }

    fun applyFont(id: String) {
        val font = _state.value.library.fonts.find { it.id == id } ?: return
        val open = session.open ?: return say("No project is open")
        runCatching { store.copyAsset(font.file, joinPath(open.dir, "library")) }
            .onSuccess { say("Copied font '${font.name}'", ok = true) }
            .onFailure { say("Cannot copy font: ${it.message}") }
    }

    fun applyPreset(id: String) {
        val preset = _state.value.library.presets.find { it.id == id } ?: return
        say("Preset '${preset.name}' (${preset.kind.name}) — application deferred to 5A-7 full impl.", ok = false)
    }

    private fun say(msg: String, ok: Boolean = false) {
        _state.value = _state.value.copy(message = msg, messageOk = ok)
    }
}
