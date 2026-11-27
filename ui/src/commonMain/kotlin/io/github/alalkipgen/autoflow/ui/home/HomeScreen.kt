package io.github.alalkipgen.autoflow.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.recap.RecapStyle
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.icons.afTooltip
import io.github.alalkipgen.autoflow.ui.jobs.*
import io.github.alalkipgen.autoflow.ui.media.MediaThumbnails
import io.github.alalkipgen.autoflow.ui.projects.ProjectEntry
import io.github.alalkipgen.autoflow.ui.theme.*

/** Transient wizard state only; the existing Quick Recap engine owns creation and approval. */
private enum class WizardStep { IDLE, LENGTH, STYLE, CONFIRM }

/**
 * Home (DESIGN.md §9.7 panel 1, glass look §9.10): header, the Quick Recap hero, Continue (the latest project with its
 * thumbnail), Create tiles, quick tools and running jobs. Home never estimates progress: the bar shows only accepted /
 * total shots. [thumbSource] reads a project's recorded source (read-only, off the main thread) for its thumbnail; the
 * frame itself comes from the app cache ([io.github.alalkipgen.autoflow.ui.media.LocalMediaThumbnails]). Jobs uses
 * the identical projection. No background of its own: the shell's ambient shows through.
 */
@Composable
fun HomeScreen(
    onNewRecap: () -> Unit = {},
    onNewMusicVideo: () -> Unit = {},
    onQuickRecapStart: (targetMs: Long, style: RecapStyle) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    status: String? = null,
    notice: String? = null,
    recent: List<ProjectEntry> = emptyList(),
    onOpenProject: (dir: String) -> Unit = {},
    jobs: List<JobRow> = rememberJobRows(),
    onViewJobs: () -> Unit = {},
    continueStep: String? = null,
    continueProjectPath: String? = null,
    backHandler: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> },
    jobActions: io.github.alalkipgen.autoflow.ui.jobs.JobsActions? = null,
    /** Opens the picker for a new Subtitles project; null keeps the tile Planned (flag `tools.subtitles` off, D-84). */
    onNewSubtitles: (() -> Unit)? = null,
    /** Opens the Voice & Audio chooser; null keeps the tile Planned (flag `tools.voiceAudio` off, D-84). */
    onNewVoice: (() -> Unit)? = null,
    /** Opens the picker for a new Dubbing project; null keeps the tile Planned (flag `labs.dubbing` off, D-84). */
    onNewDubbing: (() -> Unit)? = null,
    /** "All ›" beside Continue. */
    onOpenProjects: () -> Unit = {},
    /** A project's thumbnail source by folder; null = placeholders only. */
    thumbSource: (suspend (dir: String) -> MediaThumbnails.Source?)? = null,
) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        // D1 (DESIGN.md §9.8): from AfDesktop.HomeWide the hero shares its row with the Jobs column, Continue shows up
        // to 4 cards and the tiles form one row; below it the phone column (centred ≤ 720 dp).
        val wide = maxWidth >= AfDesktop.HomeWide
        val running = jobs.filter { it.running }
        val jobCard: @Composable (Int, JobRow) -> Unit = { i, row ->
            JobItemCard(row, thumbSource, Modifier.testTag("home-job-$i")) {
                if (row.canCancel && jobActions?.canCancel(row) == true) {
                    GlassButton(tr(UiText.CANCEL), { jobActions.cancel(row) }, Modifier.testTag("home-job-cancel-$i"), kind = ButtonKind.Danger, small = true)
                }
            }
        }
        GlassScrollColumn(Modifier.widthIn(max = if (wide) AfDesktop.HomeMax else AfSize.ContentMax).fillMaxSize().testTag("home-scroll"),
            PaddingValues(screenPadding), Arrangement.spacedBy(if (wide) AfDesktop.PaneGap else GlassShapes.SectionGap)) {
            HomeHeader(onOpenSettings)
            status?.let { AfText(My.breakSyllables(it), muted = true) }
            notice?.let { AfText(My.breakSyllables(it), color = AutoFlowColors.Failed) }
            if (wide && running.isNotEmpty()) HeroRow({ QuickRecapWizardCard(onQuickRecapStart, onNewRecap, backHandler, wide = true) }) {
                HomeSection(tr(UiText.JOB_RUNNING), Modifier.testTag("home-running"), { SeeAll(onViewJobs, "home-view-jobs") }) {
                    running.take(AfDesktop.HomeJobs).forEachIndexed { i, row -> jobCard(i, row) }
                }
            } else QuickRecapWizardCard(onQuickRecapStart, onNewRecap, backHandler, wide,
                // Phone: the hero is 84 % of the column, centred (Exact UI v4); desktop: the full row.
                if (wide) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(HeroWidth).align(Alignment.CenterHorizontally))
            val latest = recent.maxByOrNull { it.lastOpenedAt }
            if (latest == null) AfText(My.breakSyllables(tr(UiText.HOME_EMPTY)), muted = true)
            else HomeSection(tr(UiText.V3_CONTINUE), Modifier.testTag("home-continue"), { SeeAll(onOpenProjects, "home-all-projects") }) {
                val step = continueStep.takeIf { latest.path == continueProjectPath }
                if (wide) ContinueRow(recent.sortedByDescending { it.lastOpenedAt }, step, onOpenProject, thumbSource)
                else ContinueCard(latest, step, onOpenProject, thumbSource)
            }
            val recap: Tile = { m, stacked -> HomeTile(Mode.MOVIE_RECAP, tr(UiText.MOVIE_RECAP),
                tr(UiText.HOME_RECAP_CAPTION), onNewRecap, m.testTag("home-new-recap"), stacked) }
            val mv: Tile = { m, stacked -> HomeTile(Mode.MUSIC_VIDEO, tr(UiText.MUSIC_VIDEO),
                tr(UiText.HOME_MV_CAPTION), onNewMusicVideo, m.testTag("home-new-mv"), stacked) }
            val dub: Tile = { m, stacked -> HomeTile(Mode.DUBBING, tr(UiText.V3_DUBBING),
                tr(UiText.HOME_DUB_CAPTION), onNewDubbing, m.testTag("home-dubbing"), stacked) }
            val voice: Tile = { m, stacked -> HomeTile(Mode.VOICE_AUDIO, tr(UiText.V3_VOICE_AUDIO),
                tr(UiText.HOME_VOICE_CAPTION), onNewVoice, m.testTag("home-voice-tool"), stacked) }
            val subs: Tile = { m, stacked -> HomeTile(Mode.SUBTITLES, tr(UiText.V3_SUBTITLES),
                tr(UiText.HOME_SUBS_CAPTION), onNewSubtitles, m.testTag("home-subtitles-tool"), stacked) }
            val toolsNote = @Composable {
                if (onNewVoice == null || onNewSubtitles == null) {
                    AfText(My.breakSyllables(tr(UiText.V3_TOOLS_IN_PROJECT)), color = AutoFlowColors.Muted, type = AfType.Caption)
                }
            }
            if (wide) HomeSection(tr(UiText.CREATE)) { TileGrid(5, listOf(recap, mv, dub, voice, subs)); toolsNote() }
            else {
                HomeSection(tr(UiText.CREATE)) { TileGrid(3, listOf(recap, mv, dub)) }
                HomeSection(tr(UiText.V3_QUICK_TOOLS)) { toolsNote(); TileGrid(2, listOf(voice, subs)) }
            }
            if (!wide && running.isNotEmpty()) HomeSection(tr(UiText.JOB_RUNNING), Modifier.testTag("home-running"),
                { SeeAll(onViewJobs, "home-view-jobs") }) {
                running.forEachIndexed { i, row -> jobCard(i, row) }
            }
        }
    }
}

/** The phone hero's share of the column width (Exact UI v4). */
private const val HeroWidth = .84f

private typealias Tile = @Composable (Modifier, stacked: Boolean) -> Unit

/**
 * D1 top row: the Jobs column ([AfDesktop.DetailPane], at most half the row) on the right, the hero takes the rest and
 * is at least as tall (its content centred), so the two panes end on one line.
 */
@Composable
private fun HeroRow(hero: @Composable () -> Unit, side: @Composable () -> Unit) {
    Layout({ Box(propagateMinConstraints = true) { hero() }; Box { side() } }) { (heroPart, sidePart), c ->
        val gap = AfDesktop.PaneGap.roundToPx()
        val sideWidth = AfDesktop.DetailPane.roundToPx().coerceAtMost((c.maxWidth - gap) / 2)
        val sidePlaced = sidePart.measure(Constraints.fixedWidth(sideWidth))
        val heroWidth = c.maxWidth - sideWidth - gap
        val heroPlaced = heroPart.measure(Constraints(heroWidth, heroWidth, sidePlaced.height, Constraints.Infinity))
        layout(c.maxWidth, maxOf(heroPlaced.height, sidePlaced.height)) {
            heroPlaced.place(0, 0); sidePlaced.place(heroWidth + gap, 0)
        }
    }
}

/** 44 dp filled spark tile, "Auto Flow" with the greeting, and the 40 dp glass gear (Settings). */
@Composable
private fun HomeHeader(onOpenSettings: () -> Unit) {
    // Top-aligned: with a large font the title and greeting grow to several lines and the tiles stay beside the title.
    Row(Modifier.fillMaxWidth().testTag("home-header"), verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(AfSpace.M)) {
        FilledTile(AfIcons.Fill.Spark, AutoFlowColors.PrimaryFill, size = AfSize.Tile)
        Column(Modifier.weight(1f)) {
            AfText(tr(UiText.V3_APP_NAME), type = AfType.ScreenTitle)
            AfText(My.breakSyllables(tr(UiText.HOME_GREETING)), color = AutoFlowColors.Muted, type = AfType.Caption)
        }
        GlassIconButton(AfIcons.Regular.Gear, tr(UiText.SETTINGS_TITLE), onOpenSettings, Modifier.testTag("home-settings"),
            size = GlassShapes.IconButtonHeader)
    }
}

/** A section title (14 sp bold, with an optional "All ›") over its cards, 8 dp apart. */
@Composable
private fun HomeSection(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GlassShapes.InSection)) {
        GlassSectionHeader(title, action = action)
        content()
    }
}

@Composable
private fun SeeAll(onClick: () -> Unit, tag: String) =
    GlassButton(tr(UiText.HOME_SEE_ALL), onClick, Modifier.testTag(tag), kind = ButtonKind.Text, small = true)

/**
 * The latest project, on a glass card tinted with its own frame: thumbnail 96 × 60 with its recorded duration, title,
 * mode, the resume step, progress only from accepted / total shots, and the 40 dp play circle (Resume, tag
 * `recent-<name>`); a tap anywhere on the card opens it too.
 */
@Composable
private fun ContinueCard(entry: ProjectEntry, step: String?, onOpen: (String) -> Unit,
    thumbSource: (suspend (String) -> MediaThumbnails.Source?)?) {
    val source by produceState<MediaThumbnails.Source?>(null, entry.path, entry.lastOpenedAt, thumbSource) {
        value = thumbSource?.invoke(entry.path)
    }
    var art by remember(source) { mutableStateOf<ImageBitmap?>(null) }
    val thumb = @Composable {
        MediaThumb(source, entry.mode.icon(duotone = true), Modifier.glassThumb(), size = AfSize.ThumbContinue, durationMs = source?.durationMs,
            onImage = { art = it })
    }
    val resume = @Composable {
        PlayCircle(tr(UiText.HOME_RESUME, "name" to entry.name), { onOpen(entry.path) }, Modifier.testTag("recent-${entry.name}"))
    }
    val facts = @Composable { m: Modifier ->
        Column(m, verticalArrangement = Arrangement.spacedBy(AfSpace.Xs)) {
            AfText(entry.name, Modifier.semantics { contentDescription = entry.name }, type = AfType.RowTitle, maxLines = 1, ellipsis = true)
            GlassModeChip(entry.mode, Modifier.testTag("home-mode-${entry.mode.name}"))
            step?.let { AfText(My.breakSyllables(it), Modifier.testTag("home-continue-step"), color = glassCaptionColour(art), type = AfType.Caption) }
            if (entry.totalShots > 0) GlassProgress(entry.acceptedShots.coerceIn(0, entry.totalShots) / entry.totalShots.toFloat())
        }
    }
    // The whole card opens the project (a click layer under the content, so the texts stay separate nodes); the
    // round play button is the explicit Resume.
    Box(Modifier.fillMaxWidth()) {
        GlassCard(Modifier.matchParentSize().semantics { contentDescription = entry.name }, onClick = { onOpen(entry.path) }, thumbnail = art) {}
        BoxWithConstraints(Modifier.padding(GlassShapes.CardPadding)) {
            val gap = AfSpace.M
            // Large fonts: the thumbnail and play share the first row and the text gets the full width.
            if (maxWidth - AfSize.ThumbContinue.width - GlassShapes.PlayCircle - gap * 2 >= AfSize.TileCardMin * LocalDensity.current.fontScale) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
                    thumb(); facts(Modifier.weight(1f)); resume()
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    thumb(); resume()
                }
                facts(Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Desktop Continue (D1): the most recent projects as glass thumbnail cards in one row — as many as fit
 * [AfDesktop.ContinueCardMin] × the font scale, at most 4. A card opens its project (tag `recent-<name>`); the first
 * (latest) card carries the step text and the `home-mode-<MODE>` tag. The click layer sits under the content, so
 * the texts stay separate nodes.
 */
@Composable
private fun ContinueRow(entries: List<ProjectEntry>, step: String?, onOpen: (String) -> Unit,
    thumbSource: (suspend (String) -> MediaThumbnails.Source?)?) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = GlassShapes.BetweenCards
        val columns = ((maxWidth + gap) / (AfDesktop.ContinueCardMin * LocalDensity.current.fontScale + gap)).toInt().coerceIn(1, 4)
        val card = (maxWidth - gap * (columns - 1)) / columns
        val pad = GlassShapes.CardPadding
        val thumb = DpSize(card - pad * 2, (card - pad * 2) * 9f / 16f)
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
            entries.take(columns).forEachIndexed { i, entry ->
                val source by produceState<MediaThumbnails.Source?>(null, entry.path, entry.lastOpenedAt, thumbSource) {
                    value = thumbSource?.invoke(entry.path)
                }
                var art by remember(source) { mutableStateOf<ImageBitmap?>(null) }
                val label = tr(UiText.HOME_RESUME, "name" to entry.name)
                Box(Modifier.width(card).fillMaxHeight()) {
                    GlassCard(Modifier.matchParentSize().afTooltip(label).semantics { contentDescription = label }
                        .testTag("recent-${entry.name}"), onClick = { onOpen(entry.path) }, thumbnail = art) {}
                    Column(Modifier.padding(pad), verticalArrangement = Arrangement.spacedBy(AfSpace.S)) {
                        MediaThumb(source, entry.mode.icon(duotone = true), Modifier.glassThumb(), size = thumb, durationMs = source?.durationMs,
                            onImage = { art = it })
                        AfText(entry.name, Modifier.semantics { contentDescription = entry.name }, type = AfType.RowTitle, maxLines = 1, ellipsis = true)
                        GlassModeChip(entry.mode, if (i == 0) Modifier.testTag("home-mode-${entry.mode.name}") else Modifier)
                        if (i == 0) step?.let {
                            AfText(My.breakSyllables(it), Modifier.testTag("home-continue-step"), color = glassCaptionColour(art), type = AfType.Caption)
                        }
                        if (entry.totalShots > 0) GlassProgress(entry.acceptedShots.coerceIn(0, entry.totalShots) / entry.totalShots.toFloat())
                    }
                }
            }
        }
    }
}

/**
 * Equal tiles in rows, 10 dp apart: as many columns (≤ [max]) as fit [AfSize.TileCardMin] × the font scale; every
 * tile of a row is as tall as the tallest. A tile is `stacked` (icon over the text) unless it is twice that minimum wide.
 */
@Composable
private fun TileGrid(max: Int, tiles: List<Tile>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = GlassShapes.BetweenCards
        val min = AfSize.TileCardMin * LocalDensity.current.fontScale
        val columns = ((maxWidth + gap) / (min + gap)).toInt().coerceIn(1, max)
        val stacked = (maxWidth - gap * (columns - 1)) / columns < min * 2
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            tiles.chunked(columns).forEach { row ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { it(Modifier.weight(1f).fillMaxHeight(), stacked) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * Create / tool tile: a glass card tinted with the mode colour, the 40 dp filled tile (white Fill icon), title and a
 * one-line idea of what it makes, centred in the row's height. Without [onClick] the tool is switched off (D-84): a
 * muted icon on Surface3, no tint, "Planned" with the clock instead of the caption, and no tap (a chip would wrap
 * into a blob in a narrow tile).
 */
@Composable
private fun HomeTile(mode: Mode, title: String, caption: String, onClick: (() -> Unit)?, modifier: Modifier, stacked: Boolean) {
    val texts: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            AfText(My.breakSyllables(title), type = AfType.TileTitle, muted = onClick == null)
            if (onClick != null) AfText(My.breakSyllables(caption), color = AutoFlowColors.Muted, type = AfType.Caption)
            else Row(horizontalArrangement = Arrangement.spacedBy(AfSpace.Xs)) {
                AfIcon(StudioStatus.PLANNED.icon, tint = AutoFlowColors.Muted, size = AfSize.IconXs, modifier = Modifier.padding(top = AfSpace.Xs / 2))
                AfText(My.breakSyllables(StudioStatus.PLANNED.label(LocalUiLanguage.current)), color = AutoFlowColors.Muted, type = AfType.Caption)
            }
        }
    }
    val tile: @Composable () -> Unit = {
        if (onClick != null) FilledTile(mode.fillIcon(), mode.glassColour, size = TileIcon)
        else FilledTile(mode.fillIcon(), AutoFlowColors.Surface3, size = TileIcon, ink = AutoFlowColors.Muted)
    }
    GlassCard(if (onClick == null) modifier.semantics { disabled() } else modifier, onClick = onClick,
        tint = mode.glassColour.takeIf { onClick != null }, tintAlpha = GlassColors.TintHomeTile, gap = 10.dp, centred = true) {
        if (stacked) { tile(); texts(Modifier) }
        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AfSpace.M)) {
            tile(); texts(Modifier.weight(1f))
        }
    }
}

/** The filled tile on a Home tile (Exact UI v4). */
private val TileIcon = 40.dp

@Composable
private fun QuickRecapWizardCard(onStart: (Long, RecapStyle) -> Unit, onStepByStep: () -> Unit,
    backHandler: @Composable (Boolean, () -> Unit) -> Unit, wide: Boolean, modifier: Modifier = Modifier.fillMaxWidth(),
) {
    var step by remember { mutableStateOf(WizardStep.IDLE) }
    var targetMs by remember { mutableStateOf(300_000L) }
    var style by remember { mutableStateOf(RecapStyle.PLAIN) }

    backHandler(step != WizardStep.IDLE) { step = WizardStep.entries[(step.ordinal - 1).coerceAtLeast(0)] }
    GlassHero(modifier.testTag("home-quick")) {
        when (step) {
            // Exact UI v4: three items only — the title, Start (primary) and Step by step; the steps after Start are unchanged.
            WizardStep.IDLE -> {
                AfText(My.breakSyllables(tr(UiText.HOME_HERO_TITLE)), Modifier.fillMaxWidth(), color = Color.White, type = GlassSectionType,
                    weight = FontWeight.Bold, textAlign = TextAlign.Center)
                val start = @Composable { m: Modifier ->
                    GlassButton(tr(UiText.HOME_QUICK_START), { step = WizardStep.LENGTH }, m.testTag("home-quick-start"),
                        kind = ButtonKind.Primary, icon = AfIcons.Fill.Spark, small = !wide)
                }
                val stepByStep = @Composable { m: Modifier ->
                    GlassButton(tr(UiText.V3_STEP_BY_STEP), onStepByStep, m.testTag("home-step-by-step"), small = !wide)
                }
                // Desktop: the two buttons side by side at their own width; phone: both full width.
                if (wide) WrapRow(Modifier.align(Alignment.CenterHorizontally)) { start(Modifier); stepByStep(Modifier) }
                else { start(Modifier.fillMaxWidth()); stepByStep(Modifier.fillMaxWidth()) }
            }
            WizardStep.LENGTH -> {
                AfText(tr(UiText.LENGTH), type = AfType.RowTitle)
                WrapRow {
                    listOf(60_000L, 180_000L, 300_000L, 600_000L).map { it to My.minutes(it / 60_000) }.forEach { (ms, label) ->
                        AfChip(label, targetMs == ms, onClick = { targetMs = ms })
                    }
                }
                WrapRow {
                    AfButton(tr(UiText.HOME_NEXT_STYLE), { step = WizardStep.STYLE }, Modifier.testTag("quick-next-style"), kind = ButtonKind.Primary)
                    AfButton(tr(UiText.CANCEL), { step = WizardStep.IDLE })
                }
            }
            WizardStep.STYLE -> {
                AfText(tr(UiText.STYLE), type = AfType.RowTitle)
                WrapRow {
                    AfChip(styleLabel(RecapStyle.PLAIN, LocalUiLanguage.current), style == RecapStyle.PLAIN, onClick = { style = RecapStyle.PLAIN })
                    AfChip(styleLabel(RecapStyle.COMMENTARY, LocalUiLanguage.current), style == RecapStyle.COMMENTARY, onClick = { style = RecapStyle.COMMENTARY })
                }
                WrapRow {
                    AfButton("${tr(UiText.START)} →", { step = WizardStep.CONFIRM }, Modifier.testTag("quick-confirm"), kind = ButtonKind.Primary)
                    AfButton(tr(UiText.BACK), { step = WizardStep.LENGTH })
                }
            }
            WizardStep.CONFIRM -> {
                val minLabel = My.minutes(targetMs / 60_000)
                AfText(tr(UiText.HOME_CONFIRM, "minLabel" to (minLabel), "styleLabelstyle" to (styleLabel(style, LocalUiLanguage.current))), type = AfType.RowTitle)
                AfText(My.breakSyllables(tr(UiText.HOME_CONFIRM_HINT, "APPROVE" to (tr(UiText.APPROVE)), "EDIT" to (tr(UiText.EDIT)))),
                    color = AutoFlowColors.Muted, type = AfType.Caption)
                AfText(My.breakSyllables(tr(UiText.HOME_BACKGROUND)), color = AutoFlowColors.Muted, type = AfType.Caption)
                WrapRow {
                    AfButton(tr(UiText.START), {
                        onStart(targetMs, style)
                        step = WizardStep.IDLE
                    }, Modifier.testTag("quick-create"), kind = ButtonKind.Primary)
                    AfButton(tr(UiText.BACK), { step = WizardStep.STYLE })
                }
            }
        }
    }
}

/** The saved style remains an enum; project contents are never translated. */
internal fun styleLabel(style: RecapStyle, language: UiLanguage = UiLanguage.ENGLISH): String = when (style) {
    RecapStyle.PLAIN -> UiText.STYLE_PLAIN.text(language)
    RecapStyle.COMMENTARY -> UiText.STYLE_COMMENTARY.text(language)
}
