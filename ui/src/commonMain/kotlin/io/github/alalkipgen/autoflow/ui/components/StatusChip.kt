package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.projects.label
import io.github.alalkipgen.autoflow.ui.theme.*
import kotlin.math.abs
import kotlin.math.round

/** Presentation only: callers derive state from the existing engine, never from a label. */
enum class StudioStatus(val text: UiText) {
    FITS(UiText.V3_FITS), TIMING(UiText.V3_TOO_LONG), NO_VOICE(UiText.V3_NO_VOICE),
    SCRIPT_CHANGED(UiText.V3_SCRIPT_CHANGED), RUNNING(UiText.JOB_RUNNING), QUEUED(UiText.JOB_QUEUED),
    PAUSED(UiText.JOB_PAUSED), FAILED(UiText.JOB_FAILED), DONE(UiText.JOB_DONE), ACCEPTED(UiText.ACCEPTED),
    PLANNED(UiText.V3_PLANNED), CANCELLED(UiText.JOB_CANCELLED),
    SAVED(UiText.V3_SAVED), SAVING(UiText.SAVING), UNSAVED(UiText.V3_UNSAVED), SAVE_FAILED(UiText.V3_SAVE_FAILED);

    val color: Color get() = when (this) {
        FITS, DONE, ACCEPTED, SAVED -> AutoFlowColors.Green
        TIMING, SCRIPT_CHANGED, PAUSED, UNSAVED -> AutoFlowColors.Amber
        RUNNING, QUEUED, SAVING -> AutoFlowColors.Cyan
        FAILED, SAVE_FAILED -> AutoFlowColors.Failed
        else -> AutoFlowColors.Muted
    }
    /** §7 mapping as a tone: container + readable word colour (DESIGN.md §9.2). */
    val tone: Tone get() = when (this) {
        FITS, DONE, ACCEPTED, SAVED -> Tone.Success
        TIMING, SCRIPT_CHANGED, PAUSED, UNSAVED -> Tone.Warning
        RUNNING, QUEUED, SAVING -> Tone.Running
        FAILED, SAVE_FAILED -> Tone.Error
        else -> Tone.Neutral
    }
    /** Status icons are Fill, except the Regular check (DESIGN.md §9.3). */
    val icon: ImageVector get() = when (this) {
        FITS, DONE, ACCEPTED, SAVED -> AfIcons.Regular.Check
        TIMING, SCRIPT_CHANGED, UNSAVED -> AfIcons.Fill.Warn
        PAUSED -> AfIcons.Fill.Pause
        RUNNING, SAVING -> AfIcons.Fill.Redo
        QUEUED, PLANNED -> AfIcons.Fill.Clock
        FAILED, SAVE_FAILED -> AfIcons.Fill.XCircle
        CANCELLED -> AfIcons.Fill.Close
        NO_VOICE -> AfIcons.Fill.Wave
    }
    fun label(language: UiLanguage, deltaMs: Long? = null): String {
        if (this != TIMING) return text.text(language)
        val delta = requireNotNull(deltaMs) { "TIMING needs the actual duration difference" }
        if (delta == 0L) return UiText.V3_FITS.text(language)
        return (if (delta > 0) UiText.V3_TOO_LONG else UiText.V3_TOO_SHORT)
            .text(language, "seconds" to (round(abs(delta.toDouble()) / 100.0) / 10.0))
    }
}

/** Tone container + 15 dp icon + word (never colour alone); 25 dp pill (DESIGN.md §9.4). */
@Composable
fun StatusChip(status: StudioStatus, modifier: Modifier = Modifier, deltaMs: Long? = null, showIcon: Boolean = true) {
    val label = status.label(LocalUiLanguage.current, deltaMs)
    val tone = status.tone
    Row(modifier.testTag("status-${status.name}").heightIn(min = AfSize.StatusChip).clip(RoundedCornerShape(50))
        .background(tone.container).semantics { stateDescription = label }.padding(horizontal = 9.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (showIcon) AfIcon(status.icon, tint = tone.text, size = AfSize.IconXs)
        AfText(My.breakSyllables(label), color = tone.text, type = AfType.Micro)
    }
}

/** Neutral chip: the mode's icon + its name (DESIGN.md §9.4). */
@Composable
fun ModeChip(mode: Mode, modifier: Modifier = Modifier) {
    Row(modifier.heightIn(min = AfSize.StatusChip).clip(RoundedCornerShape(50)).background(Tone.Neutral.container)
        .padding(horizontal = 9.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        AfIcon(mode.icon(), tint = Tone.Neutral.text, size = AfSize.IconXs)
        AfText(tr(mode.label()), color = Tone.Neutral.text, type = AfType.Micro)
    }
}

internal fun Mode.icon(): ImageVector = when (this) {
    Mode.MOVIE_RECAP -> AfIcons.Regular.Film
    Mode.MUSIC_VIDEO -> AfIcons.Regular.Music
    Mode.SUBTITLES -> AfIcons.Regular.Subtitles
    Mode.VOICE_AUDIO -> AfIcons.Regular.Wave
    Mode.DUBBING -> AfIcons.Regular.Dub
}
