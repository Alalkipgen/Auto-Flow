package io.github.alalkipgen.autoflow.ui.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.model.VoiceTool
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.recap.RecapModel
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/**
 * Voice & Audio (specs/quick-tools.md §6): the task chooser (Text → voice, Clean up sound, Video sound), then Text → voice with the text, its counter and part count; once the project exists also the voice
 * lock, tone chips and the parts list (V-1d) with ▶ / ↻ and Speak (V-1e, [speak]); once every part is voiced Save m4a /
 * Save wav (V-1f, [export]). Clean up sound (V-2b, [clean]). Video sound (V-3b, [video]). [onBack] leaves the tool.
 */
@Composable
fun VoiceAudioScreen(model: VoiceAudioModel, session: ProjectSession, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null,
    onOpened: () -> Unit = {}, speak: VoiceSpeakModel? = null, export: VoiceExportModel? = null,
    clean: VoiceCleanModel? = null, video: VideoSoundModel? = null) {
    val open by session.state.collectAsState()
    val s by model.state.collectAsState()
    val jobs by (speak?.jobs ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList()) }).collectAsState()
    val job = speak?.jobOf(s.dir, jobs)
    LaunchedEffect(open?.dir) { model.show() }
    LaunchedEffect(open?.project) { model.sync() }
    DisposableEffect(Unit) { onDispose { model.flush() } }
    val back: (() -> Unit)? = if (s.fresh && s.task != null) model::backToChooser else onBack
    Column(modifier.fillMaxSize().background(AutoFlowColors.Background), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 900.dp).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(screenPadding)
            .testTag("voice-scroll"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            WrapRow {
                back?.let { AfButton(tr(UiText.PROJECTS_BACK), it, Modifier.heightIn(min = 48.dp).testTag("voice-back")) }
                AfText(open?.project?.name?.takeIf { !s.fresh } ?: tr(UiText.V3_VOICE_AUDIO), bold = true)
            }
            s.notice?.let { AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.testTag("voice-notice"), color = AutoFlowColors.Failed) }
            when (s.task) {
                null -> Chooser(model, clean, video, onOpened)
                VoiceTool.TASK_CLEAN -> if (clean != null) CleanStep(clean, open?.project?.voice, s.dir, export, open?.project?.name.orEmpty())
                VoiceTool.TASK_VIDEO_SOUND -> if (video != null) VideoSoundStep(video, open?.project?.voice, s.dir, export, open?.project?.name.orEmpty())
                VoiceTool.TASK_TEXT -> TextStep(model, s, speak, job, export, open?.project?.name.orEmpty())
                else -> AfText(My.breakSyllables(tr(UiText.NEEDS_NEWER_APP)), muted = true)
            }
        }
        if (s.fresh && s.task == VoiceTool.TASK_TEXT) StickyActionBar(
            primary = StudioAction(My.breakSyllables(tr(UiText.VA_CONTINUE_TEXT)), { model.createWithText(onOpened) }, enabled = s.text.isNotBlank(),
                tag = "voice-continue"),
        ) else if (s.task == VoiceTool.TASK_CLEAN && clean != null && open?.project?.voice?.source != null) {
            val c by clean.state.collectAsState()
            val idle = c.busy == null || c.dir != s.dir
            StickyActionBar(primary = StudioAction(My.breakSyllables(tr(UiText.VA_SAVE_M4A)), { clean.save("m4a") }, enabled = idle, tag = "voice-clean-m4a"),
                secondary = StudioAction(My.breakSyllables(tr(UiText.VA_SAVE_WAV)), { clean.save("wav") }, enabled = idle, tag = "voice-clean-wav"))
        } else if (s.task == VoiceTool.TASK_VIDEO_SOUND && video != null && open?.project?.voice?.source != null) {
            val v by video.state.collectAsState()
            val idle = v.busy == null || v.dir != s.dir
            val action = v.action.takeIf { v.dir == s.dir } ?: SOUND_ONLY
            if (action == SOUND_ONLY) StickyActionBar(
                primary = StudioAction(My.breakSyllables(tr(UiText.VA_SAVE_M4A)), { video.save("m4a") }, enabled = idle, tag = "video-sound-m4a"),
                secondary = StudioAction(My.breakSyllables(tr(UiText.VA_SAVE_WAV)), { video.save("wav") }, enabled = idle, tag = "video-sound-wav"))
            else StickyActionBar(primary = StudioAction(My.breakSyllables(tr(UiText.SUB_MAKE_VIDEO)), { video.save() },
                enabled = idle && v.dir == s.dir && v.sound != null, tag = "video-sound-make"))
        } else if (s.task == VoiceTool.TASK_TEXT && job == null) {
            val n = s.rows.count { !it.voiced }
            if (n == 0 && s.rows.isNotEmpty() && export != null) StickyActionBar(
                primary = StudioAction(My.breakSyllables(tr(UiText.VA_SAVE_M4A)), { export.saveM4a() }, tag = "voice-save-m4a"),
                secondary = StudioAction(My.breakSyllables(tr(UiText.VA_SAVE_WAV)), { export.saveWav() }, tag = "voice-save-wav"),
            ) else if (speak != null) StickyActionBar(primary = StudioAction(My.breakSyllables(tr(UiText.VA_SPEAK_PARTS, "n" to n)), { speak.ask() }, enabled = n > 0,
                tag = "voice-speak"))
        }
    }
}

@Composable
private fun Chooser(model: VoiceAudioModel, clean: VoiceCleanModel?, video: VideoSoundModel?, onOpened: () -> Unit) {
    AfH2(My.breakSyllables(tr(UiText.VA_CHOOSE)))
    OptionCard(My.breakSyllables(tr(UiText.VA_TEXT_TO_VOICE)), My.breakSyllables(tr(UiText.VA_TEXT_TO_VOICE_INFO)), false,
        { model.choose(VoiceTool.TASK_TEXT) }, Modifier.fillMaxWidth().testTag("voice-task-text"))
    if (clean == null) PlannedTile(My.breakSyllables(tr(UiText.VA_CLEAN)), modifier = Modifier.fillMaxWidth().testTag("voice-task-clean"))
    else {
        OptionCard(My.breakSyllables(tr(UiText.VA_CLEAN)), My.breakSyllables(tr(UiText.VA_CLEAN_INFO)), false, { clean.start(onOpened) },
            Modifier.fillMaxWidth().testTag("voice-task-clean"))
        val c by clean.state.collectAsState()
        c.notice?.takeIf { c.dir == null }?.let { AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.testTag("voice-clean-notice"), color = AutoFlowColors.Amber) }
    }
    if (video == null) PlannedTile(My.breakSyllables(tr(UiText.VA_VIDEO_SOUND)), modifier = Modifier.fillMaxWidth().testTag("voice-task-video"))
    else {
        OptionCard(My.breakSyllables(tr(UiText.VA_VIDEO_SOUND)), My.breakSyllables(tr(UiText.VA_VIDEO_SOUND_INFO)), false, { video.start(onOpened) },
            Modifier.fillMaxWidth().testTag("voice-task-video"))
        val v by video.state.collectAsState()
        v.notice?.takeIf { v.dir == null }?.let { AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.testTag("video-sound-notice"), color = AutoFlowColors.Amber) }
    }
}

@Composable
private fun TextStep(model: VoiceAudioModel, s: VoiceAudioUi, speak: VoiceSpeakModel?, job: io.github.alalkipgen.autoflow.core.recap.AiJob?,
    export: VoiceExportModel?, title: String) {
    val idle = job == null
    AfH2(My.breakSyllables(tr(UiText.VA_TEXT_TO_VOICE)))
    AfTextField(s.text, model::setText, tr(UiText.VA_PASTE), Modifier.fillMaxWidth().testTag("voice-text"), lines = 8, mono = false, enabled = idle)
    val parts = remember(s.text) { model.parts(s.text) }
    AfText(My.breakSyllables(tr(UiText.VA_COUNT, "chars" to s.text.length, "n" to parts)), Modifier.testTag("voice-count"), muted = true)
    if (s.fresh) return
    VoicePicker(model, s, idle)
    AfText(tr(UiText.VA_TONE), bold = true)
    WrapRow {
        TONES.forEach { (id, label) -> AfChip(My.breakSyllables(tr(label)), s.tone == id, { model.setTone(id) }, Modifier.testTag("voice-tone-$id"), enabled = idle) }
    }
    AfText(My.breakSyllables(tr(UiText.VA_TAKE_RULE)), muted = true)
    s.dir?.let { dir -> speak?.let { SpeakPanel(it, dir, job) } }
    s.dir?.let { dir -> export?.let { ExportPanel(it, dir, title) } }
    AfText(My.breakSyllables(tr(UiText.VA_PARTS, "n" to s.rows.size)), bold = true)
    var playing by remember { mutableStateOf<String?>(null) }
    s.rows.forEachIndexed { i, row ->
        Row(Modifier.fillMaxWidth().testTag("voice-part-${row.id}"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfText("${i + 1}.", muted = true)
            Column(Modifier.weight(1f)) {
                AfText(My.breakSyllables(row.text))
                AfText(My.breakSyllables(tr(if (row.voiced) UiText.VA_VOICED else UiText.VA_NOT_VOICED)), Modifier.testTag("voice-part-${row.id}-status"),
                    muted = !row.voiced, color = if (row.voiced) AutoFlowColors.Green else null)
                if (row.take != null) WrapRow {
                    model.takePath(row.take)?.let { AudioPlayButton(it, playing == row.id, { playing = if (playing == row.id) null else row.id }) }
                    if (speak != null) AfButton(My.breakSyllables("↻ " + tr(UiText.VA_REDO_PART)), { speak.ask(setOf(row.id)) },
                        Modifier.testTag("voice-redo-${row.id}"), enabled = idle)
                }
            }
        }
    }
}

private val TONES = listOf("calm" to UiText.VA_TONE_CALM, "lively" to UiText.VA_TONE_LIVELY, "news" to UiText.VA_TONE_NEWS,
    "story" to UiText.VA_TONE_STORY)

/** Saved voices first (Library), then the Burmese-capable models and voices; a tap saves the new lock. */
@Composable
private fun VoicePicker(model: VoiceAudioModel, s: VoiceAudioUi, idle: Boolean) {
    val lock = s.lock
    AfText(tr(UiText.VA_VOICE), bold = true)
    if (s.savedVoices.isNotEmpty()) {
        AfText(My.breakSyllables(tr(UiText.VA_SAVED_VOICES)), muted = true)
        WrapRow { s.savedVoices.forEach { v -> AfChip(v.name, lock == v.lock, { model.setLock(v.lock) }, Modifier.testTag("voice-saved-${v.id}"), enabled = idle) } }
    }
    AfText(My.breakSyllables(tr(UiText.BURMESE_TTS_MODELS)), muted = true)
    if (model.models.isEmpty()) AfText(My.breakSyllables(tr(UiText.BURMESE_MODELS_MISSING)), color = AutoFlowColors.Amber)
    WrapRow {
        model.models.forEach { id ->
            AfChip(id, lock?.model == id, { model.setLock(VoiceLock("gemini", id, lock?.voice ?: "Charon", "my")) }, Modifier.testTag("voice-model-$id"), enabled = idle)
        }
    }
    WrapRow {
        RecapModel.TTS_VOICES.forEach { (name, _) ->
            val modelId = io.github.alalkipgen.autoflow.ui.voice.EdgeVoices.geminiModel(lock, model.models)
            AfChip(My.breakSyllables("$name · ${ttsVoiceKind(name)}"), lock?.voice == name, { modelId?.let { model.setLock(VoiceLock("gemini", it, name, "my")) } },
                Modifier.testTag("voice-voice-$name"), enabled = idle && modelId != null)
        }
    }
    model.edgeVoices()?.let { edge ->
        io.github.alalkipgen.autoflow.ui.voice.EdgeVoiceChips(edge, lock, idle, "voice") { model.setLock(it) }
    }
}
