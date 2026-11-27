package io.github.alalkipgen.autoflow.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.library.*
import io.github.alalkipgen.autoflow.core.recap.LexiconEntry
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.theme.*

/**
 * The six Library sections in screen order: the filter chips after "All" (their names are the section labels), test
 * tags, and the colour and icon of their rows' tiles (also the empty row's tile).
 */
internal enum class LibrarySection(val chip: UiText, val tag: String, val colour: Color, val icon: ImageVector) {
    KITS(UiText.V3_LIBRARY_KITS, "library-kits", GlassColors.LibraryKits, AfIcons.Fill.Swatches),
    VOICES(UiText.V3_LIBRARY_VOICES, "library-voices", GlassColors.LibraryVoices, AfIcons.Fill.UserSound),
    LEXICON(UiText.V3_LIBRARY_LEXICON, "library-lexicon", GlassColors.LibraryVoices, AfIcons.Fill.Volume),
    MUSIC(UiText.V3_LIBRARY_MUSIC, "library-music", GlassColors.LibraryMusic, AfIcons.Fill.Music),
    FONTS(UiText.V3_LIBRARY_FONTS, "library-fonts", GlassColors.LibraryFonts, AfIcons.Fill.TextAa),
    PRESETS(UiText.V3_LIBRARY_PRESETS, "library-presets", GlassColors.LibraryPresets, AfIcons.Fill.Subtitles),
}

/**
 * Library (P9-16, Exact UI v4 glass): the title and copy hint, filter chips ("All" + the six sections with their
 * counts — a chip shows only its section, "All" every section) and the six sections, each its label (the chip's name;
 * with one chip selected only a TalkBack heading), its hint, a glass list tinted in the section colour (the lexicon a
 * card of small rows) and its save action. An empty section: no hint, one small empty row (Library follow-up README
 * §1–2). An empty Library: no chips and no copy hint, every section with its label. [focusLexicon] (Settings →
 * Pronunciation) selects the Lexicon chip — in an empty Library it brings Lexicon into view. An open kit / preset name
 * draft survives filtering.
 */
@Composable
fun LibraryScreen(model: LibraryModel, modifier: Modifier = Modifier, focusLexicon: Boolean = false) {
    var filter by remember { mutableStateOf(if (focusLexicon) LibrarySection.LEXICON else null) }
    LaunchedEffect(focusLexicon) { if (focusLexicon) filter = LibrarySection.LEXICON }
    val lexicon = remember { BringIntoViewRequester() }
    // Nullable = closed; an empty editable name remains an open draft. Showing another section keeps it.
    var kitName by remember { mutableStateOf<String?>(null) }
    var presetName by remember { mutableStateOf<String?>(null) }
    val state by model.state.collectAsState()
    val library = state.library
    val counts = mapOf(LibrarySection.KITS to library.brandKits.size, LibrarySection.VOICES to library.voices.size,
        LibrarySection.LEXICON to library.lexicon.size, LibrarySection.MUSIC to library.music.size,
        LibrarySection.FONTS to library.fonts.size, LibrarySection.PRESETS to library.presets.size)
    // An empty Library shows every section (with its label) and no chips, whatever chip was selected.
    val empty = counts.values.all { it == 0 }
    fun shown(section: LibrarySection) = empty || filter == null || filter == section
    val names = LibrarySection.entries.associateWith { tr(it.chip) }
    fun label(section: LibrarySection) = names.getValue(section).takeIf { empty || filter == null }
    LaunchedEffect(focusLexicon, empty) { if (focusLexicon && empty) { withFrameNanos { }; lexicon.bringIntoView() } }
    // Saving the first kit / preset from an empty Library shows All (not a chip picked before, e.g. by focusLexicon).
    fun saved() { if (empty) filter = null }
    // No background of its own: the shell's ambient shows through the glass lists (DESIGN.md §9.10).
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        GlassScrollColumn(Modifier.widthIn(max = AfSize.ContentMax).fillMaxSize().imePadding().testTag("library-content"),
            PaddingValues(screenPadding), Arrangement.spacedBy(GlassShapes.SectionGap), rememberScrollState()) {
            Column(Modifier.testTag("library-header"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AfText(tr(UiText.LIBRARY), Modifier.testTag("library-heading"), type = AfType.ScreenTitle)
                if (!empty) Hint(tr(UiText.LIBRARY_COPY_HINT, "APPLY" to tr(UiText.APPLY)))
            }
            val sections = LibrarySection.entries
            if (!empty) {
                val chips = listOf(FilterChipItem(tr(UiText.PROJECTS_FILTER_ALL), counts.values.sum())) +
                    sections.map { FilterChipItem(tr(it.chip), counts.getValue(it)) }
                FilterChipRow(chips, filter?.let { it.ordinal + 1 } ?: 0, { i -> filter = sections.getOrNull(i - 1) },
                    Modifier.testTag("library-categories"), glass = true)
            }
            if (shown(LibrarySection.KITS)) BrandKitsSection(library.brandKits, model, label(LibrarySection.KITS), kitName, ::saved) {
                kitName = it
            }
            if (shown(LibrarySection.VOICES)) VoicesSection(library.voices, model, label(LibrarySection.VOICES))
            if (shown(LibrarySection.LEXICON)) {
                LexiconSection(library.lexicon, label(LibrarySection.LEXICON), Modifier.bringIntoViewRequester(lexicon))
            }
            if (shown(LibrarySection.MUSIC)) MusicSection(library.music, model, label(LibrarySection.MUSIC))
            if (shown(LibrarySection.FONTS)) FontsSection(library.fonts, model, label(LibrarySection.FONTS))
            if (shown(LibrarySection.PRESETS)) PresetsSection(library.presets, model, label(LibrarySection.PRESETS), presetName, ::saved) {
                presetName = it
            }
            state.message?.let {
                AfText(My.breakSyllables(state.notice?.text(LocalUiLanguage.current) ?: it),
                    Modifier.testTag("library-notice"), color = if (state.messageOk) AutoFlowColors.Green else AutoFlowColors.Amber)
            }
        }
    }
}

/**
 * A section: its [label] (14 sp bold, a heading) and, 8 dp apart, its hint, list and actions. Without a label (one chip
 * selected: the chip names it) the section is a TalkBack heading with its name — nothing drawn.
 */
@Composable
private fun Section(section: LibrarySection, label: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val name = tr(section.chip)
    Column(modifier.fillMaxWidth().testTag(section.tag).then(if (label == null) Modifier.semantics { heading(); contentDescription = name }
        else Modifier), verticalArrangement = Arrangement.spacedBy(GlassShapes.InSection)) {
        label?.let { GlassSectionHeader(it, Modifier.semantics(mergeDescendants = true) { heading() }) }
        content()
    }
}

/**
 * An empty section: one small flat glass row (radius 14, White .04 fill, 1 dp White .08 border, padding 12 × 10, gap
 * 10) — the section's 28 dp tile at opacity .6 and its empty [text] (Caption, Muted, wraps).
 */
@Composable
private fun EmptyRow(section: LibrarySection, text: UiText) {
    val shape = GlassShapes.EmptyRow
    Row(Modifier.fillMaxWidth().testTag("library-empty-${section.tag.removePrefix("library-")}").clip(shape)
        .background(GlassColors.EmptyRowFill).border(1.dp, GlassColors.EmptyRowBorder, shape)
        .padding(horizontal = GlassShapes.EmptyRowPadX, vertical = GlassShapes.EmptyRowPadY),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GlassShapes.EmptyRowGap)) {
        SoftTile(section.icon, section.colour, Modifier.alpha(GlassColors.EmptyTileAlpha), size = GlassShapes.EmptyTile)
        Hint(tr(text), Modifier.weight(1f))
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
private fun BrandKitsSection(items: List<BrandKit>, model: LibraryModel, label: String?, newName: String?, onSaved: () -> Unit,
    onName: (String?) -> Unit) {
    val defaultName = tr(UiText.V3_LIBRARY_DEFAULT_KIT)
    Section(LibrarySection.KITS, label) {
        if (items.isNotEmpty()) Hint(tr(UiText.V3_LIBRARY_KIT_SCOPE))
        if (items.isNotEmpty()) GlassList(items, GlassColors.LibraryKits, { it.id }) { kit ->
            val tag = "library-kit-${kit.id}"
            val apply = { model.applyBrandKit(kit.id); Unit }
            CardRemoval(tag, UiText.CARD_DELETE, tr(UiText.LIBRARY_DELETE_KIT, "name" to kit.name),
                UiText.LIBRARY_DELETE_KEEPS_STYLE, { { model.deleteBrandKit(kit.id) } }, apply, name = kit.name,
                caption = tr(UiText.V3_LIBRARY_KITS), leading = { KitSwatch(kit.colours, GlassShapes.DialogTile) }) { context ->
                val parts = listOfNotNull(tr(UiText.LIBRARY_CAPTION_SAVED).takeIf { kit.captionStyle != null },
                    kit.watermark?.let { tr(UiText.LIBRARY_WATERMARK, "name" to it.substringAfterLast('/')) })
                GlassListRow(kit.name, Modifier.testTag(tag).then(context), parts.joinToString(" · ").ifEmpty { null },
                    leading = { KitSwatch(kit.colours) }) { RowAction(tr(UiText.APPLY), apply, "library-apply-kit-${kit.id}") }
            }
        }
        SaveAction(tr(UiText.LIBRARY_SAVE_KIT)) { onName(defaultName) }
        if (newName != null) NameDraft(newName, tr(UiText.LIBRARY_KIT_NAME), onName) { model.saveBrandKit(it); onSaved() }
        // "Use the button above": the empty row sits under the save action.
        if (items.isEmpty()) EmptyRow(LibrarySection.KITS, UiText.LIBRARY_KITS_EMPTY)
    }
}

/** The swatch's corner radius at its 34 dp row size ([GlassShapes.Tile]). */
private val SwatchRadius = 11.dp

/** `#RRGGBB` → a colour; anything else is skipped. */
private fun kitColour(hex: String): Color? =
    hex.trim().removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }

/**
 * A Brand Kit's swatch (34 dp in its row, 44 in the delete dialog): its colours as equal vertical stripes (radius 11 at
 * 34 dp, scaled with the size; 1 dp White .18 border; the colour list as its description); a kit without colours shows
 * the `swatches` tile.
 */
@Composable
private fun KitSwatch(colours: List<String>, size: Dp = GlassShapes.ListTile) {
    val parsed = colours.mapNotNull(::kitColour)
    if (parsed.isEmpty()) return SoftTile(AfIcons.Fill.Swatches, GlassColors.LibraryKits, size = size)
    val label = tr(UiText.LIBRARY_COLOURS, "joinToString" to colours.joinToString())
    val shape = if (size == GlassShapes.ListTile) GlassShapes.Tile else RoundedCornerShape(SwatchRadius * (size / GlassShapes.ListTile))
    Row(Modifier.size(size).clip(shape).border(AutoFlowShapes.Border, GlassColors.SwatchBorder, shape)
        .semantics { contentDescription = label }.testTag("library-kit-swatch")) {
        parsed.forEach { Box(Modifier.weight(1f).fillMaxHeight().background(it)) }
    }
}

@Composable
private fun VoicesSection(items: List<SavedVoice>, model: LibraryModel, label: String?) {
    Section(LibrarySection.VOICES, label) {
        if (items.isEmpty()) EmptyRow(LibrarySection.VOICES, UiText.LIBRARY_VOICES_EMPTY)
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
private fun LexiconSection(items: List<LexiconEntry>, label: String?, modifier: Modifier) {
    Section(LibrarySection.LEXICON, label, modifier) {
        if (items.isNotEmpty()) Hint(tr(UiText.LIBRARY_LEXICON_HINT))
        if (items.isEmpty()) EmptyRow(LibrarySection.LEXICON, UiText.LIBRARY_LEXICON_EMPTY) else LexiconCard(items.take(10))
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
private fun MusicSection(items: List<MusicItem>, model: LibraryModel, label: String?) {
    Section(LibrarySection.MUSIC, label) {
        if (items.isNotEmpty()) Hint(tr(UiText.V3_LIBRARY_MUSIC_COPY_HINT))
        if (items.isEmpty()) EmptyRow(LibrarySection.MUSIC, UiText.LIBRARY_MUSIC_EMPTY)
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
private fun FontsSection(items: List<FontItem>, model: LibraryModel, label: String?) {
    Section(LibrarySection.FONTS, label) {
        if (items.isNotEmpty()) Hint(tr(UiText.V3_LIBRARY_FONT_COPY_HINT, "APPLY" to tr(UiText.APPLY)))
        if (items.isEmpty()) EmptyRow(LibrarySection.FONTS, UiText.LIBRARY_FONTS_EMPTY)
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
private fun PresetsSection(items: List<Preset>, model: LibraryModel, label: String?, name: String?, onSaved: () -> Unit,
    onName: (String?) -> Unit) {
    val defaultName = tr(UiText.V3_LIBRARY_DEFAULT_PRESET)
    Section(LibrarySection.PRESETS, label) {
        if (items.isEmpty()) EmptyRow(LibrarySection.PRESETS, UiText.LIBRARY_PRESETS_EMPTY)
        else GlassList(items, GlassColors.LibraryPresets, { it.id }) { preset ->
            val tag = "library-preset-${preset.id}"
            val supported = preset.kind == PresetKind.CAPTION
            val apply = { model.applyPreset(preset.id) }
            CardRemoval(tag, UiText.CARD_DELETE, tr(UiText.LIBRARY_DELETE_KIT, "name" to preset.name),
                UiText.LIBRARY_DELETE_KEEPS_STYLE, { { model.deletePreset(preset.id) } }, apply.takeIf { supported }, name = preset.name,
                caption = tr(UiText.V3_LIBRARY_PRESETS),
                leading = { SoftTile(preset.kind.icon(), GlassColors.LibraryPresets, size = GlassShapes.DialogTile) }) { context ->
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
        if (name != null) NameDraft(name, tr(UiText.LIBRARY_PRESET_NAME), onName) { model.saveCaptionPreset(it); onSaved() }
    }
}
