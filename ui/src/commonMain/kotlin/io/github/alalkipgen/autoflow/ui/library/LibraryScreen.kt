package io.github.alalkipgen.autoflow.ui.library

import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.library.BrandKit
import io.github.alalkipgen.autoflow.core.library.FontItem
import io.github.alalkipgen.autoflow.core.library.Library
import io.github.alalkipgen.autoflow.core.library.MusicItem
import io.github.alalkipgen.autoflow.core.library.Preset
import io.github.alalkipgen.autoflow.core.library.SavedVoice
import io.github.alalkipgen.autoflow.core.recap.LexiconEntry
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfCard
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes
import io.github.alalkipgen.autoflow.ui.components.AfTextField
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.i18n.My

/**
 * Studio v2 Library screen (5A-7, DESIGN.md §7 "Library").
 * Sections: Brand Kits · Voices · Lexicon · Music · Fonts · Presets.
 * "Apply" copies the item into the current project (D-39).
 */
@Composable
fun LibraryScreen(model: LibraryModel, modifier: Modifier = Modifier) {
    val state by model.state.collectAsState()
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(screenPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AfText("စာကြည့်တိုက်", size = 20.sp, bold = true)
        AfText("${tr(UiText.APPLY)} = လက်ရှိ project ထဲကို ကူးထည့်တယ်။ Project တွေက စာကြည့်တိုက်ထဲက ဖိုင်ကို တိုက်ရိုက် မသုံးပါ။", muted = true, size = 12.sp)

        BrandKitsSection(state.library.brandKits, model)
        VoicesSection(state.library.voices, model)
        LexiconSection(state.library.lexicon, model)
        MusicSection(state.library.music, model)
        FontsSection(state.library.fonts, model)
        PresetsSection(state.library.presets, model)

        state.message?.let { AfText(it, color = if (state.messageOk) AutoFlowColors.Green else AutoFlowColors.Amber) }
    }
}

@Composable
private fun BrandKitsSection(items: List<BrandKit>, model: LibraryModel) {
    AfCard("Brand Kit များ (${items.size})") {
        // Save current project as a new Brand Kit (5B-3, D-51)
        var newName by remember { mutableStateOf("") }
        AfButton("💼 လက်ရှိ project ကို Brand Kit အဖြစ် သိမ်းမယ်…", { newName = "My Brand Kit" })
        if (newName.isNotEmpty()) {
            AfTextField(newName, { newName = it }, "Brand Kit အမည်")
            WrapRow {
                AfButton(tr(UiText.SAVE), { if (newName.isNotBlank()) { model.saveBrandKit(newName); newName = "" } }, kind = ButtonKind.Primary, enabled = newName.isNotBlank())
                AfButton(tr(UiText.CANCEL), { newName = "" })
            }
        }
        if (items.isEmpty()) {
            AfText("Brand Kit မသိမ်းရသေးပါ။ အပေါ်က ခလုတ်နဲ့ လက်ရှိ project ရဲ့ ဆက်တင်တွေကို သိမ်းပါ။", muted = true)
        } else {
            items.forEach { kit ->
                var confirmDeleteKit by remember(kit.id) { mutableStateOf(false) }
                if (confirmDeleteKit) {
                    androidx.compose.ui.window.Dialog(onDismissRequest = { confirmDeleteKit = false }) {
                        Column(
                            Modifier
                                .fillMaxWidth(0.85f)
                                .clip(AutoFlowShapes.Card)
                                .background(AutoFlowColors.Panel)
                                .padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            AfText("\"${kit.name}\" ကို ဖျက်မလား?", bold = true)
                            AfText("ဒီ Brand Kit ကို စာကြည့်တိုက်ထဲက ဖယ်ရှားမယ်။", muted = true)
                            WrapRow {
                                AfButton(tr(UiText.DELETE), { model.deleteBrandKit(kit.id); confirmDeleteKit = false }, kind = ButtonKind.Danger)
                                AfButton(tr(UiText.CANCEL), { confirmDeleteKit = false })
                            }
                        }
                    }
                }
                Column(
                    Modifier.fillMaxWidth()
                        .combinedClickable(onClick = {}, onLongClick = { confirmDeleteKit = true })
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AfText(kit.name, bold = true)
                    if (kit.colours.isNotEmpty()) AfText("အရောင်များ: ${kit.colours.joinToString()}", muted = true, size = 12.sp)
                    if (kit.captionStyle != null) AfText("စာတန်းပုံစံ: သိမ်းထား", muted = true, size = 12.sp)
                    if (kit.watermark != null) AfText("Watermark: ${kit.watermark!!.substringAfterLast('/')}", muted = true, size = 12.sp)
                    AfButton("လက်ရှိ project မှာ ${tr(UiText.APPLY)}", { model.applyBrandKit(kit.id) })
                    AfText("ဖျက်ရန် ကြာကြာ ဖိထားပါ", muted = true, size = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun VoicesSection(items: List<SavedVoice>, model: LibraryModel) {
    AfCard("အသံများ (${items.size})") {
        if (items.isEmpty()) {
            AfText("သိမ်းထားသော အသံ မရှိသေးပါ။ Movie Recap → အသံ မှာ အသံတစ်ခု သော့ခတ်ရင် ဒီမှာ သိမ်းမယ်။", muted = true)
        } else {
            items.forEach { v ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AfText(v.name, bold = true)
                    AfText("${v.lock.provider} · ${v.lock.model} · ${v.lock.voice} · ${v.lock.lang}", muted = true, size = 12.sp)
                    AfButton(tr(UiText.APPLY), { model.applyVoice(v.id) })
                }
            }
        }
    }
}

@Composable
private fun LexiconSection(items: List<LexiconEntry>, model: LibraryModel) {
    AfCard("အသံထွက် အဘိဓာန် (${items.size} ခု)") {
        AfText("TTS မပြောခင် စကားလုံးလိုက် အစားထိုးတယ်။ အရှည်ဆုံး ရေးပုံက ဦးစားပေးရတယ်။", muted = true, size = 12.sp)
        if (items.isEmpty()) {
            AfText("အဘိဓာန်ထဲ ဘာမှ မရှိသေးပါ။ Movie Recap → Script → Lexicon မှာ ထည့်ပါ။", muted = true)
        } else {
            items.take(10).forEach { entry ->
                AfText("${entry.written} → ${entry.spoken}", size = 12.sp)
            }
            if (items.size > 10) AfText("… နောက်ထပ် ${items.size - 10} ခု", muted = true, size = 11.sp)
        }
    }
}

@Composable
private fun MusicSection(items: List<MusicItem>, model: LibraryModel) {
    AfCard("တေးဂီတ (${items.size})") {
        AfText("${tr(UiText.APPLY)} = project ထဲကို ကူးထည့်တယ်။ နောက်ခံတေး အဖြစ်သုံး = Recap အသံထဲမှာပါ ချိတ်ပေးတယ်။", muted = true, size = 12.sp)
        if (items.isEmpty()) {
            AfText("တေးဂီတ မရှိသေးပါ။", muted = true)
        } else {
            items.forEach { m ->
                WrapRow {
                    AfText("${m.name} · ${m.durationMs / 1000}s", Modifier.weight(1f))
                    AfButton("နောက်ခံတေး အဖြစ်သုံးမယ်", { model.applyMusicAsBgm(m.id) })
                }
            }
        }
    }
}

@Composable
private fun FontsSection(items: List<FontItem>, model: LibraryModel) {
    AfCard("ဖောင့်များ (${items.size})") {
        if (items.isEmpty()) {
            AfText("ကိုယ်ပိုင် ဖောင့် မရှိသေးပါ။ ဆက်တင် → Fonts မှာ ထည့်ပါ။", muted = true)
        } else {
            items.forEach { f ->
                WrapRow {
                    AfText(f.name, Modifier.weight(1f))
                    AfButton(tr(UiText.APPLY), { model.applyFont(f.id) })
                }
            }
        }
    }
}

@Composable
private fun PresetsSection(items: List<Preset>, model: LibraryModel) {
    AfCard("Preset များ (${items.size})") {
        // Save current caption style as a preset (5B-8, D-51)
        var presetName by remember { mutableStateOf("") }
        AfButton("စာတန်းပုံစံကို preset အဖြစ် သိမ်းမယ်…", { presetName = "My Caption Style" })
        if (presetName.isNotEmpty()) {
            AfTextField(presetName, { presetName = it }, "Preset အမည်")
            WrapRow {
                AfButton(tr(UiText.SAVE), { if (presetName.isNotBlank()) { model.saveCaptionPreset(presetName); presetName = "" } }, kind = ButtonKind.Primary, enabled = presetName.isNotBlank())
                AfButton(tr(UiText.CANCEL), { presetName = "" })
            }
        }
        if (items.isEmpty()) {
            AfText("Preset မသိမ်းရသေးပါ။", muted = true)
        } else {
            items.forEach { p ->
                WrapRow {
                    Column(Modifier.weight(1f)) {
                        AfText(p.name, bold = true)
                        AfText(p.kind.name, muted = true, size = 11.sp)
                    }
                    AfButton(tr(UiText.APPLY), { model.applyPreset(p.id) })
                }
            }
        }
    }
}
