package io.github.alalkipgen.autoflow.ui.subtitles

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleExport
import io.github.alalkipgen.autoflow.core.subtitles.TextChoice
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/**
 * Export (spec §5, S-4a): the subtitle language (Burmese by default once there is Burmese, Q-38) and the `.srt` file —
 * Share · Save to Downloads on a phone, Save · Open folder on the desktop. The burned video is Planned (S-4b).
 */
@Composable
internal fun SubtitleExportDialog(exporter: SubtitleExportModel, doc: SubtitleDoc, version: ArtifactId?, onClose: () -> Unit) {
    val s by exporter.state.collectAsState()
    var choice by remember(version) { mutableStateOf(SubtitleExport.defaultChoice(doc)) }
    val shareTitle = tr(UiText.SUB_EXPORT_SRT)
    DisposableEffect(Unit) { onDispose(exporter::clear) }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.widthIn(max = 520.dp).verticalScroll(rememberScrollState()).testTag("subtitles-export"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AfCard(My.breakSyllables(tr(UiText.SUB_EXPORT_TEXT))) {
                WrapRow {
                    listOf(TextChoice.BURMESE, TextChoice.ORIGINAL).forEach { c ->
                        AfChip(My.breakSyllables(tr(viewLabel(c))), choice == c, { choice = c; exporter.clear() }, Modifier.testTag("subtitles-export-${c.name}"))
                    }
                }
            }
            AfCard(My.breakSyllables(tr(UiText.SUB_EXPORT_SRT)), Modifier.testTag("subtitles-export-srt")) {
                WrapRow {
                    if (exporter.sharesFiles) {
                        AfButton(My.breakSyllables(tr(UiText.SHARE)), { exporter.share(doc, version, choice, shareTitle) },
                            Modifier.testTag("subtitles-export-share"), kind = ButtonKind.Primary)
                        AfButton(My.breakSyllables(tr(UiText.SUB_SAVE_DOWNLOADS)), { exporter.saveToDownloads(doc, version, choice) },
                            Modifier.testTag("subtitles-export-downloads"), enabled = !s.busy)
                    } else {
                        AfButton(My.breakSyllables(tr(UiText.SUB_SAVE_SRT)), { exporter.save(doc, version, choice) },
                            Modifier.testTag("subtitles-export-save"), kind = ButtonKind.Primary)
                        AfButton(My.breakSyllables(tr(UiText.OPEN_FOLDER)), { exporter.openFolder(doc, version, choice) },
                            Modifier.testTag("subtitles-export-folder"))
                    }
                }
                s.notice?.let {
                    AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.testTag("subtitles-export-notice"),
                        color = if (s.ok) AutoFlowColors.Green else AutoFlowColors.Amber)
                }
            }
            PlannedTile(tr(UiText.SUB_EXPORT_VIDEO), tr(UiText.SUB_TRANSCRIBE_NEXT), Modifier.testTag("subtitles-export-video-planned"))
            AfButton(My.breakSyllables(tr(UiText.CLOSE)), onClose, Modifier.testTag("subtitles-export-close"))
        }
    }
}
