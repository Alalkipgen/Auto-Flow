package io.github.alalkipgen.autoflow.ui.voice

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.library.LibraryStore
import io.github.alalkipgen.autoflow.core.library.SavedVoice
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.VoiceTool
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.core.voice.VoiceParts
import io.github.alalkipgen.autoflow.core.voice.VoiceTakes
import io.github.alalkipgen.autoflow.ui.i18n.UiNotice
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.projects.DashboardModel
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.subtitles.localOffsetMs
import io.github.alalkipgen.autoflow.ui.subtitles.localStamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * [fresh]: the Home tile's chooser, before any project exists; [task] the chosen task; [text] the Text → voice text
 * (the open project's, or the one typed before Continue).
 */
data class VoiceAudioUi(val fresh: Boolean = false, val task: String? = null, val text: String = "", val dir: String? = null,
    val notice: UiNotice? = null, val lock: VoiceLock? = null, val tone: String? = null, val rows: List<VoicePartRow> = emptyList(),
    val savedVoices: List<SavedVoice> = emptyList())

/** One row of the parts list: its text and whether it has a current take. */
data class VoicePartRow(val id: String, val text: String, val voiced: Boolean)

/**
 * The Voice & Audio tool (specs/quick-tools.md §6, V-1c): task chooser, then the Text → voice text with its counter
 * and part count. The project is made at the first real step (Continue with text, locked to the first Burmese model of
 * [models]); after that the text is saved a moment after typing stops. V-1d: voice lock (saved voices first), tone and the
 * parts list — every save re-splits with [VoiceTakes.plan], so only changed parts lose their take. Speak comes with V-1e.
 */
class VoiceAudioModel(
    private val session: ProjectSession,
    private val dashboard: DashboardModel,
    private val config: ToolsConfig,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val offsetMs: (Long) -> Int = ::localOffsetMs,
    private val saveDelayMs: Long = 800,
    private val library: LibraryStore? = null,
    /** Burmese-capable TTS models (`config/models.json`), first = default. */
    val models: List<String> = emptyList(),
) {
    private val _state = MutableStateFlow(VoiceAudioUi())
    val state: StateFlow<VoiceAudioUi> = _state.asStateFlow()
    private var saving: Job? = null
    /** The project open when the chooser began; opening another one shows that one instead. */
    private var freshOver: String? = null
    /** The voice as last saved; rows are planned from it so a part typed away and back keeps its take. */
    private var saved: VoiceTool = VoiceTool()
    private val maxChars get() = config.tts.maxCharsPerPart
    private fun lexicon() = library?.lexicon?.value ?: Lexicon.EMPTY

    /** Home tile: the chooser; nothing is created yet. */
    fun begin() { flush(); freshOver = session.open?.dir; _state.value = VoiceAudioUi(fresh = true) }

    /** The screen of the open Voice & Audio project (or the chooser when none is open). */
    fun show() {
        val open = session.open
        if (_state.value.fresh && open?.dir == freshOver) return
        if (open == null || open.project.mode != Mode.VOICE_AUDIO) return run { freshOver = open?.dir; _state.value = VoiceAudioUi(fresh = true) }
        if (_state.value.dir == open.dir && !_state.value.fresh) return
        val voice = open.project.voice ?: VoiceTool()
        saved = voice
        _state.value = VoiceAudioUi(task = voice.task, text = voice.text, dir = open.dir, lock = voice.lock, tone = voice.tone,
            rows = rows(voice), savedVoices = library?.load()?.voices.orEmpty())
    }

    fun choose(task: String) { if (_state.value.fresh && task == VoiceTool.TASK_TEXT) _state.value = _state.value.copy(task = task) }

    /** Back inside a new task: to the chooser (the typed text is dropped; nothing was created). */
    fun backToChooser() { if (_state.value.fresh) _state.value = VoiceAudioUi(fresh = true) }

    fun setText(text: String) {
        val s = _state.value
        _state.value = s.copy(text = text, notice = null, rows = if (s.dir == null) s.rows else rows(saved.copy(text = text)))
        val dir = s.dir ?: return
        saving?.cancel()
        saving = scope.launch { delay(saveDelayMs); save(dir, text) }
    }

    fun parts(text: String): Int = VoiceParts.split(text, maxChars).size

    /** A saved voice, model or voice chip: the new lock is saved now; parts spoken with another voice lose their take. */
    fun setLock(lock: VoiceLock) = change { it.copy(lock = lock) }

    /** Tone chip; the chosen one again clears it. */
    fun setTone(tone: String?) = change { it.copy(tone = tone?.takeIf { t -> t in VoiceTakes.TONES && t != it.tone }) }

    fun defaultLock(): VoiceLock? = models.firstOrNull()?.let { VoiceLock("gemini", it, "Charon", "my") }

    private fun change(edit: (VoiceTool) -> VoiceTool) {
        val s = _state.value
        val dir = s.dir ?: return
        saving?.cancel()
        val next = save(dir, edit(saved.copy(text = s.text))) ?: return
        _state.value = s.copy(lock = next.lock, tone = next.tone, rows = rows(next))
    }

    private fun rows(voice: VoiceTool): List<VoicePartRow> =
        VoiceTakes.texts(voice, maxChars).zip(VoiceTakes.plan(voice, maxChars, lexicon()).parts) { t, p -> VoicePartRow(p.id, t, p.take != null) }

    /** Continue with text: makes and opens the project (automatic name, D-77); [onOpened] once it is open. */
    fun createWithText(onOpened: () -> Unit = {}): Job? {
        val s = _state.value.takeIf { it.fresh && it.task == VoiceTool.TASK_TEXT && it.text.isNotBlank() } ?: return null
        return scope.launch {
            val parent = dashboard.lastParent ?: dashboard.pickParent() ?: return@launch
            val name = "Voice · ${localStamp(clock.now(), offsetMs)}"
            val voice = VoiceTakes.plan(VoiceTool(task = VoiceTool.TASK_TEXT, text = s.text, lock = defaultLock()), maxChars, lexicon())
            if (!dashboard.createVoice(parent, name, voice)) {
                _state.value = s.copy(notice = dashboard.state.value.notice ?: UiNotice(UiText.RESULT_DETAIL, "detail" to (dashboard.state.value.message ?: "")))
                return@launch
            }
            saved = voice
            _state.value = VoiceAudioUi(task = VoiceTool.TASK_TEXT, text = s.text, dir = session.open?.dir, lock = voice.lock,
                rows = rows(voice), savedVoices = library?.load()?.voices.orEmpty())
            onOpened()
        }
    }

    /** Leaving the screen: a pending text save is written now. */
    fun flush() {
        val s = _state.value
        val dir = s.dir ?: return
        if (saving?.isActive == true) { saving?.cancel(); save(dir, s.text) }
    }

    private fun save(dir: String, text: String) { save(dir, saved.copy(text = text)) }

    /** Writes [voice] re-planned against the project's current parts; returns what was saved (null when it could not be). */
    private fun save(dir: String, voice: VoiceTool): VoiceTool? {
        val p = session.projectAt(dir) ?: return null
        val lexicon = lexicon()
        fun planned(v: VoiceTool?) = VoiceTakes.plan((v ?: VoiceTool(task = VoiceTool.TASK_TEXT)).copy(text = voice.text, lock = voice.lock,
            tone = voice.tone), maxChars, lexicon)
        if (planned(p.voice) == p.voice) return p.voice?.also { saved = it }
        return (session.update(dir, p.id) { it.copy(voice = planned(it.voice)) } as? CoreResult.Ok)?.value?.voice?.also { saved = it }
    }
}
