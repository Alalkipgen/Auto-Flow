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
 * Voice & Audio (specs/quick-tools.md §6): the task chooser (Text → voice; Clean up sound and Video sound stay Planned
 * until V-2/V-3), then Text → voice with the text, its counter and part count; once the project exists also the voice
 * lock, tone chips and the parts list (V-1d). [onBack] leaves the tool.
 */
@Composable
fun VoiceAudioScreen(model: VoiceAudioModel, session: ProjectSession, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null,
    onOpened: () -> Unit = {}) {
    val open by session.state.collectAsState()
    val s by model.state.collectAsState()
    LaunchedEffect(open?.dir) { model.show() }
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
                null -> Chooser(model)
                VoiceTool.TASK_TEXT -> TextStep(model, s)
                else -> AfText(My.breakSyllables(tr(UiText.NEEDS_NEWER_APP)), muted = true)
            }
        }
        if (s.fresh && s.task == VoiceTool.TASK_TEXT) StickyActionBar(
            primary = StudioAction(My.breakSyllables(tr(UiText.VA_CONTINUE_TEXT)), { model.createWithText(onOpened) }, enabled = s.text.isNotBlank(),
                tag = "voice-continue"),
        )
    }
}

@Composable
private fun Chooser(model: VoiceAudioModel) {
    AfH2(My.breakSyllables(tr(UiText.VA_CHOOSE)))
    OptionCard(My.breakSyllables(tr(UiText.VA_TEXT_TO_VOICE)), My.breakSyllables(tr(UiText.VA_TEXT_TO_VOICE_INFO)), false,
        { model.choose(VoiceTool.TASK_TEXT) }, Modifier.fillMaxWidth().testTag("voice-task-text"))
    PlannedTile(My.breakSyllables(tr(UiText.VA_CLEAN)), modifier = Modifier.fillMaxWidth().testTag("voice-task-clean"))
    PlannedTile(My.breakSyllables(tr(UiText.VA_VIDEO_SOUND)), modifier = Modifier.fillMaxWidth().testTag("voice-task-video"))
}

@Composable
private fun TextStep(model: VoiceAudioModel, s: VoiceAudioUi) {
    AfH2(My.breakSyllables(tr(UiText.VA_TEXT_TO_VOICE)))
    AfTextField(s.text, model::setText, tr(UiText.VA_PASTE), Modifier.fillMaxWidth().testTag("voice-text"), lines = 8, mono = false)
    val parts = remember(s.text) { model.parts(s.text) }
    AfText(My.breakSyllables(tr(UiText.VA_COUNT, "chars" to s.text.length, "n" to parts)), Modifier.testTag("voice-count"), muted = true)
    if (s.fresh) return
    VoicePicker(model, s)
    AfText(tr(UiText.VA_TONE), bold = true)
    WrapRow {
        TONES.forEach { (id, label) -> AfChip(My.breakSyllables(tr(label)), s.tone == id, { model.setTone(id) }, Modifier.testTag("voice-tone-$id")) }
    }
    AfText(My.breakSyllables(tr(UiText.VA_TAKE_RULE)), muted = true)
    AfText(My.breakSyllables(tr(UiText.VA_PARTS, "n" to s.rows.size)), bold = true)
    s.rows.forEachIndexed { i, row ->
        Row(Modifier.fillMaxWidth().testTag("voice-part-${row.id}"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfText("${i + 1}.", muted = true)
            Column(Modifier.weight(1f)) {
                AfText(My.breakSyllables(row.text))
                AfText(My.breakSyllables(tr(if (row.voiced) UiText.VA_VOICED else UiText.VA_NOT_VOICED)), Modifier.testTag("voice-part-${row.id}-status"),
                    muted = !row.voiced, color = if (row.voiced) AutoFlowColors.Green else null)
            }
        }
    }
    PlannedTile(My.breakSyllables(tr(UiText.VA_SPEAK_PARTS, "n" to s.rows.count { !it.voiced })), My.breakSyllables(tr(UiText.VA_SPEAK_NEXT)),
        Modifier.fillMaxWidth().testTag("voice-speak-planned"))
}

private val TONES = listOf("calm" to UiText.VA_TONE_CALM, "lively" to UiText.VA_TONE_LIVELY, "news" to UiText.VA_TONE_NEWS,
    "story" to UiText.VA_TONE_STORY)

/** Saved voices first (Library), then the Burmese-capable models and voices; a tap saves the new lock. */
@Composable
private fun VoicePicker(model: VoiceAudioModel, s: VoiceAudioUi) {
    val lock = s.lock
    AfText(tr(UiText.VA_VOICE), bold = true)
    if (s.savedVoices.isNotEmpty()) {
        AfText(My.breakSyllables(tr(UiText.VA_SAVED_VOICES)), muted = true)
        WrapRow { s.savedVoices.forEach { v -> AfChip(v.name, lock == v.lock, { model.setLock(v.lock) }, Modifier.testTag("voice-saved-${v.id}")) } }
    }
    AfText(My.breakSyllables(tr(UiText.BURMESE_TTS_MODELS)), muted = true)
    if (model.models.isEmpty()) AfText(My.breakSyllables(tr(UiText.BURMESE_MODELS_MISSING)), color = AutoFlowColors.Amber)
    WrapRow {
        model.models.forEach { id ->
            AfChip(id, lock?.model == id, { model.setLock(VoiceLock("gemini", id, lock?.voice ?: "Charon", "my")) }, Modifier.testTag("voice-model-$id"))
        }
    }
    WrapRow {
        RecapModel.TTS_VOICES.forEach { (name, _) ->
            val modelId = lock?.model?.takeIf { it.isNotBlank() } ?: model.models.firstOrNull()
            AfChip(My.breakSyllables("$name · ${ttsVoiceKind(name)}"), lock?.voice == name, { modelId?.let { model.setLock(VoiceLock("gemini", it, name, "my")) } },
                Modifier.testTag("voice-voice-$name"), enabled = modelId != null)
        }
    }
}
