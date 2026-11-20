package io.github.alalkipgen.autoflow.ui.voice

import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.VoiceTool
import io.github.alalkipgen.autoflow.core.voice.VoiceParts
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
    val notice: UiNotice? = null)

/**
 * The Voice & Audio tool (specs/quick-tools.md §6, V-1c): task chooser, then the Text → voice text with its counter
 * and part count. The project is made at the first real step (Continue with text); after that the text is saved to
 * `project.voice.text` a moment after typing stops. Voice, tone and Speak come with V-1d/V-1e.
 */
class VoiceAudioModel(
    private val session: ProjectSession,
    private val dashboard: DashboardModel,
    private val config: ToolsConfig,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val offsetMs: (Long) -> Int = ::localOffsetMs,
    private val saveDelayMs: Long = 800,
) {
    private val _state = MutableStateFlow(VoiceAudioUi())
    val state: StateFlow<VoiceAudioUi> = _state.asStateFlow()
    private var saving: Job? = null
    /** The project open when the chooser began; opening another one shows that one instead. */
    private var freshOver: String? = null

    /** Home tile: the chooser; nothing is created yet. */
    fun begin() { flush(); freshOver = session.open?.dir; _state.value = VoiceAudioUi(fresh = true) }

    /** The screen of the open Voice & Audio project (or the chooser when none is open). */
    fun show() {
        val open = session.open
        if (_state.value.fresh && open?.dir == freshOver) return
        if (open == null || open.project.mode != Mode.VOICE_AUDIO) return run { freshOver = open?.dir; _state.value = VoiceAudioUi(fresh = true) }
        if (_state.value.dir == open.dir && !_state.value.fresh) return
        val voice = open.project.voice ?: VoiceTool()
        _state.value = VoiceAudioUi(task = voice.task, text = voice.text, dir = open.dir)
    }

    fun choose(task: String) { if (_state.value.fresh && task == VoiceTool.TASK_TEXT) _state.value = _state.value.copy(task = task) }

    /** Back inside a new task: to the chooser (the typed text is dropped; nothing was created). */
    fun backToChooser() { if (_state.value.fresh) _state.value = VoiceAudioUi(fresh = true) }

    fun setText(text: String) {
        val s = _state.value
        _state.value = s.copy(text = text, notice = null)
        val dir = s.dir ?: return
        saving?.cancel()
        saving = scope.launch { delay(saveDelayMs); save(dir, text) }
    }

    fun parts(text: String): Int = VoiceParts.split(text, config.tts.maxCharsPerPart).size

    /** Continue with text: makes and opens the project (automatic name, D-77); [onOpened] once it is open. */
    fun createWithText(onOpened: () -> Unit = {}): Job? {
        val s = _state.value.takeIf { it.fresh && it.task == VoiceTool.TASK_TEXT && it.text.isNotBlank() } ?: return null
        return scope.launch {
            val parent = dashboard.lastParent ?: dashboard.pickParent() ?: return@launch
            val name = "Voice · ${localStamp(clock.now(), offsetMs)}"
            if (!dashboard.createVoice(parent, name, VoiceTool(task = VoiceTool.TASK_TEXT, text = s.text))) {
                _state.value = s.copy(notice = dashboard.state.value.notice ?: UiNotice(UiText.RESULT_DETAIL, "detail" to (dashboard.state.value.message ?: "")))
                return@launch
            }
            _state.value = VoiceAudioUi(task = VoiceTool.TASK_TEXT, text = s.text, dir = session.open?.dir)
            onOpened()
        }
    }

    /** Leaving the screen: a pending text save is written now. */
    fun flush() {
        val s = _state.value
        val dir = s.dir ?: return
        if (saving?.isActive == true) { saving?.cancel(); save(dir, s.text) }
    }

    private fun save(dir: String, text: String) {
        val p = session.projectAt(dir) ?: return
        if (p.voice?.text == text) return
        session.update(dir, p.id) { it.copy(voice = (it.voice ?: VoiceTool(task = VoiceTool.TASK_TEXT)).copy(text = text)) }
    }
}
