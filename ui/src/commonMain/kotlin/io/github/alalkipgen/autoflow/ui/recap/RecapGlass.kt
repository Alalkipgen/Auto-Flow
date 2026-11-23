package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
