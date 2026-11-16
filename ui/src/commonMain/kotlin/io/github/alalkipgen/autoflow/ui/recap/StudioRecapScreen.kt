package io.github.alalkipgen.autoflow.ui.recap

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
enum class RecapStep(val label: String) {
    SOURCE("ရင်းမြစ်"), STORY("ဇာတ်လမ်း"), VOICE("အသံ"), STYLE("ပုံစံ"), EXPORT("ထုတ်ယူ");
    fun previous(): RecapStep? = entries.getOrNull(ordinal - 1)
    fun next(): RecapStep? = entries.getOrNull(ordinal + 1)
}

/** No tab bar: the label and wrapping actions remain readable at the owner's large font sizes. */
@Composable
fun RecapStepHeader(step: RecapStep, onBack: () -> Unit, onNext: () -> Unit, onClassic: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(AutoFlowColors.Panel).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AfText("${step.label} · ${step.ordinal + 1}/5", Modifier.testTag("recap-step").semantics {
            progressBarRangeInfo = ProgressBarRangeInfo((step.ordinal + 1).toFloat(), 0f..5f, 4)
        }, bold = true)
        WrapRow {
            AfButton("← ယခင်အဆင့်", onBack, Modifier.heightIn(min = 48.dp).testTag("recap-back"))
            if (step.next() != null) AfButton("နောက်အဆင့်: ${step.next()!!.label} →", onNext,
                Modifier.heightIn(min = 48.dp).testTag("recap-next"), kind = ButtonKind.Primary)
            AfButton("Recap ပုံစံဟောင်း", onClassic, Modifier.heightIn(min = 48.dp).testTag("classic-recap"))
        }
    }
}

/** The 840 dp breakpoint is the WINDOW width, not the width remaining after the navigation rail. */
@Composable
fun ProjectPanes(
    windowWidthDp: Float,
    list: @Composable () -> Unit,
    editor: @Composable () -> Unit,
    preview: @Composable () -> Unit,
) {
    if (windowWidthDp >= 840f) {
        Row(Modifier.fillMaxSize().padding(8.dp).testTag("project-three-panes"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(0.23f).fillMaxHeight().testTag("project-list")) { list() }
            Box(Modifier.weight(0.44f).fillMaxHeight().testTag("project-editor")) { editor() }
            Box(Modifier.weight(0.33f).fillMaxHeight().testTag("project-preview")) { preview() }
        }
    } else Box(Modifier.fillMaxSize().testTag("project-single-pane")) { editor() }
}

/** Native v2 editor presentation; Classic Recap still uses the original numbered cards. */
@Composable
fun StudioRecapScreen(model: RecapModel, step: RecapStep, windowWidthDp: Float, onGoToExport: () -> Unit) {
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.refresh() }
    val recap = state.recap
    if (recap == null) { AfText("Movie Recap project တစ်ခု ဖွင့်ပါ။"); return }
    var selectedId by remember(state.projectDir) { mutableStateOf<String?>(null) }
    var selectedScene by remember(state.projectDir) { mutableIntStateOf(0) }
    val blocks = state.story?.blocks.orEmpty()
    val selectedIndex = blocks.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: blocks.indices.firstOrNull()
    val wide = windowWidthDp >= 840f
    val list: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AfText(if (step == RecapStep.SOURCE) "ပြကွက်များ" else "အပိုင်းများ", bold = true)
            if (step == RecapStep.SOURCE) {
                recap.scenes.forEachIndexed { i, scene ->
                    AfButton("${if (scene.picked) "☑" else "☐"} #${i + 1} · ${RecapModel.clock(scene.startMs)}", { selectedScene = i },
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("scene-select-$i"),
                        kind = if (i == selectedScene) ButtonKind.Primary else ButtonKind.Secondary)
                }
            } else {
                if (blocks.isEmpty()) AfText("ဇာတ်လမ်း အရင်ရေးပါ။", muted = true)
                blocks.forEachIndexed { i, b ->
                    AfButton("#${i + 1} · ${b.textMy.take(70)}", { selectedId = b.id },
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("block-select-$i"),
                        kind = if (i == selectedIndex) ButtonKind.Primary else ButtonKind.Secondary)
                    TakeVoiceLabel(recap.takeFor(b.id), recap.voiceLock)
                    if (state.isStale(i)) AfText("⚠ ${io.github.alalkipgen.autoflow.ui.i18n.My.STALE}", color = AutoFlowColors.Amber)
                    else if (state.currentTake(i) == null) AfText(io.github.alalkipgen.autoflow.ui.i18n.My.NO_VOICE, muted = true)
                }
            }
        }
    }
    val preview: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (step != RecapStep.STYLE) RecapTimelinePreview(model)
            AfText("ထုတ်ယူမည့်ပုံ · ရုပ်ငြိမ်", muted = true)
            ExactFrameCard(model)
        }
    }
    ProjectPanes(windowWidthDp, list = list, editor = {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.job?.let { AfText("⏳ $it", color = AutoFlowColors.Cyan) }
            state.message?.let { AfText(it, color = if (state.messageOk) AutoFlowColors.Green else AutoFlowColors.Amber) }
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
                AfText("ထုတ်ယူမည့်ပုံ · ရုပ်ငြိမ်", muted = true)
                ExactFrameCard(model)
            }
        }
    }, preview = preview)
}
