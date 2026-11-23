package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.storyboard.text
import io.github.alalkipgen.autoflow.ui.theme.AfSize
import io.github.alalkipgen.autoflow.ui.theme.AfSpace
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.GlassColors
import io.github.alalkipgen.autoflow.ui.theme.GlassShapes

/** Source presentation only: every write still uses the existing RecapModel/session/core path. */
@Composable
internal fun StudioSourceScreen(model: RecapModel, windowWidthDp: Float, navigation: RecapNavigation?) {
    val state by model.state.collectAsState()
    val open by model.previewSource.projects.collectAsState()
    val recap = state.recap ?: return
    if (open?.dir != state.projectDir || open?.project?.recap != recap) return
    val idle = !state.busy && state.recording == null
    val wide = windowWidthDp >= 840f
    var threshold by remember(state.projectDir, recap.sceneThreshold) { mutableIntStateOf(recap.sceneThreshold) }
    // A revision change invalidates this transient confirmation, including a same-name project switch.
    var replacing by remember(state.projectDir, open?.project?.id, open?.project?.updatedAt) { mutableStateOf(false) }
    DisposableEffect(state.projectDir) { onDispose { model.dismissTranscribe() } }
    val back = { if (replacing) replacing = false else if (state.transcribe.confirm != null) model.dismissTranscribe()
        else navigation?.back?.invoke(); Unit }
    navigation?.handleBack?.invoke(replacing || state.transcribe.confirm != null, back)
    Column(Modifier.fillMaxSize().background(AutoFlowColors.Background)) {
        navigation?.let { ProjectTopBar(open?.project?.name.orEmpty(), 1, tr(UiText.SOURCE), back,
            steps = recapStepLabels(), options = listOf(StudioAction(tr(UiText.CLASSIC_RECAP), it.classic, tag = "classic-recap", icon = AfIcons.Regular.Swap))) }
        state.transcribe.job?.takeIf { it.active }?.let { RecapTranscribeRun(model, it, pin = true) }
        TopPreviewHost(model, state, !wide, Modifier.weight(1f)) {
            ProjectPanes(windowWidthDp, list = {
                Column(Modifier.fillMaxSize().paneScroll(rememberScrollState()).testTag("source-picks"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) { SourcePicks(model, state, recap) }
            }, editor = {
                key(state.projectDir) {
                    Column(Modifier.fillMaxSize().paneScroll(rememberScrollState()).padding(14.dp).testTag("source-content"),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Phone / narrow window: the top preview (A §1) instead of a ▶ preview page — the picked scenes
                        // with the movie's own sound (PR 3; there is no output timeline before a story).
                        if (!wide) SourceTopPreview(model)
                        if (state.runnerJobId == null) state.job?.let {
                            StatusChip(StudioStatus.RUNNING)
                            AfText("⏳ ${state.jobNotice?.text(LocalUiLanguage.current) ?: it}" +
                                (state.progress?.let { p -> " ${(p * 100).toInt()} %" } ?: ""), color = AutoFlowColors.Cyan)
                        }
                        state.message?.let { AfText(state.notice?.text(LocalUiLanguage.current) ?: it,
                            color = if (state.messageOk) AutoFlowColors.Green else AutoFlowColors.Amber) }
                        SectionCard(tr(UiText.V3_MOVIE), tile = RecapTiles.Movie) {
                            RecapRulesV2.canImportMovie(recap).blockers.firstOrNull()?.let {
                                GlassFact(AfIcons.Fill.Warn, it.text(LocalUiLanguage.current), colour = AutoFlowColors.WarningText,
                                    iconColour = AutoFlowColors.Amber)
                            }
                            val movie = recap.movie
                            if (movie == null) AfText(tr(UiText.RECAP_MOVIE_IMPORT_HINT), muted = true) else {
                                // File icon · name; the facts (length · size, sound) as icon lines (Group 2 A §2).
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AfIcon(AfIcons.Regular.FileVideo, tint = AutoFlowColors.Muted, size = 18.dp)
                                    AfText(state.movieName ?: movie.file.substringAfterLast('/'), Modifier.weight(1f).testTag("source-movie-name"), size = 14.sp, bold = true)
                                }
                                GlassFact(AfIcons.Regular.Clock, listOfNotNull(RecapModel.clock(movie.durationMs),
                                    "${movie.width}×${movie.height}".takeIf { movie.width > 0 && movie.height > 0 })
                                    .joinToString(" · "), textModifier = Modifier.testTag("source-movie-metadata"))
                                if (movie.hasAudio) GlassFact(AfIcons.Regular.Volume, tr(UiText.V3_AUDIO_FOUND), colour = AutoFlowColors.SuccessText,
                                    iconColour = AutoFlowColors.Green)
                                else GlassFact(AfIcons.Regular.Volume, tr(UiText.NO_SOUND_SUFFIX).trim(' ', '·'), colour = AutoFlowColors.WarningText,
                                    iconColour = AutoFlowColors.Amber)
                                GlassButton(tr(UiText.REPLACE_MOVIE), { replacing = true }, Modifier.testTag("source-replace"),
                                    enabled = idle && RecapRulesV2.canImportMovie(recap).allowed, icon = AfIcons.Regular.ArrowsClockwise, small = true)
                            }
                        }
                        if (recap.movie != null) {
                            SectionCard(My.breakSyllables(tr(UiText.V3_SPLIT_MOVIE)), tile = RecapTiles.Split) {
                                listOf(Triple(Scenes.THRESHOLD_FEWER, UiText.FEWER_SCENES, UiText.V3_DENSITY_FEWER),
                                    Triple(Scenes.THRESHOLD_NORMAL, UiText.NORMAL, UiText.V3_DENSITY_NORMAL),
                                    Triple(Scenes.THRESHOLD_MORE, UiText.MORE_SCENES, UiText.V3_DENSITY_MORE)).forEach { (value, title, hint) ->
                                    OptionCard(tr(title), My.breakSyllables(tr(hint)), threshold == value, { threshold = value },
                                        Modifier.fillMaxWidth().testTag("source-density-$value"),
                                        recommended = value == Scenes.THRESHOLD_NORMAL, enabled = idle, radio = true)
                                }
                                GlassFact(AfIcons.Fill.Info, My.breakSyllables(tr(UiText.V3_SCAN_HINT)), colour = AutoFlowColors.Muted, caption = true)
                                GlassButton(tr(if (recap.scenes.isEmpty()) UiText.FIND_SCENES else UiText.FIND_SCENES_AGAIN),
                                    { model.findScenes(threshold) }, Modifier.testTag("source-scan"), enabled = idle && RecapRulesV2.canFindScenes(recap).allowed,
                                    icon = AfIcons.Regular.Search)
                            }
                            SectionCard(tr(UiText.V3_SCAN_RESULT, "n" to recap.scenes.size), tile = RecapTiles.ScanResult) {
                                if (recap.scenes.isEmpty()) AfText(My.breakSyllables(tr(UiText.V3_NO_SCENES)), muted = true)
                                else GlassFact(AfIcons.Fill.CheckCircle, tr(UiText.SCENES_PICKED, "size" to recap.picked.size,
                                    "pickedMs" to RecapModel.clock(recap.pickedMs), "targetMs" to RecapModel.clock(recap.targetMs)),
                                    iconColour = AutoFlowColors.Green)
                            }
                            SectionCard(tr(UiText.V3_SUBTITLES), tile = RecapTiles.Subtitles) { SourceSubtitles(model, state, recap, glass = true) }
                            SectionCard(tr(UiText.SUMMARY), tile = RecapTiles.Summary) { SourceSummary(model, state) }
                            if (state.transcribe.available) SectionCard(tr(UiText.TRANSCRIBE_SCENES), tile = RecapTiles.Transcribe) {
                                if (recap.movie?.hasAudio != true) AfText(tr(UiText.MOVIE_NO_SOUND_TO_TRANSCRIBE), muted = true)
                                else TranscribeSection(model, state, recap, pinnedRun = true, glass = true)
                            }
                            if (!wide) SourcePicks(model, state, recap)
                        }
                        GlassCallout(AfIcons.Fill.Warn, tr(UiText.RIGHTS_NOTICE), AutoFlowColors.Amber)
                    }
                }
            }, preview = {
                Column(Modifier.fillMaxSize().paneScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RecapTimelinePreview(model, studio = true)
                    ExactFrameCard(model)
                }
            }, previewPage = false)
        }
        if (recap.movie == null) StickyActionBar(StudioAction(tr(UiText.IMPORT_MOVIE), { model.importMovie() },
            idle && RecapRulesV2.canImportMovie(recap).allowed, "source-import", AfIcons.Regular.Film), glass = true)
        else navigation?.let { StickyActionBar(StudioAction(tr(UiText.NEXT_STEP, "step" to tr(UiText.STORY)),
            it.next, idle, "recap-next", trailingIcon = AfIcons.Regular.ArrowRight), glass = true) }
    }
    if (replacing) Dialog(onDismissRequest = { replacing = false }) {
        AfCard(tr(UiText.REPLACE_MOVIE), Modifier.fillMaxWidth().widthIn(max = 560.dp)) {
            Column(Modifier.weight(1f, fill = false).paneScroll(rememberScrollState())) {
                AfText(My.breakSyllables(tr(UiText.V3_REPLACE_MOVIE_CONFIRM)), Modifier.testTag("source-replace-warning"))
            }
            StickyActionBar(StudioAction(tr(UiText.REPLACE_MOVIE), { replacing = false; model.importMovie() }, idle, "source-replace-confirm"),
                StudioAction(tr(UiText.CANCEL), { replacing = false }, tag = "source-replace-cancel"))
        }
    }
}

/** Actual scene times/picks remain authoritative; missing thumbnails leave the time-only fallback. */
@Composable
private fun SourcePicks(model: RecapModel, state: RecapUiState, recap: RecapV2) {
    SectionCard(tr(UiText.SCENES), tile = RecapTiles.Scenes) {
        if (recap.scenes.isEmpty()) { AfText(My.breakSyllables(tr(UiText.V3_NO_SCENES)), muted = true); return@SectionCard }
        val idle = !state.busy && state.recording == null
        AfText(tr(UiText.TARGET_LENGTH), size = 14.sp, bold = true)
        WrapRow { Recap.TARGETS_MS.forEach { target ->
            RecapChip(tr(UiText.TARGET_MINUTES, "t60000" to target / 60_000), recap.targetMs == target, { model.setTarget(target) },
                Modifier.testTag("source-target-$target"), enabled = idle)
        } }
        AfText(tr(UiText.SCENES_PICKED, "size" to recap.picked.size, "pickedMs" to RecapModel.clock(recap.pickedMs),
            "targetMs" to RecapModel.clock(recap.targetMs)), color = if (Scenes.offTarget(recap.toV1())) AutoFlowColors.Amber else null)
        var pickedOnly by remember(state.projectDir) { mutableStateOf(false) }
        var page by remember(state.projectDir, recap.scenes.size, pickedOnly) { mutableIntStateOf(0) }
        AfSwitch(tr(UiText.SHOW_PICKED), pickedOnly, { pickedOnly = it }, Modifier.testTag("source-picked-only"))
        // Select all (Group 2 A §2) while a scene is not picked; Clear picks while one is.
        if (recap.picked.size < recap.scenes.size || recap.picked.isNotEmpty()) WrapRow(spacing = 8.dp) {
            if (recap.picked.size < recap.scenes.size) GlassButton(tr(UiText.SELECT_ALL_SCENES), model::pickAll,
                Modifier.testTag("source-select-all"), enabled = idle, icon = AfIcons.Regular.Checks, small = true)
            if (recap.picked.isNotEmpty()) GlassButton(tr(UiText.CLEAR_PICKS), model::clearPicks,
                Modifier.testTag("source-clear-picks"), kind = ButtonKind.Danger, enabled = idle, icon = AfIcons.Regular.Close, small = true)
        }
        val rows = recap.scenes.withIndex().filter { !pickedOnly || it.value.picked }
        val pages = maxOf(1, (rows.size + SCENES_PER_PAGE - 1) / SCENES_PER_PAGE)
        LaunchedEffect(pages) { page = page.coerceAtMost(pages - 1) }
        rows.drop(page * SCENES_PER_PAGE).take(SCENES_PER_PAGE).forEach { (index, scene) ->
            // A check-box row (Group 2 A §2): check-square · scene frame · "#n" and its real time in mono (the row is the
            // checkbox); picked = primary .10 fill + purple ring.
            val shape = GlassShapes.MenuRow
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape)
                .background(if (scene.picked) GlassColors.RowOnFill else GlassColors.RowFill)
                .border(1.dp, if (scene.picked) GlassColors.RowOnBorder else GlassColors.RowBorder, shape)
                .testTag("scene-pick-$index").toggleable(scene.picked, enabled = idle, role = Role.Checkbox) { model.togglePick(index) }
                .padding(8.dp)) {
                ThumbBeside(AfSize.ThumbScene.width, { ScenePreviewThumb(model, state, index, scene.startMs, scene.endMs) }, { m ->
                    Column(m, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        AfText("#${index + 1}", type = AfType.RowTitle)
                        AfText("${RecapModel.clock(scene.startMs)}–${RecapModel.clock(scene.endMs)}", muted = true, mono = true, size = 12.sp)
                    }
                }, leading = { SceneCheck(scene.picked) }, factsMin = AfSize.ThumbFactsMin, gap = 10.dp, leadingWidth = 24.dp)
            }
        }
        if (pages > 1) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AfSpace.S)) {
            GlassIconButton(AfIcons.Regular.CaretLeft, tr(UiText.PREVIOUS_PAGE), { page-- },
                Modifier.testTag("source-page-back"), size = GlassShapes.IconButtonHeader, enabled = page > 0)
            AfText(tr(UiText.SCENES_PAGE, "page1" to page + 1, "pages" to pages, "size" to rows.size), Modifier.weight(1f),
                muted = true, type = AfType.Caption, textAlign = TextAlign.Center)
            GlassIconButton(AfIcons.Regular.Chevron, tr(UiText.NEXT_PAGE), { page++ }, Modifier.testTag("source-page-next"),
                size = GlassShapes.IconButtonHeader, enabled = page < pages - 1)
        }
    }
}
