package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes

/** UI callback, not a persisted command or engine state. */
data class StudioAction(val label: String, val onClick: () -> Unit, val enabled: Boolean = true, val tag: String? = null)

@Composable
private fun Action(action: StudioAction, primary: Boolean = false, modifier: Modifier = Modifier) = AfButton(
    action.label, action.onClick, modifier.then(action.tag?.let { Modifier.testTag(it) } ?: Modifier),
    kind = if (primary) ButtonKind.Primary else ButtonKind.Secondary, enabled = action.enabled,
)

/**
 * Project editor header (DESIGN.md §9.4): [AfTopBar] (back, project name, "step k/5 · label", page preview and ⋮ as
 * icon buttons) over a [StepBar] of [steps] (bars only when the caller passes no labels).
 */
@Composable
fun ProjectTopBar(name: String, step: Int, stepLabel: String, onBack: () -> Unit,
    modifier: Modifier = Modifier, options: List<StudioAction> = emptyList(), saveState: StudioStatus? = null,
    steps: List<String> = emptyList(),
) {
    require(step in 1..5)
    var open by remember(name) { mutableStateOf(false) }
    val page = LocalProjectPreviewPage.current?.takeIf { it.available }
    Column(modifier.fillMaxWidth().background(AutoFlowColors.Panel).padding(horizontal = 6.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Right-click on the bar opens the same ⋮ options (DESIGN.md §9.8).
        AfTopBar(name, if (options.isEmpty()) Modifier else Modifier.onSecondaryClick { open = true },
            subtitle = My.breakSyllables(tr(UiText.V3_STEP, "step" to stepLabel, "n" to step)), onBack = onBack,
            backModifier = Modifier.testTag("project-back")) {
            if (page != null) AfIconButton(if (page.shown) AfIcons.Regular.Edit else AfIcons.Fill.Play,
                tr(if (page.shown) UiText.EDIT else UiText.PROJECT_PAGE_PREVIEW), { page.shown = !page.shown },
                Modifier.testTag(if (page.shown) "project-page-edit" else "project-page-preview"), selected = page.shown)
            if (options.isNotEmpty()) AfIconButton(AfIcons.Regular.More, tr(UiText.V3_MORE_OPTIONS), { open = !open },
                Modifier.testTag("project-overflow"), selected = open)
        }
        StepBar(steps, step, Modifier.padding(horizontal = 6.dp).testTag("project-progress"), count = 5)
        saveState?.let { StatusChip(it) } // callers omit this when no reliable save state exists
        if (open) WrapRow { options.forEach { action -> Action(action.copy(onClick = { open = false; action.onClick() })) } }
    }
}

/**
 * A measured sibling of the scrolling content, not an overlay with a guessed bottom spacer: bar colour with a top
 * divider, above the keyboard. [caption] is the hint (centred below on a phone, left on the desktop); [leading] is an
 * optional control before the buttons (DESIGN.md §9.4).
 */
@Composable
fun StickyActionBar(primary: StudioAction, secondary: StudioAction? = null, caption: String? = null, modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().background(AutoFlowColors.TitleBar).imePadding().testTag("sticky-action-bar")) {
        Box(Modifier.fillMaxWidth().height(AutoFlowShapes.Border).background(AutoFlowColors.Line))
        Box(Modifier.padding(horizontal = if (LocalCompact.current) 12.dp else 16.dp, vertical = 12.dp)) {
            StickyActions({ Action(primary, true, it) }, secondary?.let { s -> { Action(s, modifier = it) } }, caption, leading)
        }
    }
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, initiallyExpanded: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember(title) { mutableStateOf(initiallyExpanded) }
    val expansionLabel = tr(if (expanded) UiText.V3_EXPANDED else UiText.V3_COLLAPSED)
    AfCard(title, modifier, headerAction = {
        AfButton(if (expanded) "−" else "+", { expanded = !expanded }, Modifier.semantics {
            contentDescription = title
            stateDescription = expansionLabel
        })
    }) { if (expanded) content() }
}

@Composable
fun OptionCard(title: String, explanation: String, selected: Boolean, onSelect: () -> Unit,
    modifier: Modifier = Modifier, recommended: Boolean = false, enabled: Boolean = true,
) {
    AfCard(title, modifier.clickable(enabled = enabled, role = Role.RadioButton, onClick = onSelect).semantics { this.selected = selected }, selected) {
        AfText(explanation, muted = true)
        if (recommended) AfText(tr(UiText.V3_RECOMMENDED), color = AutoFlowColors.Cyan)
    }
}

@Composable
fun OptionChips(options: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    WrapRow(modifier) { options.forEach { value -> AfChip(value, value == selected, { onSelect(value) }) } }
}

/** A filter and its item count; [count] null hides the number. */
class FilterChipItem(val label: String, val count: Int? = null)

/** Filters scroll in one row with faded edges (DESIGN.md §9.2); options wrap instead ([OptionChips]). */
@Composable
fun FilterChipRow(items: List<FilterChipItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Row(modifier.fillMaxWidth().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        val fade = 24.dp.toPx()
        if (scroll.canScrollBackward) drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), 0f, fade),
            size = Size(fade, size.height), blendMode = BlendMode.DstIn)
        if (scroll.canScrollForward) drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), size.width - fade, size.width),
            Offset(size.width - fade, 0f), Size(fade, size.height), blendMode = BlendMode.DstIn)
    }.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { i, item -> AfChip(item.label, i == selected, { onSelect(i) }, count = item.count) }
    }
}

@Composable
fun PresetTile(title: String, explanation: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) =
    OptionCard(title, explanation, selected, onSelect, modifier)

@Composable
fun PlannedTile(title: String, explanation: String? = null, modifier: Modifier = Modifier) {
    AfCard(title, modifier.semantics { disabled() }) { StatusChip(StudioStatus.PLANNED); explanation?.let { AfText(My.breakSyllables(it), muted = true) } }
}

@Composable
fun BlockCard(number: Int, time: String, script: String, status: StudioStatus, onOpen: () -> Unit,
    modifier: Modifier = Modifier, selected: Boolean = false, deltaMs: Long? = null,
    onPlay: (() -> Unit)? = null, thumbnail: (@Composable () -> Unit)? = null, actions: @Composable ColumnScope.() -> Unit = {},
) {
    AfCard(tr(UiText.V3_BLOCK, "n" to number), modifier.clickable(role = Role.Button, onClick = onOpen), selected) {
        AfText(time, muted = true, mono = true)
        thumbnail?.invoke()
        StatusChip(status, deltaMs = deltaMs)
        AfText(script.lineSequence().firstOrNull().orEmpty())
        onPlay?.let { AfButton(tr(UiText.PLAY), it) }
        actions()
    }
}

@Composable
fun JobCard(title: String, status: StudioStatus, detail: String, modifier: Modifier = Modifier,
    progress: Float? = null, indeterminate: Boolean = false, headerAction: (@Composable () -> Unit)? = null,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    AfCard(title, modifier, headerAction = headerAction) {
        StatusChip(status)
        if (progress != null || indeterminate) AfProgress(progress)
        AfText(detail, muted = true)
        actions()
    }
}
