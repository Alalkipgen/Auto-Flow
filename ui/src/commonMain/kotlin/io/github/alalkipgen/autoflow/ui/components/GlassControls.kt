package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.ui.i18n.tr
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.icons.afTooltip
import io.github.alalkipgen.autoflow.ui.projects.label
import io.github.alalkipgen.autoflow.ui.theme.*
import kotlin.math.roundToInt

/*
 * Glass controls (Exact UI v4, docs/DESIGN.md §9.10) for the tab screens, beside AfCard / AfButton with the same
 * APIs: onClick, selected, attention, enabled and a 48 dp touch target. A control's 48 dp target overflows into the
 * spacing around it ([touchOverflow]), so the visual control sits where the design puts it.
 */

/**
 * Lays [this] out [horizontal] / [vertical] smaller on each side than it measures and draws it centred over that
 * space: a 48 dp touch target around a smaller visual control that reaches into the surrounding spacing instead of
 * pushing its neighbours away. Semantics and hit testing keep the full size.
 */
fun Modifier.touchOverflow(horizontal: Dp = 0.dp, vertical: Dp = 0.dp): Modifier = layout { measurable, constraints ->
    val h = horizontal.roundToPx(); val v = vertical.roundToPx()
    val placeable = measurable.measure(constraints.offset(2 * h, 2 * v))
    layout((placeable.width - 2 * h).coerceAtLeast(0), (placeable.height - 2 * v).coerceAtLeast(0)) { placeable.place(-h, -v) }
}

/**
 * A glass screen's scrolling column: [paneScroll] on the outer box and [padding] inside it, so the scroll viewport
 * includes the padding — the 48 dp targets of glass buttons overflow into it and still count as on screen (a viewport
 * that stops at the padding would see a header button as cut off, and scrolling to it could never finish).
 */
@Composable
fun GlassScrollColumn(modifier: Modifier, padding: PaddingValues, verticalArrangement: Arrangement.Vertical,
    state: ScrollState = rememberScrollState(), content: @Composable ColumnScope.() -> Unit) =
    Box(modifier.paneScroll(state)) {
        Column(Modifier.fillMaxWidth().padding(padding), verticalArrangement = verticalArrangement, content = content)
    }

/** Base + the five ambient blobs over this box (drawn once behind a screen's scroll; it does not scroll). */
fun Modifier.ambientBackground(): Modifier = drawBehind { drawAmbient() }

/** Dashed drop box (Group 3 B 1, sample 02): black .22 fill and a 1.5 dp White .18 dashed frame (6 / 5) at radius [radius]. */
fun Modifier.glassDashedBox(radius: Dp = 14.dp): Modifier = clip(RoundedCornerShape(radius)).background(GlassColors.PathFill)
    .drawBehind {
        val w = 1.5.dp.toPx()
        drawRoundRect(GlassColors.DashedBorder, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(radius.toPx() - w / 2),
            style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))))
    }

private fun DrawScope.drawAmbient() {
    drawRect(GlassColors.Base)
    GlassColors.Ambient.forEach { blob ->
        drawRadialEllipse(blob.colour, Offset(size.width * blob.x, size.height * blob.y), blob.radiusX.dp.toPx(), blob.radiusY.dp.toPx())
    }
}

/** A radial from [colour] at [centre] to transparent at [fade] of the [radiusX] × [radiusY] ellipse (CSS `radial-gradient`). */
internal fun DrawScope.drawRadialEllipse(colour: Color, centre: Offset, radiusX: Float, radiusY: Float, fade: Float = GlassColors.Fade) {
    if (radiusX <= 0f || radiusY <= 0f) return
    val k = radiusY / radiusX
    scale(1f, k, pivot = centre) {
        drawRect(Brush.radialGradient(0f to colour, fade to colour.copy(alpha = 0f), center = centre, radius = radiusX),
            topLeft = Offset(0f, centre.y - centre.y / k), size = Size(size.width, size.height / k))
    }
}

/** The tint of a glass surface: [colour] at top-start, an ellipse 120 % × 140 % of the surface, gone at 62 %. */
internal fun DrawScope.drawGlassTint(colour: Color) =
    drawRadialEllipse(colour, Offset.Zero, size.width * 1.2f, size.height * 1.4f)

/** A small copy (≤ [maxWidth] px wide) of a thumbnail for the blurred card tint. */
internal fun ImageBitmap.glassCopy(maxWidth: Int = 64): ImageBitmap {
    if (width <= maxWidth) return this
    val h = (height.toLong() * maxWidth / width).toInt().coerceAtLeast(1)
    val out = ImageBitmap(maxWidth, h)
    Canvas(out).drawImageRect(this, dstSize = IntSize(maxWidth, h), paint = Paint().apply { filterQuality = FilterQuality.Medium })
    return out
}

private val Saturate = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.5f) })

/**
 * The card's own frame behind its content: cropped into a box 120 % × 180 % of the card, scaled 1.1, blurred 26 dp,
 * at alpha .42 under a Base .15 → .65 horizontal overlay (.55 in the spec; raised on #286).
 */
@Composable
private fun GlassArt(image: ImageBitmap, modifier: Modifier) {
    val small = remember(image) { image.glassCopy() }
    Box(modifier.graphicsLayer { alpha = GlassColors.ThumbAlpha }) {
        Layout({
            Image(small, null, Modifier.blur(26.dp), contentScale = ContentScale.Crop,
                colorFilter = Saturate, filterQuality = FilterQuality.Medium)
        }, Modifier.matchParentSize()) { measurables, c ->
            val w = c.maxWidth; val h = c.maxHeight
            val aw = (w * 1.2f * 1.1f).roundToInt(); val ah = (h * 1.8f * 1.1f).roundToInt()
            val placeable = measurables.first().measure(Constraints.fixed(aw, ah))
            layout(w, h) { placeable.place((w - aw) / 2, (h - ah) / 2) }
        }
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(GlassColors.ThumbOverlayStart, GlassColors.ThumbOverlayEnd))))
    }
}

/** True when a [GlassCard] given [thumbnail] draws it as its art (Android 12+ and desktop; else the colour tint). */
private fun drawsArt(thumbnail: ImageBitmap?) = thumbnail != null && GlassBlurSupported

/**
 * The Muted (caption) colour for text on a [GlassCard] given the same [thumbnail]: [GlassColors.MutedOnArt] when the
 * card draws that frame, else [AutoFlowColors.Muted].
 */
fun glassCaptionColour(thumbnail: ImageBitmap?): Color = if (drawsArt(thumbnail)) GlassColors.MutedOnArt else AutoFlowColors.Muted

/** 1 dp glass border with the brighter top highlight. */
internal fun DrawScope.drawGlassBorder(shape: Shape, border: Color, highlight: Color, width: Dp = 1.dp) {
    val outline = shape.createOutline(size, layoutDirection, this)
    val stroke = width.toPx()
    val top = (3.dp.toPx() / size.height).coerceIn(0f, 1f)
    val brush = Brush.verticalGradient(0f to highlight, top to border, 1f to border)
    when (outline) {
        is androidx.compose.ui.graphics.Outline.Rounded -> {
            val r = outline.roundRect
            drawRoundRect(brush, Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke),
                CornerRadius((r.topLeftCornerRadius.x - stroke / 2).coerceAtLeast(0f)), style = Stroke(stroke))
        }
        else -> drawRect(brush, Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
    }
}

/**
 * Glass card (radius 18): linear 160° White .10 → .035 → .02 with a 1 dp White .09 border and top highlight, shadow
 * 10 dp. Behind the content: [thumbnail] (its own frame, blurred — where blur draws) or else the [tint] colour at
 * [tintAlpha]. [flat] = White .03 fill, White .14 border, no shadow. [selected] = primary border, [outline] a 1.5 dp one; [attention] = a 4 dp
 * tone bar on the left; [onClick] / [onLongClick] / [onDoubleClick] as [AfCard].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    attention: Tone? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    tint: Color? = null,
    tintAlpha: Float = GlassColors.TintProjects,
    thumbnail: ImageBitmap? = null,
    flat: Boolean = false,
    padding: PaddingValues = PaddingValues(GlassShapes.CardPadding),
    gap: Dp = GlassShapes.CardGap,
    centred: Boolean = false,
    outline: Color? = null,
    outlineWidth: Dp = 1.5.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = GlassShapes.Card
    val interaction = remember { MutableInteractionSource() }
    val art = drawsArt(thumbnail)
    Box(
        modifier
            .fillMaxWidth()
            .then(if (flat) Modifier else Modifier.shadow(GlassShapes.CardShadow, shape, ambientColor = GlassColors.CardShadow, spotColor = GlassColors.CardShadow))
            .clip(shape)
            .then(when {
                onClick == null -> Modifier
                onLongClick == null && onDoubleClick == null -> Modifier.clickable(interaction, null, role = Role.Button, onClick = onClick)
                else -> Modifier.combinedClickable(interaction, null, role = Role.Button, onLongClick = onLongClick,
                    onDoubleClick = onDoubleClick, onClick = onClick)
            })
            .drawBehind {
                if (flat) drawRect(GlassColors.FlatFill)
                else drawRect(cssLinearGradient(160f, size, 0f to GlassColors.CardTop, .55f to GlassColors.CardMid, 1f to GlassColors.CardBottom))
                if (!art) tint?.let { drawGlassTint(it.copy(alpha = tintAlpha)) }
            }
            .drawWithContent {
                drawContent()
                attention?.let { drawRect(it.fill, size = Size(AfSize.AttentionBar.toPx(), size.height)) }
                when {
                    // [outline] (the Recap block playing in the top preview; the shot sheet's approved / stale step): [outlineWidth].
                    outline != null -> drawGlassBorder(shape, outline, outline, outlineWidth)
                    selected -> drawGlassBorder(shape, AutoFlowColors.Purple, AutoFlowColors.Purple)
                    flat -> drawGlassBorder(shape, GlassColors.FlatBorder, GlassColors.FlatBorder)
                    else -> drawGlassBorder(shape, GlassColors.CardBorder, GlassColors.CardHighlight)
                }
            }
            .interactionOverlay(interaction, shape),
    ) {
        if (art) GlassArt(thumbnail!!, Modifier.matchParentSize().testTag("glass-art"))
        Column(Modifier.fillMaxWidth().then(if (centred) Modifier.fillMaxHeight() else Modifier).padding(padding),
            verticalArrangement = Arrangement.spacedBy(gap, if (centred) Alignment.CenterVertically else Alignment.Top), content = content)
    }
}

/** A glass card with a title row (as the titled [AfCard]). */
@Composable
fun GlassCard(title: String, modifier: Modifier = Modifier, selected: Boolean = false, headerAction: (@Composable () -> Unit)? = null,
    attention: Tone? = null, onClick: (() -> Unit)? = null, icon: ImageVector? = null, content: @Composable ColumnScope.() -> Unit) {
    GlassCard(modifier, selected, attention, onClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            icon?.let { AfIcon(it, Modifier.testTag("card-icon"), tint = AutoFlowColors.PrimaryText, size = AfSize.IconSmall) }
            AfText(title, Modifier.weight(1f), type = GlassSectionType, weight = androidx.compose.ui.text.font.FontWeight.Bold)
            headerAction?.invoke()
        }
        content()
    }
}

/** Section and card titles on glass screens: 14 sp bold (the AfCard title size). */
internal val GlassSectionType = AfType.Button

/** Section title (14 sp bold, min 30 dp) over glass cards, with an optional trailing action (e.g. a text button). */
@Composable
fun GlassSectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AfSpace.S)) {
        AfText(io.github.alalkipgen.autoflow.ui.i18n.My.breakSyllables(title), Modifier.weight(1f), type = GlassSectionType,
            weight = androidx.compose.ui.text.font.FontWeight.Bold)
        action?.invoke()
    }
}

/**
 * Home hero (radius 20, padding 14, gap 10): radial Purple .45 top-start and Cyan .18 bottom-end over linear White
 * .12 → .04, 1 dp lavender border, coloured shadow.
 */
@Composable
fun GlassHero(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = GlassShapes.Hero
    Column(
        modifier
            .shadow(GlassShapes.HeroShadow, shape, ambientColor = GlassColors.HeroShadow, spotColor = GlassColors.HeroShadow)
            .clip(shape)
            .drawBehind {
                drawRect(cssLinearGradient(160f, size, 0f to GlassColors.HeroTop, 1f to GlassColors.HeroBottom))
                drawRadialEllipse(GlassColors.HeroPurple, Offset.Zero, size.width * 1.2f, size.height * 1.2f, .6f)
                drawRadialEllipse(GlassColors.HeroCyan, Offset(size.width, size.height), size.width * .9f, size.height * 1.2f, .6f)
            }
            .drawWithContent { drawContent(); drawGlassBorder(shape, GlassColors.HeroBorder, GlassColors.HeroHighlight) }
            .padding(GlassShapes.HeroPadding),
        // Centred when the hero is taller than its content (desktop: as tall as the Jobs column beside it).
        verticalArrangement = Arrangement.spacedBy(GlassShapes.HeroGap, Alignment.CenterVertically),
        content = content,
    )
}

/** The primary gradient (135° PrimaryFill → #6D28D9). */
internal fun DrawScope.primaryBrush(): Brush = cssLinearGradient(135f, size, 0f to GlassColors.PrimaryStart, 1f to GlassColors.PrimaryEnd)

/**
 * Glass button, the [AfButton] API: Primary = the primary gradient with a coloured shadow and top highlight,
 * Secondary = White .08 fill + White .14 border, Danger = Failed .10 fill + .45 border, Text = no frame, Flow = cyan.
 * 44 dp ([small] 36) inside a 48 dp touch target that overflows into the spacing; icon 18 ([small] 16) before the label,
 * [trailingIcon] after it (Next step →). [accent] (Secondary only): an [accent] .12 fill and .70 border instead of the White
 * ones — a toggle that is on (Subtitles' Shift times while its card is open) or the offered next step (Translate, Group 5 A 10).
 */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Secondary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    small: Boolean = false,
    trailingIcon: ImageVector? = null,
    accent: Color? = null,
) {
    val visual = if (small) AfSize.ButtonSmall else AfSize.Button
    val shape = RoundedCornerShape(if (small) AfRadius.ButtonSmall else AfRadius.Button)
    val padX = if (small) 11.dp else 14.dp
    val ink = when (kind) {
        ButtonKind.Primary -> Color.White
        ButtonKind.Flow -> AutoFlowColors.Background
        ButtonKind.Secondary -> AutoFlowColors.Text
        ButtonKind.Danger -> AutoFlowColors.Failed
        ButtonKind.Text -> AutoFlowColors.PrimaryText
    }
    val interaction = remember { MutableInteractionSource() }
    if (kind == ButtonKind.Text) {
        // No frame: the control is its label (one line high); the 48 dp target overflows on every side.
        val type = if (small) AfType.ButtonSmall else AfType.Button
        val line = with(LocalDensity.current) {
            rememberTextMeasurer().measure(text, afTextStyle(ink, type.size, type.weight, LocalUiFontFamily.current), maxLines = 1).size.height.toDp()
        }
        // A tall fallback font (Windows' Myanmar Text at font 2.0) can make the label taller than the target itself.
        val labelHeight = maxOf(line, if (icon != null) 18.dp else 0.dp).coerceAtMost(AfSize.Touch)
        Box(Modifier.touchOverflow(horizontal = padX, vertical = ((AfSize.Touch - labelHeight) / 2).coerceAtLeast(0.dp)).then(modifier)
            .touchTarget(interaction, enabled, Role.Button, labelHeight, onClick).interactionOverlay(interaction, shape).padding(horizontal = padX),
            contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (icon != null) AfIcon(icon, Modifier.testTag("button-icon"), tint = ink, size = if (small) 16.dp else AfSize.IconSmall)
                AfText(text, color = ink, type = type)
            }
        }
        return
    }
    Box(
        Modifier.touchOverflow(vertical = (AfSize.Touch - visual) / 2)
            .then(modifier)
            .touchTarget(interaction, enabled, Role.Button, visual, onClick)
            .then(if (kind == ButtonKind.Primary && enabled) Modifier.shadow(GlassShapes.ButtonShadow, shape,
                ambientColor = GlassColors.PrimaryGlow, spotColor = GlassColors.PrimaryGlow) else Modifier)
            .clip(shape)
            .drawBehind {
                when (kind) {
                    ButtonKind.Primary -> drawRect(primaryBrush())
                    ButtonKind.Flow -> drawRect(AutoFlowColors.Cyan)
                    ButtonKind.Secondary -> drawRect(accent?.copy(alpha = .12f) ?: GlassColors.SecondaryFill)
                    else -> drawRect(GlassColors.DangerFill)
                }
            }
            .drawWithContent {
                drawContent()
                when (kind) {
                    ButtonKind.Primary -> drawRect(GlassColors.PrimaryHighlight, size = Size(size.width, 1.dp.toPx()))
                    ButtonKind.Secondary -> (accent?.copy(alpha = .7f) ?: GlassColors.SecondaryBorder).let { drawGlassBorder(shape, it, it) }
                    ButtonKind.Danger -> drawGlassBorder(shape, GlassColors.DangerBorder, GlassColors.DangerBorder)
                    else -> Unit
                }
            }
            .interactionOverlay(interaction, shape)
            .padding(horizontal = padX, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) AfIcon(icon, Modifier.testTag("button-icon"), tint = ink, size = if (small) 16.dp else AfSize.IconSmall)
            AfText(text, color = ink, type = if (small) AfType.ButtonSmall else AfType.Button)
            if (trailingIcon != null) AfIcon(trailingIcon, tint = ink, size = if (small) 16.dp else AfSize.IconSmall)
        }
    }
}

/**
 * Glass icon button, the [AfIconButton] API: [size] 36 (in cards) / 40 (headers), radius 12, White .08 fill + White
 * .14 border, 18 dp icon; [selected] = primary container + primary border; [tone] = a coloured one, as the Danger button
 * ([tone] .10 fill + .45 border, icon in [tone]; Group 3 B 4 Remove). The 48 dp target overflows into the spacing.
 */
@Composable
fun GlassIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    size: Dp = GlassShapes.IconButtonCard, selected: Boolean = false, enabled: Boolean = true, tone: Color? = null) {
    val interaction = remember { MutableInteractionSource() }
    val shape = GlassShapes.IconButton
    val slack = (AfSize.Touch - size) / 2
    Box(Modifier.touchOverflow(slack, slack).then(modifier).afTooltip(label).size(AfSize.Touch)
        .clickable(interaction, null, enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }
        .padding(slack).alpha(if (enabled) 1f else .4f).clip(shape)
        .background(if (selected) Tone.Primary.container else tone?.copy(alpha = .10f) ?: GlassColors.SecondaryFill)
        .drawWithContent {
            drawContent()
            if (selected) drawGlassBorder(shape, AutoFlowColors.PrimaryFill, AutoFlowColors.PrimaryFill)
            else if (tone != null) drawGlassBorder(shape, tone.copy(alpha = .45f), tone.copy(alpha = .45f))
            else drawGlassBorder(shape, GlassColors.SecondaryBorder, GlassColors.SecondaryBorder.copy(alpha = .26f))
        }
        .interactionOverlay(interaction, shape), contentAlignment = Alignment.Center) {
        AfIcon(icon, tint = if (selected) AutoFlowColors.PrimaryText else tone ?: AutoFlowColors.Text, size = AfSize.IconSmall)
    }
}

/** Filled tile (radius 11): linear 145° [colour] .90 → .55, white [icon] (Fill) at size × .52, coloured shadow; [ink] = icon colour. */
@Composable
fun FilledTile(icon: ImageVector, colour: Color, modifier: Modifier = Modifier, size: Dp = AfSize.Tile, ink: Color = Color.White) {
    val shape = GlassShapes.Tile
    Box(modifier.size(size).shadow(GlassShapes.TileShadow, shape, ambientColor = colour.copy(alpha = .35f), spotColor = colour.copy(alpha = .35f))
        .clip(shape)
        .drawBehind { drawRect(cssLinearGradient(145f, this.size, 0f to colour.copy(alpha = .9f), 1f to colour.copy(alpha = .55f))) }
        .drawWithContent { drawContent(); drawRect(GlassColors.TileHighlight, size = Size(this.size.width, 1.dp.toPx())) },
        contentAlignment = Alignment.Center) {
        AfIcon(icon, tint = ink, size = size * .52f)
    }
}

/** Soft tile (radius 11): [colour] .18 fill, 1 dp [colour] .35 border, [icon] (Fill) in [colour] at size × .52. */
@Composable
fun SoftTile(icon: ImageVector, colour: Color, modifier: Modifier = Modifier, size: Dp = 34.dp) {
    val shape = GlassShapes.Tile
    Box(modifier.size(size).clip(shape).background(colour.copy(alpha = .18f)).border(1.dp, colour.copy(alpha = .35f), shape),
        contentAlignment = Alignment.Center) {
        AfIcon(icon, tint = colour, size = size * .52f)
    }
}

/** A mode's glass colour (tiles, mode chips, tints). */
val Mode.glassColour: Color get() = when (this) {
    Mode.MOVIE_RECAP -> GlassColors.MovieRecap
    Mode.MUSIC_VIDEO -> GlassColors.MusicVideo
    Mode.SUBTITLES -> GlassColors.Subtitles
    Mode.VOICE_AUDIO -> GlassColors.Voice
    Mode.DUBBING -> GlassColors.Dubbing
}

/** A mode's Fill icon (glass tiles and mode chips). */
internal fun Mode.fillIcon(): ImageVector = when (this) {
    Mode.MOVIE_RECAP -> AfIcons.Fill.Film
    Mode.MUSIC_VIDEO -> AfIcons.Fill.Music
    Mode.SUBTITLES -> AfIcons.Fill.Subtitles
    Mode.VOICE_AUDIO -> AfIcons.Fill.Wave
    Mode.DUBBING -> AfIcons.Fill.Dub
}

/** Glass mode chip: 25 dp pill, White .08 fill + White .10 border, the mode's Fill icon (14) in its colour, micro label. */
@Composable
fun GlassModeChip(mode: Mode, modifier: Modifier = Modifier) {
    Row(modifier.heightIn(min = GlassShapes.ModeChip).clip(GlassShapes.Pill).background(GlassColors.ModeChipFill)
        .border(1.dp, GlassColors.ModeChipBorder, GlassShapes.Pill).padding(horizontal = 9.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        AfIcon(mode.fillIcon(), tint = mode.glassColour, size = 14.dp)
        AfText(io.github.alalkipgen.autoflow.ui.i18n.My.breakSyllables(tr(mode.label())), color = AutoFlowColors.Text.copy(alpha = .9f),
            type = AfType.Micro)
    }
}

/**
 * Glass filter chip, the [AfChip] API: 34 dp pill (48 dp target overflowing into the spacing); off = White .06 fill +
 * White .12 border, label Text, count Muted; on = the primary gradient with a glow, white label, count White .75. An
 * optional [leading] mark (e.g. a ratio shape) is drawn in the label's ink (white on, Muted off).
 */
@Composable
fun GlassFilterChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    count: Int? = null, leading: (@Composable (ink: Color) -> Unit)? = null, badge: Int? = null, badgeModifier: Modifier = Modifier) {
    val shape = GlassShapes.Pill
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.touchOverflow(vertical = (AfSize.Touch - AfSize.FilterChip) / 2).then(modifier)
            .semantics { stateDescription = if (selected) "selected" else "not selected" }
            .touchTarget(interaction, enabled, Role.RadioButton, AfSize.FilterChip, onClick)
            .then(if (selected) Modifier.shadow(4.dp, shape, ambientColor = GlassColors.PrimaryGlow, spotColor = GlassColors.PrimaryGlow) else Modifier)
            .clip(shape)
            .drawBehind { if (selected) drawRect(primaryBrush()) else drawRect(GlassColors.FilterOffFill) }
            .drawWithContent {
                drawContent()
                if (selected) drawRect(GlassColors.PrimaryHighlight, size = Size(size.width, 1.dp.toPx()))
                else drawGlassBorder(shape, GlassColors.FilterOffBorder, GlassColors.FilterOffBorder)
            }
            .interactionOverlay(interaction, shape)
            .padding(horizontal = 13.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            leading?.invoke(if (selected) Color.White else AutoFlowColors.Muted)
            AfText(text, color = if (selected) Color.White else AutoFlowColors.Text, type = AfType.Micro)
            if (count != null) AfText("$count", color = if (selected) GlassColors.FilterOnCount else AutoFlowColors.Muted, type = AfType.Micro)
            if (badge != null) GlassCountBadge(badge, badgeModifier)
        }
    }
}

/** Count badge (Group 3 A 🆕-16): 18 dp Cyan pill, the number in the dark Base colour, 11 sp bold. */
@Composable
fun GlassCountBadge(count: Int, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = 18.dp).widthIn(min = 18.dp).clip(GlassShapes.Pill).background(AutoFlowColors.Cyan).padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center) {
        AfText("$count", color = GlassColors.Base, size = 11.sp, bold = true)
    }
}

/**
 * Glass progress (5 dp, radius 3): track White .10, fill Cyan → Purple. Determinate only with a real [progress]
 * (0..1), otherwise indeterminate — never a made-up percent.
 */
@Composable
fun GlassProgress(progress: Float?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(AfRadius.Progress)
    val fill = { from: Float, to: Float -> Brush.horizontalGradient(listOf(GlassColors.ProgressStart, GlassColors.ProgressEnd), from, to) }
    if (progress == null) {
        val position by rememberInfiniteTransition().animateFloat(-.28f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)))
        Canvas(modifier.fillMaxWidth().height(GlassShapes.Progress).clip(shape).semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
            drawRect(GlassColors.Track)
            val start = position.coerceIn(0f, 1f) * size.width
            val end = (position + .28f).coerceIn(0f, 1f) * size.width
            if (end > start) drawRoundRect(fill(start, end), Offset(start, 0f), Size(end - start, size.height), CornerRadius(AfRadius.Progress.toPx()))
        }
    } else {
        require(progress.isFinite() && progress in 0f..1f)
        Canvas(modifier.fillMaxWidth().height(GlassShapes.Progress).clip(shape).semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) }) {
            drawRect(GlassColors.Track)
            val end = progress * size.width
            if (end > 0f) drawRoundRect(fill(0f, end), Offset.Zero, Size(end, size.height), CornerRadius(AfRadius.Progress.toPx()))
        }
    }
}

/** Round play button (40 dp, primary gradient, white Fill play 20) in a 48 dp target, with a tooltip. */
@Composable
fun PlayCircle(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val slack = (AfSize.Touch - GlassShapes.PlayCircle) / 2
    Box(Modifier.touchOverflow(slack, slack).then(modifier).afTooltip(label).size(AfSize.Touch)
        .clickable(interaction, null, role = Role.Button, onClick = onClick).semantics { contentDescription = label }
        .padding(slack).shadow(GlassShapes.ButtonShadow, CircleShape, ambientColor = GlassColors.PrimaryGlow, spotColor = GlassColors.PrimaryGlow)
        .clip(CircleShape).drawBehind { drawRect(primaryBrush()) }
        .interactionOverlay(interaction, CircleShape), contentAlignment = Alignment.Center) {
        AfIcon(AfIcons.Fill.Play, tint = Color.White, size = 20.dp)
    }
}

/** A glass thumbnail's frame: 4 dp black .35 shadow and a 1 dp White .08 border (pass to [MediaThumb]). */
fun Modifier.glassThumb(): Modifier {
    val shape = RoundedCornerShape(AfRadius.Thumb)
    return shadow(GlassShapes.ThumbShadow, shape, ambientColor = GlassColors.ThumbShadow, spotColor = GlassColors.ThumbShadow)
        .border(1.dp, GlassColors.ThumbBorder, shape)
}

/** 1 dp White .08 divider between glass list rows, inset 58 at the start (past the row tile) and 12 at the end. */
@Composable
fun GlassListDivider() {
    Box(Modifier.padding(start = GlassShapes.DividerStart, end = GlassShapes.DividerEnd).fillMaxWidth().height(1.dp).background(GlassColors.Divider))
}

/**
 * A glass list (Library sections): one glass card (padding 4 × 0, gap 0) tinted with [tint] at [tintAlpha], its
 * [items] as rows separated by [GlassListDivider]s; [id] keys each row.
 */
@Composable
fun <T> GlassList(items: List<T>, tint: Color, id: (T) -> Any, modifier: Modifier = Modifier, tintAlpha: Float = GlassColors.TintLibrary,
    row: @Composable (T) -> Unit) {
    GlassCard(modifier, tint = tint, tintAlpha = tintAlpha, padding = PaddingValues(vertical = GlassShapes.ListPaddingY), gap = 0.dp) {
        items.forEachIndexed { i, item ->
            androidx.compose.runtime.key(id(item)) {
                if (i > 0) GlassListDivider()
                row(item)
            }
        }
    }
}

/**
 * A glass list row (min 52, padding 12 × 6, gap 12): [leading] (a 34 dp tile), the [title] on one line (ellipsized,
 * the full title as its description) over an optional [subtitle] caption that wraps, then [trailing] (a small button
 * or chip). The trailing control moves under the text when the text beside it would be narrower than
 * [AfSize.TileCardMin] × the font scale (font 2.0, long Burmese labels), so nothing is squeezed or clipped.
 */
@Composable
fun GlassListRow(title: String, modifier: Modifier = Modifier, subtitle: String? = null, leading: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)? = null) {
    val minText = AfSize.TileCardMin * LocalDensity.current.fontScale
    Layout(
        contents = listOf(leading, {
            Column {
                AfText(title, Modifier.semantics { contentDescription = title }, type = AfType.RowTitle, maxLines = 1, ellipsis = true)
                subtitle?.let { AfText(io.github.alalkipgen.autoflow.ui.i18n.My.breakSyllables(it), color = AutoFlowColors.Muted, type = AfType.Caption) }
            }
        }, trailing ?: {}),
        modifier = modifier.fillMaxWidth().heightIn(min = GlassShapes.ListRowMin)
            .padding(horizontal = GlassShapes.ListRowPadX, vertical = GlassShapes.ListRowPadY),
    ) { (leadM, textM, trailM), constraints ->
        val gap = GlassShapes.ListRowGap.roundToPx()
        val width = constraints.maxWidth
        val lead = leadM.map { it.measure(Constraints(maxWidth = width)) }
        val leadW = lead.maxOfOrNull { it.width } ?: 0
        val textMax = (width - leadW - gap).coerceAtLeast(0)
        val trailWant = trailM.maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0
        val beside = trailM.isEmpty() || textMax - gap - trailWant >= minText.roundToPx()
        val trail = trailM.map { it.measure(Constraints(maxWidth = if (beside) trailWant.coerceAtMost(textMax) else textMax)) }
        val trailW = trail.maxOfOrNull { it.width } ?: 0
        val textW = if (beside && trail.isNotEmpty()) (textMax - gap - trailW).coerceAtLeast(0) else textMax
        val text = textM.map { it.measure(Constraints(minWidth = textW, maxWidth = textW)) }
        val leadH = lead.maxOfOrNull { it.height } ?: 0
        val textH = text.maxOfOrNull { it.height } ?: 0
        val trailH = trail.maxOfOrNull { it.height } ?: 0
        val under = AfSpace.S.roundToPx()
        val contentH = if (beside) maxOf(leadH, textH, trailH) else maxOf(leadH, textH + under + trailH)
        val height = contentH.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            val textX = leadW + gap
            if (beside) {
                lead.forEach { it.place(0, (height - it.height) / 2) }
                text.forEach { it.place(textX, (height - it.height) / 2) }
                trail.forEach { it.place(width - it.width, (height - it.height) / 2) }
            } else {
                val top = (height - contentH) / 2
                lead.forEach { it.place(0, top) }
                text.forEach { it.place(textX, top) }
                trail.forEach { it.place(textX, top + textH + under) }
            }
        }
    }
}
