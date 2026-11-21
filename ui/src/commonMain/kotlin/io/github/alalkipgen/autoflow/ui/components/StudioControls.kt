package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
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
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/** UI callback, not a persisted command or engine state. */
data class StudioAction(val label: String, val onClick: () -> Unit, val enabled: Boolean = true, val tag: String? = null)

@Composable
private fun Action(action: StudioAction, primary: Boolean = false, modifier: Modifier = Modifier) = AfButton(
    action.label, action.onClick, modifier.then(action.tag?.let { Modifier.testTag(it) } ?: Modifier),
    kind = if (primary) ButtonKind.Primary else ButtonKind.Secondary, enabled = action.enabled,
)

@Composable
fun ProjectTopBar(name: String, step: Int, stepLabel: String, onBack: () -> Unit,
    modifier: Modifier = Modifier, options: List<StudioAction> = emptyList(), saveState: StudioStatus? = null,
) {
    require(step in 1..5)
    val moreLabel = tr(UiText.V3_MORE_OPTIONS)
    val backLabel = tr(UiText.BACK)
    var open by remember(name) { mutableStateOf(false) }
    val page = LocalProjectPreviewPage.current?.takeIf { it.available }
    Column(modifier.fillMaxWidth().background(AutoFlowColors.Panel).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfButton("←", onBack, Modifier.testTag("project-back").semantics { contentDescription = backLabel })
            Column(Modifier.weight(1f)) {
                AfText(name, bold = true)
                AfText(My.breakSyllables(tr(UiText.V3_STEP, "step" to stepLabel, "n" to step)), muted = true)
            }
            if (page != null) {
                val pageLabel = tr(if (page.shown) UiText.EDIT else UiText.PROJECT_PAGE_PREVIEW)
                AfButton(if (page.shown) "✎" else "▶", { page.shown = !page.shown },
                    Modifier.testTag(if (page.shown) "project-page-edit" else "project-page-preview")
                        .semantics { contentDescription = pageLabel }, kind = if (page.shown) ButtonKind.Primary else ButtonKind.Secondary)
            }
            if (options.isNotEmpty()) Box(contentAlignment = Alignment.Center) {
                AfButton("", { open = !open }, Modifier.testTag("project-overflow").semantics { contentDescription = moreLabel })
                // Draw the standard overflow icon: the bundled Myanmar font has no U+22EE glyph.
                Canvas(Modifier.size(4.dp, 20.dp)) {
                    repeat(3) { n -> drawCircle(AutoFlowColors.Text, 1.5.dp.toPx(), Offset(size.width / 2, size.height * (n + 1) / 4)) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().testTag("project-progress").semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(step.toFloat(), 0f..5f, 4)
        }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(5) { i -> Box(Modifier.weight(1f).height(4.dp).testTag("progress-segment-$i")
                .background(if (i < step) AutoFlowColors.Purple else AutoFlowColors.Line)) }
        }
        saveState?.let { StatusChip(it) } // callers omit this when no reliable save state exists
        if (open) WrapRow { options.forEach { action -> Action(action.copy(onClick = { open = false; action.onClick() })) } }
    }
}

/** A measured sibling of the scrolling content, not an overlay with a guessed bottom spacer. */
@Composable
fun StickyActionBar(primary: StudioAction, secondary: StudioAction? = null, caption: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(AutoFlowColors.Panel).imePadding().padding(12.dp)
        .testTag("sticky-action-bar"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        caption?.let { AfText(it, muted = true) }
        if (LocalCompact.current) {
            secondary?.let { Action(it, modifier = Modifier.fillMaxWidth()) }
            Action(primary, true, Modifier.fillMaxWidth())
        } else WrapRow { secondary?.let { Action(it) }; Action(primary, true) }
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
