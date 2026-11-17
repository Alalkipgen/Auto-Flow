package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.ui.i18n.*
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
    fun label(language: UiLanguage, deltaMs: Long? = null): String {
        if (this != TIMING) return text.text(language)
        val delta = requireNotNull(deltaMs) { "TIMING needs the actual duration difference" }
        if (delta == 0L) return UiText.V3_FITS.text(language)
        return (if (delta > 0) UiText.V3_TOO_LONG else UiText.V3_TOO_SHORT)
            .text(language, "seconds" to (round(abs(delta.toDouble()) / 100.0) / 10.0))
    }
}

@Composable
fun StatusChip(status: StudioStatus, modifier: Modifier = Modifier, deltaMs: Long? = null) {
    val label = status.label(LocalUiLanguage.current, deltaMs)
    Box(modifier.testTag("status-${status.name}").clip(AutoFlowShapes.Card)
        .background(status.color.copy(alpha = .12f)).semantics { stateDescription = label }.padding(8.dp, 4.dp)) {
        AfText(My.breakSyllables(label), color = status.color)
    }
}
