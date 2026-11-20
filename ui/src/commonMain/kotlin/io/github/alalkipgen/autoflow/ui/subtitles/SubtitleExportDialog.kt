package io.github.alalkipgen.autoflow.ui.subtitles

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.foundation.Image
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleExport
import io.github.alalkipgen.autoflow.core.subtitles.TextChoice
import io.github.alalkipgen.autoflow.core.subtitles.TimeText
import io.github.alalkipgen.autoflow.media.render.BurnLook
import io.github.alalkipgen.autoflow.media.render.BurnSize
import io.github.alalkipgen.autoflow.media.render.RenderState
import kotlinx.coroutines.launch
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes

/**
 * Export (spec §5, S-4a): the subtitle language (Burmese by default once there is Burmese, Q-38) and the `.srt` file —
 * Share · Save to Downloads on a phone, Save · Open folder on the desktop — and the video with the subtitles burned
 * in (S-4c, [BurnCard]; Planned where there is no burner).
 */
@Composable
internal fun SubtitleExportDialog(exporter: SubtitleExportModel, doc: SubtitleDoc, version: ArtifactId?, onClose: () -> Unit,
    burner: SubtitleBurnModel? = null, line: Int? = null) {
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
                        AfChip(My.breakSyllables(tr(viewLabel(c))), choice == c, { choice = c; exporter.clear(); burner?.clearFrame() }, Modifier.testTag("subtitles-export-${c.name}"))
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
            if (burner != null) BurnCard(burner, doc, version, choice, line)
            else PlannedTile(tr(UiText.SUB_EXPORT_VIDEO), tr(UiText.SUB_TRANSCRIBE_NEXT), Modifier.testTag("subtitles-export-video-planned"))
            AfButton(My.breakSyllables(tr(UiText.CLOSE)), onClose, Modifier.testTag("subtitles-export-close"))
        }
    }
}

/** Video with subtitles (S-4c): look, size, Make video → a Jobs task with progress and Cancel, then Play · Share · Save. */
@Composable
private fun BurnCard(burner: SubtitleBurnModel, doc: SubtitleDoc, version: ArtifactId?, choice: TextChoice, startLine: Int?) {
    val s by burner.state.collectAsState()
    val runs by burner.runs.collectAsState()
    val run = remember(runs, s.output) { burner.run() }
    val scope = rememberCoroutineScope()
    val shareTitle = tr(UiText.SUB_EXPORT_VIDEO)
    val state = run?.state
    AfCard(My.breakSyllables(tr(UiText.SUB_EXPORT_VIDEO)), Modifier.testTag("subtitles-export-video")) {
        AfText(My.breakSyllables(tr(UiText.V3_STYLE_LOOK)), color = AutoFlowColors.Muted)
        WrapRow {
            listOf(BurnLook.CLEAN to UiText.V3_CAPTION_CLEAN, BurnLook.BOXED to UiText.V3_CAPTION_BOXED, BurnLook.OUTLINE to UiText.V3_CAPTION_OUTLINE)
                .forEach { (look, label) ->
                    AfChip(My.breakSyllables(tr(label)), s.look == look, { burner.setLook(look) }, Modifier.testTag("subtitles-burn-${look.name}"))
                }
        }
        AfText(My.breakSyllables(tr(UiText.SIZE)), color = AutoFlowColors.Muted)
        WrapRow {
            listOf(BurnSize.SMALL to UiText.SMALL, BurnSize.MEDIUM to UiText.MEDIUM, BurnSize.LARGE to UiText.LARGE).forEach { (size, label) ->
                AfChip(My.breakSyllables(tr(label)), s.size == size, { burner.setSize(size) }, Modifier.testTag("subtitles-burn-${size.name}"))
            }
        }
        AfText(My.breakSyllables(tr(UiText.SUB_BURN_KEEPS)), color = AutoFlowColors.Muted)
        CheckFrame(burner, doc, version, choice, startLine)
        when (state) {
            is RenderState.Queued, is RenderState.Running -> JobCard(tr(UiText.SUB_EXPORT_VIDEO),
                if (state is RenderState.Running) StudioStatus.RUNNING else StudioStatus.QUEUED,
                My.breakSyllables(tr(UiText.SUB_BURN_RUNNING, "percent" to ((state as? RenderState.Running)?.fraction ?: 0.0).times(100).toInt())),
                Modifier.testTag("subtitles-burn-job"), progress = (state as? RenderState.Running)?.fraction?.toFloat(),
                indeterminate = state is RenderState.Queued) {
                AfButton(My.breakSyllables(tr(UiText.CANCEL)), burner::cancel, Modifier.testTag("subtitles-burn-cancel"))
            }
            else -> AfButton(My.breakSyllables(tr(UiText.SUB_MAKE_VIDEO)), { scope.launch { burner.start(doc, version, choice) } },
                Modifier.testTag("subtitles-burn-make"), kind = ButtonKind.Primary)
        }
        when (state) {
            is RenderState.Done -> {
                AfText(My.breakSyllables(tr(UiText.SUB_BURN_DONE, "path" to state.output)), Modifier.testTag("subtitles-burn-done"), color = AutoFlowColors.Green)
                WrapRow {
                    AfButton(My.breakSyllables(tr(UiText.PLAY)), { burner.open() }, Modifier.testTag("subtitles-burn-play"))
                    if (burner.sharesFiles) {
                        AfButton(My.breakSyllables(tr(UiText.SHARE)), { burner.share(shareTitle) }, Modifier.testTag("subtitles-burn-share"))
                        AfButton(My.breakSyllables(tr(UiText.SAVE_GALLERY)), { scope.launch { burner.saveToGallery() } }, Modifier.testTag("subtitles-burn-gallery"))
                    } else AfButton(My.breakSyllables(tr(UiText.OPEN_FOLDER)), { burner.openFolder() }, Modifier.testTag("subtitles-burn-folder"))
                }
            }
            is RenderState.Cancelled -> AfText(My.breakSyllables(tr(UiText.SUB_BURN_CANCELLED)), Modifier.testTag("subtitles-burn-cancelled"), color = AutoFlowColors.Amber)
            is RenderState.Failed -> AfText(My.breakSyllables(tr(UiText.RESULT_DETAIL, "detail" to state.reason)), Modifier.testTag("subtitles-burn-failed"), color = AutoFlowColors.Failed)
            else -> {}
        }
        s.notice?.let {
            AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.testTag("subtitles-burn-notice"),
                color = if (s.ok) AutoFlowColors.Green else AutoFlowColors.Amber)
        }
    }
}

/** Check one frame (S-4d): a line (the selected one first, ‹ › to move) and its middle frame exactly as it will be burned. */
@Composable
private fun CheckFrame(burner: SubtitleBurnModel, doc: SubtitleDoc, version: ArtifactId?, choice: TextChoice, startLine: Int?) {
    val s by burner.state.collectAsState()
    val scope = rememberCoroutineScope()
    val last = doc.cues.size - 1
    if (last < 0) return
    val prev = tr(UiText.SUB_PREV_LINE); val next = tr(UiText.SUB_NEXT_LINE)
    var line by remember(doc) { mutableStateOf((startLine ?: 0).coerceIn(0, last)) }
    WrapRow {
        AfButton("‹", { line--; burner.clearFrame() }, Modifier.testTag("subtitles-check-prev").semantics { contentDescription = prev },
            enabled = line > 0)
        AfText(My.breakSyllables(tr(UiText.SUB_CHECK_LINE, "n" to line + 1, "time" to TimeText.show(doc.cues[line].startMs))),
            Modifier.testTag("subtitles-check-line"))
        AfButton("›", { line++; burner.clearFrame() }, Modifier.testTag("subtitles-check-next").semantics { contentDescription = next },
            enabled = line < last)
        AfButton(My.breakSyllables(tr(if (s.checking) UiText.SUB_CHECKING else UiText.SUB_CHECK_FRAME)),
            { scope.launch { burner.checkFrame(doc, version, choice, line) } }, Modifier.testTag("subtitles-check-frame"), enabled = !s.checking)
    }
    s.frame?.let { f ->
        val bmp = remember(f) { decodeImage(f.bytes) }
        if (bmp != null) Image(bmp, tr(UiText.EXACT_FRAME_AT, "atMs" to TimeText.show(f.atMs)),
            Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).clip(AutoFlowShapes.Card).testTag("subtitles-check-image"))
    }
}
