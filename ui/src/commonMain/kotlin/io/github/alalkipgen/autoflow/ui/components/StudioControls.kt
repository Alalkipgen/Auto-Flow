package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.theme.AfSize
import io.github.alalkipgen.autoflow.ui.theme.AfSpace
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes
import io.github.alalkipgen.autoflow.ui.theme.GlassColors
import io.github.alalkipgen.autoflow.ui.theme.GlassShapes
import io.github.alalkipgen.autoflow.ui.theme.Tone

/** UI callback, not a persisted command or engine state. [trailingIcon] = after the label (glass bars: Next →). */
data class StudioAction(val label: String, val onClick: () -> Unit, val enabled: Boolean = true, val tag: String? = null,
    val icon: ImageVector? = null, val trailingIcon: ImageVector? = null)

@Composable
private fun Action(action: StudioAction, primary: Boolean = false, modifier: Modifier = Modifier, glass: Boolean = false) {
    val m = modifier.then(action.tag?.let { Modifier.testTag(it) } ?: Modifier)
    val kind = if (primary) ButtonKind.Primary else ButtonKind.Secondary
    if (glass) GlassButton(action.label, action.onClick, m, kind = kind, enabled = action.enabled, icon = action.icon, trailingIcon = action.trailingIcon)
    else AfButton(action.label, action.onClick, m, kind = kind, enabled = action.enabled, icon = action.icon, trailingIcon = action.trailingIcon)
}

/**
 * Project editor header (DESIGN.md §9.4): [AfTopBar] (back, project name, "step k/5 · label", page preview and ⋮ as
 * icon buttons) over a [StepBar] of [steps] (bars only when the caller passes no labels); with [onStep] its steps are
 * tabs tagged [stepTags] (Dubbing moves between its steps there). [glass] (Dubbing, Group 4 A 1–2): the glass panel
 * (White .08 → .03 with a White .08 line under it, as the Music Video project bar), the 40 dp glass back button, the
 * name 17 sp bold on one line, and the glass [StepBar].
 */
@Composable
fun ProjectTopBar(name: String, step: Int, stepLabel: String, onBack: () -> Unit,
    modifier: Modifier = Modifier, options: List<StudioAction> = emptyList(), saveState: StudioStatus? = null,
    steps: List<String> = emptyList(), onStep: ((Int) -> Unit)? = null, stepTags: List<String> = emptyList(), glass: Boolean = false,
) {
    require(step in 1..5)
    var open by remember(name) { mutableStateOf(false) }
    val page = LocalProjectPreviewPage.current?.takeIf { it.available }
    val panel = if (glass) Modifier.drawBehind {
        drawRect(Brush.verticalGradient(listOf(GlassColors.TopBarTop, GlassColors.TopBarBottom)))
        drawRect(GlassColors.TopBarLine, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
    }.padding(horizontal = 12.dp, vertical = 8.dp) else Modifier.background(AutoFlowColors.Panel).padding(horizontal = 6.dp, vertical = 6.dp)
    Column(modifier.fillMaxWidth().then(panel), verticalArrangement = Arrangement.spacedBy(if (glass) 8.dp else 6.dp)) {
        val subtitle = My.breakSyllables(tr(UiText.V3_STEP, "step" to stepLabel, "n" to step))
        val actions: @Composable RowScope.() -> Unit = {
            if (page != null) {
                val icon = if (page.shown) AfIcons.Regular.Edit else AfIcons.Fill.Play
                val label = tr(if (page.shown) UiText.EDIT else UiText.PROJECT_PAGE_PREVIEW)
                val tag = Modifier.testTag(if (page.shown) "project-page-edit" else "project-page-preview")
                if (glass) GlassIconButton(icon, label, { page.shown = !page.shown }, tag, size = GlassShapes.IconButtonHeader, selected = page.shown)
                else AfIconButton(icon, label, { page.shown = !page.shown }, tag, selected = page.shown)
            }
            if (options.isNotEmpty()) {
                if (glass) GlassIconButton(AfIcons.Regular.More, tr(UiText.V3_MORE_OPTIONS), { open = !open }, Modifier.testTag("project-overflow"),
                    size = GlassShapes.IconButtonHeader, selected = open)
                else AfIconButton(AfIcons.Regular.More, tr(UiText.V3_MORE_OPTIONS), { open = !open }, Modifier.testTag("project-overflow"), selected = open)
            }
        }
        // Right-click on the bar opens the same ⋮ options (DESIGN.md §9.8).
        val menu = if (options.isEmpty()) Modifier else Modifier.onSecondaryClick { open = true }
        if (glass) Row(menu.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassIconButton(AfIcons.Regular.Back, tr(UiText.BACK), onBack, Modifier.testTag("project-back"), size = GlassShapes.IconButtonHeader)
            Column(Modifier.weight(1f)) {
                AfText(name, Modifier.semantics { contentDescription = name }, type = AfType.TopBarTitle, weight = androidx.compose.ui.text.font.FontWeight.Bold,
                    maxLines = 1, ellipsis = true)
                AfText(subtitle, color = AutoFlowColors.Muted, type = AfType.Caption)
            }
            actions()
        } else AfTopBar(name, menu, subtitle = subtitle, onBack = onBack, backModifier = Modifier.testTag("project-back"), actions = actions)
        StepBar(steps, step, Modifier.padding(horizontal = if (glass) 0.dp else 6.dp).testTag("project-progress"), count = 5, onSelect = onStep,
            tags = stepTags, glass = glass)
        saveState?.let { StatusChip(it) } // callers omit this when no reliable save state exists
        // ⋮ actions (Group 2 D 25a): small glass buttons with their icons, inline under the step bar.
        if (open) WrapRow(Modifier.padding(horizontal = if (glass) 0.dp else 6.dp), spacing = 8.dp) { options.forEach { action ->
            GlassButton(action.label, { open = false; action.onClick() }, action.tag?.let { Modifier.testTag(it) } ?: Modifier,
                enabled = action.enabled, icon = action.icon, small = true)
        } }
    }
}

/**
 * A measured sibling of the scrolling content, not an overlay with a guessed bottom spacer: bar colour with a top
 * divider, above the keyboard. [caption] is the hint (centred below on a phone, left on the desktop); [leading] is an
 * optional control before the buttons; [keys] = desktop keyboard hints above the caption (DESIGN.md §9.4, §9.8).
 * [glass] = glass buttons with their icons (Movie Recap steps, Group 2 A §2).
 */
@Composable
fun StickyActionBar(primary: StudioAction, secondary: StudioAction? = null, caption: String? = null, modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null, keys: (@Composable () -> Unit)? = null, glass: Boolean = false) {
    Column(modifier.fillMaxWidth().background(AutoFlowColors.TitleBar).imePadding().testTag("sticky-action-bar")) {
        Box(Modifier.fillMaxWidth().height(AutoFlowShapes.Border).background(AutoFlowColors.Line))
        Box(Modifier.padding(horizontal = if (LocalCompact.current) 12.dp else 16.dp, vertical = 12.dp)) {
            StickyActions({ Action(primary, true, it, glass) }, secondary?.let { s -> { Action(s, modifier = it, glass = glass) } }, caption, leading, keys)
        }
    }
}

/** A glass card's header tile: the Fill [icon] in a 30 dp soft tile of [colour], which also tints the card (Group 2 A §2). */
class CardTile(val icon: ImageVector, val colour: Color)

/**
 * A titled card whose body folds with the header button (+ / −, or with a [tile] the glass card of Group 2 A §2:
 * tile · title · caret-up / caret-down); the button's description is the [title] and its state the fold.
 */
@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, initiallyExpanded: Boolean = true, tile: CardTile? = null,
    content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember(title) { mutableStateOf(initiallyExpanded) }
    val expansionLabel = tr(if (expanded) UiText.V3_EXPANDED else UiText.V3_COLLAPSED)
    val fold = Modifier.semantics {
        contentDescription = title
        stateDescription = expansionLabel
    }
    if (tile != null) TileCard(title, tile, modifier, action = {
        GlassIconButton(if (expanded) AfIcons.Regular.CaretUp else AfIcons.Regular.CaretDown, title, { expanded = !expanded }, fold)
    }) { if (expanded) content() }
    else AfCard(title, modifier, headerAction = {
        AfButton(if (expanded) "−" else "+", { expanded = !expanded }, fold)
    }) { if (expanded) content() }
}

/**
 * Glass card with a header (Group 2 A §2): 30 dp soft [tile] · [title] (14 sp bold) · optional [action]; the card is
 * tinted with the tile colour (.16, from the top start). Padding 12, gap 10; [selected] = the primary border (the layer being edited).
 */
@Composable
fun TileCard(title: String, tile: CardTile, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null,
    selected: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    GlassCard(modifier, selected = selected, tint = tile.colour, tintAlpha = .16f, gap = 10.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftTile(tile.icon, tile.colour, Modifier.testTag("card-tile"), size = 30.dp)
            if (action == null) AfText(title, Modifier.weight(1f), type = GlassSectionType, weight = androidx.compose.ui.text.font.FontWeight.Bold)
            else TitleAndAction({ AfText(title, type = GlassSectionType, weight = androidx.compose.ui.text.font.FontWeight.Bold) }, action, Modifier.weight(1f))
        }
        content()
    }
}

/**
 * A card header's title and [action]: side by side (gap 10, both centred) while the title's widest syllable fits beside
 * the action, else the action on its own line under the title, at the end (a wide Burmese button at font 2.0), so a word is
 * never cut inside a syllable.
 */
@Composable
private fun TitleAndAction(title: @Composable () -> Unit, action: @Composable () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.ui.layout.Layout(contents = listOf(title, action), modifier) { (titleM, actionM), constraints ->
        val gap = 10.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val end = actionM.map { it.measure(loose) }
        val actionW = end.sumOf { it.width }
        val actionH = end.maxOfOrNull { it.height } ?: 0
        val text = titleM.first()
        val beside = if (end.isEmpty()) 0 else actionW + gap
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else text.maxIntrinsicWidth(Constraints.Infinity) + beside
        val stack = end.isNotEmpty() && text.minIntrinsicWidth(Constraints.Infinity) > width - beside
        if (stack) {
            val t = text.measure(loose.copy(minWidth = width, maxWidth = width))
            layout(width, t.height + gap / 2 + actionH) {
                t.place(0, 0)
                var x = width - actionW
                end.forEach { it.place(x, t.height + gap / 2); x += it.width }
            }
        } else {
            val w = (width - beside).coerceAtLeast(0)
            val t = text.measure(loose.copy(minWidth = w, maxWidth = w))
            val h = maxOf(t.height, actionH)
            layout(width, h) {
                t.place(0, (h - t.height) / 2)
                var x = width - actionW
                end.forEach { it.place(x, (h - it.height) / 2); x += it.width }
            }
        }
    }
}

/**
 * One choice of a set: a card with the [title] and its [explanation] (+ "Recommended"); [radio] = the glass radio row
 * of Group 2 A §2 (22 dp radio-button / circle, title + cyan Recommended chip, caption; selected = primary .14 fill with
 * a 1.5 dp purple ring).
 */
@Composable
fun OptionCard(title: String, explanation: String, selected: Boolean, onSelect: () -> Unit,
    modifier: Modifier = Modifier, recommended: Boolean = false, enabled: Boolean = true, radio: Boolean = false,
) {
    if (radio) {
        val shape = GlassShapes.MenuRow
        Row(modifier.fillMaxWidth().clip(shape).clickable(enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .semantics { this.selected = selected }
            .background(if (selected) GlassColors.OptionOnFill else GlassColors.OptionFill)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) AutoFlowColors.Purple else GlassColors.OptionBorder, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AfIcon(if (selected) AfIcons.Fill.RadioButton else AfIcons.Regular.Circle, Modifier.padding(top = 1.dp),
                tint = if (selected) GlassColors.RecapLavender else AutoFlowColors.Muted, size = 22.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                WrapRow(spacing = 6.dp) {
                    AfText(title, Modifier.align(Alignment.CenterVertically), type = GlassSectionType,
                        weight = androidx.compose.ui.text.font.FontWeight.Bold)
                    if (recommended) ToneChip(tr(UiText.V3_RECOMMENDED), Tone.Running, Modifier.align(Alignment.CenterVertically), AfIcons.Fill.Spark)
                }
                AfText(explanation, muted = true, type = AfType.Caption)
            }
        }
        return
    }
    AfCard(title, modifier.clickable(enabled = enabled, role = Role.RadioButton, onClick = onSelect).semantics { this.selected = selected }, selected) {
        AfText(explanation, muted = true)
        if (recommended) AfText(tr(UiText.V3_RECOMMENDED), color = AutoFlowColors.Cyan)
    }
}

@Composable
fun OptionChips(options: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    WrapRow(modifier) { options.forEach { value -> AfChip(value, value == selected, { onSelect(value) }) } }
}

/**
 * A filter and its item count; [count] null hides the number. Glass rows only: [icon] before the label, [tag] on the
 * chip and a cyan [badge] after the label (> 0 only, tagged `<tag>-badge`; the Music Video Inbox chip, Group 3 A 🆕-16).
 */
class FilterChipItem(val label: String, val count: Int? = null, val icon: ImageVector? = null, val tag: String? = null, val badge: Int? = null)

/**
 * Filters scroll in one row with faded edges (DESIGN.md §9.2); options wrap instead ([OptionChips]). [glass] = glass
 * filter chips (tab screens); the row's layer then reaches 6 dp above and below so the selected chip's glow is not cut.
 */
@Composable
fun FilterChipRow(items: List<FilterChipItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, glass: Boolean = false) {
    val scroll = rememberScrollState()
    val glow = if (glass) 6.dp else 0.dp
    Row(modifier.fillMaxWidth().touchOverflow(vertical = glow).graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        val fade = 24.dp.toPx()
        if (scroll.canScrollBackward) drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), 0f, fade),
            size = Size(fade, size.height), blendMode = BlendMode.DstIn)
        if (scroll.canScrollForward) drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), size.width - fade, size.width),
            Offset(size.width - fade, 0f), Size(fade, size.height), blendMode = BlendMode.DstIn)
    }.horizontalScroll(scroll).padding(vertical = glow), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { i, item ->
            if (glass) GlassFilterChip(item.label, i == selected, { onSelect(i) }, item.tag?.let { Modifier.testTag(it) } ?: Modifier,
                count = item.count, leading = item.icon?.let { icon -> { ink -> AfIcon(icon, tint = ink, size = 16.dp) } },
                badge = item.badge?.takeIf { it > 0 }, badgeModifier = item.tag?.let { Modifier.testTag("$it-badge") } ?: Modifier)
            else AfChip(item.label, i == selected, { onSelect(i) }, count = item.count)
        }
    }
}

@Composable
fun PresetTile(title: String, explanation: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) =
    OptionCard(title, explanation, selected, onSelect, modifier)

/**
 * A part that is not built here yet (Group 4 B, the glass empty-state look): a 56 dp soft tile `hourglass` (Muted), the
 * [title], the muted Planned pill and the [explanation], centred on a glass card; disabled for screen readers.
 */
@Composable
fun PlannedTile(title: String, explanation: String? = null, modifier: Modifier = Modifier) {
    GlassCard(modifier.semantics { disabled() }) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftTile(AfIcons.Duotone.Hourglass, AutoFlowColors.Muted, size = 56.dp)
            AfText(title, type = AfType.RowTitle, weight = androidx.compose.ui.text.font.FontWeight.Bold, textAlign = TextAlign.Center)
            StatusChip(StudioStatus.PLANNED)
            explanation?.let { AfText(My.breakSyllables(it), muted = true, textAlign = TextAlign.Center) }
        }
    }
}

/**
 * A recap block row (P9-11; glass card, Group 2 A §2): [thumbnail] (the 128 × 72 scene frame) beside "Block n" and its
 * real [time] in mono (a clip range never breaks; ranges wrap at " · ") — above them when the text column would be
 * narrower than [AfSize.TileCardMin] × the font scale — then the [status] chip, the script's first line (two lines at
 * most; the full line stays its description and is in the editor), play and [actions]. [playing] = 1.5 dp Cyan outline.
 */
@Composable
fun BlockCard(number: Int, time: String, script: String, status: StudioStatus, onOpen: () -> Unit,
    modifier: Modifier = Modifier, selected: Boolean = false, deltaMs: Long? = null,
    onPlay: (() -> Unit)? = null, thumbnail: (@Composable () -> Unit)? = null, playing: Boolean = false,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    GlassCard(modifier.clickable(role = Role.Button, onClick = onOpen), selected, gap = 10.dp,
        outline = if (playing) GlassColors.PlayingOutline else null) {
        val facts = @Composable { m: Modifier ->
            Column(m, verticalArrangement = Arrangement.spacedBy(AfSpace.Xs)) {
                AfText(tr(UiText.V3_BLOCK, "n" to number), type = AfType.RowTitle)
                AfText(unbrokenRanges(time), muted = true, mono = true, size = 12.sp)
            }
        }
        if (thumbnail == null) facts(Modifier.fillMaxWidth()) else ThumbBeside(AfSize.ThumbScene.width, thumbnail, facts)
        StatusChip(status, deltaMs = deltaMs)
        val line = script.lineSequence().firstOrNull().orEmpty()
        if (line.isNotBlank()) AfText(line, Modifier.semantics { contentDescription = line }, type = AfType.Body, maxLines = 2, ellipsis = true)
        onPlay?.let { GlassIconButton(AfIcons.Fill.Volume, tr(UiText.AUDIO_PLAY), it, size = GlassShapes.IconButtonHeader) }
        actions()
    }
}

/** "0:42–1:05 · 3:10–3:31" with a word joiner after each en dash: a line breaks only between ranges. */
fun unbrokenRanges(time: String): String = time.replace("–", "–\u2060")

/**
 * [leading] · [thumb] ([thumbWidth] wide) · [facts], or the facts (after [leading]) above the thumbnail when their column
 * would be narrower than [factsMin] × the font scale (large fonts, narrow panes): text is never squeezed into a sliver.
 */
@Composable
internal fun ThumbBeside(thumbWidth: Dp, thumb: @Composable () -> Unit, facts: @Composable (Modifier) -> Unit,
    leading: (@Composable () -> Unit)? = null, factsMin: Dp = AfSize.TileCardMin, gap: Dp = AfSpace.M, leadingWidth: Dp = AfSize.Icon) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val lead = if (leading != null) leadingWidth + gap else 0.dp
        if (maxWidth - lead - thumbWidth - gap >= factsMin * LocalDensity.current.fontScale) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
                leading?.invoke(); thumb(); facts(Modifier.weight(1f))
            }
        } else Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            if (leading == null) facts(Modifier.fillMaxWidth())
            else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
                leading(); facts(Modifier.weight(1f))
            }
            thumb()
        }
    }
}

/** A check box drawn for a toggleable row (22 dp, radius 6): checked = primary fill + white check. The row has the semantics. */
@Composable
fun CheckMark(checked: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Box(modifier.size(AfSize.Icon).clip(shape)
        .then(if (checked) Modifier.background(AutoFlowColors.PrimaryFill) else Modifier.border(1.5.dp, AutoFlowColors.LineStrong, shape))
        .testTag(if (checked) "check-mark-on" else "check-mark-off"), contentAlignment = Alignment.Center) {
        if (checked) AfIcon(AfIcons.Regular.Check, tint = Color.White, size = AfSize.IconXs)
    }
}

/**
 * A job's card: title (+ [headerAction], e.g. Cancel), status chip, progress bar while it runs, Muted [detail], [actions].
 * [tile] = the glass job card of Group 2 D §1 (Movie Recap steps): the card's soft tile · title · small Cancel header,
 * the cyan → purple bar (indeterminate = the moving segment) and a Muted caption.
 */
@Composable
fun JobCard(title: String, status: StudioStatus, detail: String, modifier: Modifier = Modifier,
    progress: Float? = null, indeterminate: Boolean = false, headerAction: (@Composable () -> Unit)? = null,
    tile: CardTile? = null, actions: @Composable ColumnScope.() -> Unit = {},
) {
    if (tile != null) TileCard(io.github.alalkipgen.autoflow.ui.i18n.My.breakSyllables(title), tile, modifier, action = headerAction) {
        StatusChip(status)
        if (progress != null || indeterminate) GlassProgress(progress)
        AfText(io.github.alalkipgen.autoflow.ui.i18n.My.breakSyllables(detail), muted = true, type = AfType.Caption)
        actions()
    } else AfCard(title, modifier, headerAction = headerAction) {
        StatusChip(status)
        if (progress != null || indeterminate) AfProgress(progress)
        AfText(detail, muted = true)
        actions()
    }
}
