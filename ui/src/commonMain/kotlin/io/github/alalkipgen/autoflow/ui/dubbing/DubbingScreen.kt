package io.github.alalkipgen.autoflow.ui.dubbing

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.dubbing.DubBlock
import io.github.alalkipgen.autoflow.core.subtitles.TimeText
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.subtitles.*
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/** The five Dubbing steps on the existing `ProjectTopBar` (specs/dubbing.md §2). */
enum class DubStep(val text: UiText) {
    VIDEO(UiText.DUB_STEP_VIDEO), TRANSLATE(UiText.DUB_STEP_TRANSLATE), SCRIPT(UiText.DUB_STEP_SCRIPT),
    VOICE(UiText.DUB_STEP_VOICE), EXPORT(UiText.DUB_STEP_EXPORT);

    companion object {
        /** Where an opened project lands: the first step that is not done yet (Voice / Export are still Planned). */
        fun first(g: DubGates): DubStep = when {
            g.video != null || g.script == DubBlock.NO_LINES -> VIDEO
            g.script != null -> TRANSLATE
            else -> SCRIPT
        }
    }
}

/**
 * Dubbing (D-1b). Video and Translate are the Subtitles source chooser and editor (D-88); Dub script shows every line
 * with its spoken line and the sticky Approve. A locked step says why and where to go; Voice and Export stay Planned
 * until D-2 / D-3.
 */
@Composable
fun DubbingScreen(script: DubScriptModel, subtitles: SubtitlesModel, session: ProjectSession, editor: SubtitleEditorModel,
    onBack: () -> Unit, modifier: Modifier = Modifier, vlcDir: String? = null, player: SubtitlePlayer = moviePlayer(vlcDir),
    transcribe: SubtitleTranscribeModel? = null, translate: SubtitleTranslateModel? = null) {
    val open by session.state.collectAsState()
    val st by script.state.collectAsState()
    val ed by editor.state.collectAsState()
    val current = open?.project?.subtitles?.current
    LaunchedEffect(open?.dir, current) { script.open() }
    val doc = st.doc?.takeIf { st.dir == open?.dir && st.base == current }
    val gates = script.gates(open, doc)
    val dirty = ed.dir == open?.dir && ed.draft?.let { it.dirty && it.base == current } == true
    var step by remember(open?.dir) { mutableStateOf<DubStep?>(null) }
    val shown = step ?: DubStep.first(gates).takeIf { current == null || doc != null } ?: DubStep.VIDEO
    var leaving by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().background(AutoFlowColors.Background)) {
        ProjectTopBar(open?.project?.name.orEmpty(), shown.ordinal + 1, tr(shown.text), { if (dirty) leaving = true else onBack() })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp).testTag("dub-steps"),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DubStep.entries.forEach { s -> AfChip(My.breakSyllables(tr(s.text)), s == shown, { step = s }, Modifier.testTag("dub-step-${s.name}")) }
        }
        val go: (DubStep) -> Unit = { step = it }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            when (shown) {
                DubStep.VIDEO -> if (gates.video == DubBlock.TOO_LONG) Lock(block(DubBlock.TOO_LONG, gates, script)) else
                    SubtitlesScreen(subtitles, session, editor, vlcDir = vlcDir, player = player, transcribe = transcribe)
                DubStep.TRANSLATE -> gates.video?.let { Lock(block(it, gates, script), DubStep.VIDEO, go) }
                    ?: if (gates.script == DubBlock.NO_LINES) Lock(block(DubBlock.NO_LINES, gates, script), DubStep.VIDEO, go)
                    else SubtitlesScreen(subtitles, session, editor, vlcDir = vlcDir, player = player, translate = translate)
                DubStep.SCRIPT -> when {
                    dirty -> Lock(tr(UiText.DUB_LOCK_UNSAVED), DubStep.TRANSLATE, go)
                    gates.script != null -> Lock(block(gates.script, gates, script), target(gates.script), go)
                    doc != null -> Script(script, open, doc, gates.approved, st.notice, st.ok)
                }
                DubStep.VOICE, DubStep.EXPORT -> Column(Modifier.widthIn(max = 900.dp).fillMaxWidth().padding(screenPadding)) {
                    PlannedTile(tr(shown.text), gates.voice?.let { block(it, gates, script) }, Modifier.testTag("dub-planned-${shown.name}"))
                }
            }
        }
    }
    if (leaving) LeaveDialog(
        save = { leaving = false; if (editor.save()) onBack() },
        discard = { leaving = false; editor.discard(); onBack() },
        stay = { leaving = false },
    )
}

private fun target(b: DubBlock) = when (b) {
    DubBlock.NO_VIDEO, DubBlock.TOO_LONG, DubBlock.NO_LINES -> DubStep.VIDEO
    DubBlock.NOT_TRANSLATED -> DubStep.TRANSLATE
    DubBlock.NOT_APPROVED -> DubStep.SCRIPT
}

@Composable
private fun block(b: DubBlock, g: DubGates, model: DubScriptModel): String = when (b) {
    DubBlock.NO_VIDEO -> tr(UiText.DUB_LOCK_NO_VIDEO)
    DubBlock.TOO_LONG -> tr(UiText.DUB_LIMIT, "minutes" to model.maxMinutes)
    DubBlock.NO_LINES -> tr(UiText.DUB_LOCK_NO_LINES)
    DubBlock.NOT_TRANSLATED -> tr(UiText.DUB_NOT_TRANSLATED, "n" to g.notTranslated)
    DubBlock.NOT_APPROVED -> tr(UiText.DUB_LOCK_NOT_APPROVED)
}

/** A locked step: why, and a button to the step that unlocks it. */
@Composable
private fun Lock(why: String, to: DubStep? = null, go: (DubStep) -> Unit = {}) {
    Column(Modifier.widthIn(max = 900.dp).fillMaxWidth().padding(screenPadding)) {
        AfCard(My.breakSyllables(why), Modifier.testTag("dub-lock")) {
            to?.let { AfButton(My.breakSyllables(tr(UiText.DUB_GO_TO, "step" to tr(it.text))), { go(it) }, Modifier.testTag("dub-go-${it.name}"), kind = ButtonKind.Primary) }
        }
    }
}

@Composable
private fun Script(model: DubScriptModel, open: io.github.alalkipgen.autoflow.ui.projects.OpenProject?,
    doc: io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc, approved: Boolean, notice: UiNotice?, ok: Boolean) {
    val rows = remember(open?.project?.subtitles?.video, doc) { model.rows(open, doc) }
    Column(Modifier.widthIn(max = 900.dp).fillMaxSize()) {
        notice?.let { AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.padding(horizontal = 12.dp).testTag("dub-notice"),
            color = if (ok) AutoFlowColors.Green else AutoFlowColors.Failed) }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("dub-rows"), contentPadding = PaddingValues(screenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(rows, key = { it.id }) { r -> ScriptRow(r, { model.setSpoken(r.id, it) }, { model.useSubtitle(r.id) }) }
        }
        StickyActionBar(StudioAction(My.breakSyllables(tr(UiText.DUB_APPROVE)), { model.approve() }, enabled = !approved, tag = "dub-approve"),
            caption = My.breakSyllables(tr(if (approved) UiText.DUB_APPROVED else UiText.DUB_NOT_APPROVED)))
    }
}

@Composable
private fun ScriptRow(r: DubScriptRow, save: (String) -> Unit, useSubtitle: () -> Unit) {
    var text by remember(r.id, r.spoken) { mutableStateOf(r.spoken) }
    AfCard("#${r.n} · ${TimeText.show(r.startMs)} → ${TimeText.show(r.endMs)}", Modifier.testTag("dub-row-${r.id}")) {
        AfText(My.breakSyllables(tr(UiText.DUB_SLOT, "s" to (r.slotMs / 100) / 10.0)), Modifier.testTag("dub-slot-${r.id}"), muted = true)
        AfText(My.breakSyllables(tr(UiText.DUB_SUBTITLE)), bold = true)
        AfText(My.breakSyllables(r.textMy), Modifier.testTag("dub-subtitle-${r.id}"))
        AfText(My.breakSyllables(tr(UiText.DUB_SPOKEN)), bold = true)
        AfTextField(text, { text = it }, tr(UiText.DUB_SPOKEN), Modifier.fillMaxWidth().testTag("dub-spoken-${r.id}"), lines = 2, mono = false)
        WrapRow {
            if (text.trim() != r.spoken) AfButton(tr(UiText.SAVE), { save(text) }, Modifier.testTag("dub-save-${r.id}"), kind = ButtonKind.Primary)
            if (r.own) AfButton(My.breakSyllables(tr(UiText.DUB_USE_SUBTITLE)), useSubtitle, Modifier.testTag("dub-use-subtitle-${r.id}"))
        }
    }
}
