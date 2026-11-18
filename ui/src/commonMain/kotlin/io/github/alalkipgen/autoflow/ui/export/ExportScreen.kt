package io.github.alalkipgen.autoflow.ui.export

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.alalkipgen.autoflow.core.recap.ExportQuality
import io.github.alalkipgen.autoflow.core.recap.PublishKit
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

const val PREVIEW_BUTTON = "နမူနာ 540p"
const val EXPORT_BUTTON = "ထုတ်ယူမယ် 1080p"
const val SAVE_BUTTON = "Gallery ထဲ သိမ်းမယ်"
const val SHARE = "မျှဝေမယ်…"
internal const val LOUDNESS = "အသံကျယ်မှု"

/**
 * Export (PLAN Q-09 c): what goes into the video (shots, accepted videos, song, lyrics font), Preview / Export,
 * progress + Cancel, then Open / Share. The battery warning (below 20 % and not charging) warns, it does not block.
 */
@Composable
fun ExportScreen(model: ExportModel, modifier: Modifier = Modifier, studio: Boolean = false,
    onPreflightFix: ((RecapStep) -> Unit)? = null,
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
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPadding).widthIn(max = 760.dp),
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
        if (recap != null) RecapSummary(recap, state.font, model, studio, state.busy) else AfCard(tr(UiText.VIDEO)) {
            AfText(tr(UiText.EXPORT_SHOT_COUNT, "shots" to (state.shots), "durationMs" to (InputModel.formatDuration(state.durationMs))))
            val pending = state.shots - state.accepted
            AfText(
                tr(UiText.ACCEPTED_VIDEO_COUNT, "accepted" to (state.accepted)) + (if (LocalUiLanguage.current == UiLanguage.ENGLISH && state.accepted != 1) "s" else "") + if (pending > 0) tr(UiText.PENDING_VIDEO_COUNT, "pending" to (pending)) else "",
                color = if (pending > 0) AutoFlowColors.Amber else null,
            )
            AfText(
                state.songFile?.let { "♪ $it" } ?: tr(UiText.NO_SONG_EXPORT),
                color = if (state.songFile == null) AutoFlowColors.Amber else null,
            )
            AfText(
                when {
                    state.font == null -> tr(UiText.NO_BURMESE_FONT_LYRICS)
                    state.syncedLines > 0 -> tr(UiText.LYRICS_SYNC_COUNT, "syncedLines" to (state.syncedLines), "font" to (state.font))
                    state.lyricShots == 0 -> tr(UiText.NO_SHOT_LYRICS)
                    else -> tr(UiText.LYRICS_SHOT_COUNT, "lyricShots" to (state.lyricShots), "font" to (state.font))
                },
                color = if (state.font == null) AutoFlowColors.Amber else null,
            )
            if (state.timingOutdated) {
                AfText(tr(UiText.LYRICS_TIMING_STALE), color = AutoFlowColors.Amber)
            }
        }
        if (studio && recap != null && recap.preflight.isNotEmpty()) PreflightCard(recap.preflight, fix, !state.busy)
        if (!studio || state.run != null || state.lastRenders.isNotEmpty() ||
            state.unavailable != null || state.lowBattery || state.message != null) AfCard(tr(UiText.RENDER_HEADING)) {
            state.unavailable?.let { AfText(if (it == ExportModel.NO_QUEUE) tr(UiText.EXPORT_NO_QUEUE) else tr(UiText.RESULT_DETAIL, "detail" to it), color = AutoFlowColors.Amber) }
            if (state.lowBattery) AfText(tr(UiText.LOW_BATTERY_EXPORT), color = AutoFlowColors.Amber)
            if (!studio) WrapRow {
                AfButton(tr(UiText.PREVIEW_BUTTON), { model.render(Quality.PREVIEW) }, Modifier.testTag("export-preview"), enabled = state.canRender)
                val exportLabel = state.recap?.let { tr(UiText.EXPORT_WITH_QUALITY, "quality" to it.quality.uiLabel()) } ?: tr(UiText.EXPORT_BUTTON)
                AfButton(exportLabel, { model.render(Quality.EXPORT) }, Modifier.testTag("export-render"), kind = ButtonKind.Primary, enabled = state.canRender)
            }
            if (!studio) AfText(tr(UiText.RENDER_BACKGROUND_HINT), muted = true)
            state.run?.let { RunStatus(it, model, state.savingToGallery) }
            if (state.run == null) state.lastRenders.forEach { LastRenderStatus(it, model) }
            state.message?.let { AfText(state.notice?.text(LocalUiLanguage.current) ?: tr(UiText.RESULT_DETAIL, "detail" to it), color = if (state.messageOk) AutoFlowColors.Green else AutoFlowColors.Amber) }
        }
        // Pre-flight (5B-9): blockers and warnings shown before export
        state.recap?.let { recap ->
            if (!studio && recap.preflight.isNotEmpty()) PreflightCard(recap.preflight)
            // Exact-frame thumbnails (5B-9): stills made by the export compiler itself.
            ExactFramesCard(state.thumbs, canMake = recap.movieFile != null && !state.busy, model)
        }
        // Publish Kit (5B-10): only for Recap projects
        state.recap?.let { recap ->
            androidx.compose.runtime.key(open?.dir, open?.project?.id) {
                PublishKitCard(recap.publishKit, recap.chapters, state.publishThumb, model, studio)
            }
        }
    }
}

/** Movie Recap project (docs/specs/recap.md §6): scenes, voice, sound, looks, captions, quality and the rights reminder. */
@Composable
private fun RecapSummary(recap: RecapExport, font: String?, model: ExportModel, studio: Boolean, busy: Boolean) {
    AfCard(if (studio) tr(UiText.OUTPUT_CARD) else "Movie Recap", Modifier.testTag("export-output")) {
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
        if (studio) AfText(tr(UiText.RENDER_BACKGROUND_HINT), muted = true, size = 12.sp)
    }
}

/** Audit F09 / 5B-4: two-pass only when the pass-1 numbers are there; otherwise the fallback and why. */
internal fun loudnessLine(l: RenderState.Loudness, language: UiLanguage = UiLanguage.ENGLISH): String =
    if (l.twoPass) UiText.LOUDNESS_TWO_PASS.text(language, "target" to l.targetLufs, "measured" to l.measured!!.inputI)
    else UiText.LOUDNESS_NOTE.text(language, "note" to (l.note ?: UiText.LOUDNESS_ONE_PASS.text(language, "target" to l.targetLufs)))

/** Run/result kind alone does not encode the resolved output size. Never infer 1080p from EXPORT. */
private fun outputLabel(quality: Quality, language: UiLanguage): String =
    if (quality == Quality.EXPORT) UiText.EXPORT.text(language) else ExportModel.label(quality, language)

/** A finished render read back from its `exports/<name>.result.json` (5B-4), e.g. after the app restarted. */
@Composable
private fun LastRenderStatus(last: LastRender, model: ExportModel) {
    val r = last.result
    AfText(
        tr(UiText.LAST_RENDER, "quality" to (outputLabel(last.quality, LocalUiLanguage.current)), "durationMs" to (InputModel.formatDuration(r.durationMs)), "wallMs1000" to (r.wallMs / 1000)),
        Modifier.testTag("export-last"),
        color = AutoFlowColors.Green,
    )
    AfText("exports/${r.output}", muted = true, mono = true)
    r.loudness?.let { AfText(loudnessLine(it, LocalUiLanguage.current), Modifier.testTag("export-last-loudness"), muted = true, size = 11.sp) }
    WrapRow {
        AfButton(tr(UiText.OPEN), { model.openLast(last) })
        AfButton(tr(UiText.SHARE), { model.shareLast(last) })
    }
}

@Composable
private fun RunStatus(run: ExportRun, model: ExportModel, saving: Boolean) {
    val what = outputLabel(run.quality, LocalUiLanguage.current)
    StatusChip(when (run.state) {
        RenderState.Queued -> StudioStatus.QUEUED
        is RenderState.Running -> StudioStatus.RUNNING
        is RenderState.Done -> StudioStatus.DONE
        is RenderState.Failed -> StudioStatus.FAILED
        RenderState.Cancelled -> StudioStatus.CANCELLED
    })
    when (val s = run.state) {
        RenderState.Queued -> {
            AfText(tr(UiText.RENDER_QUEUED, "what" to (what)), Modifier.testTag("export-status"))
            AfButton(tr(UiText.CANCEL), model::cancel)
        }
        is RenderState.Running -> {
            AfText(tr(UiText.RENDER_PROGRESS, "what" to (what), "coerceIn0100" to ((s.fraction * 100).toInt().coerceIn(0, 100))), Modifier.testTag("export-status"))
            AfButton(tr(UiText.CANCEL), model::cancel)
        }
        is RenderState.Done -> {
            // Inline video preview (D-57 / 5A-8): tap play to watch the render without leaving the app.
            io.github.alalkipgen.autoflow.ui.components.VideoPreview(s.output)
            AfText(
                tr(UiText.RENDER_DONE, "what" to (what), "durationMs" to (InputModel.formatDuration(s.durationMs)), "wallMs1000" to (s.wallMs / 1000)),
                Modifier.testTag("export-status"),
                color = AutoFlowColors.Green,
            )
            AfText(s.output.substringAfterLast('/').let { "exports/$it" }, muted = true, mono = true)
            // Audit F09: say how the loudness target was met — never claim two-pass when it fell back.
            s.loudness?.let { AfText(loudnessLine(it, LocalUiLanguage.current), Modifier.testTag("export-loudness"), muted = true, size = 11.sp) }
            WrapRow {
                AfButton(tr(UiText.OPEN), model::open, kind = ButtonKind.Primary)
                AfButton(tr(UiText.SHARE), model::share)
                AfButton(if (saving) tr(UiText.SAVING) else tr(UiText.SAVE_GALLERY), model::saveToGallery, enabled = !saving)
            }
        }
        is RenderState.Failed -> {
            AfText(tr(UiText.RENDER_FAILED, "what" to (what), "reason" to (s.reason)), Modifier.testTag("export-status"), color = AutoFlowColors.Failed)
            if (s.tail.isNotEmpty()) AfText(s.tail.takeLast(6).joinToString("\n"), muted = true, mono = true, size = 11.sp)
        }
        RenderState.Cancelled -> AfText(tr(UiText.RENDER_CANCELLED, "what" to (what)), Modifier.testTag("export-status"), muted = true)
    }
}

/**
 * Exact-frame thumbnails (5B-9, D-42): up to six stills of the recap made by the export compiler (blur boxes,
 * background, looks, layers, burned-in captions exactly as rendered) at caption times; any one can become the
 * Publish Kit thumbnail. Marked out of date when the recap changes after they were made.
 */
@Composable
private fun ExactFramesCard(thumbs: ExportThumbs?, canMake: Boolean, model: ExportModel) {
    AfCard(tr(UiText.EXACT_FRAMES)) {
        AfText(tr(UiText.EXACT_FRAMES_HINT), muted = true, size = 12.sp)
        val working = thumbs?.working == true
        val label = when {
            thumbs == null -> tr(UiText.MAKE_EXACT_FRAMES)
            working -> tr(UiText.EXACT_FRAMES_PROGRESS, "size" to (thumbs.times.size), "value2" to (minOf(thumbs.frames.size + 1, thumbs.times.size)))
            else -> tr(UiText.REMAKE)
        }
        AfButton(label, { model.makeThumbnails() }, Modifier.testTag("export-thumbs-make"), enabled = canMake && !working)
        if (thumbs == null) return@AfCard
        if (thumbs.stale) AfText(tr(UiText.EXACT_FRAMES_STALE, "STALE" to (tr(UiText.STALE))), Modifier.testTag("export-thumbs-stale"), color = AutoFlowColors.Amber, size = 12.sp)
        thumbs.failed?.let { AfText(it, Modifier.testTag("export-thumbs-failed"), color = AutoFlowColors.Amber, size = 12.sp) }
        WrapRow {
            thumbs.frames.forEachIndexed { i, f ->
                Column(Modifier.width(176.dp).testTag("export-thumb-$i"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val bmp = remember(f) { decodeImage(f.bytes) }
                    if (bmp != null) {
                        Image(bmp, tr(UiText.EXACT_FRAME_AT, "atMs" to (PublishKitText.timestamp(f.atMs))), Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).clip(AutoFlowShapes.Card))
                    } else {
                        AfText(tr(UiText.IMAGE_UNAVAILABLE), color = AutoFlowColors.Amber, size = 11.sp)
                    }
                    AfText(RecapModel.seconds(f.atMs), muted = true, size = 11.sp)
                    AfButton(tr(UiText.USE_THUMBNAIL), { model.makePublishThumbnail(f.atMs) }, Modifier.testTag("export-thumb-use-$i"), enabled = !thumbs.stale)
                }
            }
        }
    }
}

/**
 * Publish Kit (5B-10, studio-v2 §9): edit title / description / hashtags, copy each item, share the full text file;
 * chapters come from the story blocks' start times when the kit has none of its own; the thumbnail
 * (`exports/export.thumb.jpg`) is an export-size Exact frame with the saved title as a TEXT layer.
 */
@Composable
private fun PublishKitCard(kit: PublishKit, chapters: List<io.github.alalkipgen.autoflow.core.recap.PublishChapter>, thumb: PublishThumb?, model: ExportModel, studio: Boolean) {
    val scope = rememberCoroutineScope()
    var title by remember(kit.title) { mutableStateOf(kit.title) }
    var description by remember(kit.description) { mutableStateOf(kit.description) }
    var hashtags by remember(kit.hashtags) { mutableStateOf(kit.hashtags.joinToString(" ")) }
    SectionCard("Publish Kit", Modifier.testTag("publish-kit"), initiallyExpanded = !studio) {
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
            AfText(tr(UiText.THUMBNAIL_STALE, "STALE" to (tr(UiText.STALE))), Modifier.testTag("publish-thumb-stale"), color = AutoFlowColors.Amber, size = 12.sp)
        }
    }
}

/**
 * Pre-flight checklist card (5B-9, studio-v2.md §9): blockers and warnings shown before export.
 * Native Fix uses core's destination; Classic/no-router callers remain an honest read-only report.
 */
@Composable
private fun PreflightCard(items: List<io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Item>,
    onFix: ((io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Item, RecapStep) -> Unit)? = null,
    enabled: Boolean = true,
) {
    AfCard(tr(UiText.PREFLIGHT), Modifier.testTag("preflight-report")) {
        items.forEachIndexed { index, item ->
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
            val text = prefix + preflightMessage(item, LocalUiLanguage.current)
            AfText(text, Modifier.testTag("preflight-message-$index"), color = color, size = 12.sp)
            if (onFix != null) preflightStep(item)?.let { step ->
                AfButton(tr(UiText.V3_PREFLIGHT_FIX, "step" to tr(step.textKey)), { onFix(item, step) },
                    Modifier.testTag("preflight-fix-${item.reason.name}-$index"), enabled = enabled)
            }
        }
        val blockers = items.count { it.kind == io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Kind.BLOCKER }
        val warnings = items.count { it.kind == io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Kind.WARNING }
        if (blockers == 0 && warnings == 0) AfText(tr(UiText.PREFLIGHT_OK), color = AutoFlowColors.Green)
    }
}
