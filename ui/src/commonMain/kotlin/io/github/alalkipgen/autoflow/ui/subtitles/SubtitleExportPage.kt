package io.github.alalkipgen.autoflow.ui.subtitles

import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleExport
import io.github.alalkipgen.autoflow.core.subtitles.TextChoice
import io.github.alalkipgen.autoflow.core.subtitles.TimeText
import io.github.alalkipgen.autoflow.media.render.BurnLook
import io.github.alalkipgen.autoflow.media.render.RenderState
import kotlinx.coroutines.launch
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.pack.RenderPackageCard
import io.github.alalkipgen.autoflow.ui.pack.RenderPlan
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.GlassColors
import io.github.alalkipgen.autoflow.ui.theme.GlassShapes

/**
 * Export (spec §5, S-4a; Group 5 F, 🆕-5): a full page in place of the editor — the top bar (back = close, also system
 * Back), the subtitle language (Burmese by default once there is Burmese, Q-38), the `.srt` file (Share · Save to
 * Downloads on a phone, Save .srt · Open folder on the desktop), the video with the subtitles burned in (S-4c, [BurnCard];
 * Planned where there is no burner), the render package; Make video is the sticky. Same models and choices as the dialog
 * it replaces.
 */
@Composable
internal fun SubtitleExportPage(exporter: SubtitleExportModel, doc: SubtitleDoc, version: ArtifactId?, project: String, onClose: () -> Unit,
    burner: SubtitleBurnModel? = null, line: Int? = null, preview: BurnPreviewVideo? = null,
    backHandler: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> }) {
    val s by exporter.state.collectAsState()
    var choice by remember(version) { mutableStateOf(SubtitleExport.defaultChoice(doc)) }
    val shareTitle = tr(UiText.SUB_EXPORT_SRT)
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose(exporter::clear) }
    backHandler(true, onClose)
    Column(Modifier.fillMaxSize().testTag("subtitles-export")) {
        // F 1: the Group 2 top bar — back = close (`subtitles-export-close`), Export, "Subtitles · project"; no Undo / Redo.
        SubtitlesTopBar(tr(UiText.EXPORT), My.breakSyllables("${tr(UiText.V3_SUBTITLES)} · $project"), false, onClose, backTag = "subtitles-export-close")
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 900.dp).fillMaxSize().paneScroll(rememberScrollState()).padding(screenPadding)
                .testTag("subtitles-export-page"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // F 2: Subtitle language — Teal `translate` tile, Burmese / Original filter chips.
                TileCard(My.breakSyllables(tr(UiText.SUB_EXPORT_TEXT)), CardTile(AfIcons.Fill.Translate, GlassColors.ExportTeal)) {
                    WrapRow {
                        listOf(TextChoice.BURMESE, TextChoice.ORIGINAL).forEach { c ->
                            GlassFilterChip(My.breakSyllables(tr(viewLabel(c))), choice == c, { choice = c; exporter.clear(); burner?.clearFrame() },
                                Modifier.testTag("subtitles-export-${c.name}"), leading = checkMark(choice == c))
                        }
                    }
                }
                // F 3: .srt file — Blue `subtitles` tile; small secondary glass buttons (the page's one primary is Make video).
                TileCard(My.breakSyllables(tr(UiText.SUB_EXPORT_SRT)), CardTile(AfIcons.Fill.Subtitles, GlassColors.ExportBlue), Modifier.testTag("subtitles-export-srt")) {
                    WrapRow {
                        if (exporter.sharesFiles) {
                            GlassButton(My.breakSyllables(tr(UiText.SHARE)), { exporter.share(doc, version, choice, shareTitle) },
                                Modifier.testTag("subtitles-export-share"), icon = AfIcons.Regular.Share, small = true)
                            GlassButton(My.breakSyllables(tr(UiText.SUB_SAVE_DOWNLOADS)), { exporter.saveToDownloads(doc, version, choice) },
                                Modifier.testTag("subtitles-export-downloads"), enabled = !s.busy, icon = AfIcons.Regular.DownloadSimple, small = true)
                        } else {
                            GlassButton(My.breakSyllables(tr(UiText.SUB_SAVE_SRT)), { exporter.save(doc, version, choice) },
                                Modifier.testTag("subtitles-export-save"), icon = AfIcons.Regular.DownloadSimple, small = true)
                            GlassButton(My.breakSyllables(tr(UiText.OPEN_FOLDER)), { exporter.openFolder(doc, version, choice) },
                                Modifier.testTag("subtitles-export-folder"), icon = AfIcons.Regular.FolderOpen, small = true)
                        }
                    }
                    s.notice?.let { Notice(it, s.ok, "subtitles-export-notice") }
                }
                if (burner != null) BurnCard(burner, doc, version, choice, line, preview)
                else PlannedTile(tr(UiText.SUB_EXPORT_VIDEO), tr(UiText.SUB_TRANSCRIBE_NEXT), Modifier.testTag("subtitles-export-video-planned"))
                // F 6: the render package — the Group 2–4 collapsible card at the bottom of the page (same plan as before).
                if (burner != null) {
                    val b by burner.state.collectAsState()
                    RenderPackageCard({
                        val fit = burner.fit()
                        RenderPlan(mode = Mode.SUBTITLES, look = b.look.name, size = b.size.name, lines = fit.lines, length = fit.length.name,
                            text = choice.name, docVersion = version?.value, doc = doc)
                    }, tile = CardTile(AfIcons.Fill.Box, GlassColors.ExportTeal))
                }
            }
        }
        // F 5: Make video is the sticky (off while the video is queued / being made); its progress stays in the video card.
        if (burner != null) {
            val runs by burner.runs.collectAsState()
            val b by burner.state.collectAsState()
            val state = remember(runs, b.output) { burner.run() }?.state
            StickyActionBar(StudioAction(My.breakSyllables(tr(UiText.SUB_MAKE_VIDEO)), { scope.launch { burner.start(doc, version, choice) } },
                enabled = state !is RenderState.Queued && state !is RenderState.Running, tag = "subtitles-burn-make", icon = AfIcons.Regular.FilmStrip),
                glass = true)
        }
    }
}

/** The `check` before a chosen filter chip's label (Group 1). */
private fun checkMark(selected: Boolean): (@Composable (androidx.compose.ui.graphics.Color) -> Unit)? =
    if (!selected) null else ({ ink -> io.github.alalkipgen.autoflow.ui.icons.AfIcon(AfIcons.Regular.Check, tint = ink, size = 14.dp) })

/** A notice as a callout: green `check-circle` done, amber `warning` else. */
@Composable
private fun Notice(notice: UiNotice, ok: Boolean, tag: String) =
    GlassCallout(if (ok) AfIcons.Fill.CheckCircle else AfIcons.Fill.Warn, My.breakSyllables(notice.text(LocalUiLanguage.current)),
        if (ok) AutoFlowColors.Green else AutoFlowColors.Amber, textModifier = Modifier.testTag(tag))

/** The source video for the burn's live preview (F-6): its file, its size and length, and the platform player. */
internal class BurnPreviewVideo(val path: String, val video: SourceVideo, val player: SubtitlePlayer)

/**
 * Video with subtitles (S-4c; Group 5 F 4–5): Purple `film-strip` tile + a 36 dp `info` button (the preview hint behind
 * it, Group 4 rule); the video playing with the subtitles drawn as they will be burned (F-6, D-95: each chip changes it at
 * once); Look · Size · Lines on screen · Line length; Check one frame; then the video's job (Jobs card look, Cancel), done
 * (green card: Play · Share · Save to Gallery / Play · Open folder), cancelled (amber) or failed (red).
 */
@Composable
private fun BurnCard(burner: SubtitleBurnModel, doc: SubtitleDoc, version: ArtifactId?, choice: TextChoice, startLine: Int?,
    preview: BurnPreviewVideo? = null) {
    val s by burner.state.collectAsState()
    val runs by burner.runs.collectAsState()
    val run = remember(runs, s.output) { burner.run() }
    val scope = rememberCoroutineScope()
    val shareTitle = tr(UiText.SUB_EXPORT_VIDEO)
    val state = run?.state
    var hintOpen by remember { mutableStateOf(false) }
    val title = tr(UiText.SUB_EXPORT_VIDEO)
    TileCard(My.breakSyllables(title), CardTile(AfIcons.Fill.FilmStrip, AutoFlowColors.Purple), Modifier.testTag("subtitles-export-video"),
        action = if (preview == null) null else ({
            val fold = tr(if (hintOpen) UiText.V3_EXPANDED else UiText.V3_COLLAPSED)
            GlassIconButton(AfIcons.Regular.Info, title, { hintOpen = !hintOpen }, Modifier.testTag("burn-preview-info").semantics { stateDescription = fold },
                size = 36.dp, selected = hintOpen)
        })) {
        if (preview != null) {
            if (hintOpen) AfText(My.breakSyllables(tr(UiText.BURN_PREVIEW_HINT)), Modifier.testTag("burn-preview-hint"), muted = true, size = 12.sp)
            val playhead = remember(doc) { Playhead().apply { seek(doc.cues.getOrNull(startLine ?: 0)?.startMs ?: 0) } }
            val burned = remember(doc, choice, s.look, s.size, s.fit) { burner.previewOverlays(doc, choice) }
            SubtitlePreview(preview.path, preview.video, doc.cues, choice, playhead, preview.player, heightShare = 0.3f, tag = "burn", burned = burned, card = false)
            GlassHairline()
        }
        AfText(My.breakSyllables(tr(UiText.V3_STYLE_LOOK)), muted = true, size = 12.sp)
        WrapRow {
            listOf(BurnLook.CLEAN to UiText.V3_CAPTION_CLEAN, BurnLook.BOXED to UiText.V3_CAPTION_BOXED, BurnLook.OUTLINE to UiText.V3_CAPTION_OUTLINE)
                .forEach { (look, label) ->
                    GlassFilterChip(My.breakSyllables(tr(label)), s.look == look, { burner.setLook(look) }, Modifier.testTag("subtitles-burn-${look.name}"),
                        leading = checkMark(s.look == look))
                }
        }
        BurnSizeChips(s.size, burner::setSize, "subtitles-burn", glass = true)
        BurnFitChips(burner.fit(), burner::setFit, "subtitles-burn", glass = true)
        GlassFact(AfIcons.Regular.Info, My.breakSyllables(tr(UiText.SUB_BURN_KEEPS)), colour = AutoFlowColors.Muted, caption = true)
        GlassHairline()
        CheckFrame(burner, doc, version, choice, startLine)
        when (state) {
            is RenderState.Queued, is RenderState.Running -> JobCard(title,
                if (state is RenderState.Running) StudioStatus.RUNNING else StudioStatus.QUEUED,
                My.breakSyllables(tr(UiText.SUB_BURN_RUNNING, "percent" to ((state as? RenderState.Running)?.fraction ?: 0.0).times(100).toInt())),
                Modifier.testTag("subtitles-burn-job"), progress = (state as? RenderState.Running)?.fraction?.toFloat(),
                indeterminate = state is RenderState.Queued, tile = CardTile(AfIcons.Fill.FilmStrip, AutoFlowColors.Purple)) {
                GlassButton(My.breakSyllables(tr(UiText.CANCEL)), burner::cancel, Modifier.testTag("subtitles-burn-cancel"), kind = ButtonKind.Danger,
                    icon = AfIcons.Regular.Close, small = true)
            }
            is RenderState.Done -> GlassCard(tint = AutoFlowColors.Green, tintAlpha = .12f, outline = AutoFlowColors.Green.copy(alpha = .45f),
                outlineWidth = 1.dp, gap = 10.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoftTile(AfIcons.Fill.CheckCircle, AutoFlowColors.Green, size = 30.dp)
                    AfText(My.breakSyllables(tr(UiText.SUB_BURN_DONE, "path" to state.output)), Modifier.weight(1f).padding(top = 4.dp)
                        .testTag("subtitles-burn-done"), weight = FontWeight.Bold)
                }
                WrapRow {
                    GlassButton(My.breakSyllables(tr(UiText.PLAY)), { burner.open() }, Modifier.testTag("subtitles-burn-play"), kind = ButtonKind.Primary,
                        icon = AfIcons.Fill.Play, small = true)
                    if (burner.sharesFiles) {
                        GlassButton(My.breakSyllables(tr(UiText.SHARE)), { burner.share(shareTitle) }, Modifier.testTag("subtitles-burn-share"),
                            icon = AfIcons.Regular.Share, small = true)
                        GlassButton(My.breakSyllables(tr(UiText.SAVE_GALLERY)), { scope.launch { burner.saveToGallery() } },
                            Modifier.testTag("subtitles-burn-gallery"), icon = AfIcons.Regular.DownloadSimple, small = true)
                    } else GlassButton(My.breakSyllables(tr(UiText.OPEN_FOLDER)), { burner.openFolder() }, Modifier.testTag("subtitles-burn-folder"),
                        icon = AfIcons.Regular.FolderOpen, small = true)
                }
            }
            is RenderState.Cancelled -> GlassCallout(AfIcons.Fill.Warn, My.breakSyllables(tr(UiText.SUB_BURN_CANCELLED)), AutoFlowColors.Amber,
                textModifier = Modifier.testTag("subtitles-burn-cancelled"))
            is RenderState.Failed -> GlassCallout(AfIcons.Fill.XCircle, My.breakSyllables(tr(UiText.RESULT_DETAIL, "detail" to state.reason)),
                AutoFlowColors.Failed, textModifier = Modifier.testTag("subtitles-burn-failed"))
            else -> {}
        }
        s.notice?.let { Notice(it, s.ok, "subtitles-burn-notice") }
    }
}

/**
 * Check one frame (S-4d; Group 5 F 4): ‹ "Line 12 · 0:41.0" › (the selected line first), a full-width glass button
 * ("Making the frame…" + `hourglass` while it runs), then that line's middle frame exactly as it will be burned.
 */
@Composable
private fun CheckFrame(burner: SubtitleBurnModel, doc: SubtitleDoc, version: ArtifactId?, choice: TextChoice, startLine: Int?) {
    val s by burner.state.collectAsState()
    val scope = rememberCoroutineScope()
    val last = doc.cues.size - 1
    if (last < 0) return
    var line by remember(doc) { mutableStateOf((startLine ?: 0).coerceIn(0, last)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassIconButton(AfIcons.Regular.CaretLeft, tr(UiText.SUB_PREV_LINE), { line--; burner.clearFrame() }, Modifier.testTag("subtitles-check-prev"),
            size = GlassShapes.IconButtonHeader, enabled = line > 0)
        AfText(My.breakSyllables(tr(UiText.SUB_CHECK_LINE, "n" to line + 1, "time" to TimeText.show(doc.cues[line].startMs))),
            Modifier.weight(1f).testTag("subtitles-check-line"), weight = FontWeight.Bold, textAlign = TextAlign.Center)
        GlassIconButton(AfIcons.Regular.CaretRight, tr(UiText.SUB_NEXT_LINE), { line++; burner.clearFrame() }, Modifier.testTag("subtitles-check-next"),
            size = GlassShapes.IconButtonHeader, enabled = line < last)
    }
    GlassButton(My.breakSyllables(tr(if (s.checking) UiText.SUB_CHECKING else UiText.SUB_CHECK_FRAME)),
        { scope.launch { burner.checkFrame(doc, version, choice, line) } }, Modifier.fillMaxWidth().testTag("subtitles-check-frame"), enabled = !s.checking,
        icon = if (s.checking) AfIcons.Regular.Hourglass else AfIcons.Regular.Image)
    s.frame?.let { f ->
        val bmp = remember(f) { decodeImage(f.bytes) }
        val caption = tr(UiText.EXACT_FRAME_AT, "atMs" to TimeText.show(f.atMs))
        if (bmp != null) Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(bmp, caption, Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).clip(RoundedCornerShape(12.dp))
                .testTag("subtitles-check-image"))
            AfText(My.breakSyllables(caption), muted = true, size = 12.sp, textAlign = TextAlign.Center)
        }
    }
}
