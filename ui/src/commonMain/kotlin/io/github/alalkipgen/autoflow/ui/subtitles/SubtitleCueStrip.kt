package io.github.alalkipgen.autoflow.ui.subtitles

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import io.github.alalkipgen.autoflow.core.subtitles.CueIssue
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleCue
import io.github.alalkipgen.autoflow.core.subtitles.TimeText
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.theme.*

/** A line's block on the cue strip (DESIGN.md §9.7, panel 5). */
internal enum class CueMark {
    NORMAL, SELECTED, WARNING, UNTRANSLATED;

    val color: Color get() = when (this) {
        NORMAL -> Tone.Primary.fill.copy(alpha = IDLE_BLOCK_ALPHA)
        SELECTED -> Tone.Primary.fill
        WARNING -> Tone.Warning.fill
        UNTRANSLATED -> Tone.Error.fill
    }
}

/** The selected line stays primary; otherwise not translated (red) before any other check (amber). */
internal fun cueMark(selected: Boolean, issues: Set<CueIssue>?): CueMark = when {
    selected -> CueMark.SELECTED
    issues.orEmpty().contains(CueIssue.NOT_TRANSLATED) -> CueMark.UNTRANSLATED
    !issues.isNullOrEmpty() -> CueMark.WARNING
    else -> CueMark.NORMAL
}

/**
 * Display-only timeline under the video (DESIGN.md §9.7, panel 5): every line as a block over the video's length and the
 * playhead, between the playhead and length times in tabular figures. Lines are picked and timed in the list, so it takes no input.
 */
@Composable
internal fun CueStrip(cues: List<SubtitleCue>, durationMs: Long, positionMs: Long, selected: String?,
    issues: Map<String, Set<CueIssue>>, modifier: Modifier = Modifier) {
    val total = maxOf(durationMs, cues.maxOfOrNull { it.endMs } ?: 0L, 1L).toFloat()
    val marks = cues.map { cueMark(it.id == selected, issues[it.id]) }
    val shape = RoundedCornerShape(AfRadius.ButtonSmall)
    // One row — the playhead time, the blocks, the length — so the list keeps its room on a phone at font 2.0.
    Row(modifier.fillMaxWidth().testTag("subtitles-strip"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AfSpace.S)) {
        AfText(TimeText.show(positionMs), Modifier.testTag("subtitles-strip-now"), color = AutoFlowColors.Muted, type = AfType.Numbers)
        Canvas(Modifier.weight(1f).height(AfSize.CueStrip).clip(shape).background(AutoFlowColors.Card)
            .border(AutoFlowShapes.Border, AutoFlowColors.Line, shape).testTag("subtitles-strip-track")) {
            val inset = AfSpace.Xs.toPx()
            val gap = AutoFlowShapes.Border.toPx()
            val radius = CornerRadius(AfRadius.Progress.toPx())
            cues.forEachIndexed { i, c ->
                val x = c.startMs / total * size.width
                val w = ((c.endMs - c.startMs) / total * size.width - gap).coerceAtLeast(gap * 2)
                drawRoundRect(marks[i].color, Offset(x, inset), Size(w, size.height - inset * 2), radius)
            }
            val line = AutoFlowShapes.Border.toPx() * 2
            val at = (positionMs / total * size.width).coerceIn(0f, size.width - line)
            drawRect(AutoFlowColors.Text, Offset(at, 0f), Size(line, size.height))
        }
        AfText(TimeText.show(durationMs), Modifier.testTag("subtitles-strip-length"), color = AutoFlowColors.Muted, type = AfType.Numbers)
    }
}

/** Lines that need nothing are primary at a third (DESIGN.md §9.7, panel 5). */
private const val IDLE_BLOCK_ALPHA = .33f
