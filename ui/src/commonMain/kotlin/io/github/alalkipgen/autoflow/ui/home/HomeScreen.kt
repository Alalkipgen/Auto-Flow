package io.github.alalkipgen.autoflow.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import io.github.alalkipgen.autoflow.core.recap.RecapStyle
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.icons.IconTile
import io.github.alalkipgen.autoflow.ui.icons.afTooltip
import io.github.alalkipgen.autoflow.ui.jobs.*
import io.github.alalkipgen.autoflow.ui.media.MediaThumbnails
import io.github.alalkipgen.autoflow.ui.projects.ProjectEntry
import io.github.alalkipgen.autoflow.ui.theme.*

/** Transient wizard state only; the existing Quick Recap engine owns creation and approval. */
private enum class WizardStep { IDLE, LENGTH, STYLE, CONFIRM }

/**
 * Home (DESIGN.md §9.7, panel 1): header, the Quick Recap hero, Continue (the latest project with its thumbnail),
 * Create tiles, quick tools and running jobs. Home never estimates progress: the bar shows only accepted / total
 * shots. [thumbSource] reads a project's recorded source (read-only, off the main thread) for its thumbnail; the
 * frame itself comes from the app cache ([io.github.alalkipgen.autoflow.ui.media.LocalMediaThumbnails]). Jobs uses
 * the identical projection.
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
    Box(modifier.fillMaxSize().background(AutoFlowColors.Background), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = AfSize.ContentMax).fillMaxSize().paneScroll(rememberScrollState()).padding(screenPadding)
            .testTag("home-scroll"), verticalArrangement = Arrangement.spacedBy(AfSpace.L)) {
            HomeHeader(onOpenSettings)
            status?.let { AfText(My.breakSyllables(it), muted = true) }
            notice?.let { AfText(My.breakSyllables(it), color = AutoFlowColors.Failed) }
            QuickRecapWizardCard(onQuickRecapStart, onNewRecap, backHandler)
            val latest = recent.maxByOrNull { it.lastOpenedAt }
            if (latest == null) AfText(My.breakSyllables(tr(UiText.HOME_EMPTY)), muted = true)
            else HomeSection(tr(UiText.V3_CONTINUE), Modifier.testTag("home-continue"), { SeeAll(onOpenProjects, "home-all-projects") }) {
                ContinueCard(latest, continueStep.takeIf { latest.path == continueProjectPath }, onOpenProject, thumbSource)
            }
            HomeSection(tr(UiText.CREATE)) {
                TileGrid(3, listOf(
                    { m, stacked -> HomeTile(AfIcons.Duotone.Film, AutoFlowColors.PrimaryFill, tr(UiText.MOVIE_RECAP), tr(UiText.HOME_RECAP_CAPTION),
                        onNewRecap, m.testTag("home-new-recap"), stacked) },
                    { m, stacked -> HomeTile(AfIcons.Duotone.Music, AutoFlowColors.Info, tr(UiText.MUSIC_VIDEO), tr(UiText.HOME_MV_CAPTION),
                        onNewMusicVideo, m.testTag("home-new-mv"), stacked) },
                    { m, stacked -> HomeTile(AfIcons.Duotone.Dub, AutoFlowColors.Amber, tr(UiText.V3_DUBBING), tr(UiText.HOME_DUB_CAPTION),
                        onNewDubbing, m.testTag("home-dubbing"), stacked, ink = AutoFlowColors.Background) },
                ))
            }
            HomeSection(tr(UiText.V3_QUICK_TOOLS)) {
                if (onNewVoice == null || onNewSubtitles == null) {
                    AfText(My.breakSyllables(tr(UiText.V3_TOOLS_IN_PROJECT)), color = AutoFlowColors.Muted, type = AfType.Caption)
                }
                TileGrid(2, listOf(
                    { m, stacked -> HomeTile(AfIcons.Duotone.Wave, AutoFlowColors.Cyan, tr(UiText.V3_VOICE_AUDIO), tr(UiText.HOME_VOICE_CAPTION),
                        onNewVoice, m.testTag("home-voice-tool"), stacked, ink = AutoFlowColors.Background) },
                    { m, stacked -> HomeTile(AfIcons.Duotone.Subtitles, AutoFlowColors.Surface3, tr(UiText.V3_SUBTITLES), tr(UiText.HOME_SUBS_CAPTION),
                        onNewSubtitles, m.testTag("home-subtitles-tool"), stacked) },
                ))
            }
            val running = jobs.filter { it.running }
            if (running.isNotEmpty()) HomeSection(tr(UiText.JOB_RUNNING), Modifier.testTag("home-running"), { SeeAll(onViewJobs, "home-view-jobs") }) {
                running.forEachIndexed { i, row ->
                    JobRunCard(row, Modifier.testTag("home-job-$i")) {
                        if (row.canCancel && jobActions?.canCancel(row) == true) {
                            AfButton(tr(UiText.CANCEL), { jobActions.cancel(row) }, Modifier.testTag("home-job-cancel-$i"), kind = ButtonKind.Danger)
                        }
                    }
                }
            }
        }
    }
}

/** Spark tile, "Auto Flow" with the greeting, and the gear (Settings). */
@Composable
private fun HomeHeader(onOpenSettings: () -> Unit) {
    // Top-aligned: with a large font the title and greeting grow to several lines and the tiles stay beside the title.
    Row(Modifier.fillMaxWidth().testTag("home-header"), verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(AfSpace.M)) {
        IconTile(AfIcons.Fill.Spark, AutoFlowColors.PrimaryFill)
        Column(Modifier.weight(1f)) {
            AfText(tr(UiText.V3_APP_NAME), type = AfType.ScreenTitle)
            AfText(My.breakSyllables(tr(UiText.HOME_GREETING)), color = AutoFlowColors.Muted, type = AfType.Caption)
        }
        AfIconButton(AfIcons.Regular.Gear, tr(UiText.SETTINGS_TITLE), onOpenSettings, Modifier.testTag("home-settings"))
    }
}

/** A section title (with an optional "All ›") over its cards. */
@Composable
private fun HomeSection(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AfSpace.S)) {
        SectionHeader(title, action = action)
        content()
    }
}

@Composable
private fun SeeAll(onClick: () -> Unit, tag: String) =
    AfButton(tr(UiText.HOME_SEE_ALL), onClick, Modifier.testTag(tag), kind = ButtonKind.Text, small = true)

/**
 * The latest project: thumbnail 96 × 60 with its recorded duration, title, mode, the resume step, progress only from
 * accepted / total shots, and the round play button (Resume, tag `recent-<name>`); a tap anywhere on the card opens it too.
 */
@Composable
private fun ContinueCard(entry: ProjectEntry, step: String?, onOpen: (String) -> Unit,
    thumbSource: (suspend (String) -> MediaThumbnails.Source?)?) {
    val source by produceState<MediaThumbnails.Source?>(null, entry.path, entry.lastOpenedAt, thumbSource) {
        value = thumbSource?.invoke(entry.path)
    }
    val thumb = @Composable { MediaThumb(source, entry.mode.icon(duotone = true), size = AfSize.ThumbContinue, durationMs = source?.durationMs) }
    val resume = @Composable {
        ResumeButton(tr(UiText.HOME_RESUME, "name" to entry.name), { onOpen(entry.path) }, Modifier.testTag("recent-${entry.name}"))
    }
    val facts = @Composable { m: Modifier ->
        Column(m, verticalArrangement = Arrangement.spacedBy(AfSpace.Xs)) {
            AfText(entry.name, Modifier.semantics { contentDescription = entry.name }, type = AfType.RowTitle, maxLines = 1, ellipsis = true)
            ModeChip(entry.mode, Modifier.testTag("home-mode-${entry.mode.name}"))
            step?.let { AfText(My.breakSyllables(it), Modifier.testTag("home-continue-step"), color = AutoFlowColors.Muted, type = AfType.Caption) }
            if (entry.totalShots > 0) AfProgress(entry.acceptedShots.coerceIn(0, entry.totalShots) / entry.totalShots.toFloat())
        }
    }
    // The whole card opens the project (a click layer under the content, so the texts stay separate nodes); the
    // round play button is the explicit Resume.
    Box(Modifier.fillMaxWidth()) {
        AfCard(Modifier.matchParentSize().semantics { contentDescription = entry.name }, onClick = { onOpen(entry.path) }) {}
        BoxWithConstraints(Modifier.padding(AfSpace.L)) {
            val gap = AfSpace.M
            // Large fonts: the thumbnail and play share the first row and the text gets the full width.
            if (maxWidth - AfSize.ThumbContinue.width - AfSize.Touch - gap * 2 >= AfSize.TileCardMin * LocalDensity.current.fontScale) {
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

/** Round filled play button (48 dp) with a tooltip. */
@Composable
private fun ResumeButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Box(modifier.afTooltip(label).size(AfSize.Touch).clip(CircleShape).background(AutoFlowColors.PrimaryFill)
        .clickable(interaction, null, role = Role.Button, onClick = onClick).semantics { contentDescription = label }
        .interactionOverlay(interaction, CircleShape), contentAlignment = Alignment.Center) {
        AfIcon(AfIcons.Fill.Play, tint = Color.White)
    }
}

/**
 * Equal tiles in rows: as many columns (≤ [max]) as fit [AfSize.TileCardMin] × the font scale; every tile of a row
 * is as tall as the tallest. A tile is `stacked` (icon over the text) unless it is twice that minimum wide.
 */
@Composable
private fun TileGrid(max: Int, tiles: List<@Composable (Modifier, stacked: Boolean) -> Unit>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = AfSpace.CardGap
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
 * Create / tool tile: Duotone icon tile, title and a one-line idea of what it makes. Without [onClick] the tool is
 * switched off (D-84): a muted icon, "Planned" with the clock instead of the caption, and no tap (a chip would wrap
 * into a blob in a narrow tile).
 */
@Composable
private fun HomeTile(icon: ImageVector, colour: Color, title: String, caption: String, onClick: (() -> Unit)?,
    modifier: Modifier, stacked: Boolean, ink: Color = Color.White) {
    val texts: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(AfSpace.Xs / 2)) {
            AfText(My.breakSyllables(title), type = AfType.TileTitle, muted = onClick == null)
            if (onClick != null) AfText(My.breakSyllables(caption), color = AutoFlowColors.Muted, type = AfType.Caption)
            else Row(horizontalArrangement = Arrangement.spacedBy(AfSpace.Xs)) {
                AfIcon(StudioStatus.PLANNED.icon, tint = AutoFlowColors.Muted, size = AfSize.IconXs, modifier = Modifier.padding(top = AfSpace.Xs / 2))
                AfText(My.breakSyllables(StudioStatus.PLANNED.label(LocalUiLanguage.current)), color = AutoFlowColors.Muted, type = AfType.Caption)
            }
        }
    }
    val tile: @Composable () -> Unit = {
        if (onClick != null) IconTile(icon, colour, ink = ink) else IconTile(icon, AutoFlowColors.Surface3, ink = AutoFlowColors.Muted)
    }
    AfCard(if (onClick == null) modifier.semantics { disabled() } else modifier, onClick = onClick) {
        if (stacked) { tile(); texts(Modifier) }
        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AfSpace.M)) {
            tile(); texts(Modifier.weight(1f))
        }
    }
}

/** Quick Recap hero: the primary gradient over the panel with a primary border (DESIGN.md §9.7). */
@Composable
private fun HeroCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(AfRadius.Card)
    Column(Modifier.fillMaxWidth().clip(shape).background(AutoFlowColors.Panel)
        .background(Brush.linearGradient(listOf(AutoFlowColors.Hero, Color.Transparent)))
        .border(AutoFlowShapes.Border, AutoFlowColors.Purple, shape).padding(AfSpace.L).testTag("home-quick"),
        verticalArrangement = Arrangement.spacedBy(AfSpace.M), content = content)
}

@Composable
private fun QuickRecapWizardCard(onStart: (Long, RecapStyle) -> Unit, onStepByStep: () -> Unit,
    backHandler: @Composable (Boolean, () -> Unit) -> Unit,
) {
    var step by remember { mutableStateOf(WizardStep.IDLE) }
    var targetMs by remember { mutableStateOf(300_000L) }
    var style by remember { mutableStateOf(RecapStyle.PLAIN) }

    backHandler(step != WizardStep.IDLE) { step = WizardStep.entries[(step.ordinal - 1).coerceAtLeast(0)] }
    HeroCard {
        when (step) {
            WizardStep.IDLE -> {
                Row(Modifier.clip(RoundedCornerShape(50)).background(Tone.Primary.container).padding(horizontal = AfSpace.M, vertical = AfSpace.Xs / 2),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AfSpace.Xs)) {
                    AfIcon(AfIcons.Fill.Spark, tint = AutoFlowColors.PrimaryText, size = AfSize.IconXs)
                    AfText(tr(UiText.HOME_QUICK_CHIP), color = AutoFlowColors.PrimaryText, type = AfType.Micro)
                }
                AfText(My.breakSyllables(tr(UiText.HOME_HERO_TITLE)), type = AfType.CardTitle)
                AfText(My.breakSyllables(tr(UiText.V3_APPROVAL_BEFORE_VOICE)), color = AutoFlowColors.Muted, type = AfType.Caption)
                AfText(My.breakSyllables(tr(UiText.HOME_QUICK_SOURCE)), color = AutoFlowColors.Muted, type = AfType.Caption)
                AfButton(tr(UiText.HOME_QUICK_START), { step = WizardStep.LENGTH }, Modifier.fillMaxWidth().testTag("home-quick-start"),
                    kind = ButtonKind.Primary, icon = AfIcons.Fill.Spark)
                AfButton(tr(UiText.V3_STEP_BY_STEP), onStepByStep, Modifier.testTag("home-step-by-step"))
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
