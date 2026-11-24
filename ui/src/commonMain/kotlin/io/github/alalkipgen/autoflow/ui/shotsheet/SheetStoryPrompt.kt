package io.github.alalkipgen.autoflow.ui.shotsheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.model.ShotSpec
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.AfTextField
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.GlassButton
import io.github.alalkipgen.autoflow.ui.components.GlassCallout
import io.github.alalkipgen.autoflow.ui.components.GlassDialog
import io.github.alalkipgen.autoflow.ui.components.GlassDialogAction
import io.github.alalkipgen.autoflow.ui.components.GlassDialogButtons
import io.github.alalkipgen.autoflow.ui.components.GlassSwitchRow
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.shots.ShotDetail
import io.github.alalkipgen.autoflow.ui.shots.ShotsModel
import io.github.alalkipgen.autoflow.ui.shots.TonePill
import io.github.alalkipgen.autoflow.ui.storyboard.StoryCard
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardModel
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.GlassColors

/*
 * Group 3 D inner 1–2 (samples 07, 08, 09b, 09c): the shot sheet's Storyboard and Prompt step bodies in the glass look.
 * Same model calls as the classic `ShotCard` / `PromptSection` (which the classic Storyboard and Shots screens keep until
 * PR 12): edit, regenerate (new take), approve / undo approval; compile, approve / undo approval, recompile.
 */

/** The Storyboard step card's frame (sample 08): approved = Green .45 1 dp, stale = Stale .60 1.5 dp, else none. */
internal fun StoryCard?.stepOutline(): Pair<Color?, androidx.compose.ui.unit.Dp> = when {
    this?.stale == true -> GlassColors.StepStaleOutline to 1.5.dp
    this?.approved == true -> GlassColors.StepApprovedOutline to 1.dp
    else -> null to 1.dp
}

/**
 * Storyboard (sample 07 `sbCard`): the Approved (check-circle) / Stale (warning) pill and the mono version tag, the
 * Burmese scene 14 sp, the camera (`video-camera`) / mood (`smiley`) tag pills, small Edit `pencil-simple` · Regenerate
 * `arrow-clockwise` · Approve primary `check` / Undo approval `arrow-counter-clockwise`. Edit opens the glass form in the
 * card; Regenerate asks first in the New take dialog (🆕-5).
 */
@Composable
internal fun SheetStoryboard(card: StoryCard, model: StoryboardModel, busy: Boolean) {
    var editing by remember(card.number) { mutableStateOf(false) }
    var asking by remember(card.number) { mutableStateOf(false) }
    val spec = card.spec
    if (editing && spec != null) {
        SheetEditSpec(spec, onSave = { edited -> if (model.edit(card.number) { edited }) editing = false }, onBack = { editing = false })
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        WrapRow(Modifier.weight(1f), spacing = 6.dp) {
            if (card.approved) TonePill(AutoFlowColors.Green, AfIcons.Fill.CheckCircle, tr(UiText.APPROVED), Modifier.testTag("sheet-story-approved"))
            if (card.stale) TonePill(AutoFlowColors.Stale, AfIcons.Fill.Warn, tr(UiText.STALE), Modifier.testTag("sheet-story-stale"))
        }
        AfText(card.tag, Modifier.testTag("sheet-story-tag"), color = AutoFlowColors.Muted, mono = true, size = 11.5.sp)
    }
    if (spec == null) AfText(My.breakSyllables(tr(UiText.STORYBOARD_NO_SCENE)), color = AutoFlowColors.Muted)
    else {
        AfText(My.breakSyllables(spec.sceneMy), size = 14.sp)
        val tags = listOf(AfIcons.Regular.VideoCamera to spec.cameraEn, AfIcons.Regular.Smiley to spec.moodEn).filter { it.second.isNotBlank() }
        if (tags.isNotEmpty()) WrapRow(spacing = 6.dp) { tags.forEach { (icon, text) -> TagPill(icon, text) } }
        WrapRow {
            GlassButton(My.breakSyllables(tr(UiText.EDIT)), { editing = true }, Modifier.testTag("sheet-story-edit"), enabled = !busy,
                icon = AfIcons.Regular.Edit, small = true)
            GlassButton(My.breakSyllables(tr(UiText.REGENERATE)), { asking = true }, Modifier.testTag("sheet-story-regenerate"), enabled = !busy,
                icon = AfIcons.Regular.Redo, small = true)
            if (card.approved) GlassButton(My.breakSyllables(tr(UiText.UNDO_APPROVAL)), { model.approve(card.number, approved = false) },
                Modifier.testTag("sheet-story-unapprove"), enabled = !busy, icon = AfIcons.Regular.ArrowCounterClockwise, small = true)
            else GlassButton(My.breakSyllables(tr(UiText.APPROVE)), { model.approve(card.number) }, Modifier.testTag("sheet-story-approve"),
                ButtonKind.Primary, enabled = !busy, icon = AfIcons.Regular.Check, small = true)
        }
    }
    if (asking && !busy) NewTakeDialog(card, onBack = { asking = false }) { keep ->
        asking = false
        model.regenerate(card.number, keepScene = keep)
    }
}

/** A camera / mood tag (sample `tagPill`): White .06 + .14 line, a 14 dp Muted icon, the 11.5 sp text (regular). */
@Composable
private fun TagPill(icon: ImageVector, text: String) {
    val shape = RoundedCornerShape(999.dp)
    Row(Modifier.heightIn(min = 24.dp).clip(shape).background(GlassColors.TagPillFill).border(1.dp, GlassColors.TagPillBorder, shape)
        .padding(start = 7.dp, end = 9.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        AfIcon(icon, tint = AutoFlowColors.Muted, size = 14.dp)
        AfText(text, color = AutoFlowColors.Text, type = AfType.Micro, weight = FontWeight.Normal)
    }
}

/**
 * The edit form (sample 08 "editing"): Burmese scene (3 lines) and, under ENGLISH_PROMPT_DETAILS, the six English fields
 * with a 15 dp icon before each label (subject `user` · action `person-simple-run` · setting `map-pin` · camera
 * `video-camera` · mood `smiley` · lighting `sun`); small Save primary `floppy-disk` · Back. Same fields and save as before.
 */
@Composable
private fun SheetEditSpec(spec: ShotSpec, onSave: (ShotSpec) -> Unit, onBack: () -> Unit) {
    var draft by remember(spec) { mutableStateOf(spec) }
    Column(Modifier.fillMaxWidth().testTag("sheet-story-form"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SheetField(null, tr(UiText.BURMESE_SCENE), draft.sceneMy, { draft = draft.copy(sceneMy = it) }, lines = 3)
        AfText(My.breakSyllables(tr(UiText.ENGLISH_PROMPT_DETAILS)), size = 13.sp, bold = true)
        SheetField(AfIcons.Regular.User, tr(UiText.FIELD_SUBJECT), draft.subjectEn, { draft = draft.copy(subjectEn = it) })
        SheetField(AfIcons.Regular.PersonSimpleRun, tr(UiText.FIELD_ACTION), draft.actionEn, { draft = draft.copy(actionEn = it) })
        SheetField(AfIcons.Regular.MapPin, tr(UiText.FIELD_SETTING), draft.settingEn, { draft = draft.copy(settingEn = it) })
        SheetField(AfIcons.Regular.VideoCamera, tr(UiText.FIELD_CAMERA), draft.cameraEn, { draft = draft.copy(cameraEn = it) })
        SheetField(AfIcons.Regular.Smiley, tr(UiText.FIELD_MOOD), draft.moodEn, { draft = draft.copy(moodEn = it) })
        SheetField(AfIcons.Regular.Sun, tr(UiText.FIELD_LIGHTING), draft.lightingEn, { draft = draft.copy(lightingEn = it) })
        WrapRow {
            GlassButton(My.breakSyllables(tr(UiText.SAVE)), { onSave(draft) }, Modifier.testTag("sheet-story-save"), ButtonKind.Primary,
                enabled = draft.sceneMy.isNotBlank(), icon = AfIcons.Regular.FloppyDisk, small = true)
            GlassButton(My.breakSyllables(tr(UiText.BACK_PLAIN)), onBack, Modifier.testTag("sheet-story-form-back"), small = true)
        }
    }
}

/** A 12.5 sp Muted label (with an optional 15 dp icon) above a glass field that shows no placeholder (Group 2 `inputF`). */
@Composable
private fun SheetField(icon: ImageVector?, label: String, value: String, onChange: (String) -> Unit, lines: Int = 1) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            icon?.let { AfIcon(it, tint = AutoFlowColors.Muted, size = 15.dp) }
            AfText(My.breakSyllables(label), color = AutoFlowColors.Muted, type = AfType.Caption)
        }
        AfTextField(value, onChange, label, Modifier.fillMaxWidth(), lines = lines, mono = false, glass = true, showPlaceholder = false)
    }
}

/**
 * 🆕-5 (sample 09b): the New take question in a glass dialog — arrow-clockwise Purple, "New take for shot 3?", the hint
 * (+ the stale note when the shot has a prompt, reference or video), the Keep my Burmese scene switch in a quote box,
 * Back / Regenerate primary.
 */
@Composable
private fun NewTakeDialog(card: StoryCard, onBack: () -> Unit, onConfirm: (keepScene: Boolean) -> Unit) {
    var keep by remember(card.number) { mutableStateOf(card.tag.endsWith("edited")) }
    GlassDialog(onBack, AfIcons.Fill.Redo, AutoFlowColors.Purple, My.breakSyllables(tr(UiText.STORYBOARD_NEW_TAKE, "number" to card.number)),
        Modifier.testTag("confirm-new-take")) {
        AfText(My.breakSyllables(tr(UiText.STORYBOARD_TAKE_HINT) + if (card.hasDownstream) tr(UiText.STORYBOARD_TAKE_STALE) else "."))
        QuoteBox(Modifier.padding(0.dp)) {
            GlassSwitchRow(My.breakSyllables(tr(UiText.KEEP_BURMESE_SCENE)), keep, { keep = it }, Modifier.testTag("new-take-keep-scene"))
        }
        GlassDialogButtons(GlassDialogAction(My.breakSyllables(tr(UiText.BACK_PLAIN)), onBack, tag = "confirm-new-take-back"),
            GlassDialogAction(My.breakSyllables(tr(UiText.REGENERATE)), { onConfirm(keep) }, ButtonKind.Primary, AfIcons.Regular.Redo,
                tag = "confirm-new-take-ok"))
    }
}

/**
 * Prompt (sample 07 / 08 `promptCard`): FLOW_PROMPT label and the mono prompt in a quote box; approved = the Approved
 * pill + Undo approval · Recompile, else Approve prompt primary `check` · Recompile (`arrow-clockwise`, asks first in the
 * Recompile dialog, 🆕-5); not compiled = `note-pencil` line + Compile prompt primary `gear`; a compile error = red
 * `x-circle` callout; the workflow gate (storyboard not approved) and no storyboard = amber `lock-simple` callouts.
 */
@Composable
internal fun SheetPrompt(detail: ShotDetail, model: ShotsModel) {
    var asking by remember(detail.number) { mutableStateOf(false) }
    val prompt = detail.promptText
    when {
        prompt != null -> {
            AfText(My.breakSyllables(tr(UiText.FLOW_PROMPT)), color = AutoFlowColors.Muted, type = AfType.Micro, weight = FontWeight.Normal)
            QuoteBox(Modifier.padding(horizontal = 12.dp, vertical = 10.dp).testTag("sheet-prompt-text")) {
                AfText(prompt, size = 11.5.sp, mono = true)
            }
            val recompile: @Composable () -> Unit = {
                GlassButton(My.breakSyllables(tr(UiText.RECOMPILE)), { asking = true }, Modifier.testTag("sheet-prompt-recompile"),
                    icon = AfIcons.Regular.Redo, small = true)
            }
            if (detail.approvedPrompt) {
                TonePill(AutoFlowColors.Green, AfIcons.Fill.CheckCircle, tr(UiText.APPROVED), Modifier.testTag("sheet-prompt-approved"))
                WrapRow {
                    GlassButton(My.breakSyllables(tr(UiText.UNDO_APPROVAL)), { model.approvePrompt(detail.number, false) },
                        Modifier.testTag("sheet-prompt-unapprove"), icon = AfIcons.Regular.ArrowCounterClockwise, small = true)
                    recompile()
                }
            } else WrapRow {
                GlassButton(My.breakSyllables(tr(UiText.V3_APPROVE_PROMPT)), { model.approvePrompt(detail.number) },
                    Modifier.testTag("sheet-prompt-approve"), ButtonKind.Primary, icon = AfIcons.Regular.Check, small = true)
                recompile()
            }
            if (asking) RecompileDialog(prompt, onCancel = { asking = false }) {
                asking = false
                model.compilePrompt(detail.number)
            }
        }
        detail.compileError != null -> {
            val text = My.breakSyllables(tr(UiText.COMPILE_ERROR, "compileError" to detail.compileError))
            if (detail.compileGated) GlassCallout(AfIcons.Fill.Lock, text, AutoFlowColors.Amber, Modifier.testTag("sheet-prompt-locked"))
            else GlassCallout(AfIcons.Fill.XCircle, text, AutoFlowColors.Failed, Modifier.testTag("sheet-prompt-error"))
        }
        detail.spec != null -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AfIcon(AfIcons.Regular.NotePencil, Modifier.padding(top = 2.dp), tint = AutoFlowColors.Muted, size = 17.dp)
                AfText(My.breakSyllables(tr(UiText.PROMPT_NOT_COMPILED)), Modifier.weight(1f), color = AutoFlowColors.Muted, type = AfType.Caption)
            }
            GlassButton(My.breakSyllables(tr(UiText.COMPILE_PROMPT)), { model.compilePrompt(detail.number) }, Modifier.testTag("sheet-prompt-compile"),
                ButtonKind.Primary, icon = AfIcons.Regular.Gear, small = true)
        }
        else -> GlassCallout(AfIcons.Fill.Lock, My.breakSyllables(tr(UiText.STORYBOARD_FIRST)), AutoFlowColors.Amber, Modifier.testTag("sheet-prompt-locked"))
    }
}

/**
 * 🆕-5 (sample 09c): the Recompile question in a glass dialog — arrow-clockwise Cyan, V3_RECOMPILE_TITLE, the first 140
 * characters of the prompt (mono, Muted), Cancel / Recompile danger. Recompile = the same `compilePrompt` as before.
 */
@Composable
private fun RecompileDialog(prompt: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    GlassDialog(onCancel, AfIcons.Fill.Redo, AutoFlowColors.Cyan, My.breakSyllables(tr(UiText.V3_RECOMPILE_TITLE)), Modifier.testTag("confirm-recompile")) {
        QuoteBox(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            AfText(if (prompt.length > 140) prompt.take(140).trimEnd() + "…" else prompt, color = AutoFlowColors.Muted, size = 11.5.sp, mono = true)
        }
        GlassDialogButtons(GlassDialogAction(My.breakSyllables(tr(UiText.CANCEL)), onCancel, tag = "confirm-recompile-cancel"),
            GlassDialogAction(My.breakSyllables(tr(UiText.RECOMPILE)), onConfirm, ButtonKind.Danger, AfIcons.Regular.Redo, tag = "confirm-recompile-ok"))
    }
}

/** The quote box (sample `.quote`): Black .20 + White .08 line, radius 12; [inner] = its padding and tag. */
@Composable
private fun QuoteBox(inner: Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.fillMaxWidth().clip(shape).background(GlassColors.QuoteFill).border(1.dp, GlassColors.QuoteBorder, shape).then(inner)) { content() }
}

/** A message on a step card (the last job's result, a model message or action error): an amber `warning` callout, same text. */
@Composable
internal fun SheetNotice(text: String) = GlassCallout(AfIcons.Fill.Warn, text, AutoFlowColors.Amber, Modifier.testTag("sheet-notice"))
