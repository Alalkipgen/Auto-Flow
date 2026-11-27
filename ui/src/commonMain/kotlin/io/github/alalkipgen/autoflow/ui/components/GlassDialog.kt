package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.theme.*

/*
 * The glass dialog (Projects dialogs README §1, docs/DESIGN.md §9.10): one shell for every confirm / options dialog —
 * the Projects dialogs first, later Library and Jobs. Same Dialog window and dismiss logic as before: Back and a tap
 * outside call onDismissRequest.
 */

/** Window properties: our own width; the desktop draws the Base .66 scrim, Android dims its dialog window. */
internal expect fun glassDialogProperties(): DialogProperties

/** Android: the dialog window's dim = the scrim's .66; desktop: nothing (the scrim is drawn). */
@Composable
internal expect fun GlassDialogScrim()

/** A dialog window for [content] (one or more [GlassDialogSurface]s, e.g. steps keyed by the caller). */
@Composable
fun GlassDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest, properties = glassDialogProperties()) {
        GlassDialogScrim()
        content()
    }
}

/** A dialog window with one [GlassDialogSurface]. */
@Composable
fun GlassDialog(onDismissRequest: () -> Unit, icon: ImageVector, tone: Color, title: String, modifier: Modifier = Modifier,
    subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    GlassDialog(onDismissRequest) { GlassDialogSurface(icon, tone, title, modifier, subtitle, content) }
}

/**
 * The dialog surface: width min(screen − 32, 400) dp, radius 22, `DialogFill` + the 160° White .07 → .02 gradient,
 * 1 dp border with a top highlight, shadow 24, padding 20, gap 14; scrolls when taller than the screen. Header = a 44 dp
 * soft tile ([icon] in [tone]) + [title] (17 sp bold, wraps) + optional Muted [subtitle].
 */
@Composable
fun GlassDialogSurface(icon: ImageVector, tone: Color, title: String, modifier: Modifier = Modifier, subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit) {
    GlassDialogShell(modifier, {
        GlassDialogHeader(title, subtitle) { SoftTile(icon, tone, Modifier.testTag("glass-dialog-tile"), size = GlassShapes.DialogTile) }
    }, content)
}

/**
 * The dialog surface for one item (Library follow-up README §3): header = the item's own [leading] (44 dp tall) + its
 * [title] + optional Muted [subtitle]; no header at all when [title] is null.
 */
@Composable
fun GlassDialogSurface(title: String?, modifier: Modifier = Modifier, subtitle: String? = null, leading: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    val header: (@Composable () -> Unit)? = title?.let { t ->
        { GlassDialogHeader(t, subtitle, leading?.let { lead -> { Box(Modifier.testTag("glass-dialog-lead")) { lead() } } }) }
    }
    GlassDialogShell(modifier, header, content)
}

/** The surface shell (see [GlassDialogSurface]): [header] (if any), then [content], 14 dp apart. */
@Composable
private fun GlassDialogShell(modifier: Modifier, header: (@Composable () -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    val shape = GlassShapes.Dialog
    val pad = GlassShapes.DialogPadding
    val overflow = (AfSize.Touch - AfSize.Button) / 2
    Column(
        modifier
            .dialogSize()
            .shadow(GlassShapes.DialogShadow, shape, ambientColor = GlassColors.DialogShadow, spotColor = GlassColors.DialogShadow)
            .clip(shape)
            .drawBehind {
                drawRect(GlassColors.DialogFill)
                drawRect(cssLinearGradient(160f, size, 0f to GlassColors.DialogTop, .55f to GlassColors.DialogBottom))
            }
            .drawWithContent { drawContent(); drawGlassBorder(shape, GlassColors.DialogBorder, GlassColors.DialogHighlight) }
            .testTag("glass-dialog")
            .paneScroll(rememberScrollState())
            .padding(start = pad, top = pad, end = pad, bottom = pad - overflow),
    ) {
        // The last button's 48 dp target overflows its 44 dp slot by 2 dp; keep that inside the scrolled content (the
        // visual padding stays 20), so a scroll-to of the last button never asks a dialog that cannot scroll to scroll.
        Column(Modifier.padding(bottom = overflow), verticalArrangement = Arrangement.spacedBy(GlassShapes.DialogGap)) {
            header?.invoke()
            content()
        }
    }
}

/** A dialog header: [leading] (if any), 12 dp, then [title] (17 sp bold, wraps) over an optional Muted [subtitle]. */
@Composable
private fun GlassDialogHeader(title: String, subtitle: String?, leading: (@Composable () -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AfText(title, Modifier.testTag("glass-dialog-title"), type = AfType.CardTitle, weight = FontWeight.Bold)
            subtitle?.let { AfText(it, Modifier.testTag("glass-dialog-subtitle"), color = AutoFlowColors.Muted, type = AfType.Caption) }
        }
    }
}

/** Width min(max − 2 × margin, 400) dp; height at most max − 2 × margin (the surface scrolls inside). */
private fun Modifier.dialogSize(): Modifier = layout { measurable, constraints ->
    val margin = GlassShapes.DialogMargin.roundToPx()
    val most = GlassShapes.DialogMaxWidth.roundToPx()
    val width = if (constraints.hasBoundedWidth) minOf(constraints.maxWidth - 2 * margin, most).coerceAtLeast(0) else most
    val height = if (constraints.hasBoundedHeight) (constraints.maxHeight - 2 * margin).coerceAtLeast(0) else Constraints.Infinity
    val placeable = measurable.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = height))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * A menu row (radius 14, min 56, White .04 fill + .09 border, padding 10 / 12): 36 dp soft tile, [title] (14.5 bold, in
 * [titleColour] — Failed for a Danger row) with an optional Muted [caption] and a caret; the whole row clicks. [mono] =
 * a removed project: the name on one line (the full name stays in its content description), the folder in monospace.
 */
@Composable
fun GlassMenuRow(icon: ImageVector, tone: Color, title: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    caption: String? = null, mono: Boolean = false, enabled: Boolean = true, titleColour: Color = AutoFlowColors.Text) {
    val shape = GlassShapes.MenuRow
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier.fillMaxWidth().heightIn(min = GlassShapes.MenuRowMin).clip(shape).background(GlassColors.MenuRowFill)
            .border(1.dp, GlassColors.MenuRowBorder, shape)
            .clickable(interaction, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .interactionOverlay(interaction, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SoftTile(icon, tone, size = GlassShapes.MenuTile)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (mono) AfText(title, Modifier.semantics { contentDescription = title }, color = titleColour, type = AfType.RowTitle,
                weight = FontWeight.Bold, maxLines = 1, ellipsis = true)
            else AfText(title, color = titleColour, type = AfType.RowTitle, weight = FontWeight.Bold)
            caption?.let {
                if (mono) MonoText(it, 11.5.sp, AutoFlowColors.Muted)
                else AfText(it, color = AutoFlowColors.Muted, type = AfType.Caption)
            }
        }
        AfIcon(AfIcons.Regular.Chevron, tint = AutoFlowColors.Muted, size = 18.dp)
    }
}

/**
 * A folder path box (radius 12, black .22 fill, White .10 border, padding 10 / 12): folder icon, the folder name (mono
 * 13 bold) and its parent path (mono 11 Muted), which breaks only after a path separator.
 */
@Composable
fun GlassPathBox(path: String, modifier: Modifier = Modifier) {
    val shape = GlassShapes.PathBox
    val cut = path.trimEnd('/', '\\').indexOfLast { it == '/' || it == '\\' }
    Row(
        modifier.fillMaxWidth().clip(shape).background(GlassColors.PathFill).border(1.dp, GlassColors.PathBorder, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AfIcon(AfIcons.Regular.Folder, Modifier.padding(top = 2.dp), tint = AutoFlowColors.Muted, size = 16.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            MonoText(path.substring(cut + 1), 13.sp, AutoFlowColors.Text, bold = true, modifier = Modifier.testTag("glass-path-name"))
            if (cut >= 0) MonoText(path.substring(0, cut + 1), 11.sp, AutoFlowColors.Muted, modifier = Modifier.testTag("glass-path-parent"))
        }
    }
}

/** A fact line: 17 dp Muted icon (Regular) + 13 sp text. */
@Composable
fun GlassFact(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AfIcon(icon, Modifier.padding(top = 2.dp), tint = AutoFlowColors.Muted, size = 17.dp)
        AfText(text, Modifier.weight(1f))
    }
}

/**
 * A callout: [tone] .10 fill and .30 border (radius 12, padding 10 / 12), 18 dp Fill [icon] and caption text in
 * [textColour] ([tone]; the Settings callouts use Text).
 */
@Composable
fun GlassCallout(icon: ImageVector, text: String, tone: Color, modifier: Modifier = Modifier, textColour: Color = tone) {
    val shape = GlassShapes.Callout
    Row(
        modifier.fillMaxWidth().clip(shape).background(tone.copy(alpha = .10f)).border(1.dp, tone.copy(alpha = .30f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AfIcon(icon, Modifier.padding(top = 2.dp), tint = tone, size = 18.dp)
        AfText(text, Modifier.weight(1f), color = textColour, type = AfType.Caption)
    }
}

/**
 * Monospace text (line height 1.55) whose Burmese runs use the UI font (the monospace fonts have no Burmese). A path
 * breaks only after "/" (a zero-width space after each separator); a piece still wider than the box (e.g.
 * "io.github.alalkipgen.autoflow/" at font 2.0) may also break after ".", "-" or "_", so no word is cut letter by letter;
 * only a part still wider than the box after that (a key or hash in the models JSON) may break between its letters.
 */
@Composable
internal fun MonoText(text: String, size: TextUnit, colour: Color, bold: Boolean = false, modifier: Modifier = Modifier) {
    val ui = LocalUiFontFamily.current
    val style = afTextStyle(colour, size, if (bold) FontWeight.Bold else FontWeight.Normal, FontFamily.Monospace)
        .copy(lineHeight = size * 1.55f)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints {
        val max = constraints.maxWidth
        val content = remember(text, ui, style, max) {
            fun annotated(t: String) = buildAnnotatedString {
                var i = 0
                while (i < t.length) {
                    val burmese = t[i] in '\u1000'..'\u109F'
                    var j = i + 1
                    while (j < t.length && (t[j] in '\u1000'..'\u109F') == burmese) j++
                    if (burmese) { pushStyle(SpanStyle(fontFamily = ui)); append(t.substring(i, j)); pop() } else append(t.substring(i, j))
                    i = j
                }
            }
            fun tooWide(piece: String) = max != Constraints.Infinity && measurer.measure(annotated(piece), style).size.width > max
            val pieces = Regex("[^/\\\\ ]*[/\\\\ ]?").findAll(text).map { it.value }.filter { it.isNotEmpty() }
            val broken = pieces.joinToString("") { piece ->
                val soft = if (!tooWide(piece)) piece else piece.replace(Regex("([._-])(?=.)"), "$1\u200B").split('\u200B')
                    .joinToString("\u200B") { part -> if (tooWide(part)) part.replace(Regex("(?<=[A-Za-z0-9])(?=[A-Za-z0-9])"), "\u200B") else part }
                soft.replace("/", "/\u200B").replace("\\", "\\\u200B")
            }
            annotated(broken)
        }
        BasicText(content, modifier, style = style)
    }
}

/** One dialog button ([GlassButton] look): label, action, kind, optional icon, enabled and test tag. */
class GlassDialogAction(val text: String, val onClick: () -> Unit, val kind: ButtonKind = ButtonKind.Secondary,
    val icon: ImageVector? = null, val enabled: Boolean = true, val tag: String? = null)

/**
 * Dialog buttons (README §1): [cancel] alone = full width; one action + [cancel] = one row of two equal buttons (gap
 * 10, Cancel at the start, the action at the end), stacked full width (the action on top, Cancel last) when a label
 * needs two lines at half width or at font 2.0; two or more actions = stacked full width, Cancel last.
 */
@Composable
fun GlassDialogButtons(cancel: GlassDialogAction, vararg actions: GlassDialogAction) {
    val gap = GlassShapes.DialogButtonGap
    @Composable fun Button(a: GlassDialogAction, modifier: Modifier) =
        GlassButton(a.text, a.onClick, modifier.then(if (a.tag != null) Modifier.testTag(a.tag) else Modifier), a.kind, a.enabled, a.icon)
    if (actions.isEmpty()) { Button(cancel, Modifier.fillMaxWidth()); return }
    val measurer = rememberTextMeasurer()
    val family = LocalUiFontFamily.current
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val half = (maxWidth - gap) / 2
        fun oneLine(a: GlassDialogAction): Boolean {
            val label = measurer.measure(a.text, afTextStyle(Color.Unspecified, AfType.Button.size, AfType.Button.weight, family),
                softWrap = false, maxLines = 1, density = density).size.width
            return with(density) { label.toDp() } + 28.dp + (if (a.icon != null) 24.dp else 0.dp) <= half
        }
        val row = actions.size == 1 && density.fontScale < 2f && oneLine(actions[0]) && oneLine(cancel)
        if (row) Row(Modifier.fillMaxWidth().testTag("glass-dialog-buttons-row"), horizontalArrangement = Arrangement.spacedBy(gap)) {
            Button(cancel, Modifier.weight(1f))
            Button(actions[0], Modifier.weight(1f))
        } else Column(Modifier.fillMaxWidth().testTag("glass-dialog-buttons-stacked"), verticalArrangement = Arrangement.spacedBy(gap)) {
            actions.forEach { Button(it, Modifier.fillMaxWidth()) }
            Button(cancel, Modifier.fillMaxWidth())
        }
    }
}
