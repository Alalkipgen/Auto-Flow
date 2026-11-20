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
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/**
 * Voice & Audio (specs/quick-tools.md §6): the task chooser (Text → voice; Clean up sound and Video sound stay Planned
 * until V-2/V-3), then Text → voice with the text, its counter and part count. [onBack] leaves the tool.
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
    if (!s.fresh) PlannedTile(My.breakSyllables(tr(UiText.VA_SPEAK_PARTS, "n" to parts)), My.breakSyllables(tr(UiText.VA_SPEAK_NEXT)),
        Modifier.fillMaxWidth().testTag("voice-speak-planned"))
}
