package io.github.alalkipgen.autoflow.ui.subtitles

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.subtitles.CueIssue
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleCue
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import io.github.alalkipgen.autoflow.ui.theme.*
import kotlin.math.roundToInt

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

/** Where [x] px of a [width] px strip is on a [totalMs] timeline (the seek bar's tap / drag). */
internal fun stripTimeAt(x: Float, width: Float, totalMs: Long): Long =
    if (width <= 0f) 0L else ((x / width).coerceIn(0f, 1f) * totalMs).toLong()

/**
 * The cue strip under the video, the editor's seek bar (Group 5 🆕-2; DESIGN.md §9.7, panel 5): every line as a block
 * over the video's length (`cueMark`: selected primary, not translated red, other checks amber, idle primary α .33) on a
 * 32 dp track (`Card`, 1 dp `Line` edge) in a 48 dp touch row, the playhead a 2 dp `Text` line with a 14 dp white knob in
 * a 3 dp purple ring above the track (`$tag-strip-knob`, drawn outside the `$tag-strip-track` Canvas). Tap or drag =
 * [onSeek] there (progress semantics with SetProgress on `$tag-scrubber`, SUB_PLAYHEAD); it never changes a line's times.
 * [tag] = "subtitles" (the card) or "subtitles-fullscreen" (the Fullscreen window's copy).
 */
@Composable
internal fun CueStrip(cues: List<SubtitleCue>, durationMs: Long, positionMs: Long, selected: String?,
    issues: Map<String, Set<CueIssue>>, modifier: Modifier = Modifier, onSeek: (Long) -> Unit = {}, tag: String = "subtitles") {
    val total = maxOf(durationMs, cues.maxOfOrNull { it.endMs } ?: 0L, 1L)
    val marks = cues.map { cueMark(it.id == selected, issues[it.id]) }
    val shape = RoundedCornerShape(AfRadius.ButtonSmall)
    val seek by rememberUpdatedState(onSeek)
    val fraction = (positionMs.toFloat() / total).coerceIn(0f, 1f)
    val label = tr(UiText.SUB_PLAYHEAD)
    BoxWithConstraints(modifier.fillMaxWidth().height(AfSize.Touch).testTag("$tag-strip")) {
        val trackTop = (AfSize.Touch - AfSize.CueStrip) / 2
        Canvas(Modifier.fillMaxWidth().height(AfSize.CueStrip).align(Alignment.Center).clip(shape).background(AutoFlowColors.Card)
            .border(AutoFlowShapes.Border, AutoFlowColors.Line, shape).testTag("$tag-strip-track")) {
            val inset = AfSpace.Xs.toPx()
            val gap = AutoFlowShapes.Border.toPx()
            val radius = CornerRadius(AfRadius.Progress.toPx())
            cues.forEachIndexed { i, c ->
                val x = c.startMs.toFloat() / total * size.width
                val w = ((c.endMs - c.startMs).toFloat() / total * size.width - gap).coerceAtLeast(gap * 2)
                drawRoundRect(marks[i].color, Offset(x, inset), Size(w, size.height - inset * 2), radius)
            }
            val line = AutoFlowShapes.Border.toPx() * 2
            val at = (fraction * size.width).coerceIn(0f, size.width - line)
            drawRect(AutoFlowColors.Text, Offset(at, 0f), Size(line, size.height))
        }
        // The knob sits on the track's top edge, above the blocks (outside the track's Canvas, so its pixels stay the lines').
        val knob = KNOB + KNOB_RING * 2
        Box(Modifier.offset { IntOffset((fraction * constraints.maxWidth - knob.toPx() / 2).roundToInt(), (trackTop - knob / 2 - 2.dp).roundToPx()) }
            .size(knob).shadow(4.dp, CircleShape).clip(CircleShape).background(AutoFlowColors.Purple)
            .padding(KNOB_RING).clip(CircleShape).background(Color.White).testTag("$tag-strip-knob"))
        Box(Modifier.matchParentSize().semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { next -> if (!next.isFinite()) false else { seek((next.coerceIn(0f, 1f) * total).toLong()); true } }
            }.pointerInput(total) {
                detectTapGestures { p -> seek(stripTimeAt(p.x, size.width.toFloat(), total)) }
            }.pointerInput(total) {
                detectHorizontalDragGestures { change, _ -> change.consume(); seek(stripTimeAt(change.position.x, size.width.toFloat(), total)) }
            }.testTag(if (tag == "subtitles") "subtitles-scrubber" else "$tag-scrubber"))
    }
}

/** Lines that need nothing are primary at a third (DESIGN.md §9.7, panel 5). */
private const val IDLE_BLOCK_ALPHA = .33f
/** 🆕-2: the 14 dp white knob and its 3 dp purple ring. */
private val KNOB = 14.dp
private val KNOB_RING = 3.dp
