package io.github.alalkipgen.autoflow.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/** State tone (docs/DESIGN.md §9.2): always shown with an icon and a word, never by colour alone. */
enum class Tone(val fill: Color, val text: Color, val container: Color) {
    Success(AutoFlowColors.Green, AutoFlowColors.SuccessText, AutoFlowColors.Green.copy(alpha = .12f)),
    Warning(AutoFlowColors.Amber, AutoFlowColors.WarningText, AutoFlowColors.Amber.copy(alpha = .13f)),
    Error(AutoFlowColors.Failed, AutoFlowColors.Failed, AutoFlowColors.Failed.copy(alpha = .13f)),
    Info(AutoFlowColors.Info, AutoFlowColors.InfoText, AutoFlowColors.Info.copy(alpha = .12f)),
    Running(AutoFlowColors.Cyan, AutoFlowColors.Cyan, AutoFlowColors.Cyan.copy(alpha = .15f)),
    Neutral(AutoFlowColors.Line, AutoFlowColors.Muted, AutoFlowColors.Card),
    Primary(AutoFlowColors.PrimaryFill, AutoFlowColors.PrimaryText, AutoFlowColors.PrimaryFill.copy(alpha = .15f)),
}

/** Spacing steps (dp). Screen padding stays `screenPadding` (14 phone / 24 desktop). */
object AfSpace {
    val Xs = 4.dp
    val S = 8.dp
    val M = 12.dp
    val L = 16.dp
    val Xl = 24.dp
    val CardGap = 12.dp
    val DenseGap = 8.dp
}

/** Corner radii (dp). [AutoFlowShapes.Radius] (12) stays for today's cards until their PR. */
object AfRadius {
    val Card = 18.dp
    val ListCard = 14.dp
    val Button = 12.dp
    val ButtonSmall = 10.dp
    val Tile = 13.dp
    val TileSmall = 10.dp
    val Thumb = 10.dp
    val Player = 16.dp
    val Progress = 3.dp
}

/** Component sizes (dp): visual sizes; every control keeps a 48 dp touch target ([Touch]). */
object AfSize {
    val Touch = 48.dp
    val Button = 44.dp
    val ButtonSmall = 36.dp
    val IconButton = 40.dp
    val IconButtonCompact = 34.dp
    val StatusChip = 25.dp
    val FilterChip = 34.dp
    val Tile = 44.dp
    val TileCompact = 38.dp
    val TileSmall = 32.dp
    val Avatar = 40.dp
    val Progress = 6.dp
    val StepSegment = 4.dp
    val StepGap = 6.dp
    val AttentionBar = 4.dp
    val Badge = 19.dp
    val Icon = 22.dp
    val IconSmall = 18.dp
    val IconXs = 15.dp
    val IconLarge = 28.dp
    val ThumbList = DpSize(100.dp, 62.dp)
    val ThumbCompact = DpSize(64.dp, 40.dp)
    val ThumbContinue = DpSize(96.dp, 60.dp)
    val ThumbExport = DpSize(124.dp, 74.dp)
    val ThumbScene = DpSize(128.dp, 72.dp)

    /** A tile in a tile grid (Home create / tools) is at least this wide × the font scale; fewer columns otherwise. */
    val TileCardMin = 96.dp

    /** The narrowest short-fact column ("#n" + a time) beside a thumbnail × the font scale; stacked below otherwise. */
    val ThumbFactsMin = 80.dp

    /** One-column screens are capped at this width and centred at 840 / 1440 dp. */
    val ContentMax = 720.dp

    /** Medium width (600–839 dp): the phone layout, centred, beside the rail (DESIGN.md §9.8). */
    val ContentMaxMedium = 640.dp
}

/** Desktop shell sizes and width classes (docs/DESIGN.md §9.8). */
object AfDesktop {
    /** Width classes: compact < [Medium] ≤ medium < [Expanded] ≤ expanded; below [Wide] (and in editors) the rail replaces the sidebar. */
    val Medium = 600.dp
    val Expanded = 840.dp
    val Wide = 1200.dp

    val Sidebar = 244.dp
    val SidebarRow = 42.dp
    val SidebarRowRadius = 10.dp
    val SidebarTile = 26.dp
    val Rail = 72.dp
    val RailItem = 48.dp
    val ContentTop = 22.dp
    val ContentSide = 30.dp
    val PaneGap = 18.dp
    val PaneRadius = 16.dp
    val ListPaneMin = 420.dp
    val ListPaneMax = 480.dp
    val DetailPane = 360.dp
    val StickyButtonMin = 150.dp
    val SelectedAccent = 3.dp

    /** Home D1 from this Home width (window minus the nav): the hero beside the Jobs column (≤ [HomeJobs] cards). */
    val HomeWide = 900.dp
    val HomeMax = 1280.dp
    const val HomeJobs = 3
    /** A desktop Continue card is at least this wide × the font scale (≤ 4 in the row). */
    val ContinueCardMin = 200.dp

    /** Projects D2 from this Projects width: a card grid (cards ≥ [ProjectCardMin] × the font scale) + the detail pane. */
    val GridWide = 900.dp
    val ProjectCardMin = 240.dp

    /** The selected job's thumbnail in the Jobs detail pane is at most this wide (16:9). */
    val DetailThumbMax = 480.dp
}
