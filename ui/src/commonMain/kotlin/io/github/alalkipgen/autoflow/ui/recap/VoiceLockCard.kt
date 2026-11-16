package io.github.alalkipgen.autoflow.ui.recap

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
    AfText(lock?.let { "${it.provider} · ${it.model.ifBlank { "configured default (first take)" }} · ${it.voice} · 🔒 သော့ခတ်ထား" }
        ?: "အသံ သော့မခတ်ရသေးပါ — ပထမဆုံး TTS အဖြေက သူ့ model ကို သော့ခတ်မယ်။")
    AfButton("အသံ ပြောင်းမယ်…", { draft = controls.begin() }, enabled = idle, modifier = Modifier.testTag("change-voice"))
    AfText("Model အလုပ်များရင် ခဏရပ်မယ်၊ model / provider ကို အလိုအလျောက် မပြောင်းပါ။ အသံသွင်းထားတာနဲ့ ထည့်ထားတဲ့ အသံတွေ မပြောင်းပါ။", muted = true)
    val captured = draft ?: return
    Dialog(onDismissRequest = { draft = null }) {
        AfCard("အသံ ပြောင်းမယ်…", modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var selectedModel by remember(captured) { mutableStateOf(lock?.model?.takeIf { it in controls.models } ?: controls.models.firstOrNull().orEmpty()) }
                var voice by remember(captured) { mutableStateOf(lock?.voice ?: "Charon") }
                var error by remember { mutableStateOf<String?>(null) }
                val next = VoiceLock("gemini", selectedModel, voice, "my")
                val affected = controls.affected(captured.project.recap!!, captured.story, next)
                val requests = controls.regenerationCount(captured, next, state.lexicon)
                AfText("မြန်မာလို ပြောနိုင်သော model များ (config/models.json)", muted = true)
                if (controls.models.isEmpty()) AfText("မြန်မာလို ပြောနိုင်သော model မသတ်မှတ်ရသေးပါ။ Model ဆက်တင်ကို စစ်ပါ။", color = AutoFlowColors.Amber)
                WrapRow { controls.models.forEach { id -> AfChip(id, selectedModel == id, { selectedModel = id }) } }
                WrapRow { RecapModel.TTS_VOICES.forEach { (name, kind) -> AfChip("$name · $kind", voice == name, { voice = name }) } }
                AfText("ရွေးထားသော TTS take ${affected.size} ခု အသံ မတူ / အသံ မသိ ဖြစ်မယ်: " + affected.joinToString { "#${it + 1}" }, modifier = Modifier.testTag("voice-change-impact"))
                AfText("အသံဟောင်း၊ take မှတ်တမ်း၊ စာတန်းနဲ့ အတည်ပြုချက်တွေ ကျန်မယ်။ ပြန်ပြောရင် take အသစ် ထပ်ထည့်တယ်၊ အဟောင်းကို မဖျက်ပါ။", muted = true)
                error?.let { AfText(it, color = AutoFlowColors.Amber) }
                fun commit(regenerate: Boolean) {
                    error = controls.apply(captured, next)
                    if (error == null) {
                        draft = null
                        if (regenerate) model.speakAll(confirmed = true, includeDifferentVoice = true)
                    }
                }
                WrapRow {
                    AfButton("Take ဟောင်းတွေ ထားမယ်", { commit(false) }, enabled = selectedModel.isNotBlank(), modifier = Modifier.testTag("voice-keep"))
                    val seconds = requests * 60L / RecapModel.TTS_RPM
                    AfButton("အပိုင်း $requests ခု ပြန်ပြောမယ် (≈ ${(seconds + 59) / 60} မိနစ်)", { commit(true) }, kind = ButtonKind.Primary,
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
        TakeVoiceStatus.DIFFERENT -> AfText(DIFFERENT_VOICE, color = AutoFlowColors.Amber)
        TakeVoiceStatus.UNKNOWN -> AfText("အသံ မသိ", color = AutoFlowColors.Amber)
        else -> Unit
    }
}

/** A take spoken with another voice than the lock (DESIGN.md §7 "Different voice" chip). */
internal const val DIFFERENT_VOICE = "အသံ မတူ"
