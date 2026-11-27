package io.github.alalkipgen.autoflow.ui.shotsheet

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.model.Pass
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.GlassButton
import io.github.alalkipgen.autoflow.ui.components.GlassCallout
import io.github.alalkipgen.autoflow.ui.components.GlassCard
import io.github.alalkipgen.autoflow.ui.components.SoftTile
import io.github.alalkipgen.autoflow.ui.components.VideoPreview
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.components.decodeImage
import io.github.alalkipgen.autoflow.ui.components.glassDashedBox
import io.github.alalkipgen.autoflow.ui.components.paneScroll
import io.github.alalkipgen.autoflow.ui.export.VideoShape
import io.github.alalkipgen.autoflow.ui.handoff.FlowHandoffModel
import io.github.alalkipgen.autoflow.ui.handoff.HandoffRow
import io.github.alalkipgen.autoflow.ui.handoff.refThumbLabel
import io.github.alalkipgen.autoflow.ui.handoff.videoThumbLabel
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.shots.ShotDetail
import io.github.alalkipgen.autoflow.ui.shots.ShotsModel
import io.github.alalkipgen.autoflow.ui.shots.TonePill
import io.github.alalkipgen.autoflow.ui.shots.VideoVersion
import io.github.alalkipgen.autoflow.ui.shots.passLabel
import io.github.alalkipgen.autoflow.ui.shots.versionsTitle
import io.github.alalkipgen.autoflow.ui.storyboard.text
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.GlassColors

/*
 * Group 3 D inner 5 and E (samples 07 / 08 / 10): the shot sheet's Videos step body and the desktop media pane in the glass
 * look. Same model calls as the classic `VersionBrowser` (preview, use this version) and the classic Flow handoff thumbnails
 * (open the reference image, play the video); the classic Shots screen keeps `VersionBrowser`.
 */

/**
 * Videos · Accept (sample 07 / 08 `videosCard`): no version = the dashed box with the Duotone `file-video` (Green) and
 * SHEET_NO_VIDEO; else VIDEO_VERSION_COUNT (13 sp bold), the player of the previewed version (radius 12, soft shadow, as
 * on Export) and one row per version, newest first ([VersionRow]); an accepted Draft = the amber `lightning` callout.
 */
@Composable
internal fun SheetVideos(detail: ShotDetail?, model: ShotsModel) {
    if (detail == null || detail.versions.isEmpty()) {
        Column(Modifier.fillMaxWidth().glassDashedBox().padding(horizontal = 16.dp, vertical = 18.dp).testTag("sheet-videos-empty"),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AfIcon(AfIcons.Duotone.FileVideo, tint = AutoFlowColors.Green, size = 30.dp)
            AfText(My.breakSyllables(tr(UiText.SHEET_NO_VIDEO)), color = AutoFlowColors.Muted, type = AfType.Caption, textAlign = TextAlign.Center)
        }
        return
    }
    AfText(My.breakSyllables(versionsTitle(detail.versions.size, LocalUiLanguage.current)), Modifier.testTag("sheet-videos-title"),
        size = 13.sp, bold = true)
    detail.versions.firstOrNull { it.id == detail.previewing }?.path?.let { path ->
        key(path) { VideoPreview(path, Modifier.shadow(8.dp, VideoShape).clip(VideoShape).testTag("sheet-video-player")) }
    }
    detail.versions.asReversed().forEach { v -> VersionRow(v, v.id == detail.previewing, detail.number, model) }
    if (detail.versions.firstOrNull { it.accepted }?.pass == Pass.DRAFT) {
        GlassCallout(AfIcons.Fill.Lightning, My.breakSyllables(tr(UiText.DRAFT_ACCEPTED_HINT)), AutoFlowColors.Amber, Modifier.testTag("sheet-draft-hint"))
    }
}

/**
 * One version (sample `verRow`, the `.srow` look): the previewed one Purple .12 + .55 line, else White .04 + .09; the mono
 * "v2" tag, the pass pill (Final (Quality) `diamond` light purple · Draft (Fast) `lightning` Muted), the green Accepted pill;
 * the import date (mono, Muted, 11.5 sp); Preview `play` (not on the previewed row) · Use this version `check` (primary on
 * the previewed row, not on the accepted one). Tags `sheet-version-n`, `sheet-version-preview-n`, `sheet-version-use-n`.
 */
@Composable
private fun VersionRow(v: VideoVersion, now: Boolean, shot: Int, model: ShotsModel) {
    val shape = RoundedCornerShape(14.dp)
    val language = LocalUiLanguage.current
    Column(Modifier.fillMaxWidth().clip(shape).background(if (now) GlassColors.ShotRowSelectedFill else GlassColors.ShotRowFill)
        .border(1.dp, if (now) GlassColors.ShotRowSelectedBorder else GlassColors.ShotRowBorder, shape).padding(8.dp).testTag("sheet-version-${v.version}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WrapRow(spacing = 6.dp) {
            val tag = RoundedCornerShape(7.dp)
            Box(Modifier.heightIn(min = 24.dp).clip(tag).background(GlassColors.VersionTagFill).border(1.dp, GlassColors.VersionTagBorder, tag)
                .padding(horizontal = 8.dp, vertical = 1.dp), contentAlignment = Alignment.Center) {
                AfText("v${v.version}", color = AutoFlowColors.Text, size = 12.5.sp, mono = true)
            }
            v.pass?.let { p ->
                val final = p == Pass.FINAL
                TonePill(if (final) GlassColors.ShotNeedsFlow else AutoFlowColors.Muted, if (final) AfIcons.Fill.Diamond else AfIcons.Fill.Lightning,
                    passLabel(p, language), Modifier.testTag("sheet-version-pass-${v.version}"))
            }
            if (v.accepted) TonePill(AutoFlowColors.Green, AfIcons.Fill.CheckCircle, tr(UiText.ACCEPTED), Modifier.testTag("sheet-version-accepted-${v.version}"))
        }
        if (v.importedAt.isNotBlank()) AfText(v.importedAt, color = AutoFlowColors.Muted, size = 11.5.sp, mono = true)
        val preview = !now && v.path != null
        if (preview || !v.accepted) WrapRow {
            if (preview) GlassButton(My.breakSyllables(tr(UiText.PREVIEW_VERSION)), { model.preview(shot, v.id) },
                Modifier.testTag("sheet-version-preview-${v.version}"), icon = AfIcons.Fill.Play, small = true)
            if (!v.accepted) GlassButton(My.breakSyllables(tr(UiText.USE_VIDEO_VERSION)), { model.acceptVersion(shot, v.id) },
                Modifier.testTag("sheet-version-use-${v.version}"), if (now) ButtonKind.Primary else ButtonKind.Secondary,
                icon = AfIcons.Regular.Check, small = true)
        }
    }
}

/**
 * The desktop media pane (≥ 840 dp, sample 10 `deskMedia`): a glass card tinted Cyan .14 with the `film-strip` tile and
 * SHEET_MEDIA_SHOT; the reference image and the shown video at the pane's width (16:9, radius 12, the picture fitted;
 * nothing yet = a dashed frame with the Duotone icon), each under its existing label; the video has the big play mark. A tap
 * opens the image / plays the video (the same model calls as the Flow handoff tiles). Under the card: `keyboard` and the
 * shortcuts hint (tag `shortcuts-hint`).
 */
@Composable
internal fun SheetMediaPane(number: Int, row: HandoffRow?, model: FlowHandoffModel) {
    val language = LocalUiLanguage.current
    Column(Modifier.fillMaxSize().paneScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCard(Modifier.fillMaxWidth().testTag("sheet-media"), tint = AutoFlowColors.Cyan, tintAlpha = GlassColors.TintMediaPane, gap = 10.dp) {
            Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftTile(AfIcons.Fill.FilmStrip, AutoFlowColors.Cyan, size = 30.dp)
                AfText(My.breakSyllables(tr(UiText.SHEET_MEDIA_SHOT, "number" to number)), Modifier.weight(1f).testTag("sheet-media-title"),
                    size = 14.sp, bold = true)
            }
            if (row != null) {
                val ref by produceState<ImageBitmap?>(null, row.refImage) { value = row.refImage?.let { model.refThumbnail(row.number)?.let(::decodeImage) } }
                PaneMedia(refThumbLabel(row, language), ref, row.refImage != null, AfIcons.Duotone.Image, video = false, tag = "sheet-media-ref",
                    description = tr(UiText.REFERENCE_SHOT_DESCRIPTION, "number" to row.number),
                    onClick = if (row.refImage != null) ({ model.openRefImage(row.number) }) else null)
                val video by produceState<ImageBitmap?>(null, row.shownVideo) { value = row.shownVideo?.let { model.videoThumbnail(row.number)?.let(::decodeImage) } }
                PaneMedia(videoThumbLabel(row, language), video, row.shownVideo != null, AfIcons.Duotone.FileVideo, video = true, tag = "sheet-media-video",
                    description = tr(UiText.VIDEO_SHOT_DESCRIPTION, "number" to row.number),
                    onClick = if (row.shownVideo != null) ({ model.playVideo(row.number) }) else null)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfIcon(AfIcons.Regular.Keyboard, Modifier.padding(top = 2.dp), tint = AutoFlowColors.Muted, size = 16.dp)
            AfText(My.breakSyllables(tr(UiText.V3_MV_SHORTCUTS_HINT)), Modifier.weight(1f).testTag("shortcuts-hint"), color = AutoFlowColors.Muted,
                type = AfType.Caption)
        }
    }
}

/** One labelled 16:9 frame of the media pane (sample `.vid`): black, radius 12, White .08 line, a soft shadow. */
@Composable
private fun PaneMedia(label: String, image: ImageBitmap?, present: Boolean, empty: ImageVector, video: Boolean, tag: String, description: String,
    onClick: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AfText(My.breakSyllables(label), color = AutoFlowColors.Muted, type = AfType.Micro, weight = FontWeight.Normal)
        if (present) Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).shadow(6.dp, VideoShape).clip(VideoShape).background(Color.Black)
            .border(1.dp, GlassColors.ThumbBorder, VideoShape)
            .then(onClick?.let { Modifier.clickable(role = Role.Button, onClick = it) } ?: Modifier)
            .semantics { contentDescription = description }.testTag("$tag-frame"), contentAlignment = Alignment.Center) {
            if (image != null) Image(image, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else if (!video) AfText(tr(UiText.IMAGE), color = AutoFlowColors.Muted, type = AfType.Micro)
            if (video) Box(Modifier.size(54.dp).clip(CircleShape).background(GlassColors.BigPlayFill).border(1.5.dp, GlassColors.BigPlayRing, CircleShape),
                contentAlignment = Alignment.Center) { AfIcon(AfIcons.Fill.Play, tint = Color.White, size = 24.dp) }
        } else Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).glassDashedBox(12.dp).testTag("$tag-frame"), contentAlignment = Alignment.Center) {
            AfIcon(empty, tint = AutoFlowColors.Faint, size = 32.dp)
        }
    }
}
