package io.github.alalkipgen.autoflow.ui.shotsheet

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.HandoffState
import io.github.alalkipgen.autoflow.core.model.Pass
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.AfTextField
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.CardTile
import io.github.alalkipgen.autoflow.ui.components.GlassButton
import io.github.alalkipgen.autoflow.ui.components.GlassCallout
import io.github.alalkipgen.autoflow.ui.components.GlassFilterChip
import io.github.alalkipgen.autoflow.ui.components.GlassSegment
import io.github.alalkipgen.autoflow.ui.components.GlassSegmented
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.components.decodeImage
import io.github.alalkipgen.autoflow.ui.components.glassDashedBox
import io.github.alalkipgen.autoflow.ui.handoff.FlowHandoffModel
import io.github.alalkipgen.autoflow.ui.handoff.HandoffRow
import io.github.alalkipgen.autoflow.ui.handoff.THUMB_H
import io.github.alalkipgen.autoflow.ui.handoff.THUMB_W
import io.github.alalkipgen.autoflow.ui.handoff.handoffBadges
import io.github.alalkipgen.autoflow.ui.handoff.refThumbLabel
import io.github.alalkipgen.autoflow.ui.handoff.videoThumbLabel
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.recap.InnerGlassCard
import io.github.alalkipgen.autoflow.ui.references.RefRow
import io.github.alalkipgen.autoflow.ui.references.ReferencesModel
import io.github.alalkipgen.autoflow.ui.shots.TonePill
import io.github.alalkipgen.autoflow.ui.storyboard.text
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.GlassColors

/*
 * Group 3 D inner 3–4 (samples 07, 08): the shot sheet's Reference images and Flow handoff step bodies in the glass look.
 * Same model calls as the classic `RefSection` / `HandoffCard` (the classic References and Flow handoff screens keep
 * `HandoffCard` until PR 12): copy the image prompt, AI Studio, import, pick, approve; pass, batch, import, accept, credits.
 */

/**
 * Reference images (sample 07 / 08 `refsCard`): the gate = amber `lock-simple` callout; small Copy image prompt `copy` ·
 * Open AI Studio `arrow-square-out` · Import image `upload-simple`; no image = the dashed box with the Duotone `image`
 * (Blue) and SHEET_NO_REF; else a tile per image (the chosen one = purple ring + `check-circle` badge, its label in a purple
 * pill) and Approve Ref X primary `check` — once approved the green Ref approved pill + Undo approval (same toggle).
 */
@Composable
internal fun SheetRefs(row: RefRow, model: ReferencesModel, importRef: () -> Unit) {
    val language = LocalUiLanguage.current
    row.copyGateMsg?.let {
        GlassCallout(AfIcons.Fill.Lock, My.breakSyllables(row.copyBlocker?.text(language) ?: forSheet(it, language)), AutoFlowColors.Amber,
            Modifier.testTag("sheet-ref-locked"))
    }
    WrapRow {
        GlassButton(My.breakSyllables(tr(UiText.COPY_IMAGE_PROMPT)), { model.copyImagePrompt(row.number) }, Modifier.testTag("ref-copy-prompt"),
            enabled = row.imagePrompt != null && row.copyGateMsg == null, icon = AfIcons.Regular.Copy, small = true)
        GlassButton(My.breakSyllables(tr(UiText.OPEN_AI_STUDIO)), { model.openAiStudio() }, Modifier.testTag("ref-open-ai-studio"),
            icon = AfIcons.Regular.ArrowSquareOut, small = true)
        GlassButton(My.breakSyllables(tr(UiText.IMPORT_IMAGE)), importRef, Modifier.testTag("ref-import"), icon = AfIcons.Regular.Upload, small = true)
    }
    if (row.refs.isEmpty()) {
        Column(Modifier.fillMaxWidth().glassDashedBox().padding(horizontal = 16.dp, vertical = 18.dp).testTag("sheet-ref-empty"),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AfIcon(AfIcons.Duotone.Image, tint = GlassColors.SheetRefs, size = 30.dp)
            AfText(My.breakSyllables(tr(UiText.SHEET_NO_REF)), color = AutoFlowColors.Muted, type = AfType.Caption, textAlign = TextAlign.Center)
        }
        return
    }
    WrapRow(spacing = 10.dp) {
        row.refs.forEachIndexed { i, id -> SheetRefTile(ReferencesModel.refLabel(i), id, id == row.chosenRef, model) { model.setChosenRef(row.number, id) } }
    }
    if (row.refApproved) WrapRow {
        TonePill(AutoFlowColors.Green, AfIcons.Fill.CheckCircle, tr(UiText.REF_APPROVED), Modifier.testTag("ref-approved"))
        GlassButton(My.breakSyllables(tr(UiText.UNDO_APPROVAL)), { model.approveRef(row.number, false) }, Modifier.testTag("ref-approve"),
            icon = AfIcons.Regular.ArrowCounterClockwise, small = true)
    } else {
        val chosen = row.refs.indexOf(row.chosenRef).takeIf { it >= 0 }?.let(ReferencesModel::refLabel)
        val label = chosen?.let { tr(UiText.V3_REF_APPROVE_NAMED, "ref" to it) } ?: tr(UiText.REF_APPROVE_BUTTON)
        GlassButton(My.breakSyllables(label), { model.approveRef(row.number, true) }, Modifier.testTag("ref-approve"), ButtonKind.Primary,
            enabled = row.chosenRef != null, icon = AfIcons.Regular.Check, small = true)
    }
}

/**
 * One reference image (sample `refTile`): the 128 × 72 picture (radius 10) in a radius-13 frame (White .12 line, padding 3);
 * the chosen one = Light purple .12 fill + 2 dp ring and a Fill `check-circle` badge; the label under it (chosen = purple
 * pill with `check`, else Muted). The whole tile picks it (`ref-pick-A` …, a radio button).
 */
@Composable
private fun SheetRefTile(label: String, id: ArtifactId, chosen: Boolean, model: ReferencesModel, onPick: () -> Unit) {
    val image by produceState<ImageBitmap?>(null, id) { value = model.refImage(id)?.let(::decodeImage) }
    val frame = RoundedCornerShape(13.dp)
    Column(Modifier.selectable(chosen, onClick = onPick, role = Role.RadioButton).testTag("ref-pick-${label.removePrefix("Ref ")}"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.clip(frame).background(if (chosen) GlassColors.RefChosenFill else Color.Transparent)
            .border(if (chosen) 2.dp else 1.dp, if (chosen) GlassColors.ShotNeedsFlow else GlassColors.RefTileBorder, frame).padding(3.dp)) {
            Thumb(image, label, Modifier)
            if (chosen) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(24.dp).clip(CircleShape).background(GlassColors.ThumbBadgeFill),
                contentAlignment = Alignment.Center) { AfIcon(AfIcons.Fill.CheckCircle, tint = GlassColors.ShotNeedsFlow, size = 20.dp) }
        }
        if (chosen) TonePill(GlassColors.ShotNeedsFlow, AfIcons.Regular.Check, label)
        else AfText(label, color = AutoFlowColors.Muted, type = AfType.Micro)
    }
}

/** The 128 × 72 thumbnail (radius 10, black, White .08 line): the picture, or [placeholder] (Image) while it loads; videos show only the play mark. */
@Composable
private fun Thumb(image: ImageBitmap?, description: String, modifier: Modifier, placeholder: UiText? = UiText.IMAGE, overlay: @Composable () -> Unit = {}) {
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.size(width = THUMB_W.dp, height = THUMB_H.dp).clip(shape).background(Color.Black).border(1.dp, GlassColors.ThumbBorder, shape),
        contentAlignment = Alignment.Center) {
        if (image != null) Image(image, contentDescription = description, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else placeholder?.let { AfText(tr(it), color = AutoFlowColors.Muted, type = AfType.Micro) }
        overlay()
    }
}

/**
 * Flow handoff (sample 07 / 08 `flowCard`): the state pills (Needs Flow `arrow-square-out` light purple · Imported
 * `download-simple` Amber · Accepted `check-circle` Green); the reference + video tiles (128 × 72, caption below; none = a
 * dashed tile with the Duotone icon; a video = the play mark); the gate = amber lock callout; the first 200 characters of
 * the prompt in a mono quote box; PASS_LABEL + the Draft (Fast) `lightning` / Final (Quality) `diamond` segmented control;
 * In batch sheet = a glass chip with `check-square` / `square`; Import video `upload-simple` (primary until a video is in) ·
 * Accept video primary `check-circle` · Log credits `coins`; the credit question box; Credits logged = a `coins` line.
 */
@Composable
internal fun SheetHandoff(row: HandoffRow, model: FlowHandoffModel, importVideo: () -> Unit, fast: Int, quality: Int, noPromptText: String) {
    var showCredits by remember(row.number) { mutableStateOf(false) }
    var creditInput by remember(row.number) { mutableStateOf("") }
    val language = LocalUiLanguage.current

    val pills = handoffBadges(row.state, row.accepted)
    if (pills.isNotEmpty()) WrapRow(spacing = 6.dp) { pills.forEach { FlowStatePill(it) } }

    WrapRow(spacing = 10.dp) {
        val ref by produceState<ImageBitmap?>(null, row.refImage) { value = row.refImage?.let { model.refThumbnail(row.number)?.let(::decodeImage) } }
        MediaTile(refThumbLabel(row, language), ref, row.refImage != null, AfIcons.Duotone.Image, video = false, tag = "sheet-flow-ref-tile",
            description = tr(UiText.REFERENCE_SHOT_DESCRIPTION, "number" to row.number),
            onClick = if (row.refImage != null) ({ model.openRefImage(row.number) }) else null)
        val video by produceState<ImageBitmap?>(null, row.shownVideo) { value = row.shownVideo?.let { model.videoThumbnail(row.number)?.let(::decodeImage) } }
        MediaTile(videoThumbLabel(row, language), video, row.shownVideo != null, AfIcons.Duotone.FileVideo, video = true, tag = "sheet-flow-video-tile",
            description = tr(UiText.VIDEO_SHOT_DESCRIPTION, "number" to row.number),
            onClick = if (row.shownVideo != null) ({ model.playVideo(row.number) }) else null)
    }

    row.gateMsg?.let {
        GlassCallout(AfIcons.Fill.Lock, My.breakSyllables(row.blocker?.text(language) ?: it), AutoFlowColors.Amber, Modifier.testTag("sheet-flow-locked"))
    }

    row.promptText?.let { prompt ->
        QuoteBox(Modifier.padding(horizontal = 12.dp, vertical = 10.dp).testTag("sheet-flow-prompt")) {
            AfText(prompt.take(200) + if (prompt.length > 200) "…" else "", color = AutoFlowColors.Muted, size = 11.5.sp, mono = true)
        }
    } ?: AfText(My.breakSyllables(noPromptText), Modifier.testTag("sheet-flow-no-prompt"), color = AutoFlowColors.Muted, type = AfType.Caption)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AfText(My.breakSyllables(tr(UiText.PASS_LABEL)), color = AutoFlowColors.Muted, type = AfType.Micro, weight = FontWeight.Normal)
        val on = Pass.entries.indexOf(row.pass)
        GlassSegmented(Pass.entries.mapIndexed { i, p ->
            val draft = p == Pass.DRAFT
            GlassSegment(tr(if (draft) UiText.PASS_DRAFT else UiText.PASS_FINAL), "sheet-flow-pass-${p.name}",
                icon = if (draft) (if (i == on) AfIcons.Fill.Lightning else AfIcons.Regular.Lightning) else (if (i == on) AfIcons.Fill.Diamond else AfIcons.Regular.Diamond))
        }, on, { model.setPass(row.number, Pass.entries[it]) }, Modifier.testTag("sheet-flow-pass"))
    }

    if (row.batchable) {
        GlassFilterChip(My.breakSyllables(tr(UiText.IN_BATCH)), row.inBatch, { model.toggleBatch(row.number) }, Modifier.testTag("sheet-flow-batch"),
            leading = { ink -> AfIcon(if (row.inBatch) AfIcons.Fill.CheckSquare else AfIcons.Regular.Square, tint = ink, size = 15.dp) })
    }

    WrapRow {
        GlassButton(My.breakSyllables(tr(UiText.IMPORT_VIDEO)), importVideo, Modifier.testTag("sheet-flow-import"),
            if (row.videoCount == 0) ButtonKind.Primary else ButtonKind.Secondary, enabled = !row.accepted, icon = AfIcons.Regular.Upload, small = true)
        if (row.videoCount > 0 && !row.accepted) GlassButton(My.breakSyllables(tr(UiText.ACCEPT_VIDEO)), { model.acceptVideo(row.number) },
            Modifier.testTag("sheet-flow-accept"), ButtonKind.Primary, icon = AfIcons.Regular.CheckCircle, small = true)
        if (row.videoCount > 0 && !row.askCredits && !showCredits) GlassButton(My.breakSyllables(tr(UiText.LOG_CREDITS)), { showCredits = true },
            Modifier.testTag("sheet-flow-log-credits"), icon = AfIcons.Regular.Coins, small = true)
    }

    if (row.askCredits || showCredits) {
        CreditQuestionBox(row, model, creditInput, { creditInput = it }, fast, quality) {
            showCredits = false
            creditInput = ""
        }
    } else if (row.creditsLogged > 0) {
        Row(Modifier.testTag("sheet-flow-credits-logged"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfIcon(AfIcons.Regular.Coins, Modifier.padding(top = 1.dp), tint = AutoFlowColors.Muted, size = 16.dp)
            AfText(My.breakSyllables(tr(UiText.CREDITS_LOGGED, "creditsLogged" to row.creditsLogged)), color = AutoFlowColors.Muted, type = AfType.Caption)
        }
    }
}

/** A handoff state pill (sample `pill9`): the shot status pill's tones and icons for Needs Flow / Imported / Accepted. */
@Composable
private fun FlowStatePill(state: HandoffState) {
    val (tone, icon, text) = when (state) {
        HandoffState.NONE -> return
        HandoffState.SENT_TO_FLOW -> Triple(GlassColors.ShotNeedsFlow, AfIcons.Regular.ArrowSquareOut, UiText.NEEDS_FLOW)
        HandoffState.IMPORTED -> Triple(AutoFlowColors.Amber, AfIcons.Regular.DownloadSimple, UiText.IMPORTED)
        HandoffState.ACCEPTED -> Triple(AutoFlowColors.Green, AfIcons.Fill.CheckCircle, UiText.ACCEPTED)
    }
    TonePill(tone, icon, tr(text), Modifier.testTag("sheet-flow-state-${state.name}"))
}

/**
 * A labelled media tile (sample `mediaTile`): the 128 × 72 thumbnail (tap opens the file) or, with nothing yet, a dashed
 * tile with the Duotone [empty] icon; videos get the play mark (28 dp dark circle, White .45 ring, Fill `play`); the
 * caption (Muted, max 128 dp) under it.
 */
@Composable
private fun MediaTile(label: String, image: ImageBitmap?, present: Boolean, empty: ImageVector, video: Boolean, tag: String, description: String,
    onClick: (() -> Unit)?) {
    Column(Modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (present) Thumb(image, description, onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier, if (video) null else UiText.IMAGE) {
            if (video) Box(Modifier.size(28.dp).clip(CircleShape).background(GlassColors.PlayMarkFill).border(1.dp, GlassColors.PlayMarkRing, CircleShape),
                contentAlignment = Alignment.Center) { AfIcon(AfIcons.Fill.Play, tint = Color.White, size = 14.dp) }
        } else Box(Modifier.size(width = THUMB_W.dp, height = THUMB_H.dp).glassDashedBox(10.dp), contentAlignment = Alignment.Center) {
            AfIcon(empty, tint = AutoFlowColors.Faint, size = 24.dp)
        }
        AfText(My.breakSyllables(label), Modifier.widthIn(max = THUMB_W.dp), color = AutoFlowColors.Muted, type = AfType.Micro, weight = FontWeight.Normal)
    }
}

/**
 * The credit question (sample `creditQ`, stays inline): a glass box with the `coins` tile (Amber) and CREDITS_QUESTION; two
 * option chips "20 · Fast" `lightning` / "100 · Quality" `diamond` (the suggested one selected, with a check; a tap logs it);
 * CUSTOM_CREDITS above a mono number field, Save, Skip. Same calls as before.
 */
@Composable
private fun CreditQuestionBox(row: HandoffRow, model: FlowHandoffModel, creditInput: String, onCreditChange: (String) -> Unit, fast: Int, quality: Int,
    onDone: () -> Unit) {
    InnerGlassCard(My.breakSyllables(tr(UiText.CREDITS_QUESTION)), CardTile(AfIcons.Fill.Coins, AutoFlowColors.Amber), Modifier.testTag("sheet-credit-question")) {
        WrapRow {
            CreditOption(tr(UiText.V3_CREDITS_FAST, "n" to fast), AfIcons.Fill.Lightning, row.creditDefault == fast, "credit-fast") {
                model.logCredits(row.number, fast); onDone()
            }
            CreditOption(tr(UiText.V3_CREDITS_QUALITY, "n" to quality), AfIcons.Fill.Diamond, row.creditDefault == quality && fast != quality, "credit-quality") {
                model.logCredits(row.number, quality); onDone()
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AfText(My.breakSyllables(tr(UiText.CUSTOM_CREDITS)), color = AutoFlowColors.Muted, type = AfType.Caption)
            WrapRow {
                AfTextField(creditInput, { v -> onCreditChange(v.filter { it.isDigit() }.take(5)) }, tr(UiText.CUSTOM_CREDITS),
                    Modifier.width(96.dp).testTag("credit-custom"), glass = true, showPlaceholder = false)
                GlassButton(My.breakSyllables(tr(UiText.SAVE)), { creditInput.toIntOrNull()?.let { model.logCredits(row.number, it) }; onDone() },
                    Modifier.testTag("credit-save"), enabled = creditInput.toIntOrNull() != null, small = true)
                GlassButton(My.breakSyllables(tr(UiText.SKIP_CREDITS)), { model.skipCredits(row.number); onDone() }, Modifier.testTag("credit-skip"), small = true)
            }
        }
    }
}

/** A credit option chip (sample `optChip`): the glass filter chip; off = the Amber Fill icon, on = a white check. */
@Composable
private fun CreditOption(text: String, icon: ImageVector, on: Boolean, tag: String, onClick: () -> Unit) =
    GlassFilterChip(text, on, onClick, Modifier.testTag(tag),
        leading = { ink -> AfIcon(if (on) AfIcons.Regular.Check else icon, tint = if (on) ink else AutoFlowColors.Amber, size = 14.dp) })
