package io.github.alalkipgen.autoflow.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/*
 * Phase 9 glass look (Exact UI v4, owner approved): translucent fills and borders over an ambient background on the
 * tab screens (Home, Projects, Library, Jobs, Settings). No backdrop blur and no new dependency — the look is the
 * fills alone. Typography, icons and status tones stay as in [AutoFlowColors] / [AfType].
 */

/** Glass colours (Exact UI v4 token table). */
object GlassColors {
    /** Tab screens' base background (below the ambient blobs). */
    val Base = Color(0xFF090E1D)

    /** Ambient blobs: colour, alpha, centre as a fraction of the screen (x may lie outside), radii in dp (x, y). */
    internal class Blob(val colour: Color, val x: Float, val y: Float, val radiusX: Float, val radiusY: Float)
    internal val Ambient = listOf(
        Blob(Color(0xFF7C3AED).copy(alpha = .50f), -.08f, .02f, 420f, 360f),
        Blob(Color(0xFF22D3EE).copy(alpha = .22f), 1.12f, .22f, 380f, 340f),
        Blob(Color(0xFFF59E0B).copy(alpha = .14f), -.10f, .52f, 420f, 380f),
        Blob(Color(0xFF8B5CF6).copy(alpha = .30f), 1.10f, .72f, 420f, 380f),
        Blob(Color(0xFF2DD4BF).copy(alpha = .16f), 0f, .98f, 420f, 380f),
    )

    /** Every radial (ambient, tints, hero) fades to transparent at this fraction of its radius. */
    const val Fade = .62f

    // Glass card: linear 160° White .10 → .035 (55 %) → .02, 1 dp White .09 border with a .16 top highlight.
    val CardTop = Color.White.copy(alpha = .10f)
    val CardMid = Color.White.copy(alpha = .035f)
    val CardBottom = Color.White.copy(alpha = .02f)
    val CardBorder = Color.White.copy(alpha = .09f)
    val CardHighlight = Color.White.copy(alpha = .16f)
    val CardShadow = Color(0xFF02040E).copy(alpha = .35f)

    /** Flat variant (removed-projects row): no shadow. */
    val FlatFill = Color.White.copy(alpha = .03f)
    val FlatBorder = Color.White.copy(alpha = .14f)

    // Thumbnail tint: the card's own frame, blurred, at .42 under a Base .15 → .65 horizontal overlay (the end was .55;
    // raised on #286, as the owner asked, so MutedOnArt captions keep 4.5:1 over a near-white frame).
    const val ThumbAlpha = .42f
    val ThumbOverlayStart = Base.copy(alpha = .15f)
    val ThumbOverlayEnd = Base.copy(alpha = .65f)

    /**
     * Muted (caption) text on a card drawn with its thumbnail's frame (owner decision on #286, option b): the frame can
     * be bright, so these captions are lighter than [AutoFlowColors.Muted], which stays everywhere else.
     */
    val MutedOnArt = Color(0xFFC4CBDD)

    /** Colour tint alphas per surface. */
    const val TintProjects = .25f
    const val TintHomeTile = .22f
    const val TintJobs = .22f
    const val TintLibrary = .18f
    const val TintLexicon = .16f

    // Hero: radial Purple .45 top-start + radial Cyan .18 bottom-end over linear White .12 → .04.
    val HeroPurple = Color(0xFF8B5CF6).copy(alpha = .45f)
    val HeroCyan = Color(0xFF22D3EE).copy(alpha = .18f)
    val HeroTop = Color.White.copy(alpha = .12f)
    val HeroBottom = Color.White.copy(alpha = .04f)
    val HeroBorder = Color(0xFFC4B5FD).copy(alpha = .35f)
    val HeroHighlight = Color.White.copy(alpha = .22f)
    val HeroShadow = Color(0xFF4C1D95).copy(alpha = .35f)

    // Buttons. Primary starts at PrimaryFill (white text AA) and ends at #6D28D9.
    val PrimaryStart = AutoFlowColors.PrimaryFill
    val PrimaryEnd = Color(0xFF6D28D9)
    val PrimaryGlow = AutoFlowColors.PrimaryFill.copy(alpha = .45f)
    val PrimaryHighlight = Color.White.copy(alpha = .25f)
    val SecondaryFill = Color.White.copy(alpha = .08f)
    val SecondaryBorder = Color.White.copy(alpha = .14f)
    val DangerFill = AutoFlowColors.Failed.copy(alpha = .10f)
    val DangerBorder = AutoFlowColors.Failed.copy(alpha = .45f)
    val IconButtonHighlight = Color.White.copy(alpha = .12f)

    // Chips.
    val ModeChipFill = Color.White.copy(alpha = .08f)
    val ModeChipBorder = Color.White.copy(alpha = .10f)
    val FilterOffFill = Color.White.copy(alpha = .06f)
    val FilterOffBorder = Color.White.copy(alpha = .12f)
    val FilterOnCount = Color.White.copy(alpha = .75f)

    // Progress: track White .10, fill Cyan → Purple with a Cyan glow.
    val Track = Color.White.copy(alpha = .10f)
    val ProgressStart = AutoFlowColors.Cyan
    val ProgressEnd = AutoFlowColors.Purple
    val ProgressGlow = AutoFlowColors.Cyan.copy(alpha = .6f)

    /** Thumbnails: 1 dp White .08 border over a black .35 shadow. */
    val ThumbBorder = Color.White.copy(alpha = .08f)
    val ThumbShadow = Color.Black.copy(alpha = .35f)

    val Divider = Color.White.copy(alpha = .08f)

    /** Bottom nav over the ambient background. */
    val NavFill = Base.copy(alpha = .72f)
    val NavLine = Color.White.copy(alpha = .08f)

    /** Tile highlights (inset top line). */
    val TileHighlight = Color.White.copy(alpha = .35f)

    // Mode colours (tiles, mode chips, tints).
    val MovieRecap = Color(0xFF8B5CF6)
    val MusicVideo = Color(0xFF2DD4BF)
    val Subtitles = Color(0xFF60A5FA)
    val Voice = Color(0xFF22D3EE)
    val Dubbing = Color(0xFFF59E0B)

    /** Music Video Input's Video card tile (Group 3 B 4, film-strip). */
    val MvVideoTile = Color(0xFF60A5FA)

    /** The Needs Flow status pill and number tile (Group 3 §3.2, sample 05): the lighter purple, readable at 11.5 sp. */
    val ShotNeedsFlow = Color(0xFFA78BFA)

    /** A shot list row (Group 3 C, sample 05 `.srow`): White .04 + .09 line; the selected one Purple .12 + .55 line. */
    val ShotRowFill = Color.White.copy(alpha = .04f)
    val ShotRowBorder = Color.White.copy(alpha = .09f)
    val ShotRowSelectedFill = AutoFlowColors.Purple.copy(alpha = .12f)
    val ShotRowSelectedBorder = AutoFlowColors.Purple.copy(alpha = .55f)

    /** The All shots row (Group 3 C, sample 05 `.sub`): White .03 + .10 line. */
    val AllShotsFill = Color.White.copy(alpha = .03f)

    /**
     * One shot's sheet (Group 3 D, samples 07 / 08): step 3 References Blue; the step number badge (#4B5573, green when
     * done, a 2 dp #151B33 ring); the navigator's "3 / 12" chip (`.ofn` White .06 + .12 line), lyric quote (`.quote`
     * Black .20 + White .08 line) and step dots (`.dt` White .06 + .22 line, the next one Cyan .14; `.dl` White .14).
     */
    val SheetRefs = Color(0xFF60A5FA)
    val StepBadge = Color(0xFF4B5573)
    val StepBadgeRing = Color(0xFF151B33)
    val PositionChipFill = Color.White.copy(alpha = .06f)
    val PositionChipBorder = Color.White.copy(alpha = .12f)
    val QuoteFill = Color.Black.copy(alpha = .20f)
    val QuoteBorder = Color.White.copy(alpha = .08f)
    val StepDotFill = Color.White.copy(alpha = .06f)
    val StepDotBorder = Color.White.copy(alpha = .22f)
    val StepDotNowFill = AutoFlowColors.Cyan.copy(alpha = .14f)
    val StepLine = Color.White.copy(alpha = .14f)

    /**
     * The shot sheet's Storyboard and Prompt cards (Group 3 D inner, samples 07 / 08): the approved step card's outline
     * (`.okb` Green .45, 1 dp), the stale one (`.staleb` Stale .60, 1.5 dp) and the camera / mood tag pills (White .06 + .14).
     */
    val StepApprovedOutline = AutoFlowColors.Green.copy(alpha = .45f)
    val StepStaleOutline = AutoFlowColors.Stale.copy(alpha = .60f)
    val TagPillFill = Color.White.copy(alpha = .06f)
    val TagPillBorder = Color.White.copy(alpha = .14f)

    /**
     * The shot sheet's References and Flow handoff cards (Group 3 D inner 3–4, samples 07 / 08): the chosen Ref tile
     * (`.rt9.on` lavender .12 under a 2 dp [ShotNeedsFlow] line; the others a White .12 line), the thumbnail corner badge
     * (`.rk9` Base-dark .72) and the video tile's play mark (`.tp` .5 fill, a White .45 ring). Thumbnails keep [ThumbBorder].
     */
    val RefChosenFill = Color(0xFFA78BFA).copy(alpha = .12f)
    val RefTileBorder = Color.White.copy(alpha = .12f)
    val ThumbBadgeFill = Color(0xFF050812).copy(alpha = .72f)
    val PlayMarkFill = Color(0xFF050812).copy(alpha = .5f)
    val PlayMarkRing = Color.White.copy(alpha = .45f)

    /**
     * The shot sheet's Videos card and desktop media pane (Group 3 D inner 5 / E, samples 07 / 08 / 10): the "v2" version tag
     * (`.vt9` White .08 + .14 line), the big play mark on the pane's video (`.vplay.big` Base .55, a 1.5 dp White .55 ring)
     * and the media pane's Cyan tint (.14).
     */
    val VersionTagFill = Color.White.copy(alpha = .08f)
    val VersionTagBorder = Color.White.copy(alpha = .14f)
    val BigPlayFill = Color(0xFF0A0E1C).copy(alpha = .55f)
    val BigPlayRing = Color.White.copy(alpha = .55f)
    const val TintMediaPane = .14f

    // Music Video Inbox (Group 3 F, sample 11): the file kind tiles (video lavender, image blue, other Muted), each file's
    // row card (White .04 fill, 1 dp White .10 border, radius 16) and the Shared to Auto Flow card's 1 dp Cyan .5 line.
    val InboxVideo = Color(0xFFA78BFA)
    val InboxImage = Color(0xFF60A5FA)
    val InboxRowFill = Color.White.copy(alpha = .04f)
    val InboxRowBorder = Color.White.copy(alpha = .10f)
    val InboxSharedLine = Color(0xFF22D3EE).copy(alpha = .5f)
    const val TintInboxShared = .16f

    // Music Video Preview (Group 3 G + 🆕-3, samples 12 / 12b): the card's blue tint, the time chip on the picture, a
    // pending shot's dark glass (radial glow → deep, White .08 card with a White .16 line) and the picture's 1 dp edge.
    const val TintMvPreview = .16f
    val MvTimeChip = Color(0xFF050812).copy(alpha = .62f)
    val MvPendingGlow = Color(0xFF2A2050)
    val MvPendingDeep = Color(0xFF070914)
    val MvPendingFill = Color.White.copy(alpha = .08f)
    val MvPendingBorder = Color.White.copy(alpha = .16f)
    val MvFrameEdge = Color.White.copy(alpha = .08f)

    // Library list tints (at [TintLibrary]) and their row tiles; the lexicon card is tinted Voices at [TintLexicon].
    val LibraryKits = Color(0xFF8B5CF6)
    val LibraryVoices = Color(0xFFA78BFA)
    val LibraryMusic = Color(0xFF2DD4BF)
    val LibraryFonts = Color(0xFFF59E0B)
    val LibraryPresets = Color(0xFF22D3EE)
    /** A Brand Kit swatch's 1 dp border. */
    val SwatchBorder = Color.White.copy(alpha = .18f)
    /** Lexicon entry: White .04 row fill; the spoken form in lavender. */
    val LexiconRowFill = Color.White.copy(alpha = .04f)
    val LexiconSpoken = Color(0xFFC6B8FF)
    /** An empty Library section's row: White .04 fill, 1 dp White .08 border; its tile at [EmptyTileAlpha]. */
    val EmptyRowFill = Color.White.copy(alpha = .04f)
    val EmptyRowBorder = Color.White.copy(alpha = .08f)
    const val EmptyTileAlpha = .6f

    /**
     * Dialogs ([io.github.alalkipgen.autoflow.ui.components.GlassDialog], Projects dialogs README §1): scrim Base
     * `#04060E` .66; surface `#141B33` .97 under a 160° White .07 → .02 (55 %) gradient, 1 dp White .12 border with a
     * White .18 top highlight, black .55 shadow — opaque enough for AA over any card.
     */
    val DialogScrim = Color(0xFF04060E).copy(alpha = .66f)
    val DialogFill = Color(0xFF141B33).copy(alpha = .97f)
    val DialogTop = Color.White.copy(alpha = .07f)
    val DialogBottom = Color.White.copy(alpha = .02f)
    val DialogBorder = Color.White.copy(alpha = .12f)
    val DialogHighlight = Color.White.copy(alpha = .18f)
    val DialogShadow = Color.Black.copy(alpha = .55f)
    /** Path box: black .22 fill, White .10 border. */
    val PathFill = Color.Black.copy(alpha = .22f)
    val PathBorder = Color.White.copy(alpha = .10f)
    /** The dashed drop box frame (Group 3 B 1). */
    val DashedBorder = Color.White.copy(alpha = .18f)
    /** Menu row: White .04 fill, White .09 border. */
    val MenuRowFill = Color.White.copy(alpha = .04f)
    val MenuRowBorder = Color.White.copy(alpha = .09f)
    /** Neutral dialog tiles (⋮, folder). */
    val Neutral = Color(0xFF94A3B8)

    // Movie Recap step cards (Group 2 A §2): 30 dp soft header tiles and Style layer tiles.
    val RecapPink = Color(0xFFF472B6)
    val RecapMovie = AutoFlowColors.Purple
    val RecapSplit = AutoFlowColors.Cyan
    val RecapScan = AutoFlowColors.Info
    val RecapSubtitles = Subtitles
    val RecapLavender = LibraryVoices
    val RecapScenes = AutoFlowColors.Amber
    val RecapTeal = MusicVideo
    val LayerImage = Subtitles
    val LayerText = RecapPink
    val LayerBorder = AutoFlowColors.Amber
    val LayerBlur = AutoFlowColors.Cyan
    val LayerBackground = AutoFlowColors.Info
    // Movie Recap Export step (Group 2 B): Render preview / Publish Kit Blue, Exact frames lavender, Render package Teal.
    val ExportBlue = Subtitles
    val ExportLavender = LibraryVoices
    val ExportTeal = MusicVideo
    /** Option (radio) row: White .04 fill, White .10 border; selected Purple .14 (+ its 1.5 dp ring). */
    val OptionFill = Color.White.copy(alpha = .04f)
    val OptionBorder = Color.White.copy(alpha = .10f)
    val OptionOnFill = AutoFlowColors.Purple.copy(alpha = .14f)
    /** Scene / layer row: White .04 fill, White .09 border; picked Purple .12 with a Purple .55 border. */
    val RowFill = Color.White.copy(alpha = .04f)
    val RowBorder = Color.White.copy(alpha = .09f)
    val RowOnFill = AutoFlowColors.Purple.copy(alpha = .12f)
    val RowOnBorder = AutoFlowColors.Purple.copy(alpha = .55f)
    /** The block card playing in the top preview: 1.5 dp Cyan .75. */
    val PlayingOutline = AutoFlowColors.Cyan.copy(alpha = .75f)

    // Settings pages (P9-17a, Settings sample src/gen9.js).
    /** Panel top bar: vertical White .08 → .03, 1 dp White .08 line under it. */
    val TopBarTop = Color.White.copy(alpha = .08f)
    val TopBarBottom = Color.White.copy(alpha = .03f)
    val TopBarLine = Color.White.copy(alpha = .08f)
    /** Neutral (Surface3) row tile: White .08 fill, White .12 border, Text icon. */
    val NeutralTileFill = Color.White.copy(alpha = .08f)
    val NeutralTileBorder = Color.White.copy(alpha = .12f)
    /** Text field: black .25 fill, White .12 border. */
    val FieldFill = Color.Black.copy(alpha = .25f)
    val FieldBorder = Color.White.copy(alpha = .12f)
    /** Masked-key / version pill: black .25 fill, White .10 border. */
    val PillFill = Color.Black.copy(alpha = .25f)
    val PillBorder = Color.White.copy(alpha = .10f)
    /** Two-segment control track: black .22 fill, White .10 border. */
    val SegmentFill = Color.Black.copy(alpha = .22f)
    val SegmentBorder = Color.White.copy(alpha = .10f)
    /** Mono boxes: the models JSON (black .30) and one-line readouts (black .22), White .08 border. */
    val JsonFill = Color.Black.copy(alpha = .30f)
    val MonoFill = Color.Black.copy(alpha = .22f)
    val MonoBorder = Color.White.copy(alpha = .08f)
    /** Bottom fade of the JSON box while more text is below. */
    val JsonFade = Base.copy(alpha = .70f)
    /** Radio off ring (2 dp). */
    val RadioOff = Color.White.copy(alpha = .25f)
    /** Switch off track (White .14 + .12 border), its knob, and the inner collapsible row (White .04 + .09). */
    val SwitchOffFill = Color.White.copy(alpha = .14f)
    val SwitchOffBorder = Color.White.copy(alpha = .12f)
    val SwitchKnobOff = AutoFlowColors.Text
    val SwitchKnobShadow = Color.Black.copy(alpha = .35f)
    val InnerRowFill = Color.White.copy(alpha = .04f)
    val InnerRowBorder = Color.White.copy(alpha = .09f)
    /** Key / font cards: their tile colour at .14 behind the glass. */
    const val TintSettingsCard = .14f
}

/** Glass shapes and sizes (dp). */
object GlassShapes {
    val Card = RoundedCornerShape(18.dp)
    val Hero = RoundedCornerShape(20.dp)
    val Tile = RoundedCornerShape(11.dp)
    val IconButton = RoundedCornerShape(12.dp)
    val Pill = RoundedCornerShape(50)
    val CardPadding = 12.dp
    val CardGap = 8.dp
    val JobCardGap = 10.dp
    val HeroPadding = 14.dp
    val HeroGap = 10.dp
    val IconButtonCard = 36.dp
    val IconButtonHeader = 40.dp
    val ModeChip = 25.dp
    val Progress = 5.dp
    val PlayCircle = 40.dp
    val CardShadow = 10.dp
    val HeroShadow = 12.dp
    val ButtonShadow = 6.dp
    val TileShadow = 6.dp
    val ThumbShadow = 4.dp
    val DividerStart = 58.dp
    val DividerEnd = 12.dp

    /** Glass list (Library): card padding 4 × 0; rows min 52, padding 12 × 6, gap 12, 34 dp leading tile. */
    val ListPaddingY = 4.dp
    val ListRowMin = 52.dp
    val ListRowPadX = 12.dp
    val ListRowPadY = 6.dp
    val ListRowGap = 12.dp
    val ListTile = 34.dp
    /** Lexicon card padding 10, entries gap 6; entry radius 10, padding 6 × 4, written column 84 (× font scale). */
    val LexiconPadding = 10.dp
    val LexiconGap = 6.dp
    val LexiconRow = RoundedCornerShape(10.dp)
    val LexiconWritten = 84.dp
    /** An empty Library section's row: radius 14, padding 12 × 10, gap 10, 28 dp tile. */
    val EmptyRow = RoundedCornerShape(14.dp)
    val EmptyRowPadX = 12.dp
    val EmptyRowPadY = 10.dp
    val EmptyRowGap = 10.dp
    val EmptyTile = 28.dp

    /** Dialogs: width min(screen − 2 × margin, 400), radius 22, padding 20, gap 14, 44 dp header tile, shadow 24. */
    val Dialog = RoundedCornerShape(22.dp)
    val DialogMaxWidth = 400.dp
    val DialogMargin = 16.dp
    val DialogPadding = 20.dp
    val DialogGap = 14.dp
    val DialogTile = 44.dp
    val DialogShadow = 24.dp
    /** Dialog buttons: gap between two in a row and between stacked ones. */
    val DialogButtonGap = 10.dp
    /** Menu row (radius 14, min 56, 36 dp tile), path box and callout (radius 12). */
    val MenuRow = RoundedCornerShape(14.dp)
    val MenuRowMin = 56.dp
    val MenuTile = 36.dp
    val PathBox = RoundedCornerShape(12.dp)
    /** A Music Video Inbox file card (sample 11 `.rc`). */
    val InboxRow = RoundedCornerShape(16.dp)
    val Callout = RoundedCornerShape(12.dp)

    /** Tab screen spacing: section gap, inside a section, between cards. */
    val SectionGap = 18.dp
    val InSection = 8.dp
    val BetweenCards = 10.dp

    // Settings pages (P9-17a): Panel bar padding 8 × 12, row min 56 (padding 8 × 12, 32 dp tile, gap 12), dividers
    // inset 56; field 48 radius 12; pill radius 8; segment track radius 12 (padding 3) with 38 dp items radius 9;
    // mono box radius 10, JSON box radius 12 (max 240); radio 22; switch 46 × 28; option row min 52.
    val TopBarPadX = 12.dp
    val TopBarPadY = 8.dp
    val SettingsRowMin = 56.dp
    val SettingsRowPadX = 12.dp
    val SettingsRowPadY = 8.dp
    val SettingsTile = 32.dp
    val SettingsDividerStart = 56.dp
    val Field = RoundedCornerShape(12.dp)
    val Pill8 = RoundedCornerShape(8.dp)
    val SegmentTrack = RoundedCornerShape(12.dp)
    val SegmentItem = RoundedCornerShape(9.dp)
    val SegmentItemHeight = 38.dp
    val MonoBox = RoundedCornerShape(10.dp)
    val JsonBox = RoundedCornerShape(12.dp)
    val JsonMax = 240.dp
    val Radio = 22.dp
    val SwitchTrack = DpSize(46.dp, 28.dp)
    val SwitchKnob = 20.dp
    val OptionRowMin = 52.dp
    val CardHeaderMin = 34.dp
    val InnerRow = RoundedCornerShape(12.dp)
}

/**
 * A CSS `linear-gradient(<angle>deg, …)` over [size]: 0° points up, 90° right; the gradient line passes through the
 * centre and is long enough for the corners to reach the first and last stop (as in CSS).
 */
fun cssLinearGradient(angleDeg: Float, size: Size, vararg stops: Pair<Float, Color>): Brush {
    val rad = angleDeg * PI.toFloat() / 180f
    val dx = sin(rad); val dy = -cos(rad)
    val half = (abs(size.width * dx) + abs(size.height * dy)) / 2f
    val centre = Offset(size.width / 2f, size.height / 2f)
    return Brush.linearGradient(*stops, start = centre - Offset(dx, dy) * half, end = centre + Offset(dx, dy) * half)
}

/** A radial from [colour] to its transparent self at [GlassColors.Fade] (premultiplied-like fade, no dark fringe). */
fun glassRadial(colour: Color, centre: Offset, radius: Float): Brush =
    Brush.radialGradient(0f to colour, GlassColors.Fade to colour.copy(alpha = 0f), center = centre, radius = radius.coerceAtLeast(1f))

/** True where `Modifier.blur` draws (Android 12+ RenderEffect, desktop Skia); else the colour tint is used. */
internal expect val GlassBlurSupported: Boolean
