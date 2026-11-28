package io.github.alalkipgen.autoflow.ui.dubbing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.dubbing.DubMasks
import io.github.alalkipgen.autoflow.core.model.DubMask
import io.github.alalkipgen.autoflow.core.model.DubMaskKind
import io.github.alalkipgen.autoflow.core.recap.MaskStrength
import io.github.alalkipgen.autoflow.core.recap.PreviewGeometry
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.recap.InsetGlassBox
import io.github.alalkipgen.autoflow.ui.recap.RecapSwatchChip
import io.github.alalkipgen.autoflow.ui.recap.RecapTiles
import io.github.alalkipgen.autoflow.ui.recap.strengthLabel
import io.github.alalkipgen.autoflow.ui.subtitles.overlayColour
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/**
 * Hide things on the video (D-107, specs/dubbing.md §2 Style): blur or colour boxes over the video's own subtitles (the
 * bottom / top strip), its logo (a corner) or anything else (Add a box, as many as needed). Each box: Blur or Colour
 * (Black / Grey / White), nudges, Taller / Shorter (and Wider / Narrower, sideways moves off the strips), Remove; on the
 * preview a box is picked with a touch, dragged and pinched (the mouse wheel on a computer). One blur strength for all
 * blurred boxes; Put my captions on the bottom box when the Burmese subtitles are on. Saved in the project.
 */
@Composable
internal fun DubHideCard(model: DubExportModel, open: OpenProject, idle: Boolean, picked: Int?, burn: Boolean) {
    val tool = open.project.dubbing
    val masks = tool?.masks.orEmpty()
    val language = LocalUiLanguage.current
    TileCard(My.breakSyllables(tr(UiText.DUB_HIDE_CARD)), RecapTiles.HideThings, Modifier.testTag("dub-style-hide")) {
        AfText(My.breakSyllables(tr(UiText.DUB_HIDE_HINT)), muted = true, type = AfType.Caption)
        WrapRow {
            DubMaskKind.entries.filter { it != DubMaskKind.CUSTOM }.forEach { k ->
                DubOptionChip(dubMaskLabel(k, language), masks.any { it.kind == k }, { model.toggleBox(k) }, "dub-hide-${k.name}", idle)
            }
        }
        GlassButton(My.breakSyllables(tr(UiText.DUB_ADD_BOX)), { model.toggleBox(DubMaskKind.CUSTOM) }, Modifier.testTag("dub-hide-add"),
            enabled = idle, icon = AfIcons.Regular.Plus, small = true)
        if (masks.isEmpty()) return@TileCard
        if (masks.any { it.fill == null }) InsetOptions(Modifier.testTag("dub-hide-strength")) {
            DubOptionLabel(tr(UiText.BLUR_STRENGTH))
            WrapRow {
                MaskStrength.entries.forEach { s ->
                    DubOptionChip(strengthLabel(s, language), (tool?.blurStrength ?: MaskStrength.STRONG) == s, { model.setBlurStrength(s) },
                        "dub-hide-strength-${s.name}", idle)
                }
            }
        }
        masks.forEachIndexed { i, m -> DubBoxOptions(model, masks, i, m, idle, picked == i) }
        val touch = picked?.takeIf { it in masks.indices }?.let { tr(UiText.MASK_GESTURE_HINT, "maskLabelp" to boxLabel(masks, it, language)) }
            ?: tr(UiText.MASK_TOUCH_HINT)
        GlassFact(AfIcons.Regular.HandTap, My.breakSyllables(touch), colour = AutoFlowColors.Muted, caption = true,
            textModifier = Modifier.testTag("dub-hide-touch"))
        if (burn && masks.any { it.centerY >= .5f }) GlassSwitchRow(My.breakSyllables(tr(UiText.CAPTIONS_ON_BOX)), tool?.captionsOnBox ?: true,
            { model.setCaptionsOnBox(it) }, Modifier.testTag("dub-hide-captions-on-box"), enabled = idle)
    }
}

/** One box's choices (an inset box, cyan-edged while picked): its name and Remove, Blur / Colour, then the moves. */
@Composable
private fun DubBoxOptions(model: DubExportModel, masks: List<DubMask>, i: Int, m: DubMask, idle: Boolean, picked: Boolean) {
    val language = LocalUiLanguage.current
    val step = DubMasks.STEP
    fun act(f: () -> Unit): () -> Unit = { if (idle) { model.select(i); f() } }
    InsetGlassBox(Modifier.testTag("dub-box-$i"), line = if (picked) AutoFlowColors.Cyan else Color.White.copy(alpha = .08f)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfText(My.breakSyllables(boxLabel(masks, i, language)), Modifier.weight(1f), type = AfType.Caption)
            GlassIconButton(AfIcons.Regular.Trash, tr(UiText.REMOVE), { if (idle) model.removeBox(i) }, Modifier.testTag("dub-box-$i-remove"),
                enabled = idle, tone = AutoFlowColors.Failed)
        }
        WrapRow(spacing = 6.dp) {
            DubOptionChip(tr(UiText.DUB_BOX_BLUR), m.fill == null, act { model.fillBox(i, null) }, "dub-box-$i-blur", idle)
            DubOptionChip(tr(UiText.COLOUR), m.fill != null, act { if (m.fill == null) model.fillBox(i, DubMasks.FILL) }, "dub-box-$i-fill", idle)
        }
        if (m.fill != null) WrapRow(spacing = 6.dp) {
            DubMasks.FILLS.forEach { (name, hex) ->
                RecapSwatchChip(colourPresetLabel(name), overlayColour(hex), m.fill.equals(hex, ignoreCase = true), act { model.fillBox(i, hex) },
                    Modifier.testTag("dub-box-$i-fill-$name"), enabled = idle)
            }
        }
        WrapRow(spacing = 6.dp) {
            GlassIconButton(AfIcons.Regular.CaretUp, tr(UiText.MOVE_UP), act { model.moveBox(i, 0f, -step) }, Modifier.testTag("dub-box-$i-up"), enabled = idle)
            GlassIconButton(AfIcons.Regular.CaretDown, tr(UiText.MOVE_DOWN), act { model.moveBox(i, 0f, step) }, Modifier.testTag("dub-box-$i-down"), enabled = idle)
            if (!m.kind.isStrip) {
                GlassIconButton(AfIcons.Regular.CaretLeft, tr(UiText.MOVE_LEFT), act { model.moveBox(i, -step, 0f) }, Modifier.testTag("dub-box-$i-left"), enabled = idle)
                GlassIconButton(AfIcons.Regular.CaretRight, tr(UiText.MOVE_RIGHT), act { model.moveBox(i, step, 0f) }, Modifier.testTag("dub-box-$i-right"), enabled = idle)
            }
            GlassButton(My.breakSyllables(tr(UiText.TALLER)), act { model.resizeBox(i, 0f, 2 * step) }, Modifier.testTag("dub-box-$i-taller"), enabled = idle, small = true)
            GlassButton(My.breakSyllables(tr(UiText.SHORTER)), act { model.resizeBox(i, 0f, -2 * step) }, Modifier.testTag("dub-box-$i-shorter"), enabled = idle, small = true)
            if (!m.kind.isStrip) {
                GlassButton(My.breakSyllables(tr(UiText.WIDER)), act { model.resizeBox(i, 2 * step, 0f) }, Modifier.testTag("dub-box-$i-wider"), enabled = idle, small = true)
                GlassButton(My.breakSyllables(tr(UiText.NARROWER)), act { model.resizeBox(i, -2 * step, 0f) }, Modifier.testTag("dub-box-$i-narrower"), enabled = idle, small = true)
            }
        }
    }
}

/** A strip / corner by its place; a custom box by its number among the custom boxes ("Box 2"). */
internal fun boxLabel(masks: List<DubMask>, index: Int, language: UiLanguage): String {
    val m = masks[index]
    return if (m.kind != DubMaskKind.CUSTOM) dubMaskLabel(m.kind, language)
    else UiText.DUB_BOX_N.text(language, "n" to masks.take(index + 1).count { it.kind == DubMaskKind.CUSTOM })
}

internal fun dubMaskLabel(k: DubMaskKind, language: UiLanguage): String = when (k) {
    DubMaskKind.BOTTOM -> UiText.MASK_BOTTOM.text(language)
    DubMaskKind.TOP -> UiText.MASK_TOP.text(language)
    DubMaskKind.LOGO_TL -> UiText.MASK_LOGO_TL.text(language)
    DubMaskKind.LOGO_TR -> UiText.MASK_LOGO_TR.text(language)
    DubMaskKind.LOGO_BL -> UiText.MASK_LOGO_BL.text(language)
    DubMaskKind.LOGO_BR -> UiText.MASK_LOGO_BR.text(language)
    DubMaskKind.CUSTOM -> UiText.MASK_CUSTOM.text(language)
}

/**
 * The boxes on the preview picture, where the export puts them (0–1 of the picture): a colour box in its colour, a blur
 * box frosted (the preview does not blur the moving picture; Export does). While [edit] (Style) each box has a dashed
 * edge and the picked one a cyan edge; [draft] = the box under the fingers.
 */
@Composable
internal fun DubBoxes(masks: List<DubMask>, picked: Int?, draft: Pair<Int, List<Float>>?, edit: Boolean, modifier: Modifier = Modifier) {
    val description = "${masks.size}"
    Canvas(modifier.testTag("dub-preview-boxes").semantics { contentDescription = description }) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
        masks.forEachIndexed { i, m ->
            val r = draft?.takeIf { it.first == i }?.second ?: m.rect
            if (r.size != 4) return@forEachIndexed
            val topLeft = Offset(r[0] * size.width, r[1] * size.height)
            val box = Size(r[2] * size.width, r[3] * size.height)
            val fill = m.fill
            if (fill != null) drawRect(overlayColour(fill), topLeft, box)
            else {
                drawRect(Color(0xFF9AA3AD).copy(alpha = .62f), topLeft, box)
                drawRect(Color.White.copy(alpha = .10f), topLeft, box)
            }
            if (edit) {
                if (i == picked) drawRect(AutoFlowColors.Cyan, topLeft, box, style = Stroke(width = 3.dp.toPx()))
                else drawRect(Color.White.copy(alpha = .7f), topLeft, box, style = Stroke(width = 1.dp.toPx(), pathEffect = dash))
            }
        }
    }
}

/**
 * Style's box editing on the preview picture (as Movie Recap's mask preview): a touch on a box picks it (elsewhere =
 * none, and the touch goes on to the picture), a drag moves it, a pinch resizes it around the fingers, the mouse wheel
 * resizes the picked box; [onDraft] shows it under the fingers, saved when they lift. Seen before the picture's own
 * buttons (initial pass), so a box over the big Play still moves.
 */
@Composable
internal fun dubBoxEditing(model: DubExportModel, masks: List<DubMask>, onDraft: (Pair<Int, List<Float>>?) -> Unit): Modifier {
    val current by rememberUpdatedState(masks)
    val draft by rememberUpdatedState(onDraft)
    return Modifier.pointerInput(model) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val w = size.width.toFloat().coerceAtLeast(1f)
            val h = size.height.toFloat().coerceAtLeast(1f)
            val list = current
            val hit = PreviewGeometry.hit(list.map { it.rect }, down.position.x / w, down.position.y / h, slop = 0.02f)
            model.select(hit)
            if (hit == null) return@awaitEachGesture
            down.consume()
            var rect = list[hit].rect
            var moved = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                if (zoom != 1f || pan != Offset.Zero) {
                    val c = event.calculateCentroid(useCurrent = false).takeIf { it.isSpecified }
                        ?: Offset((rect[0] + rect[2] / 2) * w, (rect[1] + rect[3] / 2) * h)
                    rect = DubMasks.setRect(list, hit, PreviewGeometry.transform(rect, pan.x / w, pan.y / h, zoom, c.x / w, c.y / h, DubMasks.MIN_SIZE))[hit].rect
                    moved = true
                    draft(hit to rect)
                }
                event.changes.forEach { if (it.positionChanged() || it.pressed != it.previousPressed) it.consume() }
            } while (event.changes.any { it.pressed })
            if (moved) model.setBoxRect(hit, rect)
            draft(null)
        }
    }.pointerInput(model) {
        // Mouse wheel = pinch on a computer: resizes the picked box around its centre, saved at once.
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Scroll) continue
                val index = model.state.value.box ?: continue
                val rect = current.getOrNull(index)?.rect ?: continue
                val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (dy == 0f) continue
                val zoom = if (dy < 0) 1.1f else 1f / 1.1f
                model.setBoxRect(index, PreviewGeometry.transform(rect, 0f, 0f, zoom, rect[0] + rect[2] / 2, rect[1] + rect[3] / 2, DubMasks.MIN_SIZE))
                event.changes.forEach { it.consume() }
            }
        }
    }
}
