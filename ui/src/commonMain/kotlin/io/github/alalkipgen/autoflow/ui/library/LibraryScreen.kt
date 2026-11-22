package io.github.alalkipgen.autoflow.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.library.*
import io.github.alalkipgen.autoflow.core.recap.LexiconEntry
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.theme.*

/** The six Library sections in screen order: the filter chips after "All", their labels and test tags. */
internal enum class LibrarySection(val chip: UiText, val tag: String) {
    KITS(UiText.V3_LIBRARY_KITS, "library-kits"),
    VOICES(UiText.V3_LIBRARY_VOICES, "library-voices"),
    LEXICON(UiText.V3_LIBRARY_LEXICON, "library-lexicon"),
    MUSIC(UiText.V3_LIBRARY_MUSIC, "library-music"),
    FONTS(UiText.V3_LIBRARY_FONTS, "library-fonts"),
    PRESETS(UiText.V3_LIBRARY_PRESETS, "library-presets"),
}

/**
 * Library (P9-16, Exact UI v4 glass): the title and copy hint, filter chips ("All" + the six sections with their
 * counts — a chip shows only its section, "All" every section) and the six sections, each a section title, its hint, a
 * glass list tinted in the section colour (the lexicon a card of small rows) and its save action. [focusLexicon]
 * (Settings → Pronunciation) selects the Lexicon chip. An open kit / preset name draft survives filtering.
 */
@Composable
fun LibraryScreen(model: LibraryModel, modifier: Modifier = Modifier, focusLexicon: Boolean = false) {
    var filter by remember { mutableStateOf(if (focusLexicon) LibrarySection.LEXICON else null) }
    LaunchedEffect(focusLexicon) { if (focusLexicon) filter = LibrarySection.LEXICON }
    // Nullable = closed; an empty editable name remains an open draft. Showing another section keeps it.
    var kitName by remember { mutableStateOf<String?>(null) }
    var presetName by remember { mutableStateOf<String?>(null) }
    val state by model.state.collectAsState()
    val library = state.library
    val counts = mapOf(LibrarySection.KITS to library.brandKits.size, LibrarySection.VOICES to library.voices.size,
        LibrarySection.LEXICON to library.lexicon.size, LibrarySection.MUSIC to library.music.size,
        LibrarySection.FONTS to library.fonts.size, LibrarySection.PRESETS to library.presets.size)
    fun shown(section: LibrarySection) = filter == null || filter == section
    // No background of its own: the shell's ambient shows through the glass lists (DESIGN.md §9.10).
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        GlassScrollColumn(Modifier.widthIn(max = AfSize.ContentMax).fillMaxSize().imePadding().testTag("library-content"),
            PaddingValues(screenPadding), Arrangement.spacedBy(GlassShapes.SectionGap), rememberScrollState()) {
            Column(Modifier.testTag("library-header"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AfText(tr(UiText.LIBRARY), Modifier.testTag("library-heading"), type = AfType.ScreenTitle)
                Hint(tr(UiText.LIBRARY_COPY_HINT, "APPLY" to tr(UiText.APPLY)))
            }
            val sections = LibrarySection.entries
            val chips = listOf(FilterChipItem(tr(UiText.PROJECTS_FILTER_ALL), counts.values.sum())) +
                sections.map { FilterChipItem(tr(it.chip), counts.getValue(it)) }
            FilterChipRow(chips, filter?.let { it.ordinal + 1 } ?: 0, { i -> filter = sections.getOrNull(i - 1) },
                Modifier.testTag("library-categories"), glass = true)
            if (shown(LibrarySection.KITS)) BrandKitsSection(library.brandKits, model, kitName) { kitName = it }
            if (shown(LibrarySection.VOICES)) VoicesSection(library.voices, model)
            if (shown(LibrarySection.LEXICON)) LexiconSection(library.lexicon)
            if (shown(LibrarySection.MUSIC)) MusicSection(library.music, model)
            if (shown(LibrarySection.FONTS)) FontsSection(library.fonts, model)
            if (shown(LibrarySection.PRESETS)) PresetsSection(library.presets, model, presetName) { presetName = it }
            state.message?.let {
                AfText(My.breakSyllables(state.notice?.text(LocalUiLanguage.current) ?: it),
                    Modifier.testTag("library-notice"), color = if (state.messageOk) AutoFlowColors.Green else AutoFlowColors.Amber)
            }
        }
    }
}

/** A section: its title (14 sp bold, "(n)" count) and, 8 dp apart, its hint, list and actions. */
@Composable
private fun Section(section: LibrarySection, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().testTag(section.tag), verticalArrangement = Arrangement.spacedBy(GlassShapes.InSection)) {
        GlassSectionHeader(title)
        content()
    }
}

/** A Muted caption (section hints, empty texts). */
@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) =
    AfText(My.breakSyllables(text), modifier, color = AutoFlowColors.Muted, type = AfType.Caption)

/** A full-width Secondary save action with the `plus` icon, under the section's list. */
@Composable
private fun SaveAction(text: String, onClick: () -> Unit) =
    GlassButton(text, onClick, Modifier.fillMaxWidth(), icon = AfIcons.Regular.Plus)

/** The open name draft under its save action: the name field, then Save (needs a name) and Cancel. */
@Composable
private fun NameDraft(name: String, placeholder: String, onName: (String?) -> Unit, onSave: (String) -> Unit) {
    AfTextField(name, { onName(it) }, placeholder)
    WrapRow {
        GlassButton(tr(UiText.SAVE), { onSave(name); onName(null) }, kind = ButtonKind.Primary, enabled = name.isNotBlank())
        GlassButton(tr(UiText.CANCEL), { onName(null) })
    }
}

/** The row's small Secondary action (Apply / Copy to project). */
@Composable
private fun RowAction(text: String, onClick: () -> Unit, tag: String) =
    GlassButton(text, onClick, Modifier.testTag(tag), small = true)

@Composable
private fun BrandKitsSection(items: List<BrandKit>, model: LibraryModel, newName: String?, onName: (String?) -> Unit) {
    val defaultName = tr(UiText.V3_LIBRARY_DEFAULT_KIT)
    Section(LibrarySection.KITS, tr(UiText.LIBRARY_KITS, "size" to items.size)) {
        Hint(tr(UiText.V3_LIBRARY_KIT_SCOPE))
        if (items.isNotEmpty()) GlassList(items, GlassColors.LibraryKits, { it.id }) { kit ->
            val tag = "library-kit-${kit.id}"
            val apply = { model.applyBrandKit(kit.id); Unit }
            CardRemoval(tag, UiText.CARD_DELETE, tr(UiText.LIBRARY_DELETE_KIT, "name" to kit.name),
                UiText.LIBRARY_DELETE_KEEPS_STYLE, { { model.deleteBrandKit(kit.id) } }, apply) { context ->
                val parts = listOfNotNull(tr(UiText.LIBRARY_CAPTION_SAVED).takeIf { kit.captionStyle != null },
                    kit.watermark?.let { tr(UiText.LIBRARY_WATERMARK, "name" to it.substringAfterLast('/')) })
                GlassListRow(kit.name, Modifier.testTag(tag).then(context), parts.joinToString(" · ").ifEmpty { null },
                    leading = { KitSwatch(kit.colours) }) { RowAction(tr(UiText.APPLY), apply, "library-apply-kit-${kit.id}") }
            }
        }
        SaveAction(tr(UiText.LIBRARY_SAVE_KIT)) { onName(defaultName) }
        if (newName != null) NameDraft(newName, tr(UiText.LIBRARY_KIT_NAME), onName) { model.saveBrandKit(it) }
        // "Use the button above": the empty text sits under the save action.
        if (items.isEmpty()) Hint(tr(UiText.LIBRARY_KITS_EMPTY))
    }
}

/** `#RRGGBB` → a colour; anything else is skipped. */
private fun kitColour(hex: String): Color? =
    hex.trim().removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }

/**
 * A Brand Kit's 34 dp swatch: its colours as equal vertical stripes (radius 11, 1 dp White .18 border; the colour
 * list as its description); a kit without colours shows the `swatches` tile.
 */
@Composable
private fun KitSwatch(colours: List<String>) {
    val parsed = colours.mapNotNull(::kitColour)
    if (parsed.isEmpty()) return SoftTile(AfIcons.Fill.Swatches, GlassColors.LibraryKits, size = GlassShapes.ListTile)
    val label = tr(UiText.LIBRARY_COLOURS, "joinToString" to colours.joinToString())
    Row(Modifier.size(GlassShapes.ListTile).clip(GlassShapes.Tile).border(AutoFlowShapes.Border, GlassColors.SwatchBorder, GlassShapes.Tile)
        .semantics { contentDescription = label }.testTag("library-kit-swatch")) {
        parsed.forEach { Box(Modifier.weight(1f).fillMaxHeight().background(it)) }
    }
}

@Composable
private fun VoicesSection(items: List<SavedVoice>, model: LibraryModel) {
    Section(LibrarySection.VOICES, tr(UiText.LIBRARY_VOICES, "size" to items.size)) {
        if (items.isEmpty()) Hint(tr(UiText.LIBRARY_VOICES_EMPTY))
        else GlassList(items, GlassColors.LibraryVoices, { it.id }) { voice ->
            GlassListRow(voice.name, Modifier.testTag("library-voice-${voice.id}"),
                "${voice.lock.provider} · ${voice.lock.model} · ${voice.lock.voice} · ${voice.lock.lang}",
                leading = { SoftTile(AfIcons.Fill.UserSound, GlassColors.LibraryVoices, size = GlassShapes.ListTile) }) {
                RowAction(tr(UiText.APPLY), { model.applyVoice(voice.id) }, "library-apply-voice-${voice.id}")
            }
        }
    }
}

@Composable
private fun LexiconSection(items: List<LexiconEntry>) {
    Section(LibrarySection.LEXICON, tr(UiText.LIBRARY_LEXICON, "size" to items.size)) {
        Hint(tr(UiText.LIBRARY_LEXICON_HINT))
        if (items.isEmpty()) Hint(tr(UiText.LIBRARY_LEXICON_EMPTY)) else LexiconCard(items.take(10))
        if (items.size > 10) Hint(tr(UiText.LIBRARY_MORE, "size10" to (items.size - 10)), Modifier.testTag("library-lexicon-more"))
    }
}

/**
 * The lexicon: one glass card (padding 10, Voices tint .16) of small rows (radius 10, White .04, padding 6 × 4, gap
 * 6): the written form on one line in an 84 dp column (the full form as its description), a Muted `arrow-right`, the
 * spoken form in lavender (wraps). With larger text the column grows to the widest written form, at most 84 dp × the
 * font scale, so the spoken forms keep their room.
 */
@Composable
private fun LexiconCard(items: List<LexiconEntry>) {
    val density = LocalDensity.current
    val style = afTextStyle(AutoFlowColors.Text, AfType.RowTitle.size, AfType.RowTitle.weight, LocalUiFontFamily.current,
        AfType.RowTitle.numbers)
    val measurer = rememberTextMeasurer()
    val widest = remember(items, style, density) { items.maxOfOrNull { measurer.measure(it.written, style).size.width } ?: 0 }
    val written = with(density) { minOf(GlassShapes.LexiconWritten * fontScale, maxOf(GlassShapes.LexiconWritten, (widest + 1).toDp())) }
    GlassCard(Modifier.testTag("library-lexicon-card"), tint = GlassColors.LibraryVoices, tintAlpha = GlassColors.TintLexicon,
        padding = PaddingValues(GlassShapes.LexiconPadding), gap = GlassShapes.LexiconGap) {
        items.forEachIndexed { i, entry ->
            Row(Modifier.fillMaxWidth().clip(GlassShapes.LexiconRow).background(GlassColors.LexiconRowFill)
                .padding(horizontal = GlassShapes.LexiconGap, vertical = AfSpace.Xs).testTag("library-lexicon-$i"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GlassShapes.LexiconGap)) {
                AfText(entry.written, Modifier.width(written).semantics { contentDescription = entry.written }, type = AfType.RowTitle,
                    maxLines = 1, ellipsis = true)
                // arrow-right = Phosphor arrow-left mirrored (as in the owner's image).
                Box(Modifier.width(AfSize.IconSmall), contentAlignment = Alignment.Center) {
                    AfIcon(AfIcons.Regular.Back, Modifier.graphicsLayer { scaleX = -1f }, tint = AutoFlowColors.Muted, size = 14.dp)
                }
                AfText(My.breakSyllables(entry.spoken), Modifier.weight(1f), color = GlassColors.LexiconSpoken, type = AfType.Body)
            }
        }
    }
}

@Composable
private fun MusicSection(items: List<MusicItem>, model: LibraryModel) {
    Section(LibrarySection.MUSIC, tr(UiText.LIBRARY_MUSIC, "size" to items.size)) {
        Hint(tr(UiText.V3_LIBRARY_MUSIC_COPY_HINT))
        if (items.isEmpty()) Hint(tr(UiText.LIBRARY_MUSIC_EMPTY))
        else GlassList(items, GlassColors.LibraryMusic, { it.id }) { music ->
            // The duration as m:ss, like a thumbnail's badge; none when unknown.
            GlassListRow(music.name, Modifier.testTag("library-music-${music.id}"), music.durationMs.takeIf { it > 0 }?.let(::durationLabel),
                leading = { SoftTile(AfIcons.Fill.Music, GlassColors.LibraryMusic, size = GlassShapes.ListTile) }) {
                RowAction(tr(UiText.V3_LIBRARY_COPY_PROJECT), { model.applyMusicAsBgm(music.id) }, "library-copy-music-${music.id}")
            }
        }
    }
}

@Composable
private fun FontsSection(items: List<FontItem>, model: LibraryModel) {
    Section(LibrarySection.FONTS, tr(UiText.LIBRARY_FONTS, "size" to items.size)) {
        Hint(tr(UiText.V3_LIBRARY_FONT_COPY_HINT, "APPLY" to tr(UiText.APPLY)))
        if (items.isEmpty()) Hint(tr(UiText.LIBRARY_FONTS_EMPTY))
        else GlassList(items, GlassColors.LibraryFonts, { it.id }) { font ->
            GlassListRow(font.name, Modifier.testTag("library-font-${font.id}"),
                leading = { SoftTile(AfIcons.Fill.TextAa, GlassColors.LibraryFonts, size = GlassShapes.ListTile) }) {
                RowAction(tr(UiText.APPLY), { model.applyFont(font.id) }, "library-apply-font-${font.id}")
            }
        }
    }
}

/** A preset kind's row tile icon and its label (instead of the enum name). */
private fun PresetKind.icon(): ImageVector = when (this) {
    PresetKind.CAPTION -> AfIcons.Fill.Subtitles
    PresetKind.STYLE -> AfIcons.Fill.Palette
    PresetKind.EXPORT -> AfIcons.Fill.Film
}
private fun PresetKind.label(): UiText = when (this) {
    PresetKind.CAPTION -> UiText.V3_STYLE_CAPTIONS
    PresetKind.STYLE -> UiText.STYLE
    PresetKind.EXPORT -> UiText.EXPORT
}

@Composable
private fun PresetsSection(items: List<Preset>, model: LibraryModel, name: String?, onName: (String?) -> Unit) {
    val defaultName = tr(UiText.V3_LIBRARY_DEFAULT_PRESET)
    Section(LibrarySection.PRESETS, tr(UiText.LIBRARY_PRESETS, "size" to items.size)) {
        if (items.isEmpty()) Hint(tr(UiText.LIBRARY_PRESETS_EMPTY))
        else GlassList(items, GlassColors.LibraryPresets, { it.id }) { preset ->
            val tag = "library-preset-${preset.id}"
            val supported = preset.kind == PresetKind.CAPTION
            val apply = { model.applyPreset(preset.id) }
            CardRemoval(tag, UiText.CARD_DELETE, tr(UiText.LIBRARY_DELETE_KIT, "name" to preset.name),
                UiText.LIBRARY_DELETE_KEEPS_STYLE, { { model.deletePreset(preset.id) } }, apply.takeIf { supported }) { context ->
                GlassListRow(preset.name, Modifier.testTag(tag).then(context), tr(preset.kind.label()),
                    leading = { SoftTile(preset.kind.icon(), GlassColors.LibraryPresets, size = GlassShapes.ListTile) }) {
                    // Style / export presets cannot be applied here yet: a Planned chip, no disabled button.
                    if (supported) RowAction(tr(UiText.APPLY), apply, "library-apply-preset-${preset.id}")
                    else StatusChip(StudioStatus.PLANNED)
                }
            }
        }
        if (items.any { it.kind != PresetKind.CAPTION }) Hint(tr(UiText.V3_LIBRARY_PRESET_UNSUPPORTED), Modifier.testTag("library-preset-unsupported"))
        SaveAction(tr(UiText.LIBRARY_SAVE_PRESET)) { onName(defaultName) }
        if (name != null) NameDraft(name, tr(UiText.LIBRARY_PRESET_NAME), onName) { model.saveCaptionPreset(it) }
    }
}
