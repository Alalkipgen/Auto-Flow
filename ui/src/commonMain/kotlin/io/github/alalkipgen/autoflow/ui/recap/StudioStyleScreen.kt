package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.storyboard.text
import io.github.alalkipgen.autoflow.ui.theme.AfSize
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

private enum class StyleSection(val label: UiText) {
    LAYERS(UiText.V3_STYLE_LAYERS), CAPTIONS(UiText.V3_STYLE_CAPTIONS),
    LOOK(UiText.V3_STYLE_LOOK), AUDIO(UiText.V3_STYLE_AUDIO),
}

/** Presentation only: the native player, caption painter, layer transaction and render gates are unchanged. */
@Composable
internal fun StudioStyleScreen(model: RecapModel, windowWidthDp: Float, navigation: RecapNavigation?, onExport: () -> Unit) {
    val state by model.state.collectAsState()
    val open by model.previewSource.projects.collectAsState()
    val layers by model.layerEditor.state.collectAsState()
    val recap = state.recap ?: return
    if (open?.dir != state.projectDir || open?.project?.recap != recap) return
    var section by remember(state.projectDir) { mutableStateOf(StyleSection.LAYERS) }
    val draft = layers.draft != null
    val idle = !state.busy && state.recording == null
    val ready = RecapRulesV2.canVoice(recap, state.story)
    val render = RecapRulesV2.canRender(recap, state.story, state.lexicon)
    val back = { if (draft) model.layerEditor.cancel() else navigation?.back?.invoke(); Unit }
    navigation?.handleBack?.invoke(draft, back)
    DisposableEffect(state.projectDir) { onDispose { model.layerEditor.cancel() } }
    val sections: @Composable () -> Unit = {
        WrapRow(Modifier.testTag("style-sections")) { StyleSection.entries.forEach { item ->
            RecapChip(tr(item.label), section == item, { section = item },
                Modifier.testTag("style-section-${item.name}"), enabled = !draft)
        } }
    }
    val preview: @Composable () -> Unit = {
        RecapTimelinePreview(model, studio = true, editLayers = true)
        ExactFrameCard(model, enabled = !draft)
    }
    val wide = windowWidthDp >= 840f
    val diagnostics = remember(state.projectDir) { mutableStateOf(false) }
    val diagnosticsAction = diagnosticsAction(model, state, diagnostics)
    key(state.projectDir) {
        Column(Modifier.fillMaxSize().background(AutoFlowColors.Background)) {
            navigation?.let { ProjectTopBar(open?.project?.name.orEmpty(), 4, tr(UiText.STYLE), back,
                steps = recapStepLabels(), options = listOfNotNull(StudioAction(tr(UiText.CLASSIC_RECAP), it.classic, !draft, "classic-recap", AfIcons.Regular.Swap),
                    diagnosticsAction.takeIf { !wide })) }
            TopPreviewHost(model, state, !wide, Modifier.weight(1f)) {
                ProjectPanes(windowWidthDp, list = {
                    // The glass chips' 48 dp targets overflow their 34 dp pill: the padding sits inside the scroll viewport.
                    GlassScrollColumn(Modifier.fillMaxSize().testTag("style-list"), PaddingValues(vertical = (AfSize.Touch - AfSize.FilterChip) / 2),
                        Arrangement.spacedBy(12.dp)) { sections() }
                }, editor = {
                    Column(Modifier.fillMaxSize().paneScroll(rememberScrollState()).padding(14.dp).testTag("style-content"),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Phone / narrow window: the top preview (A §1) edits layers on the picture; Exact frame waits for Save.
                        if (!wide) RecapTopPreview(model, diagnostics, editLayers = true, exactEnabled = !draft)
                        if (state.busy) StatusChip(StudioStatus.RUNNING)
                        state.job?.let { AfText(state.jobNotice?.text(LocalUiLanguage.current) ?: it, color = AutoFlowColors.Cyan) }
                        state.message?.let { AfText(state.notice?.text(LocalUiLanguage.current) ?: it,
                            color = if (state.messageOk) AutoFlowColors.Green else AutoFlowColors.Amber) }
                        if (!wide) sections()
                        if (draft) AfText(tr(UiText.V3_UNSAVED), color = AutoFlowColors.Amber)
                        if (!ready.allowed) ready.blockers.forEach { AfText(it.text(LocalUiLanguage.current), muted = true) }
                        else when (section) {
                            StyleSection.LAYERS -> {
                                RecapLayerControls(recap, idle, model, studio = true)
                                if (!draft) SectionCard(tr(UiText.V3_MORE_OPTIONS), Modifier.testTag("style-layer-options"), initiallyExpanded = false,
                                    tile = RecapTiles.MoreOptions) {
                                    RecapPictureControls(recap, idle, model, PictureSection.LAYERS)
                                    SaveBrandKitControls(recap, idle && !draft, model, state.projectDir)
                                    StyleTimingControls(model, state, recap, idle && !draft)
                                }
                            }
                            StyleSection.CAPTIONS -> {
                                AfSwitch(tr(UiText.BURN_CAPTIONS), recap.burnCaptions, { if (idle) model.setBurnCaptions(it) })
                                RecapPictureControls(recap, idle, model, PictureSection.CAPTIONS)
                                if (recap.burnCaptions) SaveCaptionPresetControls(recap, idle, model, state.projectDir)
                                AfText(tr(UiText.CAPTIONS_SRT_ALWAYS), muted = true)
                            }
                            StyleSection.LOOK -> AfCard(tr(UiText.V3_STYLE_LOOK), Modifier.testTag("style-look")) {
                                AfText(tr(UiText.LOOKS_HINT), muted = true)
                                WrapRow { RecapModel.LOOKS.forEach { (look, _) ->
                                    AfChip(look.uiLabel(), look in recap.looks, { if (idle) model.toggleLook(look) },
                                        Modifier.testTag("style-look-${look.name}"), enabled = idle)
                                } }
                            }
                            StyleSection.AUDIO -> AfCard(tr(UiText.V3_STYLE_AUDIO), Modifier.testTag("style-audio")) {
                                AfText(tr(UiText.MOVIE_SOUND_UNDER_VOICE), muted = true)
                                WrapRow { RecapModel.MOVIE_VOLUMES.forEach { (_, value) ->
                                    AfChip(movieVolumeLabel(value), recap.movieVolume == value, { if (idle) model.setMovieVolume(value) }, enabled = idle)
                                } }
                                RecapSoundControls(recap.audio, state.bgmName, idle, model)
                            }
                        }
                        render.blockers.firstOrNull()?.let { AfText(it.text(LocalUiLanguage.current), muted = true) }
                    }
                }, preview = {
                    Column(Modifier.fillMaxSize().paneScroll(rememberScrollState()).testTag("style-preview"),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) { preview() }
                }, previewPage = false)
            }
            StickyActionBar(StudioAction(tr(UiText.NEXT_STEP, "step" to tr(UiText.EXPORT)), onExport,
                idle && render.allowed && !draft, "recap-next", trailingIcon = AfIcons.Regular.ArrowRight),
                navigation?.let { StudioAction(tr(if (draft) UiText.DISCARD else UiText.PREVIOUS_STEP), back, tag = "recap-back",
                    icon = if (draft) AfIcons.Regular.Close else AfIcons.Regular.Back) }, glass = true)
        }
    }
}

@Composable
private fun StyleTimingControls(model: RecapModel, state: RecapUiState, recap: RecapV2, idle: Boolean) {
    val story = state.story ?: return
    val rows = RecapFit.rows(recap, story, state.lexicon)
    AfText(fitFooter(recap, rows, LocalUiLanguage.current), bold = true)
    rows.forEach { row ->
        AfText("${row.segment + 1} · ${row.label(LocalUiLanguage.current)}")
        if (row.status == FitStatus.RUNS_ON && row.overMs > 400)
            SmartFitChoices(model, recap, row.block ?: story.blocks[row.segment].id, row, idle)
    }
}
