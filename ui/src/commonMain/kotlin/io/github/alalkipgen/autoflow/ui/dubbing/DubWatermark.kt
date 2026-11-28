package io.github.alalkipgen.autoflow.ui.dubbing

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.dubbing.DubMarks
import io.github.alalkipgen.autoflow.core.model.DubWatermark
import io.github.alalkipgen.autoflow.core.recap.WatermarkAnchor
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.recap.RecapIconChip
import io.github.alalkipgen.autoflow.ui.recap.RecapTiles
import io.github.alalkipgen.autoflow.ui.recap.WatermarkWidth
import io.github.alalkipgen.autoflow.ui.recap.icon
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/**
 * The watermark / logo on the dub (D-108, specs/dubbing.md §2 Style), Movie Recap's Watermark card: Set watermark…
 * (an image chosen here) or a Brand Kit's watermark / logo image (only the image — the kit is not applied), then
 * Change… / Remove, its width (Small / Medium / Large / custom % of the frame) and its place (four corners, centre).
 * Over the boxes, under the Burmese subtitles; saved in the project.
 */
@Composable
internal fun DubWatermarkCard(model: DubExportModel, open: OpenProject, idle: Boolean, notice: UiNotice?, ok: Boolean) {
    val mark = model.watermark(open)
    val kits = remember(open.dir, open.project) { model.kitImages() }
    val language = LocalUiLanguage.current
    TileCard(My.breakSyllables(tr(UiText.WATERMARK_CARD)), RecapTiles.Watermark, Modifier.testTag("dub-style-watermark")) {
        AfText(My.breakSyllables(tr(UiText.DUB_WATERMARK_HINT)), muted = true, type = AfType.Caption)
        if (mark != null) {
            val file = mark.file.substringAfterLast('/')
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AfIcon(AfIcons.Regular.Image, tint = AutoFlowColors.Muted, size = 17.dp)
                AfText(file, Modifier.weight(1f).semantics { contentDescription = file }.testTag("dub-watermark-file"), type = AfType.Body,
                    maxLines = 1, ellipsis = true)
            }
        }
        WrapRow {
            if (mark == null) GlassButton(My.breakSyllables(tr(UiText.SET_WATERMARK)), { if (idle) model.importWatermark() },
                Modifier.testTag("dub-watermark-set"), enabled = idle, icon = AfIcons.Regular.Upload, small = true)
            else {
                GlassButton(My.breakSyllables(tr(UiText.CHANGE)), { if (idle) model.importWatermark() }, Modifier.testTag("dub-watermark-change"),
                    enabled = idle, icon = AfIcons.Regular.ArrowsClockwise, small = true)
                GlassButton(My.breakSyllables(tr(UiText.REMOVE)), { if (idle) model.removeWatermark() }, Modifier.testTag("dub-watermark-remove"),
                    kind = ButtonKind.Danger, enabled = idle, icon = AfIcons.Regular.Close, small = true)
            }
        }
        if (kits.isNotEmpty()) InsetOptions(Modifier.testTag("dub-watermark-kits")) {
            DubOptionLabel(tr(UiText.DUB_WATERMARK_FROM_KIT))
            WrapRow {
                kits.forEach { k ->
                    val label = tr(if (k.logo) UiText.DUB_KIT_LOGO else UiText.DUB_KIT_WATERMARK, "name" to k.name)
                    GlassButton(My.breakSyllables(label), { if (idle) model.useKitImage(k.kitId, k.logo) },
                        Modifier.testTag("dub-watermark-kit-${k.kitId}-${if (k.logo) "logo" else "mark"}"), enabled = idle, icon = AfIcons.Regular.Image, small = true)
                }
            }
        }
        if (mark != null) {
            InsetOptions(Modifier.testTag("dub-watermark-options")) {
                WatermarkWidth(mark.widthPercent, idle, glass = true) { model.setWatermarkWidth(it) }
                DubOptionLabel(tr(UiText.POSITION))
                WrapRow {
                    WatermarkAnchor.entries.forEach { a ->
                        RecapIconChip(a.uiLabel(language), a.icon(), mark.anchor == a, { if (idle) model.setWatermarkAnchor(a) },
                            Modifier.testTag("dub-watermark-at-${a.name}"), enabled = idle)
                    }
                }
            }
        }
        notice?.let {
            val tone = if (ok) AutoFlowColors.Green else AutoFlowColors.Amber
            GlassCallout(if (ok) AfIcons.Fill.CheckCircle else AfIcons.Fill.Warn, My.breakSyllables(it.text(language)), tone,
                textModifier = Modifier.testTag("dub-watermark-notice"))
        }
    }
}

/**
 * The watermark on the preview where the export puts it (D-108): [DubMarks.widthPx] of the picture's width, the image's
 * own aspect, [DubMarks.PAD] from the edges at its anchor, at [DubMarks.OPACITY].
 */
@Composable
internal fun DubWatermarkImage(mark: DubWatermark, image: ImageBitmap, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.testTag("dub-preview-watermark")) {
        val w = DubMarks.width(mark.widthPercent) / 100f
        val h = if (image.width > 0 && maxHeight.value > 0f) w * maxWidth.value * image.height / image.width / maxHeight.value else 0f
        val (x, y) = DubMarks.origin(mark.anchor, w, h)
        Image(image, null, Modifier.offset(maxWidth * x, maxHeight * y).size(maxWidth * w, maxHeight * h), alpha = DubMarks.OPACITY,
            contentScale = ContentScale.FillBounds)
    }
}
