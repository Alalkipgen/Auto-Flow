package io.github.alalkipgen.autoflow.ui.library

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
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

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
        AfText("Library", size = 20.sp, bold = true)
        AfText("Apply copies the item into the current project. Projects never reference library paths.", muted = true, size = 12.sp)

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
    AfCard("Brand Kits (${items.size})") {
        if (items.isEmpty()) {
            AfText("No brand kits yet. Add one via Settings → Library.", muted = true)
        } else {
            items.forEach { kit ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AfText(kit.name, bold = true)
                    if (kit.colours.isNotEmpty()) AfText("Colours: ${kit.colours.joinToString()}", muted = true, size = 12.sp)
                    AfButton("Apply", { model.applyBrandKit(kit.id) })
                }
            }
        }
    }
}

@Composable
private fun VoicesSection(items: List<SavedVoice>, model: LibraryModel) {
    AfCard("Voices (${items.size})") {
        if (items.isEmpty()) {
            AfText("No saved voices yet. Lock a voice in Movie Recap → Voice to save it.", muted = true)
        } else {
            items.forEach { v ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AfText(v.name, bold = true)
                    AfText("${v.lock.provider} · ${v.lock.model} · ${v.lock.voice} · ${v.lock.lang}", muted = true, size = 12.sp)
                    AfButton("Apply", { model.applyVoice(v.id) })
                }
            }
        }
    }
}

@Composable
private fun LexiconSection(items: List<LexiconEntry>, model: LibraryModel) {
    AfCard("Lexicon (${items.size} entries)") {
        AfText("Whole-word replacements applied before TTS. Longest written form wins.", muted = true, size = 12.sp)
        if (items.isEmpty()) {
            AfText("No lexicon entries. Add via Movie Recap → Script → Lexicon.", muted = true)
        } else {
            items.take(10).forEach { entry ->
                AfText("${entry.written} → ${entry.spoken}", size = 12.sp)
            }
            if (items.size > 10) AfText("… and ${items.size - 10} more", muted = true, size = 11.sp)
        }
    }
}

@Composable
private fun MusicSection(items: List<MusicItem>, model: LibraryModel) {
    AfCard("Music (${items.size})") {
        if (items.isEmpty()) {
            AfText("No music tracks yet.", muted = true)
        } else {
            items.forEach { m ->
                WrapRow {
                    AfText("${m.name} · ${m.durationMs / 1000}s", Modifier.weight(1f))
                    AfButton("Apply", { model.applyMusic(m.id) })
                }
            }
        }
    }
}

@Composable
private fun FontsSection(items: List<FontItem>, model: LibraryModel) {
    AfCard("Fonts (${items.size})") {
        if (items.isEmpty()) {
            AfText("No custom fonts. Add via Settings → Fonts.", muted = true)
        } else {
            items.forEach { f ->
                WrapRow {
                    AfText(f.name, Modifier.weight(1f))
                    AfButton("Apply", { model.applyFont(f.id) })
                }
            }
        }
    }
}

@Composable
private fun PresetsSection(items: List<Preset>, model: LibraryModel) {
    AfCard("Presets (${items.size})") {
        if (items.isEmpty()) {
            AfText("No presets saved yet.", muted = true)
        } else {
            items.forEach { p ->
                WrapRow {
                    Column(Modifier.weight(1f)) {
                        AfText(p.name, bold = true)
                        AfText(p.kind.name, muted = true, size = 11.sp)
                    }
                    AfButton("Apply", { model.applyPreset(p.id) })
                }
            }
        }
    }
}
