package io.github.alalkipgen.autoflow.ui.subtitles

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.media.render.BurnFit
import io.github.alalkipgen.autoflow.media.render.BurnLength
import io.github.alalkipgen.autoflow.media.render.BurnSize
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The longest burned line for [fit]: Short = `subtitles.shortCharsPerLine`, Long = `subtitles.maxCharsPerLine`. */
fun BurnFit.maxChars(config: ToolsConfig.Subtitles): Int =
    if (length == BurnLength.SHORT) config.shortCharsPerLine else config.maxCharsPerLine

/** The output name part of a burn choice, so another choice makes another file (never overwritten). */
fun BurnFit.nameSuffix(): String = "${lines}l-${length.name.lowercase()}"

/** Size chips of a burned caption; the same in Subtitles and Dubbing export. [glass] = small labels and glass filter chips (Dubbing Export, Group 4 G 2). */
@Composable
internal fun BurnSizeChips(size: BurnSize, onSize: (BurnSize) -> Unit, tag: String, enabled: Boolean = true, glass: Boolean = false) {
    Label(tr(UiText.SIZE), glass)
    WrapRow {
        listOf(BurnSize.SMALL to UiText.SMALL, BurnSize.MEDIUM to UiText.MEDIUM, BurnSize.LARGE to UiText.LARGE).forEach { (s, label) ->
            Chip(tr(label), size == s, { onSize(s) }, "$tag-${s.name}", enabled, glass)
        }
    }
}

/** Lines on screen (1 / 2) and line length (Short / Long) of a burned caption; the same in Subtitles and Dubbing export. */
@Composable
internal fun BurnFitChips(fit: BurnFit, onFit: (BurnFit) -> Unit, tag: String, enabled: Boolean = true, glass: Boolean = false) {
    Label(tr(UiText.BURN_LINES), glass)
    WrapRow {
        listOf(1 to UiText.BURN_ONE_LINE, 2 to UiText.BURN_TWO_LINES).forEach { (n, label) ->
            Chip(tr(label), fit.lines == n, { onFit(fit.copy(lines = n)) }, "$tag-lines-$n", enabled, glass)
        }
    }
    Label(tr(UiText.BURN_LINE_LENGTH), glass)
    WrapRow {
        listOf(BurnLength.SHORT to UiText.BURN_SHORT, BurnLength.LONG to UiText.BURN_LONG).forEach { (l, label) ->
            Chip(tr(label), fit.length == l, { onFit(fit.copy(length = l)) }, "$tag-length-${l.name}", enabled, glass)
        }
    }
    if (glass) AfText(My.breakSyllables(tr(UiText.BURN_FIT_HINT)), muted = true, size = 12.sp)
    else AfText(My.breakSyllables(tr(UiText.BURN_FIT_HINT)), muted = true)
}

/** A group's label: Muted (glass: 12 sp, Group 1 `GroupLabel` size). */
@Composable
private fun Label(text: String, glass: Boolean) =
    if (glass) AfText(My.breakSyllables(text), muted = true, size = 12.sp) else AfText(My.breakSyllables(text), color = AutoFlowColors.Muted)

/** A choice chip: [AfChip], or the glass filter chip with `check` when chosen. */
@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit, tag: String, enabled: Boolean, glass: Boolean) {
    if (!glass) AfChip(My.breakSyllables(text), selected, onClick, Modifier.testTag(tag), enabled = enabled)
    else GlassFilterChip(My.breakSyllables(text), selected, onClick, Modifier.testTag(tag), enabled = enabled,
        leading = if (!selected) null else ({ ink -> AfIcon(AfIcons.Regular.Check, tint = ink, size = 14.dp) }))
}
