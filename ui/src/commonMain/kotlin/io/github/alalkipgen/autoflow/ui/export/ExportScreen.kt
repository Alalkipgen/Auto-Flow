package io.github.alalkipgen.autoflow.ui.export

import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.components.paneScroll
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.storyboard.text
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.alalkipgen.autoflow.core.recap.ExportQuality
import io.github.alalkipgen.autoflow.core.recap.PublishKit
import io.github.alalkipgen.autoflow.ui.pack.RenderPackageCard
import io.github.alalkipgen.autoflow.ui.pack.RenderPlan
import io.github.alalkipgen.autoflow.core.recap.PublishKitText
import io.github.alalkipgen.autoflow.media.render.Quality
import io.github.alalkipgen.autoflow.ui.components.AfTextField
import kotlinx.coroutines.launch
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfCard
import io.github.alalkipgen.autoflow.ui.components.AfChip
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.components.decodeImage
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.components.SectionCard
import io.github.alalkipgen.autoflow.ui.components.StatusChip
import io.github.alalkipgen.autoflow.ui.components.StudioStatus
import io.github.alalkipgen.autoflow.ui.input.InputModel
import io.github.alalkipgen.autoflow.ui.projects.RIGHTS_NOTICE
import io.github.alalkipgen.autoflow.ui.recap.RecapModel
import io.github.alalkipgen.autoflow.ui.recap.RecapStep
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.GlassColors
import io.github.alalkipgen.autoflow.ui.theme.GlassShapes
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.recap.RecapChip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

const val PREVIEW_BUTTON = "နမူနာ 540p"
const val EXPORT_BUTTON = "ထုတ်ယူမယ် 1080p"
const val SAVE_BUTTON = "Gallery ထဲ သိမ်းမယ်"
const val SHARE = "မျှဝေမယ်…"
internal const val LOUDNESS = "အသံကျယ်မှု"

/**
 * Export (PLAN Q-09 c): what goes into the video (shots, accepted videos, song, lyrics font), Preview / Export,
 * progress + Cancel, then Open / Share. The battery warning (below 20 % and not charging) warns, it does not block.
 * [studio] = the glass look of Studio v2 (Movie Recap step 5, the Music Video Export page): no page title, the Render
 * card only when there is a run, a last render or a notice (Preview / Export sit in the page's sticky bar).
 */
@Composable
fun ExportScreen(model: ExportModel, modifier: Modifier = Modifier, studio: Boolean = false,
    onPreflightFix: ((RecapStep) -> Unit)? = null,
    /** F-5: the Movie Recap live preview above the render controls (phone Export step); F-6: the Music Video one. */
    livePreview: (@Composable () -> Unit)? = null,
) {
    val state by model.state.collectAsState()
    val open by model.projects.collectAsState()
    val displayed = open
    val fix: ((io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Item, RecapStep) -> Unit)? =
        if (studio && onPreflightFix != null) { item, step ->
            if (preflightNavigationAllowed(model, displayed, item) && preflightStep(item) == step) onPreflightFix(step)
        } else null
    LaunchedEffect(model, open?.dir, open?.project?.id) { model.refresh() }
    Column(
        modifier.fillMaxSize().paneScroll(rememberScrollState()).padding(screenPadding).widthIn(max = 760.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!studio) AfText(tr(UiText.EXPORT), size = 18.sp, bold = true)
        // Never expose another project's controls during the frame before the refresh effect runs.
        if (state.projectDir != open?.dir) return@Column
        if (state.projectName == null) {
            AfText(tr(UiText.EXPORT_NO_PROJECT), muted = true)
            return@Column
        }
        val recap = state.recap
        livePreview?.invoke()
        val inlineRun = state.run?.let { it.quality == Quality.PREVIEW && it.state is RenderState.Done } == true
        val showOpen = !inlineRun && (state.run != null || state.lastRenders.none { it.quality == Quality.PREVIEW })
        ExportPreviewCard(state.preview, model, showOpen, glass = studio)
        if (recap != null) RecapSummary(recap, state.font, model, studio, state.busy)
        else if (studio) MusicVideoFacts(state)
        else AfCard(tr(UiText.VIDEO)) {
            AfText(tr(UiText.EXPORT_SHOT_COUNT, "shots" to (state.shots), "durationMs" to (InputModel.formatDuration(state.durationMs))))
            val pending = state.shots - state.accepted
            AfText(acceptedLine(state), color = if (pending > 0) AutoFlowColors.Amber else null)
            AfText(
                state.songFile?.let { "♪ $it" } ?: tr(UiText.NO_SONG_EXPORT),
                color = if (state.songFile == null) AutoFlowColors.Amber else null,
            )
            AfText(
                state.font?.let { lyricsLine(state, it) } ?: tr(UiText.NO_BURMESE_FONT_LYRICS),
                color = if (state.font == null) AutoFlowColors.Amber else null,
            )
            if (state.timingOutdated) {
                AfText(tr(UiText.LYRICS_TIMING_STALE), color = AutoFlowColors.Amber)
            }
        }
        if (studio && recap != null && recap.preflight.isNotEmpty()) PreflightCard(recap.preflight, fix, !state.busy, glass = true)
        if (!studio || state.run != null || state.lastRenders.isNotEmpty() ||
            state.unavailable != null || state.lowBattery || state.message != null) RenderCard(state, model, studio)
        // Pre-flight (5B-9): blockers and warnings shown before export
        state.recap?.let { recap ->
            if (!studio && recap.preflight.isNotEmpty()) PreflightCard(recap.preflight)
            // Exact-frame thumbnails (5B-9): stills made by the export compiler itself.
            ExactFramesCard(state.thumbs, canMake = recap.movieFile != null && !state.busy, model, glass = studio)
        }
        // Publish Kit (5B-10): only for Recap projects
        state.recap?.let { recap ->
            androidx.compose.runtime.key(open?.dir, open?.project?.id) {
                PublishKitCard(recap.publishKit, recap.chapters, state.publishThumb, model, studio)
            }
        }
        // F-7 (D-96): the files this render needs, to render it on another device or a server.
        open?.project?.mode?.let { mode -> RenderPackageCard({ RenderPlan(mode = mode, quality = Quality.EXPORT.name) },
            tile = if (studio) ExportTiles.Package else null) }
    }
}

/**
 * The Movie Recap Export step's card tiles (Group 2 B, PR 5): Fill icon in the card colour, which also tints the card
 * (.16). Render preview Blue · Output Purple · Preflight Green · Render Purple · Exact frames lavender · Publish Kit
 * Blue · Render package Teal.
 */
internal object ExportTiles {
    val Preview get() = CardTile(AfIcons.Fill.MonitorPlay, GlassColors.ExportBlue)
    val Output get() = CardTile(AfIcons.Fill.Film, AutoFlowColors.Purple)
    val Preflight get() = CardTile(AfIcons.Fill.Checks, AutoFlowColors.Green)
    val Render get() = CardTile(AfIcons.Fill.FilmReel, AutoFlowColors.Purple)
    val ExactFrames get() = CardTile(AfIcons.Fill.FrameCorners, GlassColors.ExportLavender)
    val PublishKit get() = CardTile(AfIcons.Fill.Megaphone, GlassColors.ExportBlue)
    val Package get() = CardTile(AfIcons.Fill.Box, GlassColors.ExportTeal)
}

/** A card of the Export screen: the glass tile card in Studio v2 ([glass]: Movie Recap step 5, Music Video Export), the plain card elsewhere. */
@Composable
internal fun ExportCard(title: String, tile: CardTile, glass: Boolean, modifier: Modifier = Modifier, icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit) {
    if (glass) TileCard(title, tile, modifier, content = content) else AfCard(title, modifier, icon = icon, content = content)
}

/** A fact row whose text is a file name: 17 dp Muted [icon] + mono text (never broken mid-name). */
@Composable
private fun MonoFact(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AfIcon(icon, Modifier.padding(top = 2.dp), tint = AutoFlowColors.Muted, size = 17.dp)
        Box(Modifier.weight(1f)) { MonoText(text, 12.5.sp, AutoFlowColors.Muted, modifier = modifier) }
    }
}

/** "N accepted video(s)" and, when some shots have none yet, " · M show as "pending" cards". */
@Composable
private fun acceptedLine(state: ExportUiState): String {
    val pending = state.shots - state.accepted
    return tr(UiText.ACCEPTED_VIDEO_COUNT, "accepted" to (state.accepted)) +
        (if (LocalUiLanguage.current == UiLanguage.ENGLISH && state.accepted != 1) "s" else "") +
        if (pending > 0) tr(UiText.PENDING_VIDEO_COUNT, "pending" to (pending)) else ""
}

/** The lyrics that go into the video with the Burmese [font]: the synced lines, or the shots' own lyrics. */
@Composable
private fun lyricsLine(state: ExportUiState, font: String): String = when {
    state.syncedLines > 0 -> tr(UiText.LYRICS_SYNC_COUNT, "syncedLines" to (state.syncedLines), "font" to (font))
    state.lyricShots == 0 -> tr(UiText.NO_SHOT_LYRICS)
    else -> tr(UiText.LYRICS_SHOT_COUNT, "lyricShots" to (state.lyricShots), "font" to (font))
}

/**
 * Group 3 H (PR 11): the Video card of the Music Video Export page — the film-slate Purple tile, one 17 dp icon row
 * per fact (Group 2 B "Output" style): shots · accepted videos (green check-circle; an amber hourglass while some
 * shots show as "pending" cards) · the song file on a mono row · the lyrics line. No song, no Burmese font and lyric
 * timings older than the lyrics are amber callouts.
 */
@Composable
internal fun MusicVideoFacts(state: ExportUiState) {
    TileCard(tr(UiText.VIDEO), ExportTiles.Output, Modifier.testTag("export-video")) {
        GlassFact(AfIcons.Regular.FilmStrip, tr(UiText.EXPORT_SHOT_COUNT, "shots" to (state.shots),
            "durationMs" to (InputModel.formatDuration(state.durationMs))))
        val pending = state.shots - state.accepted
        when {
            pending > 0 -> GlassFact(AfIcons.Fill.Hourglass, acceptedLine(state), colour = AutoFlowColors.Amber, iconColour = AutoFlowColors.Amber)
            state.accepted > 0 -> GlassFact(AfIcons.Fill.CheckCircle, acceptedLine(state), iconColour = AutoFlowColors.Green)
            else -> GlassFact(AfIcons.Regular.CheckCircle, acceptedLine(state))
        }
        state.songFile?.let { MonoFact(AfIcons.Regular.MusicNote, it, Modifier.testTag("export-song")) }
            ?: GlassCallout(AfIcons.Fill.Warn, tr(UiText.NO_SONG_EXPORT), AutoFlowColors.Amber)
        state.font?.let { GlassFact(AfIcons.Regular.TextAa, lyricsLine(state, it)) }
            ?: GlassCallout(AfIcons.Fill.Warn, tr(UiText.NO_BURMESE_FONT_LYRICS), AutoFlowColors.Amber)
        if (state.timingOutdated) GlassCallout(AfIcons.Fill.Warn, tr(UiText.LYRICS_TIMING_STALE), AutoFlowColors.Amber)
    }
}

/**
 * Group 3 H (PR 11, 🆕-4): the Music Video Export page of Studio v2 — the glass Export cards of the Movie Recap step
 * (`studio`: no page title, the Render card only when there is a run, a last render or a notice) under the live
 * preview, then a sticky bar with Preview 540p · Export 1080p (as on Recap step 5) and the background hint.
 */
@Composable
fun MusicVideoExportScreen(model: ExportModel, livePreview: (@Composable () -> Unit)? = null) {
    val state by model.state.collectAsState()
    val open by model.projects.collectAsState()
    val current = open != null && state.projectDir == open?.dir
    Column(Modifier.fillMaxSize()) {
        ExportScreen(model, Modifier.weight(1f), studio = true, livePreview = livePreview)
        StickyActionBar(
            StudioAction(tr(UiText.EXPORT_BUTTON), { model.render(Quality.EXPORT) }, current && state.canRender, "export-render", AfIcons.Regular.Export),
            StudioAction(tr(UiText.PREVIEW_BUTTON), { model.render(Quality.PREVIEW) }, current && state.canRender, "export-preview",
                AfIcons.Regular.MonitorPlay),
            caption = tr(UiText.RENDER_BACKGROUND_HINT),
            glass = true,
        )
    }
}

/** Movie Recap project (docs/specs/recap.md §6): scenes, voice, sound, looks, captions, quality and the rights reminder. */
@Composable
private fun RecapSummary(recap: RecapExport, font: String?, model: ExportModel, studio: Boolean, busy: Boolean) {
    if (studio) return GlassRecapSummary(recap, font, model, busy)
    AfCard("Movie Recap", Modifier.testTag("export-output")) {
        AfText(tr(UiText.RIGHTS_NOTICE), color = AutoFlowColors.Amber, size = 12.sp)
        AfText(tr(UiText.RECAP_SCENE_COUNT, "segments" to (recap.segments), "durationMs" to (InputModel.formatDuration(recap.durationMs))) + (recap.movieFile?.let { " · 🎬 $it" } ?: ""))
        val silent = recap.segments - recap.voiced
        AfText(
            tr(UiText.RECAP_VOICED_COUNT, "voiced" to (recap.voiced), "segments" to (recap.segments)) + if (silent > 0) tr(UiText.RECAP_SILENT_COUNT, "silent" to (silent)) else "",
            color = if (silent > 0) AutoFlowColors.Amber else null,
        )
        val sound = movieVolumeLabel(recap.movieVolume)
        AfText(tr(UiText.RECAP_SOUND_LOOKS, "sound" to (sound), "toString" to (if (recap.looks == 0) tr(UiText.NONE) else recap.looks.toString())))
        AfText(
            when {
                !recap.burnCaptions -> tr(UiText.CAPTIONS_SRT_ONLY)
                font == null -> tr(UiText.NO_BURMESE_FONT_CAPTIONS)
                else -> tr(UiText.CAPTIONS_BURNED, "font" to (font))
            },
            color = if (recap.burnCaptions && font == null) AutoFlowColors.Amber else null,
        )
        AfText(tr(UiText.EXPORT_QUALITY), muted = true)
        WrapRow {
            ExportQuality.entries.forEach { q ->
                AfChip(q.uiLabel(), q == recap.quality, onClick = { model.setRecapQuality(q) },
                    modifier = Modifier.testTag("export-quality-${q.name}"), enabled = !busy)
            }
        }
        recap.blocker?.let { AfText(recap.workflowBlocker?.text(LocalUiLanguage.current) ?: tr(UiText.EXPORT_MOVIE_FIRST), color = AutoFlowColors.Amber) }
    }
}

/**
 * Group 2 B (PR 5): the Output card of the Movie Recap Export step — the rights reminder as an amber callout, one 17 dp
 * icon row per fact (the movie file on its own mono row), glass quality chips (check when selected), the render hint.
 */
@Composable
private fun GlassRecapSummary(recap: RecapExport, font: String?, model: ExportModel, busy: Boolean) {
    TileCard(tr(UiText.OUTPUT_CARD), ExportTiles.Output, Modifier.testTag("export-output")) {
        GlassCallout(AfIcons.Fill.Warn, tr(UiText.RIGHTS_NOTICE), AutoFlowColors.Amber)
        GlassFact(AfIcons.Regular.FilmStrip, tr(UiText.RECAP_SCENE_COUNT, "segments" to (recap.segments),
            "durationMs" to (InputModel.formatDuration(recap.durationMs))))
        recap.movieFile?.let { MonoFact(AfIcons.Regular.FileVideo, it) }
        val silent = recap.segments - recap.voiced
        val voiceTone = if (silent > 0) AutoFlowColors.Amber else null
        GlassFact(AfIcons.Regular.Microphone, tr(UiText.RECAP_VOICED_COUNT, "voiced" to (recap.voiced), "segments" to (recap.segments)) +
            if (silent > 0) tr(UiText.RECAP_SILENT_COUNT, "silent" to (silent)) else "",
            colour = voiceTone ?: AutoFlowColors.Text, iconColour = voiceTone ?: AutoFlowColors.Muted)
        val sound = movieVolumeLabel(recap.movieVolume)
        GlassFact(AfIcons.Regular.Volume, tr(UiText.RECAP_SOUND_LOOKS, "sound" to (sound),
            "toString" to (if (recap.looks == 0) tr(UiText.NONE) else recap.looks.toString())))
        val captionTone = if (recap.burnCaptions && font == null) AutoFlowColors.Amber else null
        GlassFact(AfIcons.Regular.Subtitles, when {
            !recap.burnCaptions -> tr(UiText.CAPTIONS_SRT_ONLY)
            font == null -> tr(UiText.NO_BURMESE_FONT_CAPTIONS)
            else -> tr(UiText.CAPTIONS_BURNED, "font" to (font))
        }, colour = captionTone ?: AutoFlowColors.Text, iconColour = captionTone ?: AutoFlowColors.Muted)
        Box(Modifier.fillMaxWidth().height(1.dp).background(GlassColors.Divider))
        AfText(tr(UiText.EXPORT_QUALITY), muted = true, type = AfType.Caption)
        WrapRow {
            ExportQuality.entries.forEach { q ->
                RecapChip(q.uiLabel(), q == recap.quality, { model.setRecapQuality(q) }, Modifier.testTag("export-quality-${q.name}"), enabled = !busy)
            }
        }
        recap.blocker?.let {
            GlassFact(AfIcons.Fill.Warn, recap.workflowBlocker?.text(LocalUiLanguage.current) ?: tr(UiText.EXPORT_MOVIE_FIRST),
                colour = AutoFlowColors.Amber, iconColour = AutoFlowColors.Amber)
        }
        GlassFact(AfIcons.Regular.Info, tr(UiText.RENDER_BACKGROUND_HINT), colour = AutoFlowColors.Muted, caption = true)
    }
}

/**
 * The Render card: why this app cannot render here, the low-battery warning, the run (or the last renders) and the
 * last action's message; outside the Movie Recap step also Preview / Export and the background hint.
 */
@Composable
internal fun RenderCard(state: ExportUiState, model: ExportModel, studio: Boolean) {
    ExportCard(tr(UiText.RENDER_HEADING), ExportTiles.Render, glass = studio) {
        state.unavailable?.let {
            val why = if (it == ExportModel.NO_QUEUE) tr(UiText.EXPORT_NO_QUEUE) else tr(UiText.RESULT_DETAIL, "detail" to it)
            // Group 2 D (24 · 5): an amber callout on the Movie Recap step.
            if (studio) GlassCallout(AfIcons.Fill.Warn, why, AutoFlowColors.Amber) else AfText(why, color = AutoFlowColors.Amber)
        }
        if (state.lowBattery) {
            if (studio) GlassCallout(AfIcons.Fill.BatteryWarning, tr(UiText.LOW_BATTERY_EXPORT), AutoFlowColors.Amber)
            else AfText(tr(UiText.LOW_BATTERY_EXPORT), color = AutoFlowColors.Amber)
        }
        if (!studio) WrapRow {
            AfButton(tr(UiText.PREVIEW_BUTTON), { model.render(Quality.PREVIEW) }, Modifier.testTag("export-preview"), enabled = state.canRender)
            val exportLabel = state.recap?.let { tr(UiText.EXPORT_WITH_QUALITY, "quality" to it.quality.uiLabel()) } ?: tr(UiText.EXPORT_BUTTON)
            AfButton(exportLabel, { model.render(Quality.EXPORT) }, Modifier.testTag("export-render"), kind = ButtonKind.Primary, enabled = state.canRender)
        }
        if (!studio) AfText(tr(UiText.RENDER_BACKGROUND_HINT), muted = true)
        state.run?.let { RunStatus(it, model, state.savingToGallery, glass = studio) }
        if (state.run == null) state.lastRenders.forEachIndexed { i, last ->
            if (studio && i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(GlassColors.Divider))
            LastRenderStatus(last, model, glass = studio)
        }
        state.message?.let { AfText(state.notice?.text(LocalUiLanguage.current) ?: tr(UiText.RESULT_DETAIL, "detail" to it), color = if (state.messageOk) AutoFlowColors.Green else AutoFlowColors.Amber) }
    }
}

/** Audit F09 / 5B-4: two-pass only when the pass-1 numbers are there; otherwise the fallback and why. */
internal fun loudnessLine(l: RenderState.Loudness, language: UiLanguage = UiLanguage.ENGLISH): String =
    if (l.twoPass) UiText.LOUDNESS_TWO_PASS.text(language, "target" to l.targetLufs, "measured" to l.measured!!.inputI)
    else UiText.LOUDNESS_NOTE.text(language, "note" to (l.note ?: UiText.LOUDNESS_ONE_PASS.text(language, "target" to l.targetLufs)))

/** Run/result kind alone does not encode the resolved output size. Never infer 1080p from EXPORT. */
private fun outputLabel(quality: Quality, language: UiLanguage): String =
    if (quality == Quality.EXPORT) UiText.EXPORT.text(language) else ExportModel.label(quality, language)

/**
 * A finished render read back from its `exports/<name>.result.json` (5B-4), e.g. after the app restarted. [glass] = the
 * Movie Recap step (Group 2 D, 24 · 4): a green check-circle row, the file on a mono row, the loudness as a Muted caption,
 * small Open / Share.
 */
@Composable
private fun LastRenderStatus(last: LastRender, model: ExportModel, glass: Boolean = false) {
    val r = last.result
    val done = tr(UiText.LAST_RENDER, "quality" to (outputLabel(last.quality, LocalUiLanguage.current)), "durationMs" to (InputModel.formatDuration(r.durationMs)), "wallMs1000" to (r.wallMs / 1000))
    if (glass) {
        GlassFact(AfIcons.Fill.CheckCircle, done, colour = AutoFlowColors.Green, iconColour = AutoFlowColors.Green, textModifier = Modifier.testTag("export-last"))
        MonoFact(AfIcons.Regular.FileVideo, "exports/${r.output}")
        r.loudness?.let { AfText(loudnessLine(it, LocalUiLanguage.current), Modifier.testTag("export-last-loudness"), muted = true, type = AfType.Caption) }
        WrapRow {
            GlassButton(tr(UiText.OPEN), { model.openLast(last) }, icon = AfIcons.Regular.ArrowSquareOut, small = true)
            GlassButton(tr(UiText.SHARE), { model.shareLast(last) }, icon = AfIcons.Regular.Share, small = true)
        }
        return
    }
    AfText(done, Modifier.testTag("export-last"), color = AutoFlowColors.Green)
    AfText("exports/${r.output}", muted = true, mono = true)
    r.loudness?.let { AfText(loudnessLine(it, LocalUiLanguage.current), Modifier.testTag("export-last-loudness"), muted = true, size = 11.sp) }
    WrapRow {
        AfButton(tr(UiText.OPEN), { model.openLast(last) })
        AfButton(tr(UiText.SHARE), { model.shareLast(last) })
    }
}

@Composable
private fun RunStatus(run: ExportRun, model: ExportModel, saving: Boolean, glass: Boolean = false) {
    val what = outputLabel(run.quality, LocalUiLanguage.current)
    StatusChip(when (run.state) {
        RenderState.Queued -> StudioStatus.QUEUED
        is RenderState.Running -> StudioStatus.RUNNING
        is RenderState.Done -> StudioStatus.DONE
        is RenderState.Failed -> StudioStatus.FAILED
        RenderState.Cancelled -> StudioStatus.CANCELLED
    })
    // Group 2 B: Cancel is a Danger button on the Movie Recap step (as on Jobs).
    val cancel: @Composable () -> Unit = {
        if (glass) GlassButton(tr(UiText.CANCEL), model::cancel, kind = ButtonKind.Danger, icon = AfIcons.Regular.Close)
        else AfButton(tr(UiText.CANCEL), model::cancel)
    }
    when (val s = run.state) {
        RenderState.Queued -> {
            AfText(tr(UiText.RENDER_QUEUED, "what" to (what)), Modifier.testTag("export-status"))
            cancel()
        }
        is RenderState.Running -> {
            val percent = (s.fraction * 100).toInt().coerceIn(0, 100)
            AfText(tr(UiText.RENDER_PROGRESS, "what" to (what), "coerceIn0100" to (percent)), Modifier.testTag("export-status"))
            // The same fraction as the text, as a bar.
            if (glass) GlassProgress(percent / 100f)
            cancel()
        }
        is RenderState.Done -> {
            // Inline video preview (D-57 / 5A-8): tap play to watch the render without leaving the app.
            if (run.quality == Quality.EXPORT) {
                if (glass) VideoPreview(s.output, Modifier.shadow(8.dp, VideoShape).clip(VideoShape))
                else VideoPreview(s.output)
            }
            val done = tr(UiText.RENDER_DONE, "what" to (what), "durationMs" to (InputModel.formatDuration(s.durationMs)), "wallMs1000" to (s.wallMs / 1000))
            val file = s.output.substringAfterLast('/').let { "exports/$it" }
            if (glass) {
                GlassFact(AfIcons.Fill.CheckCircle, done, colour = AutoFlowColors.Green, iconColour = AutoFlowColors.Green,
                    textModifier = Modifier.testTag("export-status"))
                MonoFact(AfIcons.Regular.FileVideo, file)
                // Audit F09: say how the loudness target was met — never claim two-pass when it fell back.
                s.loudness?.let { GlassFact(AfIcons.Regular.Wave, loudnessLine(it, LocalUiLanguage.current), colour = AutoFlowColors.Muted,
                    caption = true, textModifier = Modifier.testTag("export-loudness")) }
                WrapRow {
                    GlassButton(tr(UiText.OPEN), model::open, kind = ButtonKind.Primary, icon = AfIcons.Regular.ArrowSquareOut)
                    GlassButton(tr(UiText.SHARE), model::share, icon = AfIcons.Regular.Share)
                    GlassButton(if (saving) tr(UiText.SAVING) else tr(UiText.SAVE_GALLERY), model::saveToGallery, enabled = !saving,
                        icon = AfIcons.Regular.DownloadSimple)
                }
                return
            }
            AfText(done, Modifier.testTag("export-status"), color = AutoFlowColors.Green)
            AfText(file, muted = true, mono = true)
            // Audit F09: say how the loudness target was met — never claim two-pass when it fell back.
            s.loudness?.let { AfText(loudnessLine(it, LocalUiLanguage.current), Modifier.testTag("export-loudness"), muted = true, size = 11.sp) }
            WrapRow {
                AfButton(tr(UiText.OPEN), model::open, kind = ButtonKind.Primary)
                AfButton(tr(UiText.SHARE), model::share)
                AfButton(if (saving) tr(UiText.SAVING) else tr(UiText.SAVE_GALLERY), model::saveToGallery, enabled = !saving)
            }
        }
        is RenderState.Failed -> {
            val failed = tr(UiText.RENDER_FAILED, "what" to (what), "reason" to (s.reason))
            // Group 2 D (24 · 2): the reason as a red x-circle row, the log's last lines in a dark mono box.
            if (glass) {
                GlassFact(AfIcons.Fill.XCircle, failed, colour = AutoFlowColors.Failed, iconColour = AutoFlowColors.Failed,
                    textModifier = Modifier.testTag("export-status"))
                if (s.tail.isNotEmpty()) LogBox(s.tail.takeLast(6))
                return
            }
            AfText(failed, Modifier.testTag("export-status"), color = AutoFlowColors.Failed)
            if (s.tail.isNotEmpty()) AfText(s.tail.takeLast(6).joinToString("\n"), muted = true, mono = true, size = 11.sp)
        }
        RenderState.Cancelled -> AfText(tr(UiText.RENDER_CANCELLED, "what" to (what)), Modifier.testTag("export-status"), muted = true)
    }
}

/**
 * A failed render's log tail (Group 2 D, 24 · 2): a dark box (radius 12, black .22, 1 dp White .10), one mono Muted line
 * each, wrapping at spaces and separators (never inside a word).
 */
@Composable
private fun LogBox(lines: List<String>) {
    Column(Modifier.fillMaxWidth().clip(VideoShape).background(GlassColors.PathFill).border(1.dp, GlassColors.PathBorder, VideoShape)
        .padding(horizontal = 12.dp, vertical = 10.dp)) {
        lines.forEach { MonoText(it, 11.sp, AutoFlowColors.Muted) }
    }
}

/** Videos and stills on the Movie Recap Export step: radius 12 (Group 2 B). */
internal val VideoShape = RoundedCornerShape(12.dp)

/**
 * Exact-frame thumbnails (5B-9, D-42): up to six stills of the recap made by the export compiler (blur boxes,
 * background, looks, layers, burned-in captions exactly as rendered) at caption times; any one can become the
 * Publish Kit thumbnail. Marked out of date when the recap changes after they were made.
 */
@Composable
internal fun ExactFramesCard(thumbs: ExportThumbs?, canMake: Boolean, model: ExportModel, glass: Boolean = false) {
    ExportCard(tr(UiText.EXACT_FRAMES), ExportTiles.ExactFrames, glass) {
        AfText(tr(UiText.EXACT_FRAMES_HINT), muted = true, size = 12.sp)
        val working = thumbs?.working == true
        val label = when {
            thumbs == null -> tr(UiText.MAKE_EXACT_FRAMES)
            working -> tr(UiText.EXACT_FRAMES_PROGRESS, "size" to (thumbs.times.size), "value2" to (minOf(thumbs.frames.size + 1, thumbs.times.size)))
            else -> tr(UiText.REMAKE)
        }
        if (glass) GlassButton(label, { model.makeThumbnails() }, Modifier.fillMaxWidth().testTag("export-thumbs-make"), enabled = canMake && !working,
            icon = if (thumbs != null && !working) AfIcons.Regular.Redo else AfIcons.Regular.Image)
        else AfButton(label, { model.makeThumbnails() }, Modifier.testTag("export-thumbs-make"), enabled = canMake && !working)
        if (thumbs == null) return@ExportCard
        if (thumbs.stale) {
            val stale = tr(UiText.EXACT_FRAMES_STALE, "STALE" to (tr(UiText.STALE)))
            if (glass) GlassFact(AfIcons.Fill.Warn, stale, colour = AutoFlowColors.Amber, iconColour = AutoFlowColors.Amber, caption = true,
                textModifier = Modifier.testTag("export-thumbs-stale"))
            else AfText("⚠ $stale", Modifier.testTag("export-thumbs-stale"), color = AutoFlowColors.Amber, size = 12.sp)
        }
        thumbs.failed?.let { AfText(it, Modifier.testTag("export-thumbs-failed"), color = AutoFlowColors.Amber, size = 12.sp) }
        val frame: @Composable (Int, RecapFrames.Frame, Modifier) -> Unit = { i, f, modifier ->
            Column(modifier.testTag("export-thumb-$i"), verticalArrangement = Arrangement.spacedBy(if (glass) 6.dp else 4.dp)) {
                val bmp = remember(f) { decodeImage(f.bytes) }
                if (bmp != null) {
                    Image(bmp, tr(UiText.EXACT_FRAME_AT, "atMs" to (PublishKitText.timestamp(f.atMs))), Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height)
                        .then(if (glass) Modifier.shadow(6.dp, VideoShape).clip(VideoShape) else Modifier.clip(AutoFlowShapes.Card)))
                } else {
                    AfText(tr(UiText.IMAGE_UNAVAILABLE), color = AutoFlowColors.Amber, size = 11.sp)
                }
                AfText(RecapModel.seconds(f.atMs), muted = true, size = 11.sp)
                if (glass) GlassButton(tr(UiText.USE_THUMBNAIL), { model.makePublishThumbnail(f.atMs) }, Modifier.fillMaxWidth().testTag("export-thumb-use-$i"),
                    enabled = !thumbs.stale)
                else AfButton(tr(UiText.USE_THUMBNAIL), { model.makePublishThumbnail(f.atMs) }, Modifier.testTag("export-thumb-use-$i"), enabled = !thumbs.stale)
            }
        }
        // Group 2 B: two columns on a phone ((content − 8) / 2, the button as wide as its column); one column at a large font
        // (2.0: "Use for thumbnail" no longer fits half a phone); the desktop keeps 176 dp tiles.
        val columns = if (LocalDensity.current.fontScale > 1.3f) 1 else 2
        if (glass && LocalCompact.current) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            thumbs.frames.withIndex().chunked(columns).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { (i, f) -> frame(i, f, Modifier.weight(1f)) }
                    if (pair.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        } else WrapRow {
            thumbs.frames.forEachIndexed { i, f -> frame(i, f, Modifier.width(176.dp)) }
        }
    }
}

/**
 * Publish Kit (5B-10, studio-v2 §9): edit title / description / hashtags, copy each item, share the full text file;
 * chapters come from the story blocks' start times when the kit has none of its own; the thumbnail
 * (`exports/export.thumb.jpg`) is an export-size Exact frame with the saved title as a TEXT layer.
 * [studio] = the glass card of the Movie Recap step (Group 2 D, 23 · 1–4): glass fields in the prose font, small Copy
 * buttons, the chapters in a dark inner box (one line each), Save / Discard / Share file with icons, a divider before the
 * thumbnail.
 */
@Composable
internal fun PublishKitCard(kit: PublishKit, chapters: List<io.github.alalkipgen.autoflow.core.recap.PublishChapter>, thumb: PublishThumb?, model: ExportModel, studio: Boolean,
    initiallyExpanded: Boolean = !studio) {
    val scope = rememberCoroutineScope()
    var title by remember(kit.title) { mutableStateOf(kit.title) }
    var description by remember(kit.description) { mutableStateOf(kit.description) }
    var hashtags by remember(kit.hashtags) { mutableStateOf(kit.hashtags.joinToString(" ")) }
    SectionCard("Publish Kit", Modifier.testTag("publish-kit"), initiallyExpanded = initiallyExpanded, tile = if (studio) ExportTiles.PublishKit else null) {
        if (studio) {
            val copy: @Composable (UiText, String, () -> Unit) -> Unit = { label, tag, onClick ->
                GlassButton(tr(label), onClick, Modifier.testTag(tag), icon = AfIcons.Regular.Copy, small = true)
            }
            AfText(tr(UiText.TITLE), muted = true, type = AfType.Caption)
            AfTextField(title, { title = it }, tr(UiText.VIDEO_TITLE_MY), Modifier.fillMaxWidth().testTag("publish-title"), mono = false, glass = true)
            copy(UiText.COPY_TITLE, "publish-copy-title") { model.copyPublishText(title, "title") }
            AfText(tr(UiText.DESCRIPTION), muted = true, type = AfType.Caption)
            val posted = PublishKitText.withChapters(kit, chapters)
            val chapterLines = PublishKitText.chapterLines(posted.chapters)
            if (chapterLines.isEmpty()) AfText(tr(UiText.CHAPTERS_EMPTY), Modifier.testTag("publish-chapters"), muted = true, type = AfType.Caption)
            else ChapterBox(tr(UiText.CHAPTERS_FROM_SCRIPT).trim(), chapterLines, Modifier.testTag("publish-chapters"))
            copy(UiText.V3_COPY_CHAPTERS, "publish-copy-chapters") { model.copyPublishText(chapterLines.joinToString("\n"), "chapters") }
            AfTextField(description, { description = it }, tr(UiText.DESCRIPTION_MY), Modifier.fillMaxWidth(), lines = 4, mono = false, glass = true)
            copy(UiText.COPY_DESCRIPTION, "publish-copy-description") {
                model.copyPublishText(PublishKitText.description(posted.copy(description = description)), "description")
            }
            AfText(tr(UiText.HASHTAGS_HINT), muted = true, type = AfType.Caption)
            AfTextField(hashtags, { hashtags = it }, "#မြန်မာ #movie", Modifier.fillMaxWidth(), mono = false, glass = true)
            copy(UiText.V3_COPY_HASHTAGS, "publish-copy-hashtags") { model.copyPublishText(hashtags, "hashtags") }
            WrapRow {
                GlassButton(tr(UiText.SAVE), {
                    val parsedHashtags = hashtags.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                    model.setPublishKit(kit.copy(title = title, description = description, hashtags = parsedHashtags))
                }, Modifier.testTag("publish-save"), kind = ButtonKind.Primary, icon = AfIcons.Regular.Check, small = true)
                GlassButton(tr(UiText.DISCARD), { title = kit.title; description = kit.description; hashtags = kit.hashtags.joinToString(" ") },
                    Modifier.testTag("publish-discard"), icon = AfIcons.Regular.ArrowCounterClockwise, small = true)
                GlassButton(tr(UiText.SHARE_FILE), { scope.launch { model.sharePublishFile("export") } }, icon = AfIcons.Regular.Share, small = true)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(GlassColors.Divider))
            GlassPublishThumb(kit, thumb, model)
            return@SectionCard
        }
        AfText(tr(UiText.TITLE), muted = true, size = 12.sp)
        AfTextField(title, { title = it }, tr(UiText.VIDEO_TITLE_MY), Modifier.fillMaxWidth().testTag("publish-title"))
        AfButton(tr(UiText.COPY_TITLE), { model.copyPublishText(title, "title") }, Modifier.testTag("publish-copy-title"))
        AfText(tr(UiText.DESCRIPTION), muted = true, size = 12.sp)
        val posted = PublishKitText.withChapters(kit, chapters)
        val chapterLines = PublishKitText.chapterLines(posted.chapters)
        AfText(
            if (chapterLines.isEmpty()) tr(UiText.CHAPTERS_EMPTY)
            else tr(UiText.CHAPTERS_FROM_SCRIPT) + chapterLines.joinToString("\n"),
            Modifier.testTag("publish-chapters"), muted = true, size = 11.sp,
        )
        AfButton(tr(UiText.V3_COPY_CHAPTERS), { model.copyPublishText(chapterLines.joinToString("\n"), "chapters") },
            Modifier.testTag("publish-copy-chapters"))
        AfTextField(description, { description = it }, tr(UiText.DESCRIPTION_MY), Modifier.fillMaxWidth(), lines = 4, mono = false)
        AfButton(tr(UiText.COPY_DESCRIPTION), { model.copyPublishText(PublishKitText.description(posted.copy(description = description)), "description") },
            Modifier.testTag("publish-copy-description"))
        AfText(tr(UiText.HASHTAGS_HINT), muted = true, size = 12.sp)
        AfTextField(hashtags, { hashtags = it }, "#မြန်မာ #movie", Modifier.fillMaxWidth())
        AfButton(tr(UiText.V3_COPY_HASHTAGS), { model.copyPublishText(hashtags, "hashtags") }, Modifier.testTag("publish-copy-hashtags"))
        WrapRow {
            AfButton(tr(UiText.SAVE), {
                val parsedHashtags = hashtags.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                model.setPublishKit(kit.copy(title = title, description = description, hashtags = parsedHashtags))
            }, Modifier.testTag("publish-save"), kind = ButtonKind.Primary)
            AfButton(tr(UiText.DISCARD), { title = kit.title; description = kit.description; hashtags = kit.hashtags.joinToString(" ") },
                Modifier.testTag("publish-discard"))
            AfButton(tr(UiText.SHARE_FILE), { scope.launch { model.sharePublishFile("export") } })
        }
        PublishThumbSection(kit, thumb, model)
    }
}

/**
 * The chapters on the Movie Recap step (Group 2 D, 23 · 1): a dark inner box (radius 12, black .22, 1 dp White .10) with
 * [heading] and one Muted caption line per chapter, cut with … when it does not fit (the whole line is its description).
 */
@Composable
private fun ChapterBox(heading: String, lines: List<String>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clip(VideoShape).background(GlassColors.PathFill).border(1.dp, GlassColors.PathBorder, VideoShape)
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        AfText(heading, muted = true, type = AfType.Caption)
        lines.forEach { line ->
            AfText(line, Modifier.semantics { contentDescription = line }, muted = true, type = AfType.Caption, maxLines = 1, ellipsis = true)
        }
    }
}

/**
 * The thumbnail on the Movie Recap step (Group 2 D, 23 · 1–4): small Make (`image`; Remake / Making `arrow-clockwise` once
 * there is one) and Share buttons, the title hint as a Muted info row, the image (radius 12 + shadow), failed / stale as
 * amber warning rows.
 */
@Composable
internal fun GlassPublishThumb(kit: PublishKit, thumb: PublishThumb?, model: ExportModel) {
    AfText(tr(UiText.THUMBNAIL_TITLE), muted = true, type = AfType.Caption)
    val working = thumb?.working == true
    val made = thumb?.frame != null
    WrapRow {
        GlassButton(
            if (working) tr(UiText.THUMBNAIL_WORKING) else if (!made) tr(UiText.MAKE_THUMBNAIL) else tr(UiText.REMAKE_THUMBNAIL),
            { model.makePublishThumbnail() }, Modifier.testTag("publish-thumb-make"), enabled = !working && kit.title.isNotBlank(),
            icon = if (made) AfIcons.Regular.Redo else AfIcons.Regular.Image, small = true,
        )
        GlassButton(tr(UiText.SHARE_THUMBNAIL), { model.sharePublishThumbnail() }, Modifier.testTag("publish-thumb-share"), enabled = !working && made,
            icon = AfIcons.Regular.Share, small = true)
    }
    if (kit.title.isBlank()) GlassFact(AfIcons.Regular.Info, tr(UiText.THUMBNAIL_TITLE_HINT, "SAVE" to (tr(UiText.SAVE))), colour = AutoFlowColors.Muted, caption = true)
    thumb ?: return
    thumb.failed?.let { GlassFact(AfIcons.Fill.Warn, it, colour = AutoFlowColors.Amber, iconColour = AutoFlowColors.Amber, caption = true,
        textModifier = Modifier.testTag("publish-thumb-failed")) }
    thumb.frame?.let { f ->
        val bmp = remember(f) { decodeImage(f.bytes) }
        if (bmp != null) {
            Image(bmp, tr(UiText.THUMBNAIL), Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height)
                .shadow(8.dp, VideoShape).clip(VideoShape).testTag("publish-thumb-image"))
        }
        AfText(
            "${PublishKitText.thumbPath(ExportModel.EXPORT_NAME)} · ${bmp?.let { "${it.width}×${it.height} · " }.orEmpty()}frame at ${RecapModel.seconds(thumb.atMs)}",
            Modifier.testTag("publish-thumb-info"), muted = true, type = AfType.Caption,
        )
        if (thumb.stale && !working) {
            GlassFact(AfIcons.Fill.Warn, tr(UiText.THUMBNAIL_STALE, "STALE" to (tr(UiText.STALE))), colour = AutoFlowColors.Amber,
                iconColour = AutoFlowColors.Amber, caption = true, textModifier = Modifier.testTag("publish-thumb-stale"))
        }
    }
}

/** Thumbnail (5B-10): make it from the saved title (and the chosen Exact frame time), see it, share it. */
@Composable
private fun PublishThumbSection(kit: PublishKit, thumb: PublishThumb?, model: ExportModel) {
    AfText(tr(UiText.THUMBNAIL_TITLE), muted = true, size = 12.sp)
    val working = thumb?.working == true
    WrapRow {
        AfButton(
            if (working) tr(UiText.THUMBNAIL_WORKING) else if (thumb?.frame == null) tr(UiText.MAKE_THUMBNAIL) else tr(UiText.REMAKE_THUMBNAIL),
            { model.makePublishThumbnail() }, Modifier.testTag("publish-thumb-make"), enabled = !working && kit.title.isNotBlank(),
        )
        AfButton(tr(UiText.SHARE_THUMBNAIL), { model.sharePublishThumbnail() }, Modifier.testTag("publish-thumb-share"), enabled = !working && thumb?.frame != null)
    }
    if (kit.title.isBlank()) AfText(tr(UiText.THUMBNAIL_TITLE_HINT, "SAVE" to (tr(UiText.SAVE))), muted = true, size = 11.sp)
    thumb ?: return
    thumb.failed?.let { AfText(it, Modifier.testTag("publish-thumb-failed"), color = AutoFlowColors.Amber, size = 12.sp) }
    thumb.frame?.let { f ->
        val bmp = remember(f) { decodeImage(f.bytes) }
        if (bmp != null) {
            Image(bmp, tr(UiText.THUMBNAIL), Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).clip(AutoFlowShapes.Card).testTag("publish-thumb-image"))
        }
        AfText(
            "${PublishKitText.thumbPath(ExportModel.EXPORT_NAME)} · ${bmp?.let { "${it.width}×${it.height} · " }.orEmpty()}frame at ${RecapModel.seconds(thumb.atMs)}",
            Modifier.testTag("publish-thumb-info"), muted = true, size = 11.sp,
        )
        if (thumb.stale && !working) {
            AfText("⚠ " + tr(UiText.THUMBNAIL_STALE, "STALE" to (tr(UiText.STALE))), Modifier.testTag("publish-thumb-stale"), color = AutoFlowColors.Amber, size = 12.sp)
        }
    }
}

/**
 * Pre-flight checklist card (5B-9, studio-v2.md §9): blockers and warnings shown before export.
 * Native Fix uses core's destination; Classic/no-router callers remain an honest read-only report.
 */
@Composable
internal fun PreflightCard(items: List<io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Item>,
    onFix: ((io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Item, RecapStep) -> Unit)? = null,
    enabled: Boolean = true, glass: Boolean = false,
) {
    ExportCard(tr(UiText.PREFLIGHT), ExportTiles.Preflight, glass, Modifier.testTag("preflight-report")) {
        items.forEachIndexed { index, item ->
            val message = preflightMessage(item, LocalUiLanguage.current)
            val fix: (@Composable (Modifier) -> Unit)? = if (onFix != null) preflightStep(item)?.let { step -> { modifier ->
                val label = tr(UiText.V3_PREFLIGHT_FIX, "step" to tr(step.textKey))
                val tag = Modifier.testTag("preflight-fix-${item.reason.name}-$index")
                if (glass) GlassButton(label, { onFix(item, step) }, modifier.then(tag), enabled = enabled, icon = AfIcons.Regular.Wrench)
                else AfButton(label, { onFix(item, step) }, modifier.then(tag), enabled = enabled)
            } } else null
            if (glass) PreflightRow(item.kind, message, index, fix)
            else {
                val color = when (item.kind) {
                    io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Kind.BLOCKER -> AutoFlowColors.Failed
                    io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Kind.WARNING -> AutoFlowColors.Amber.copy(alpha = 0.8f)
                    io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Kind.INFO -> null
                }
                val prefix = when (item.kind) {
                    io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Kind.BLOCKER -> "⛔ "
                    io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Kind.WARNING -> "⚠ "
                    io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Kind.INFO -> "ℹ "
                }
                AfText(prefix + message, Modifier.testTag("preflight-message-$index"), color = color, size = 12.sp)
                fix?.invoke(Modifier)
            }
        }
        val blockers = items.count { it.kind == io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Kind.BLOCKER }
        val warnings = items.count { it.kind == io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Kind.WARNING }
        if (blockers == 0 && warnings == 0) {
            if (glass) GlassFact(AfIcons.Fill.CheckCircle, tr(UiText.PREFLIGHT_OK), colour = AutoFlowColors.Green, iconColour = AutoFlowColors.Green)
            else AfText("✓ " + tr(UiText.PREFLIGHT_OK), color = AutoFlowColors.Green)
        }
    }
}

/**
 * Group 2 B: one preflight item on the Movie Recap step — a row tinted in its tone (.08 fill, .22 border) with the Fill
 * x-circle / warning / info icon instead of ⛔ / ⚠ / ℹ, and its Fix button inside.
 */
@Composable
private fun PreflightRow(kind: io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Kind, message: String, index: Int,
    fix: (@Composable (Modifier) -> Unit)?) {
    val (icon, tone) = when (kind) {
        io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Kind.BLOCKER -> AfIcons.Fill.XCircle to AutoFlowColors.Failed
        io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Kind.WARNING -> AfIcons.Fill.Warn to AutoFlowColors.Amber
        io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Kind.INFO -> AfIcons.Fill.Info to AutoFlowColors.Info
    }
    val shape = GlassShapes.Callout
    Column(Modifier.fillMaxWidth().clip(shape).background(tone.copy(alpha = .08f)).border(1.dp, tone.copy(alpha = .22f), shape)
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfIcon(icon, Modifier.padding(top = 2.dp), tint = tone, size = 18.dp)
            AfText(message, Modifier.weight(1f).testTag("preflight-message-$index"),
                color = if (kind == io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Kind.INFO) AutoFlowColors.Text else tone, type = AfType.Caption)
        }
        fix?.invoke(Modifier.padding(start = 26.dp))
    }
}
