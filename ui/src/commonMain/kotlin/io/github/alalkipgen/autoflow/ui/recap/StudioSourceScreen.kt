package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.storyboard.text
import io.github.alalkipgen.autoflow.ui.theme.AfSize
import io.github.alalkipgen.autoflow.ui.theme.AfSpace
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

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
        Box(Modifier.weight(1f)) {
            ProjectPanes(windowWidthDp, list = {
                Column(Modifier.fillMaxSize().paneScroll(rememberScrollState()).testTag("source-picks"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) { SourcePicks(model, state, recap) }
            }, editor = {
                key(state.projectDir) {
                    Column(Modifier.fillMaxSize().paneScroll(rememberScrollState()).padding(14.dp).testTag("source-content"),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Phone / narrow window: the top preview (A §1) instead of a ▶ preview page.
                        if (!wide) RecapTopPreview(model, null)
                        if (state.runnerJobId == null) state.job?.let {
                            StatusChip(StudioStatus.RUNNING)
                            AfText("⏳ ${state.jobNotice?.text(LocalUiLanguage.current) ?: it}" +
                                (state.progress?.let { p -> " ${(p * 100).toInt()} %" } ?: ""), color = AutoFlowColors.Cyan)
                        }
                        state.message?.let { AfText(state.notice?.text(LocalUiLanguage.current) ?: it,
                            color = if (state.messageOk) AutoFlowColors.Green else AutoFlowColors.Amber) }
                        SectionCard(tr(UiText.V3_MOVIE)) {
                            RecapRulesV2.canImportMovie(recap).blockers.firstOrNull()?.let {
                                AfText(it.text(LocalUiLanguage.current), color = AutoFlowColors.Amber)
                            }
                            val movie = recap.movie
                            if (movie == null) AfText(tr(UiText.RECAP_MOVIE_IMPORT_HINT), muted = true) else {
                                AfText(state.movieName ?: movie.file, Modifier.testTag("source-movie-name"), bold = true)
                                AfText(listOfNotNull(RecapModel.clock(movie.durationMs),
                                    "${movie.width}×${movie.height}".takeIf { movie.width > 0 && movie.height > 0 })
                                    .joinToString(" · "), Modifier.testTag("source-movie-metadata"))
                                AfText(tr(if (movie.hasAudio) UiText.V3_AUDIO_FOUND else UiText.NO_SOUND_SUFFIX).trim(' ', '·'))
                                AfButton(tr(UiText.REPLACE_MOVIE), { replacing = true }, Modifier.testTag("source-replace"),
                                    enabled = idle && RecapRulesV2.canImportMovie(recap).allowed)
                            }
                        }
                        if (recap.movie != null) {
                            SectionCard(My.breakSyllables(tr(UiText.V3_SPLIT_MOVIE))) {
                                listOf(Triple(Scenes.THRESHOLD_FEWER, UiText.FEWER_SCENES, UiText.V3_DENSITY_FEWER),
                                    Triple(Scenes.THRESHOLD_NORMAL, UiText.NORMAL, UiText.V3_DENSITY_NORMAL),
                                    Triple(Scenes.THRESHOLD_MORE, UiText.MORE_SCENES, UiText.V3_DENSITY_MORE)).forEach { (value, title, hint) ->
                                    OptionCard(tr(title), My.breakSyllables(tr(hint)), threshold == value, { threshold = value },
                                        Modifier.fillMaxWidth().testTag("source-density-$value"),
                                        recommended = value == Scenes.THRESHOLD_NORMAL, enabled = idle)
                                }
                                AfText(My.breakSyllables(tr(UiText.V3_SCAN_HINT)), muted = true)
                                AfButton(tr(if (recap.scenes.isEmpty()) UiText.FIND_SCENES else UiText.FIND_SCENES_AGAIN),
                                    { model.findScenes(threshold) }, Modifier.testTag("source-scan"), enabled = idle && RecapRulesV2.canFindScenes(recap).allowed,
                                    icon = AfIcons.Regular.Search)
                            }
                            SectionCard(tr(UiText.V3_SCAN_RESULT, "n" to recap.scenes.size)) {
                                if (recap.scenes.isEmpty()) AfText(My.breakSyllables(tr(UiText.V3_NO_SCENES)), muted = true)
                                else AfText(tr(UiText.SCENES_PICKED, "size" to recap.picked.size,
                                    "pickedMs" to RecapModel.clock(recap.pickedMs), "targetMs" to RecapModel.clock(recap.targetMs)))
                            }
                            SectionCard(tr(UiText.V3_SUBTITLES)) { SourceSubtitles(model, state, recap) }
                            SectionCard(tr(UiText.SUMMARY)) { SourceSummary(model, state) }
                            if (state.transcribe.available) SectionCard(tr(UiText.TRANSCRIBE_SCENES)) {
                                if (recap.movie?.hasAudio != true) AfText(tr(UiText.MOVIE_NO_SOUND_TO_TRANSCRIBE), muted = true)
                                else TranscribeSection(model, state, recap, pinnedRun = true)
                            }
                            if (!wide) SourcePicks(model, state, recap)
                        }
                        AfText(tr(UiText.RIGHTS_NOTICE), muted = true)
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
            idle && RecapRulesV2.canImportMovie(recap).allowed, "source-import", AfIcons.Regular.Film))
        else navigation?.let { StickyActionBar(StudioAction(tr(UiText.NEXT_STEP, "step" to tr(UiText.STORY)),
            it.next, idle, "recap-next")) }
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
    SectionCard(tr(UiText.SCENES)) {
        if (recap.scenes.isEmpty()) { AfText(My.breakSyllables(tr(UiText.V3_NO_SCENES)), muted = true); return@SectionCard }
        val idle = !state.busy && state.recording == null
        AfText(tr(UiText.TARGET_LENGTH), bold = true)
        WrapRow { Recap.TARGETS_MS.forEach { target ->
            AfChip(tr(UiText.TARGET_MINUTES, "t60000" to target / 60_000), recap.targetMs == target, { model.setTarget(target) },
                Modifier.testTag("source-target-$target"), enabled = idle)
        } }
        AfText(tr(UiText.SCENES_PICKED, "size" to recap.picked.size, "pickedMs" to RecapModel.clock(recap.pickedMs),
            "targetMs" to RecapModel.clock(recap.targetMs)), color = if (Scenes.offTarget(recap.toV1())) AutoFlowColors.Amber else null)
        var pickedOnly by remember(state.projectDir) { mutableStateOf(false) }
        var page by remember(state.projectDir, recap.scenes.size, pickedOnly) { mutableIntStateOf(0) }
        AfSwitch(tr(UiText.SHOW_PICKED), pickedOnly, { pickedOnly = it }, Modifier.testTag("source-picked-only"))
        if (recap.picked.isNotEmpty()) AfButton(tr(UiText.CLEAR_PICKS), model::clearPicks,
            Modifier.testTag("source-clear-picks"), kind = ButtonKind.Danger, enabled = idle)
        val rows = recap.scenes.withIndex().filter { !pickedOnly || it.value.picked }
        val pages = maxOf(1, (rows.size + SCENES_PER_PAGE - 1) / SCENES_PER_PAGE)
        LaunchedEffect(pages) { page = page.coerceAtMost(pages - 1) }
        rows.drop(page * SCENES_PER_PAGE).take(SCENES_PER_PAGE).forEach { (index, scene) ->
            // A check-box row: check mark · scene frame · "#n" and its real time (the row is the checkbox).
            AfCard(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("scene-pick-$index").toggleable(scene.picked, enabled = idle, role = Role.Checkbox) { model.togglePick(index) },
                selected = scene.picked) {
                ThumbBeside(AfSize.ThumbScene.width, { RecapThumbnail(model, state, scene.startMs, scene.endMs, "scene-thumbnail-$index") }, { m ->
                    Column(m, verticalArrangement = Arrangement.spacedBy(AfSpace.Xs)) {
                        AfText("#${index + 1}", type = AfType.RowTitle)
                        AfText("${RecapModel.clock(scene.startMs)}–${RecapModel.clock(scene.endMs)}", muted = true, type = AfType.Numbers)
                    }
                }, leading = { CheckMark(scene.picked) }, factsMin = AfSize.ThumbFactsMin)
            }
        }
        if (pages > 1) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AfSpace.S)) {
            // The previous-page button is the next-page chevron mirrored (the icon set has no left caret).
            AfIconButton(AfIcons.Regular.Chevron, tr(UiText.PREVIOUS_PAGE), { page-- },
                Modifier.graphicsLayer(scaleX = -1f).testTag("source-page-back"), enabled = page > 0)
            AfText(tr(UiText.SCENES_PAGE, "page1" to page + 1, "pages" to pages, "size" to rows.size), Modifier.weight(1f),
                muted = true, textAlign = TextAlign.Center)
            AfIconButton(AfIcons.Regular.Chevron, tr(UiText.NEXT_PAGE), { page++ }, Modifier.testTag("source-page-next"),
                enabled = page < pages - 1)
        }
    }
}
