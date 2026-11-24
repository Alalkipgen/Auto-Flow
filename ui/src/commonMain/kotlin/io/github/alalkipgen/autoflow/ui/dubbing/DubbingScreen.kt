package io.github.alalkipgen.autoflow.ui.dubbing

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.dubbing.DubBlock
import io.github.alalkipgen.autoflow.core.subtitles.TimeText
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.subtitles.*
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/** The five Dubbing steps on the existing `ProjectTopBar`, whose step bar chooses them (specs/dubbing.md §2). */
enum class DubStep(val text: UiText) {
    VIDEO(UiText.DUB_STEP_VIDEO), TRANSLATE(UiText.DUB_STEP_TRANSLATE), SCRIPT(UiText.DUB_STEP_SCRIPT),
    VOICE(UiText.DUB_STEP_VOICE), EXPORT(UiText.DUB_STEP_EXPORT);

    companion object {
        /** Where an opened project lands: the first step that is not done yet (Voice once approved; Export is chosen from there). */
        fun first(g: DubGates): DubStep = when {
            g.video != null || g.script == DubBlock.NO_LINES -> VIDEO
            g.script != null -> TRANSLATE
            g.voice != null -> SCRIPT
            else -> VOICE
        }
    }
}

/**
 * Dubbing (D-1b). Video and Translate are the Subtitles source chooser and editor (D-88); Dub script shows every line
 * with its spoken line and the sticky Approve; Voice (D-2b) makes and checks each line's take; Export (D-3b) mixes and
 * saves the dubbed video. A locked step says why and where to go.
 */
@Composable
fun DubbingScreen(script: DubScriptModel, subtitles: SubtitlesModel, session: ProjectSession, editor: SubtitleEditorModel,
    onBack: () -> Unit, modifier: Modifier = Modifier, vlcDir: String? = null, player: SubtitlePlayer = moviePlayer(vlcDir),
    transcribe: SubtitleTranscribeModel? = null, translate: SubtitleTranslateModel? = null, voice: DubVoiceModel? = null,
    shorten: DubShortenModel? = null, export: DubExportModel? = null) {
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
    // Landing on Dub script or Voice stays put (Approve does not move the owner away); Video → Translate still follows the lines.
    LaunchedEffect(open?.dir, doc != null) { if (step == null && doc != null && shown >= DubStep.SCRIPT) step = shown }
    var leaving by remember { mutableStateOf(false) }
    // F-6 (D-95): one preview clock for Dub script, Voice and Export; a step change pauses it where it is.
    val playhead = remember(open?.dir) { Playhead() }
    LaunchedEffect(shown) { playhead.playing = false; playhead.seek(playhead.positionMs) }
    val preview: @Composable (Boolean) -> Unit = { captions ->
        if (export != null && doc != null) DubPreview(export, open, doc, playhead, player, captions)
    }
    // Group 4 A 3: the ambient background (Group 1) behind every step, not the flat Background fill.
    Column(modifier.fillMaxSize().ambientBackground()) {
        // "Dubbing · <project>"; the step bar is the step chooser (panel 6): each labelled step a tab, no chip row under it.
        ProjectTopBar(listOfNotNull(tr(UiText.V3_DUBBING), open?.project?.name?.takeIf { it.isNotBlank() }).joinToString(" · "),
            shown.ordinal + 1, tr(shown.text), { if (dirty) leaving = true else onBack() },
            steps = DubStep.entries.map { tr(it.text) }, onStep = { step = DubStep.entries[it] },
            stepTags = DubStep.entries.map { "dub-step-${it.name}" }, glass = true)
        val go: (DubStep) -> Unit = { step = it }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            when (shown) {
                DubStep.VIDEO -> if (gates.video == DubBlock.TOO_LONG) Lock(block(DubBlock.TOO_LONG, gates, script), tooLong = true) else
                    SubtitlesScreen(subtitles, session, editor, vlcDir = vlcDir, player = player, transcribe = transcribe)
                DubStep.TRANSLATE -> gates.video?.let { Lock(block(it, gates, script), DubStep.VIDEO, go) }
                    ?: if (gates.script == DubBlock.NO_LINES) Lock(block(DubBlock.NO_LINES, gates, script), DubStep.VIDEO, go)
                    else SubtitlesScreen(subtitles, session, editor, vlcDir = vlcDir, player = player, translate = translate)
                DubStep.SCRIPT -> when {
                    dirty -> Lock(tr(UiText.DUB_LOCK_UNSAVED), DubStep.TRANSLATE, go)
                    gates.script != null -> Lock(block(gates.script, gates, script), target(gates.script), go)
                    doc != null -> Script(script, open, doc, gates.approved, st.notice, st.ok) { preview(true) }
                }
                DubStep.VOICE -> when {
                    dirty -> Lock(tr(UiText.DUB_LOCK_UNSAVED), DubStep.TRANSLATE, go)
                    gates.voice != null -> Lock(block(gates.voice, gates, script), target(gates.voice), go)
                    voice != null && doc != null -> VoiceStep(voice, open, doc, { step = DubStep.EXPORT }, shorten, script,
                        { step = DubStep.SCRIPT }) { preview(true) }
                    else -> Column(Modifier.widthIn(max = 900.dp).fillMaxWidth().padding(screenPadding)) {
                        PlannedTile(tr(shown.text), null, Modifier.testTag("dub-planned-VOICE"))
                    }
                }
                DubStep.EXPORT -> when {
                    dirty -> Lock(tr(UiText.DUB_LOCK_UNSAVED), DubStep.TRANSLATE, go)
                    gates.voice != null -> Lock(block(gates.voice, gates, script), target(gates.voice), go)
                    export != null && doc != null -> ExportStep(export, open, doc, go) { preview(open?.project?.dubbing?.burnSubtitles == true) }
                    else -> Column(Modifier.widthIn(max = 900.dp).fillMaxWidth().padding(screenPadding)) {
                        PlannedTile(tr(shown.text), null, Modifier.testTag("dub-planned-${shown.name}"))
                    }
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

/** A step's icon (plan §3.3): the Lock buttons and the Export checklist's Go-to buttons. */
internal val DubStep.icon: ImageVector get() = when (this) {
    DubStep.VIDEO -> AfIcons.Regular.FilmStrip
    DubStep.TRANSLATE -> AfIcons.Regular.Translate
    DubStep.SCRIPT -> AfIcons.Regular.NotePencil
    DubStep.VOICE -> AfIcons.Regular.Dub
    DubStep.EXPORT -> AfIcons.Regular.Export
}

/**
 * A locked step (Group 4 B): a glass empty state centred in the step area (at most 420 dp wide) — a 56 dp Amber soft
 * tile (`lock-simple`; `clock` for a video over the limit, [tooLong]), why (one string, centred), then the primary
 * button to the step that unlocks it with that step's icon and a trailing arrow. It scrolls when taller than the area.
 */
@Composable
private fun Lock(why: String, to: DubStep? = null, go: (DubStep) -> Unit = {}, tooLong: Boolean = false) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().heightIn(min = maxHeight).verticalScroll(rememberScrollState()).padding(screenPadding),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f)) // the card sits a third of the way down, as in the sample
            GlassCard(Modifier.widthIn(max = 420.dp).testTag("dub-lock"), padding = PaddingValues(horizontal = 20.dp, vertical = 24.dp)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SoftTile(if (tooLong) AfIcons.Duotone.Clock else AfIcons.Duotone.Lock, AutoFlowColors.Amber, size = 56.dp)
                    AfText(My.breakSyllables(why), size = 15.sp, textAlign = TextAlign.Center)
                    to?.let { GlassButton(My.breakSyllables(tr(UiText.DUB_GO_TO, "step" to tr(it.text))), { go(it) }, Modifier.testTag("dub-go-${it.name}"),
                        kind = ButtonKind.Primary, icon = it.icon, trailingIcon = AfIcons.Regular.ArrowRight) }
                }
            }
            Spacer(Modifier.weight(2f))
        }
    }
}

@Composable
private fun Script(model: DubScriptModel, open: io.github.alalkipgen.autoflow.ui.projects.OpenProject?,
    doc: io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc, approved: Boolean, notice: UiNotice?, ok: Boolean,
    preview: @Composable () -> Unit = {}) {
    val rows = remember(open?.project?.subtitles?.video, doc) { model.rows(open, doc) }
    Column(Modifier.widthIn(max = 900.dp).fillMaxSize()) {
        notice?.let { AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.padding(horizontal = 12.dp).testTag("dub-notice"),
            color = if (ok) AutoFlowColors.Green else AutoFlowColors.Failed) }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("dub-rows"), contentPadding = PaddingValues(screenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "preview") { preview() } // F-6: scrolls with the lines
            items(rows, key = { it.id }) { r -> ScriptRow(r, { model.setSpoken(r.id, it) }, { model.useSubtitle(r.id) }) }
        }
        StickyActionBar(StudioAction(My.breakSyllables(tr(UiText.DUB_APPROVE)), { model.approve() }, enabled = !approved, tag = "dub-approve",
            icon = AfIcons.Regular.Check),
            caption = My.breakSyllables(tr(if (approved) UiText.DUB_APPROVED else UiText.DUB_NOT_APPROVED)))
    }
}

/** One line as a cue card (panel 6): "#n start → end" in tabular figures, its room, then the subtitle and the spoken line. */
@Composable
private fun ScriptRow(r: DubScriptRow, save: (String) -> Unit, useSubtitle: () -> Unit) {
    var text by remember(r.id, r.spoken) { mutableStateOf(r.spoken) }
    AfCard(Modifier.testTag("dub-row-${r.id}")) {
        WrapRow(Modifier.testTag("dub-row-head-${r.id}")) {
            AfText("#${r.n}", color = AutoFlowColors.Muted, type = AfType.Numbers, weight = FontWeight.SemiBold)
            AfText("${TimeText.show(r.startMs)} → ${TimeText.show(r.endMs)}", color = AutoFlowColors.Muted, type = AfType.Numbers)
        }
        AfText(My.breakSyllables(tr(UiText.DUB_SLOT, "s" to (r.slotMs / 100) / 10.0)), Modifier.testTag("dub-slot-${r.id}"), muted = true)
        AfText(My.breakSyllables(tr(UiText.DUB_SUBTITLE)), bold = true)
        AfText(My.breakSyllables(r.textMy), Modifier.testTag("dub-subtitle-${r.id}"))
        AfText(My.breakSyllables(tr(UiText.DUB_SPOKEN)), bold = true)
        AfTextField(text, { text = it }, tr(UiText.DUB_SPOKEN), Modifier.fillMaxWidth().testTag("dub-spoken-${r.id}"), lines = 2, mono = false)
        WrapRow {
            if (text.trim() != r.spoken) AfButton(tr(UiText.SAVE), { save(text) }, Modifier.testTag("dub-save-${r.id}"), kind = ButtonKind.Primary,
                icon = AfIcons.Regular.Check)
            if (r.own) AfButton(My.breakSyllables(tr(UiText.DUB_USE_SUBTITLE)), useSubtitle, Modifier.testTag("dub-use-subtitle-${r.id}"),
                icon = AfIcons.Regular.Subtitles)
        }
    }
}
