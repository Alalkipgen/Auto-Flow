package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfCard
import io.github.alalkipgen.autoflow.ui.components.AfChip
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/** Explicit lock changes, never a chip that silently changes the voice of future blocks (D-35). */
@Composable
internal fun VoiceLockControls(model: RecapModel, state: RecapUiState, recap: RecapV2, idle: Boolean) {
    val controls = model.voiceSettings
    var draft by remember(state.projectDir) { mutableStateOf<VoiceChangeDraft?>(null) }
    val lock = recap.voiceLock
    AfText(lock?.let { tr(UiText.VOICE_LOCK_STATUS, "provider" to (it.provider), "ifBlankconfigureddef" to (it.model.ifBlank { tr(UiText.VOICE_CONFIG_DEFAULT) }), "voice" to (it.voice)) }
        ?: tr(UiText.VOICE_NOT_LOCKED))
    AfButton(tr(UiText.CHANGE_VOICE), { draft = controls.begin() }, enabled = idle, modifier = Modifier.testTag("change-voice"))
    AfText(tr(UiText.VOICE_LOCK_POLICY), muted = true)
    val captured = draft ?: return
    Dialog(onDismissRequest = { draft = null }) {
        AfCard(tr(UiText.CHANGE_VOICE), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var selectedModel by remember(captured) { mutableStateOf(lock?.model?.takeIf { it in controls.models } ?: controls.models.firstOrNull().orEmpty()) }
                var voice by remember(captured) { mutableStateOf(lock?.voice ?: "Charon") }
                var error by remember { mutableStateOf<String?>(null) }
                val next = VoiceLock("gemini", selectedModel, voice, "my")
                val affected = controls.affected(captured.project.recap!!, captured.story, next)
                val requests = controls.regenerationCount(captured, next, state.lexicon)
                AfText(tr(UiText.BURMESE_TTS_MODELS), muted = true)
                if (controls.models.isEmpty()) AfText(tr(UiText.BURMESE_MODELS_MISSING), color = AutoFlowColors.Amber)
                WrapRow { controls.models.forEach { id -> AfChip(id, selectedModel == id, { selectedModel = id }) } }
                WrapRow { RecapModel.TTS_VOICES.forEach { (name, kind) -> AfChip("$name · ${ttsVoiceKind(name)}", voice == name, { voice = name }) } }
                AfText(tr(UiText.VOICE_CHANGE_IMPACT, "size" to (affected.size)) + affected.joinToString { "#${it + 1}" }, modifier = Modifier.testTag("voice-change-impact"))
                AfText(tr(UiText.VOICE_CHANGE_PRESERVES), muted = true)
                error?.let { AfText(it, color = AutoFlowColors.Amber) }
                fun commit(regenerate: Boolean) {
                    error = controls.apply(captured, next)
                    if (error == null) {
                        draft = null
                        if (regenerate) model.speakAll(confirmed = true, includeDifferentVoice = true)
                    }
                }
                WrapRow {
                    AfButton(tr(UiText.KEEP_OLD_TAKES), { commit(false) }, enabled = selectedModel.isNotBlank(), modifier = Modifier.testTag("voice-keep"))
                    val seconds = requests * 60L / RecapModel.TTS_RPM
                    AfButton(tr(UiText.REGENERATE_TAKES, "requests" to (requests), "seconds5960" to ((seconds + 59) / 60)), { commit(true) }, kind = ButtonKind.Primary,
                        enabled = selectedModel.isNotBlank() && requests > 0, modifier = Modifier.testTag("voice-regenerate"))
                    AfButton(tr(UiText.CANCEL), { draft = null })
                }
            }
        }
    }
}

@Composable
internal fun TakeVoiceLabel(take: io.github.alalkipgen.autoflow.core.recap.BlockTake?, lock: VoiceLock?) {
    when (take?.voiceStatus(lock)) {
        TakeVoiceStatus.DIFFERENT -> AfText(tr(UiText.DIFFERENT_VOICE), color = AutoFlowColors.Amber)
        TakeVoiceStatus.UNKNOWN -> AfText(tr(UiText.VOICE_UNKNOWN), color = AutoFlowColors.Amber)
        else -> Unit
    }
}

/** A take spoken with another voice than the lock (DESIGN.md §7 "Different voice" chip). */
internal const val DIFFERENT_VOICE = "အသံ မတူ"
