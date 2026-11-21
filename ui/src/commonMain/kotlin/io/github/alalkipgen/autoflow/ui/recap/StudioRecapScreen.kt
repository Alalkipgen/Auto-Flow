package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/** Presentation state only. Persisted project/block identifiers never depend on translated labels. */
enum class RecapStep(val textKey: UiText) {
    SOURCE(UiText.SOURCE), STORY(UiText.STORY), VOICE(UiText.VOICE), STYLE(UiText.STYLE), EXPORT(UiText.EXPORT);
    val label: String get() = textKey.en
    fun previous(): RecapStep? = entries.getOrNull(ordinal - 1)
    fun next(): RecapStep? = entries.getOrNull(ordinal + 1)
}

/** The five step names under the project top bar's step bar. */
@Composable
internal fun recapStepLabels(): List<String> = RecapStep.entries.map { tr(it.textKey) }

/** No tab bar: the label and wrapping actions remain readable at the owner's large font sizes. */
@Composable
fun RecapStepHeader(step: RecapStep, onBack: () -> Unit, onNext: () -> Unit, onClassic: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(AutoFlowColors.Panel).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AfText("${tr(step.textKey)} · ${step.ordinal + 1}/5", Modifier.testTag("recap-step").semantics {
            progressBarRangeInfo = ProgressBarRangeInfo((step.ordinal + 1).toFloat(), 0f..5f, 4)
        }, bold = true)
        WrapRow {
            AfButton(tr(UiText.PREVIOUS_STEP), onBack, Modifier.heightIn(min = 48.dp).testTag("recap-back"))
            if (step.next() != null) AfButton(tr(UiText.NEXT_STEP, "step" to tr(step.next()!!.textKey)), onNext,
                Modifier.heightIn(min = 48.dp).testTag("recap-next"), kind = ButtonKind.Primary)
            AfButton(tr(UiText.CLASSIC_RECAP), onClassic, Modifier.heightIn(min = 48.dp).testTag("classic-recap"))
        }
    }
}

/**
 * The 840 dp breakpoint is the WINDOW width, not the width remaining after the navigation rail.
 * At/above 840 dp: list · preview · inspector (UI v3 PR-8, concept 11). [editor] is the inspector; its pane keeps the
 * `project-editor` tag. Below 1200 dp the inspector keeps the former 44 % share so 840 dp editors do not get
 * narrower; from 1200 dp the preview takes the centre as in concept 11.
 */
@Composable
fun ProjectPanes(
    windowWidthDp: Float,
    list: @Composable () -> Unit,
    editor: @Composable () -> Unit,
    preview: @Composable () -> Unit,
    previewPage: Boolean = true,
) {
    if (windowWidthDp >= 840f) {
        val (listShare, previewShare, inspectorShare) = if (windowWidthDp >= 1200f) Triple(.23f, .45f, .32f) else Triple(.24f, .32f, .44f)
        Row(Modifier.fillMaxSize().padding(8.dp).testTag("project-three-panes"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(listShare).fillMaxHeight().testTag("project-list")) { list() }
            Box(Modifier.weight(previewShare).fillMaxHeight().testTag("project-preview")) { preview() }
            Box(Modifier.weight(inspectorShare).fillMaxHeight().testTag("project-editor")) { editor() }
        }
    } else {
        // F-6: phones have no room for the preview pane, so it is a second page; the top bar's ▶ / ✎ toggle switches it.
        val page = LocalProjectPreviewPage.current?.takeIf { previewPage }
        if (page != null) DisposableEffect(page) { page.offer(); onDispose { page.withdraw() } }
        Box(Modifier.fillMaxSize().testTag("project-single-pane")) {
            if (page?.shown == true) Box(Modifier.fillMaxSize().padding(8.dp).testTag("project-preview-page")) { preview() } else editor()
        }
    }
}

/** Native v2 editor presentation; Classic Recap still uses the original numbered cards. */
@Composable
fun StudioRecapScreen(model: RecapModel, step: RecapStep, windowWidthDp: Float, onGoToExport: () -> Unit,
    navigation: RecapNavigation? = null,
) {
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.refresh() }
    val recap = state.recap
    if (recap == null) { AfText(tr(UiText.OPEN_RECAP_PROJECT)); return }
    // One focus/shortcut host per native step; the Classic Recap screen keeps no shortcuts.
    when (step) {
        RecapStep.VOICE -> { ProjectShortcutHost { StudioVoiceScreen(model, navigation, windowWidthDp) }; return }
        RecapStep.STORY -> { ProjectShortcutHost { StudioStoryScreen(model, windowWidthDp, navigation) }; return }
        RecapStep.SOURCE -> { ProjectShortcutHost { StudioSourceScreen(model, windowWidthDp, navigation) }; return }
        RecapStep.STYLE -> { ProjectShortcutHost { StudioStyleScreen(model, windowWidthDp, navigation, onGoToExport) }; return }
        RecapStep.EXPORT -> Unit
    }
    var selectedId by remember(state.projectDir) { mutableStateOf<String?>(null) }
    var selectedScene by remember(state.projectDir) { mutableIntStateOf(0) }
    val blocks = state.story?.blocks.orEmpty()
    val selectedIndex = blocks.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: blocks.indices.firstOrNull()
    val wide = windowWidthDp >= 840f
    val list: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AfText(if (step == RecapStep.SOURCE) tr(UiText.SCENES) else tr(UiText.BLOCKS), bold = true)
            if (step == RecapStep.SOURCE) {
                recap.scenes.forEachIndexed { i, scene ->
                    AfButton("${if (scene.picked) "☑" else "☐"} #${i + 1} · ${RecapModel.clock(scene.startMs)}", { selectedScene = i },
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("scene-select-$i"),
                        kind = if (i == selectedScene) ButtonKind.Primary else ButtonKind.Secondary)
                }
            } else {
                if (blocks.isEmpty()) AfText(tr(UiText.WRITE_STORY_FIRST), muted = true)
                blocks.forEachIndexed { i, b ->
                    AfButton("#${i + 1} · ${b.textMy.take(70)}", { selectedId = b.id },
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("block-select-$i"),
                        kind = if (i == selectedIndex) ButtonKind.Primary else ButtonKind.Secondary)
                    TakeVoiceLabel(recap.takeFor(b.id), recap.voiceLock)
                    if (state.isStale(i)) AfText("⚠ ${tr(UiText.STALE)}", color = AutoFlowColors.Amber)
                    else if (state.currentTake(i) == null) AfText(tr(UiText.NO_VOICE), muted = true)
                }
            }
        }
    }
    val preview: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (step != RecapStep.STYLE) RecapTimelinePreview(model)
            AfText(tr(UiText.OUTPUT_STILL_PREVIEW), muted = true)
            ExactFrameCard(model)
        }
    }
    ProjectPanes(windowWidthDp, list = list, editor = {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.job?.let { AfText("⏳ ${state.jobNotice?.text(LocalUiLanguage.current) ?: it}", color = AutoFlowColors.Cyan) }
            state.message?.let { AfText(state.notice?.text(LocalUiLanguage.current) ?: it, color = if (state.messageOk) AutoFlowColors.Green else AutoFlowColors.Amber) }
            if (!wide && step != RecapStep.SOURCE && blocks.isNotEmpty()) {
                WrapRow { blocks.forEachIndexed { i, b -> AfChip("#${i + 1}", i == selectedIndex, { selectedId = b.id }, Modifier.heightIn(min = 48.dp).testTag("block-select-$i")) } }
            }
            when (step) {
                RecapStep.SOURCE -> {
                    MovieCard(model, state, recap)
                    ScenesCard(model, state, recap, selectedScene = if (wide) selectedScene else null)
                }
                RecapStep.STORY -> ScriptCard(model, state, recap, selectedIndex)
                RecapStep.VOICE -> VoiceCard(model, state, recap, selectedIndex)
                RecapStep.STYLE -> FitCard(model, state, recap, onGoToExport)
                RecapStep.EXPORT -> Unit // Export has its own job-aware screen in AppShell.
            }
            if (!wide && step in listOf(RecapStep.STORY, RecapStep.VOICE, RecapStep.STYLE)) {
                if (step != RecapStep.STYLE) RecapTimelinePreview(model)
                AfText(tr(UiText.OUTPUT_STILL_PREVIEW), muted = true)
                ExactFrameCard(model)
            }
        }
    }, preview = preview)
}
