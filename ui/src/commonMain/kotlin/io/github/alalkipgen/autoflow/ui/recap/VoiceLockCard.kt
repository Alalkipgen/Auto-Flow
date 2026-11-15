package io.github.alalkipgen.autoflow.ui.recap

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
    AfText(lock?.let { "${it.provider} · ${it.model.ifBlank { "configured default (first take)" }} · ${it.voice} · locked" }
        ?: "No voice lock — the first TTS response locks its configured model.")
    AfButton("Change voice…", { draft = controls.begin() }, enabled = idle, modifier = Modifier.testTag("change-voice"))
    AfText("Busy models pause; no automatic model/provider switch. Recorded and imported audio stay unchanged.", muted = true)
    val captured = draft ?: return
    Dialog(onDismissRequest = { draft = null }) {
        AfCard("Change voice…", modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var selectedModel by remember(captured) { mutableStateOf(lock?.model?.takeIf { it in controls.models } ?: controls.models.firstOrNull().orEmpty()) }
                var voice by remember(captured) { mutableStateOf(lock?.voice ?: "Charon") }
                var error by remember { mutableStateOf<String?>(null) }
                val next = VoiceLock("gemini", selectedModel, voice, "my")
                val affected = controls.affected(captured.project.recap!!, captured.story, next)
                val requests = controls.regenerationCount(captured, next, state.lexicon)
                AfText("Burmese-capable models (config/models.json)", muted = true)
                if (controls.models.isEmpty()) AfText("No configured Burmese-capable model. Check model configuration.", color = AutoFlowColors.Amber)
                WrapRow { controls.models.forEach { id -> AfChip(id, selectedModel == id, { selectedModel = id }) } }
                WrapRow { RecapModel.TTS_VOICES.forEach { (name, kind) -> AfChip("$name · $kind", voice == name, { voice = name }) } }
                AfText("${affected.size} selected TTS takes will be Different voice / voice unknown: " + affected.joinToString { "#${it + 1}" }, modifier = Modifier.testTag("voice-change-impact"))
                AfText("Old audio, take history, captions and approvals are kept. Re-speaking adds new takes; it does not overwrite artifacts.", muted = true)
                error?.let { AfText(it, color = AutoFlowColors.Amber) }
                fun commit(regenerate: Boolean) {
                    error = controls.apply(captured, next)
                    if (error == null) {
                        draft = null
                        if (regenerate) model.speakAll(confirmed = true, includeDifferentVoice = true)
                    }
                }
                WrapRow {
                    AfButton("Keep old takes", { commit(false) }, enabled = selectedModel.isNotBlank(), modifier = Modifier.testTag("voice-keep"))
                    val seconds = requests * 60L / RecapModel.TTS_RPM
                    AfButton("Re-speak $requests blocks (≈ ${(seconds + 59) / 60} min)", { commit(true) }, kind = ButtonKind.Primary,
                        enabled = selectedModel.isNotBlank() && requests > 0, modifier = Modifier.testTag("voice-regenerate"))
                    AfButton("Cancel", { draft = null })
                }
            }
        }
    }
}

@Composable
internal fun TakeVoiceLabel(take: io.github.alalkipgen.autoflow.core.recap.BlockTake?, lock: VoiceLock?) {
    when (take?.voiceStatus(lock)) {
        TakeVoiceStatus.DIFFERENT -> AfText("Different voice", color = AutoFlowColors.Amber)
        TakeVoiceStatus.UNKNOWN -> AfText("Voice unknown", color = AutoFlowColors.Amber)
        else -> Unit
    }
}
