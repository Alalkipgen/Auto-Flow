package io.github.alalkipgen.autoflow.ui.shotsheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.GlassButton
import io.github.alalkipgen.autoflow.ui.components.GlassCard
import io.github.alalkipgen.autoflow.ui.components.GlassSectionType
import io.github.alalkipgen.autoflow.ui.components.SoftTile
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.shots.ShotRow
import io.github.alalkipgen.autoflow.ui.shots.ShotStatusPill
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.GlassColors

// Group 3 D outer (samples 07 / 08, 10): one shot's sheet in the glass look — the navigator hero card with the 5-dot
// step strip and the 5 step cards' headers (🆕-15). The cards' insides stay the classic ones until Group 3 PR 6–8.

/** 🆕-15: whether a step is done, on the step card (`sheet-step-n`) and its navigator dot (`shot-sheet-dot-n`). No text. */
internal val StepDone = SemanticsPropertyKey<Boolean>("StepDone")
internal var SemanticsPropertyReceiver.stepDone by StepDone

/** The sheet's 5 steps (plan D 3): title (no "N · " — the badge shows N), the header tile's Fill icon and colour. */
internal enum class SheetStep(val title: UiText, val icon: () -> ImageVector, val colour: Color) {
    STORYBOARD(UiText.SHEET_STORYBOARD, { AfIcons.Fill.Film }, AutoFlowColors.Purple),
    PROMPT(UiText.SHEET_PROMPT, { AfIcons.Fill.NotePencil }, AutoFlowColors.Cyan),
    REFS(UiText.SHEET_REFS, { AfIcons.Fill.Image }, GlassColors.SheetRefs),
    FLOW(UiText.SHEET_FLOW, { AfIcons.Fill.ArrowSquareOut }, GlassColors.ShotNeedsFlow),
    VIDEOS(UiText.SHEET_VIDEOS, { AfIcons.Fill.FileVideo }, AutoFlowColors.Green),
}

/** Text that keeps its size at every font scale: the digits inside the 18 / 24 dp step badges and dots (they never grow). */
@Composable
private fun fixed(size: Dp): TextUnit = with(LocalDensity.current) { size.toSp() }

/**
 * A step card (sample 07 `stepCard`): glass, tinted with the step colour (.16); header = the 30 dp soft tile with the 18 dp
 * number badge on its top-right corner (grey with N, green with `check` when done, 2 dp ring) and the title 14 sp bold.
 */
@Composable
internal fun StepCard(step: SheetStep, done: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val n = step.ordinal + 1
    GlassCard(Modifier.testTag("sheet-step-$n").semantics { stepDone = done }, tint = step.colour, tintAlpha = .16f, gap = 10.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.padding(top = 4.dp, end = 4.dp)) {
                SoftTile(step.icon(), step.colour, size = 30.dp)
                Box(Modifier.align(Alignment.TopEnd).offset(9.dp, (-9).dp).size(22.dp).clip(CircleShape).background(GlassColors.StepBadgeRing)
                    .padding(2.dp).clip(CircleShape).background(if (done) AutoFlowColors.Green else GlassColors.StepBadge),
                    contentAlignment = Alignment.Center) {
                    if (done) AfIcon(AfIcons.Regular.Check, tint = Color.White, size = 12.dp)
                    else AfText("$n", color = Color.White, size = fixed(9.5.dp), weight = FontWeight.SemiBold)
                }
            }
            AfText(My.breakSyllables(tr(step.title)), Modifier.weight(1f), type = GlassSectionType, weight = FontWeight.Bold)
        }
        content()
    }
}

/**
 * The navigator (sample 07 `navigator`): glass hero card tinted Purple .22 — "Shot 3 · 0:16–0:24" 17 sp bold and the status
 * pill, the mono "3 / 12" chip; the lyric in a quote box; the 5-dot step strip (🆕-15); previous / next as small glass
 * buttons (caret-left "Shot 2" / "Shot 4" caret-right; none at either end). Presentation only: [onSelect] = shots.select.
 */
@Composable
internal fun SheetNavigator(row: ShotRow, position: String, lyric: String?, steps: List<Boolean>, previous: ShotRow?, next: ShotRow?,
    onSelect: (Int) -> Unit) {
    GlassCard(Modifier.testTag("shot-sheet-navigator"), tint = AutoFlowColors.Purple, tintAlpha = .22f, gap = 10.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val end = if (row.endTimecode.isEmpty()) "" else "–${row.endTimecode}"
                AfText("Shot ${row.number} · ${row.timecode}$end", Modifier.testTag("shot-sheet-title"), size = 17.sp, bold = true)
                ShotStatusPill(row.status)
            }
            val chip = RoundedCornerShape(8.dp)
            Box(Modifier.clip(chip).background(GlassColors.PositionChipFill).border(1.dp, GlassColors.PositionChipBorder, chip)
                .padding(horizontal = 10.dp, vertical = 2.dp)) {
                AfText(position, Modifier.testTag("shot-sheet-position"), mono = true, size = 12.5.sp)
            }
        }
        lyric?.takeIf { it.isNotBlank() }?.let {
            val quote = RoundedCornerShape(12.dp)
            Box(Modifier.fillMaxWidth().clip(quote).background(GlassColors.QuoteFill).border(1.dp, GlassColors.QuoteBorder, quote)
                .padding(horizontal = 12.dp, vertical = 10.dp)) {
                AfText(My.breakSyllables(it), size = 13.sp)
            }
        }
        StepDots(steps)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            previous?.let { p ->
                GlassButton("Shot ${p.number}", { onSelect(p.number) }, Modifier.testTag("shot-sheet-prev"), icon = AfIcons.Regular.CaretLeft, small = true)
            }
            Spacer(Modifier.weight(1f))
            next?.let { n ->
                GlassButton("Shot ${n.number}", { onSelect(n.number) }, Modifier.testTag("shot-sheet-next"), trailingIcon = AfIcons.Regular.Chevron, small = true)
            }
        }
    }
}

/**
 * 🆕-15 step strip (sample 07 `.dots9`): five 24 dp dots joined by 2 dp lines — done = Green with a white `check`, the first
 * open step = Cyan .14 with a 1.5 dp Cyan ring, the others White .06 with a .22 line and the number; a line is Green when both
 * its dots are done. Dot n = the same flag as step card n.
 */
@Composable
private fun StepDots(steps: List<Boolean>) {
    val now = steps.indexOfFirst { !it }
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp).testTag("shot-sheet-steps"), verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { k, done ->
            val ring = when { done -> null; k == now -> AutoFlowColors.Cyan; else -> GlassColors.StepDotBorder }
            Box(Modifier.size(24.dp).clearAndSetSemantics { testTag = "shot-sheet-dot-${k + 1}"; stepDone = done }.clip(CircleShape)
                .background(when { done -> AutoFlowColors.Green; k == now -> GlassColors.StepDotNowFill; else -> GlassColors.StepDotFill })
                .then(if (ring != null) Modifier.border(if (k == now) 1.5.dp else 1.dp, ring, CircleShape) else Modifier),
                contentAlignment = Alignment.Center) {
                if (done) AfIcon(AfIcons.Regular.Check, tint = Color.White, size = 13.dp)
                else AfText("${k + 1}", color = AutoFlowColors.Text, size = fixed(11.5.dp), weight = FontWeight.SemiBold)
            }
            if (k < steps.lastIndex) Box(Modifier.weight(1f).padding(horizontal = 4.dp).height(2.dp).clip(RoundedCornerShape(1.dp))
                .background(if (done && steps[k + 1]) AutoFlowColors.Green else GlassColors.StepLine))
        }
    }
}
