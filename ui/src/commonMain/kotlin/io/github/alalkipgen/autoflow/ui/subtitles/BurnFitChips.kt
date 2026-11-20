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

/** The longest burned line for [fit]: Short = `subtitles.shortCharsPerLine`, Long = `subtitles.maxCharsPerLine`. */
fun BurnFit.maxChars(config: ToolsConfig.Subtitles): Int =
    if (length == BurnLength.SHORT) config.shortCharsPerLine else config.maxCharsPerLine

/** The output name part of a burn choice, so another choice makes another file (never overwritten). */
fun BurnFit.nameSuffix(): String = "${lines}l-${length.name.lowercase()}"

/** Size chips of a burned caption; the same in Subtitles and Dubbing export. */
@Composable
internal fun BurnSizeChips(size: BurnSize, onSize: (BurnSize) -> Unit, tag: String, enabled: Boolean = true) {
    AfText(My.breakSyllables(tr(UiText.SIZE)), color = AutoFlowColors.Muted)
    WrapRow {
        listOf(BurnSize.SMALL to UiText.SMALL, BurnSize.MEDIUM to UiText.MEDIUM, BurnSize.LARGE to UiText.LARGE).forEach { (s, label) ->
            AfChip(My.breakSyllables(tr(label)), size == s, { onSize(s) }, Modifier.testTag("$tag-${s.name}"), enabled = enabled)
        }
    }
}

/** Lines on screen (1 / 2) and line length (Short / Long) of a burned caption; the same in Subtitles and Dubbing export. */
@Composable
internal fun BurnFitChips(fit: BurnFit, onFit: (BurnFit) -> Unit, tag: String, enabled: Boolean = true) {
    AfText(My.breakSyllables(tr(UiText.BURN_LINES)), color = AutoFlowColors.Muted)
    WrapRow {
        listOf(1 to UiText.BURN_ONE_LINE, 2 to UiText.BURN_TWO_LINES).forEach { (n, label) ->
            AfChip(My.breakSyllables(tr(label)), fit.lines == n, { onFit(fit.copy(lines = n)) }, Modifier.testTag("$tag-lines-$n"), enabled = enabled)
        }
    }
    AfText(My.breakSyllables(tr(UiText.BURN_LINE_LENGTH)), color = AutoFlowColors.Muted)
    WrapRow {
        listOf(BurnLength.SHORT to UiText.BURN_SHORT, BurnLength.LONG to UiText.BURN_LONG).forEach { (l, label) ->
            AfChip(My.breakSyllables(tr(label)), fit.length == l, { onFit(fit.copy(length = l)) }, Modifier.testTag("$tag-length-${l.name}"), enabled = enabled)
        }
    }
    AfText(My.breakSyllables(tr(UiText.BURN_FIT_HINT)), muted = true)
}
