package io.github.alalkipgen.autoflow.ui.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.workflow.ShotStatus
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.GlassColors
import io.github.alalkipgen.autoflow.ui.theme.GlassShapes

/** The status pill's tone (Group 3 §3.2): the badge colours, Needs Flow in the lighter purple (sample 05). */
internal fun ShotStatus.pillColour(): Color = when (this) {
    ShotStatus.DRAFT -> AutoFlowColors.Muted
    ShotStatus.PROMPT_READY -> AutoFlowColors.Cyan
    ShotStatus.NEEDS_FLOW -> GlassColors.ShotNeedsFlow
    ShotStatus.IMPORTED -> AutoFlowColors.Amber
    ShotStatus.ACCEPTED -> AutoFlowColors.Green
    ShotStatus.STALE -> AutoFlowColors.Stale
}

/** The pill's 14 dp icon (Group 3 §3.2): Regular, Accepted and Stale (and Prompt ready) in Fill. */
internal fun ShotStatus.pillIcon(): ImageVector = when (this) {
    ShotStatus.DRAFT -> AfIcons.Regular.CircleDashed
    ShotStatus.PROMPT_READY -> AfIcons.Fill.NotePencil
    ShotStatus.NEEDS_FLOW -> AfIcons.Regular.ArrowSquareOut
    ShotStatus.IMPORTED -> AfIcons.Regular.DownloadSimple
    ShotStatus.ACCEPTED -> AfIcons.Fill.CheckCircle
    ShotStatus.STALE -> AfIcons.Fill.Warn
}

/** The pill's label: the status words without symbols (the icon shows them); Prompt ready is the new V3_PROMPT_READY. */
internal fun ShotStatus.pillLabel(language: UiLanguage = UiLanguage.ENGLISH): String = when (this) {
    ShotStatus.DRAFT -> UiText.DRAFT
    ShotStatus.PROMPT_READY -> UiText.V3_PROMPT_READY
    ShotStatus.NEEDS_FLOW -> UiText.NEEDS_FLOW
    ShotStatus.IMPORTED -> UiText.IMPORTED
    ShotStatus.ACCEPTED -> UiText.ACCEPTED
    ShotStatus.STALE -> UiText.STALE
}.text(language)

/**
 * The shared shot status pill (Group 3 §3.2, sample 05 `.pl`): min height 24, radius full, padding 7 / 9, the tone at
 * .14 fill and .38 line, the 14 dp icon and the 11.5 sp label in the tone. Tagged `shot-status-<STATUS>`.
 */
@Composable
internal fun ShotStatusPill(status: ShotStatus, modifier: Modifier = Modifier) =
    TonePill(status.pillColour(), status.pillIcon(), status.pillLabel(LocalUiLanguage.current), modifier.testTag("shot-status-${status.name}"))

/** The status pill's look for any tone (sample `pill9`): Approved = Green check-circle, Stale = warning on the shot sheet. */
@Composable
internal fun TonePill(tone: Color, icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    val shape = GlassShapes.Pill
    Row(modifier.heightIn(min = 24.dp).clip(shape).background(tone.copy(alpha = .14f)).border(1.dp, tone.copy(alpha = .38f), shape)
        .padding(start = 7.dp, end = 9.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        AfIcon(icon, tint = tone, size = 14.dp)
        AfText(My.breakSyllables(text), color = tone, type = AfType.Micro)
    }
}
