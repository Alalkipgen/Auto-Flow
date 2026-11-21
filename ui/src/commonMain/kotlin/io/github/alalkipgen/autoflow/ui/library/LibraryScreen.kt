package io.github.alalkipgen.autoflow.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.library.*
import io.github.alalkipgen.autoflow.core.recap.LexiconEntry
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import kotlinx.coroutines.launch

/** Six existing Library sections. Category chips jump, never filter out or discard another section. */
@Composable
fun LibraryScreen(model: LibraryModel, modifier: Modifier = Modifier, focusLexicon: Boolean = false) {
    val targets = remember { List(6) { BringIntoViewRequester() } }
    val scope = rememberCoroutineScope()
    var selected by remember { mutableIntStateOf(0) }
    // Nullable = closed; an empty editable name remains an open draft. Collapsing a section keeps it.
    var kitName by remember { mutableStateOf<String?>(null) }
    var presetName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusLexicon) {
        if (focusLexicon) { selected = 2; withFrameNanos { }; targets[2].bringIntoView() }
    }
    val state by model.state.collectAsState()
    val categories = listOf(UiText.V3_LIBRARY_KITS, UiText.V3_LIBRARY_VOICES, UiText.V3_LIBRARY_LEXICON,
        UiText.V3_LIBRARY_MUSIC, UiText.V3_LIBRARY_FONTS, UiText.V3_LIBRARY_PRESETS).map { tr(it) }
    fun section(index: Int, tag: String) = Modifier.testTag(tag).bringIntoViewRequester(targets[index])
    Column(modifier.fillMaxSize().background(AutoFlowColors.Background).imePadding()
        .paneScroll(rememberScrollState()).padding(screenPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AfText(tr(UiText.LIBRARY), Modifier.testTag("library-heading"), size = 20.sp, bold = true)
        AfText(My.breakSyllables(tr(UiText.LIBRARY_COPY_HINT, "APPLY" to tr(UiText.APPLY))), muted = true)
        AfText(tr(UiText.V3_LIBRARY_JUMP), muted = true)
        OptionChips(categories, categories[selected], { label ->
            val index = categories.indexOf(label)
            selected = index
            scope.launch { targets[index].bringIntoView() }
        }, Modifier.testTag("library-categories"))
        BrandKitsSection(state.library.brandKits, model, kitName, { kitName = it }, section(0, "library-kits"))
        VoicesSection(state.library.voices, model, section(1, "library-voices"))
        LexiconSection(state.library.lexicon, section(2, "library-lexicon"))
        MusicSection(state.library.music, model, section(3, "library-music"))
        FontsSection(state.library.fonts, model, section(4, "library-fonts"))
        PresetsSection(state.library.presets, model, presetName, { presetName = it }, section(5, "library-presets"))
        state.message?.let {
            AfText(My.breakSyllables(state.notice?.text(LocalUiLanguage.current) ?: it),
                Modifier.testTag("library-notice"), color = if (state.messageOk) AutoFlowColors.Green else AutoFlowColors.Amber)
        }
    }
}

@Composable
private fun BrandKitsSection(items: List<BrandKit>, model: LibraryModel, newName: String?,
    onName: (String?) -> Unit, modifier: Modifier) {
    val defaultName = tr(UiText.V3_LIBRARY_DEFAULT_KIT)
    SectionCard(tr(UiText.LIBRARY_KITS, "size" to items.size), modifier) {
        AfText(My.breakSyllables(tr(UiText.V3_LIBRARY_KIT_SCOPE)), muted = true)
        AfButton(tr(UiText.LIBRARY_SAVE_KIT), { onName(defaultName) })
        if (newName != null) {
            AfTextField(newName, { onName(it) }, tr(UiText.LIBRARY_KIT_NAME))
            WrapRow {
                AfButton(tr(UiText.SAVE), { model.saveBrandKit(newName); onName(null) },
                    kind = ButtonKind.Primary, enabled = newName.isNotBlank())
                AfButton(tr(UiText.CANCEL), { onName(null) })
            }
        }
        if (items.isEmpty()) AfText(tr(UiText.LIBRARY_KITS_EMPTY), muted = true)
        items.forEach { kit -> key(kit.id) {
            val tag = "library-kit-${kit.id}"
            val apply = { model.applyBrandKit(kit.id); Unit }
            CardRemoval(tag, UiText.CARD_DELETE, tr(UiText.LIBRARY_DELETE_KIT, "name" to kit.name),
                UiText.LIBRARY_DELETE_KEEPS_STYLE, { { model.deleteBrandKit(kit.id) } }, apply) { context ->
                AfCard(kit.name, Modifier.testTag(tag).then(context)) {
                    if (kit.colours.isNotEmpty()) AfText(tr(UiText.LIBRARY_COLOURS, "joinToString" to kit.colours.joinToString()), muted = true)
                    if (kit.captionStyle != null) AfText(tr(UiText.LIBRARY_CAPTION_SAVED), muted = true)
                    kit.watermark?.let { AfText(tr(UiText.LIBRARY_WATERMARK, "name" to it.substringAfterLast('/')), muted = true) }
                    AfButton(tr(UiText.LIBRARY_APPLY_CURRENT, "APPLY" to tr(UiText.APPLY)), apply,
                        Modifier.testTag("library-apply-kit-${kit.id}"))
                }
            }
        } }
    }
}

@Composable
private fun VoicesSection(items: List<SavedVoice>, model: LibraryModel, modifier: Modifier) {
    SectionCard(tr(UiText.LIBRARY_VOICES, "size" to items.size), modifier) {
        if (items.isEmpty()) AfText(tr(UiText.LIBRARY_VOICES_EMPTY), muted = true)
        items.forEach { voice -> AfCard(voice.name) {
            AfText("${voice.lock.provider} · ${voice.lock.model} · ${voice.lock.voice} · ${voice.lock.lang}", muted = true)
            AfButton(tr(UiText.APPLY), { model.applyVoice(voice.id) }, Modifier.testTag("library-apply-voice-${voice.id}"))
        } }
    }
}

@Composable
private fun LexiconSection(items: List<LexiconEntry>, modifier: Modifier) {
    SectionCard(tr(UiText.LIBRARY_LEXICON, "size" to items.size), modifier) {
        AfText(tr(UiText.LIBRARY_LEXICON_HINT), muted = true)
        if (items.isEmpty()) AfText(tr(UiText.LIBRARY_LEXICON_EMPTY), muted = true)
        items.take(10).forEach { AfText("${it.written} → ${it.spoken}") }
        if (items.size > 10) AfText(tr(UiText.LIBRARY_MORE, "size10" to (items.size - 10)), muted = true)
    }
}

@Composable
private fun MusicSection(items: List<MusicItem>, model: LibraryModel, modifier: Modifier) {
    SectionCard(tr(UiText.LIBRARY_MUSIC, "size" to items.size), modifier) {
        AfText(My.breakSyllables(tr(UiText.V3_LIBRARY_MUSIC_COPY_HINT)), muted = true)
        if (items.isEmpty()) AfText(tr(UiText.LIBRARY_MUSIC_EMPTY), muted = true)
        items.forEach { music -> AfCard(music.name) {
            AfText(io.github.alalkipgen.autoflow.ui.recap.RecapModel.seconds(music.durationMs), muted = true)
            AfButton(tr(UiText.V3_LIBRARY_COPY_PROJECT), { model.applyMusicAsBgm(music.id) },
                Modifier.testTag("library-copy-music-${music.id}"))
        } }
    }
}

@Composable
private fun FontsSection(items: List<FontItem>, model: LibraryModel, modifier: Modifier) {
    SectionCard(tr(UiText.LIBRARY_FONTS, "size" to items.size), modifier) {
        AfText(My.breakSyllables(tr(UiText.V3_LIBRARY_FONT_COPY_HINT, "APPLY" to tr(UiText.APPLY))), muted = true)
        if (items.isEmpty()) AfText(tr(UiText.LIBRARY_FONTS_EMPTY), muted = true)
        items.forEach { font -> AfCard(font.name) {
            AfButton(tr(UiText.APPLY), { model.applyFont(font.id) }, Modifier.testTag("library-apply-font-${font.id}"))
        } }
    }
}

@Composable
private fun PresetsSection(items: List<Preset>, model: LibraryModel, name: String?,
    onName: (String?) -> Unit, modifier: Modifier) {
    val defaultName = tr(UiText.V3_LIBRARY_DEFAULT_PRESET)
    SectionCard(tr(UiText.LIBRARY_PRESETS, "size" to items.size), modifier) {
        AfButton(tr(UiText.LIBRARY_SAVE_PRESET), { onName(defaultName) })
        if (name != null) {
            AfTextField(name, { onName(it) }, tr(UiText.LIBRARY_PRESET_NAME))
            WrapRow {
                AfButton(tr(UiText.SAVE), { model.saveCaptionPreset(name); onName(null) }, kind = ButtonKind.Primary, enabled = name.isNotBlank())
                AfButton(tr(UiText.CANCEL), { onName(null) })
            }
        }
        if (items.isEmpty()) AfText(tr(UiText.LIBRARY_PRESETS_EMPTY), muted = true)
        items.forEach { preset -> key(preset.id) {
            val tag = "library-preset-${preset.id}"
            val supported = preset.kind == PresetKind.CAPTION
            val apply = { model.applyPreset(preset.id) }
            CardRemoval(tag, UiText.CARD_DELETE, tr(UiText.LIBRARY_DELETE_KIT, "name" to preset.name),
                UiText.LIBRARY_DELETE_KEEPS_STYLE, { { model.deletePreset(preset.id) } }, apply.takeIf { supported }) { context ->
                AfCard(preset.name, Modifier.testTag(tag).then(context)) {
                    AfText(preset.kind.name, muted = true)
                    if (!supported) {
                        StatusChip(StudioStatus.PLANNED)
                        AfText(My.breakSyllables(tr(UiText.V3_LIBRARY_PRESET_UNSUPPORTED)), muted = true)
                    }
                    AfButton(tr(UiText.APPLY), apply, Modifier.testTag("library-apply-preset-${preset.id}"), enabled = supported)
                }
            }
        } }
    }
}
