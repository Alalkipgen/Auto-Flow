package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.GlassSectionType
import io.github.alalkipgen.autoflow.ui.components.GlassSwitch
import io.github.alalkipgen.autoflow.ui.components.SoftTile
import io.github.alalkipgen.autoflow.ui.components.interactionOverlay
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.core.recap.LayerType
import io.github.alalkipgen.autoflow.ui.components.CardTile
import io.github.alalkipgen.autoflow.ui.components.GlassFilterChip
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.GlassColors

/** The Movie Recap step cards' header tiles (Group 2 A §2): Fill icon · colour (also the card's tint). */
internal object RecapTiles {
    val Movie get() = CardTile(AfIcons.Fill.Film, GlassColors.RecapMovie)
    val Split get() = CardTile(AfIcons.Fill.Scissors, GlassColors.RecapSplit)
    val ScanResult get() = CardTile(AfIcons.Fill.ListMagnifyingGlass, GlassColors.RecapScan)
    val Subtitles get() = CardTile(AfIcons.Fill.Subtitles, GlassColors.RecapSubtitles)
    val Summary get() = CardTile(AfIcons.Fill.NotePencil, GlassColors.RecapLavender)
    val Transcribe get() = CardTile(AfIcons.Fill.Wave, GlassColors.RecapPink)
    val Scenes get() = CardTile(AfIcons.Fill.FilmStrip, GlassColors.RecapScenes)
    val Voice get() = CardTile(AfIcons.Fill.Microphone, GlassColors.RecapPink)
    val Layers get() = CardTile(AfIcons.Fill.Stack, GlassColors.RecapLavender)
    val MoreOptions get() = CardTile(AfIcons.Fill.SlidersHorizontal, GlassColors.Neutral)
    val WritingOptions get() = CardTile(AfIcons.Fill.Spark, GlassColors.RecapLavender)
    // Block detail (Group 2 C 11).
    val Script get() = CardTile(AfIcons.Fill.NotePencil, GlassColors.RecapLavender)
    val Clips get() = CardTile(AfIcons.Fill.Scissors, GlassColors.RecapSplit)
    val FixTiming get() = CardTile(AfIcons.Fill.Wrench, AutoFlowColors.Amber)
    val Takes get() = CardTile(AfIcons.Fill.Wave, AutoFlowColors.Purple)
    val BlockTools get() = CardTile(AfIcons.Fill.Dub, GlassColors.RecapTeal)
    // Style · More options / Captions / Look / Audio (Group 2 C 15–17).
    val Output get() = CardTile(AfIcons.Fill.FilmStrip, AutoFlowColors.Purple)
    val HideThings get() = CardTile(AfIcons.Fill.EyeSlash, AutoFlowColors.Failed)
    val FrameBackground get() = CardTile(AfIcons.Fill.Image, GlassColors.RecapTeal)
    val Watermark get() = CardTile(AfIcons.Fill.Stamp, AutoFlowColors.Amber)
    val Timing get() = CardTile(AfIcons.Fill.Timer, AutoFlowColors.Cyan)
    val Captions get() = CardTile(AfIcons.Fill.Subtitles, GlassColors.RecapSubtitles)
    val Look get() = CardTile(AfIcons.Fill.MagicWand, GlassColors.RecapPink)
    val Audio get() = CardTile(AfIcons.Fill.Volume, GlassColors.RecapTeal)
    val Music get() = CardTile(AfIcons.Fill.MusicNote, GlassColors.RecapTeal)
}

/** A Style layer's tile: Image · Text · Border · Blur · Background (Group 2 A §2). */
internal fun LayerType.tile(): CardTile = when (this) {
    LayerType.IMAGE -> CardTile(AfIcons.Fill.Image, GlassColors.LayerImage)
    LayerType.TEXT -> CardTile(AfIcons.Fill.TextT, GlassColors.LayerText)
    LayerType.BORDER -> CardTile(AfIcons.Fill.Selection, GlassColors.LayerBorder)
    LayerType.BLUR -> CardTile(AfIcons.Fill.DropHalf, GlassColors.LayerBlur)
    LayerType.BACKGROUND -> CardTile(AfIcons.Fill.PaintBucket, GlassColors.LayerBackground)
}

/** Glass choice chip: the glass filter chip with a check before the label when [selected] (target length, filters, sections). */
@Composable
internal fun RecapChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    GlassFilterChip(text, selected, onClick, modifier, enabled,
        leading = if (selected) { ink -> AfIcon(AfIcons.Regular.Check, tint = ink, size = 14.dp) } else null)

/** A scene row's 24 dp check box (the row is the checkbox): check-square (Fill, lavender) when picked, else a Muted square. */
@Composable
internal fun SceneCheck(checked: Boolean) {
    AfIcon(if (checked) AfIcons.Fill.CheckSquare else AfIcons.Regular.Square,
        Modifier.size(24.dp).testTag(if (checked) "check-mark-on" else "check-mark-off"),
        tint = if (checked) GlassColors.RecapLavender else AutoFlowColors.Muted, size = 24.dp)
}

/**
 * An inner glass card (Group 2 C §1, 15): radius 14, White .04 fill, 1 dp White .10 line, padding 12, gap [gap]; the
 * header = a 26 dp soft [tile] (tagged `card-tile`) and the [title] (14 sp bold) when given.
 */
@Composable
internal fun InnerGlassCard(title: String?, tile: CardTile?, modifier: Modifier = Modifier, gap: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(GlassColors.OptionFill).border(1.dp, GlassColors.OptionBorder, shape)
        .padding(12.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
        if (title != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tile?.let { SoftTile(it.icon, it.colour, Modifier.testTag("card-tile"), size = 26.dp) }
            AfText(title, Modifier.weight(1f), type = GlassSectionType, weight = FontWeight.Bold)
        }
        content()
    }
}

/** A darker inner box (radius 12, black .18, 1 dp White .08, padding 10, gap 6): one blur box's nudge row (C 15). */
@Composable
internal fun InsetGlassBox(modifier: Modifier = Modifier, fill: Color = Color.Black.copy(alpha = .18f), line: Color = Color.White.copy(alpha = .08f),
    content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(fill).border(1.dp, line, shape).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

/** Glass chip with an [icon] before the label (the check instead while [selected]): watermark positions (C 15). */
@Composable
internal fun RecapIconChip(text: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    GlassFilterChip(text, selected, onClick, modifier, enabled,
        leading = { ink -> AfIcon(if (selected) AfIcons.Regular.Check else icon, tint = ink, size = 14.dp) })

/** Glass colour chip: a 12 dp swatch dot of [colour] (1 dp White .18 ring) before the label (C 16). */
@Composable
internal fun RecapSwatchChip(text: String, colour: Color, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    GlassFilterChip(text, selected, onClick, modifier, enabled, leading = {
        Box(Modifier.size(12.dp).clip(CircleShape).background(colour).border(1.dp, GlassColors.SwatchBorder, CircleShape))
    })

/** A switch line inside a glass card (C 15–17): the label (body) and the 46 × 28 glass switch; the whole line (≥ 48 dp) toggles. */
@Composable
internal fun RecapSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(checked, interaction, null, role = Role.Switch, onValueChange = onChange)
        .semantics { stateDescription = if (checked) "on" else "off" }
        .interactionOverlay(interaction, RectangleShape).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AfText(label, Modifier.weight(1f), type = AfType.Body)
        GlassSwitch(checked)
    }
}
