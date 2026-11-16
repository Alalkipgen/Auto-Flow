package io.github.alalkipgen.autoflow.ui.export

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
import io.github.alalkipgen.autoflow.ui.input.InputModel
import io.github.alalkipgen.autoflow.ui.projects.RIGHTS_NOTICE
import io.github.alalkipgen.autoflow.ui.recap.RecapModel
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
fun ExportScreen(model: ExportModel, modifier: Modifier = Modifier) {
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.refresh() }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPadding).widthIn(max = 760.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AfText("ထုတ်ယူ", size = 18.sp, bold = true)
        if (state.projectName == null) {
            AfText("Dashboard မှာ project တစ်ခု အရင်ဖွင့်ပါ။ (Open a project on the Dashboard first.)", muted = true)
            return@Column
        }
        val recap = state.recap
        if (recap != null) RecapSummary(recap, state.font, model) else AfCard("Video") {
            AfText("${state.shots} shots · ${InputModel.formatDuration(state.durationMs)}")
            val pending = state.shots - state.accepted
            AfText(
                "${state.accepted} accepted video" + (if (state.accepted == 1) "" else "s") + if (pending > 0) " · $pending show as \"pending\" cards" else "",
                color = if (pending > 0) AutoFlowColors.Amber else null,
            )
            AfText(
                state.songFile?.let { "♪ $it" } ?: "No song: the video will be silent (Input → သီချင်းဖိုင် ထည့်မယ်).",
                color = if (state.songFile == null) AutoFlowColors.Amber else null,
            )
            AfText(
                when {
                    state.font == null -> "No Burmese font: lyrics are left out (Settings → Fonts: Pyidaungsu or Noto Sans Myanmar)."
                    state.syncedLines > 0 -> "Lyrics: ${state.syncedLines} synced lines · ${state.font}"
                    state.lyricShots == 0 -> "No lyrics on the shots."
                    else -> "Lyrics on ${state.lyricShots} shots · ${state.font}"
                },
                color = if (state.font == null) AutoFlowColors.Amber else null,
            )
            if (state.timingOutdated) {
                AfText("⚠ The lyrics changed after the sync: each shot's lyrics are used (Input → Lyrics timing → Sync again).", color = AutoFlowColors.Amber)
            }
        }
        AfCard("Render") {
            state.unavailable?.let { AfText(it, color = AutoFlowColors.Amber) }
            if (state.lowBattery) AfText("ဘက်ထရီ နည်းနေပြီး အားမသွင်းထားပါ: အကြာကြီး မထုတ်ယူခင် အားသွင်းကြိုး တပ်ပါ။", color = AutoFlowColors.Amber)
            WrapRow {
                AfButton(PREVIEW_BUTTON, { model.render(Quality.PREVIEW) }, enabled = state.canRender)
                val exportLabel = state.recap?.let { "ထုတ်ယူမယ် " + ExportModel.label(it.quality) } ?: EXPORT_BUTTON
                AfButton(exportLabel, { model.render(Quality.EXPORT) }, kind = ButtonKind.Primary, enabled = state.canRender)
            }
            AfText("နမူနာက မြန်ပြီး သေးတယ်၊ ထုတ်ယူက အရည်အသွေး အပြည့်နဲ့ ဗွီဒီယို ဖြစ်တယ်။ ဖန်သားပြင် ပိတ်ထားလည်း render ဆက်လုပ်မယ်။", muted = true)
            state.run?.let { RunStatus(it, model, state.savingToGallery) }
            if (state.run == null) state.lastRenders.forEach { LastRenderStatus(it, model) }
            state.message?.let { AfText(it, color = if (state.messageOk) AutoFlowColors.Green else AutoFlowColors.Amber) }
        }
        // Pre-flight (5B-9): blockers and warnings shown before export
        state.recap?.let { recap ->
            if (recap.preflight.isNotEmpty()) PreflightCard(recap.preflight)
            // Exact-frame thumbnails (5B-9): stills made by the export compiler itself.
            ExactFramesCard(state.thumbs, canMake = recap.movieFile != null && !state.busy, model)
        }
        // Publish Kit (5B-10): only for Recap projects
        state.recap?.let { recap ->
            PublishKitCard(recap.publishKit, recap.chapters, state.publishThumb, model)
        }
    }
}

/** Movie Recap project (docs/specs/recap.md §6): scenes, voice, sound, looks, captions, quality and the rights reminder. */
@Composable
private fun RecapSummary(recap: RecapExport, font: String?, model: ExportModel) {
    AfCard("Movie Recap") {
        AfText(RIGHTS_NOTICE, color = AutoFlowColors.Amber, size = 12.sp)
        AfText("${recap.segments} scenes · ${InputModel.formatDuration(recap.durationMs)}" + (recap.movieFile?.let { " · 🎬 $it" } ?: ""))
        val silent = recap.segments - recap.voiced
        AfText(
            "${recap.voiced} of ${recap.segments} voiced" + if (silent > 0) " · $silent play with movie sound only" else "",
            color = if (silent > 0) AutoFlowColors.Amber else null,
        )
        val sound = RecapModel.MOVIE_VOLUMES.firstOrNull { it.second == recap.movieVolume }?.first ?: "${(recap.movieVolume * 100).toInt()} %"
        AfText("Movie sound: $sound · Looks: ${if (recap.looks == 0) "none" else recap.looks.toString()}")
        AfText(
            when {
                !recap.burnCaptions -> "Captions: .srt file only (not burned in)."
                font == null -> "No Burmese font: captions are not burned in, .srt only (Settings → Fonts)."
                else -> "Captions burned in · $font · and saved as .srt"
            },
            color = if (recap.burnCaptions && font == null) AutoFlowColors.Amber else null,
        )
        AfText("ထုတ်ယူမည့် အရည်အသွေး", muted = true)
        WrapRow {
            ExportQuality.entries.forEach { q ->
                AfChip(ExportModel.label(q), q == recap.quality, onClick = { model.setRecapQuality(q) })
            }
        }
        recap.blocker?.let { AfText(it, color = AutoFlowColors.Amber) }
    }
}

/** Audit F09 / 5B-4: two-pass only when the pass-1 numbers are there; otherwise the fallback and why. */
internal fun loudnessLine(l: RenderState.Loudness): String =
    if (l.twoPass) "$LOUDNESS: two-pass ဖြင့် ${l.targetLufs} LUFS (တိုင်းတာချက် ${l.measured!!.inputI} LUFS)" else "$LOUDNESS: ${l.note ?: "one-pass ဖြင့် ${l.targetLufs} LUFS"}"

/** A finished render read back from its `exports/<name>.result.json` (5B-4), e.g. after the app restarted. */
@Composable
private fun LastRenderStatus(last: LastRender, model: ExportModel) {
    val r = last.result
    AfText(
        "နောက်ဆုံး ${ExportModel.label(last.quality)} ✓ · ${InputModel.formatDuration(r.durationMs)} · ${r.wallMs / 1000} s ကြာ",
        Modifier.testTag("export-last"),
        color = AutoFlowColors.Green,
    )
    AfText("exports/${r.output}", muted = true, mono = true)
    r.loudness?.let { AfText(loudnessLine(it), Modifier.testTag("export-last-loudness"), muted = true, size = 11.sp) }
    WrapRow {
        AfButton(tr(UiText.OPEN), { model.openLast(last) })
        AfButton(SHARE, { model.shareLast(last) })
    }
}

@Composable
private fun RunStatus(run: ExportRun, model: ExportModel, saving: Boolean) {
    val what = ExportModel.label(run.quality)
    when (val s = run.state) {
        RenderState.Queued -> {
            AfText("$what: စတင်ရန် စောင့်နေတယ်…", Modifier.testTag("export-status"))
            AfButton(tr(UiText.CANCEL), model::cancel)
        }
        is RenderState.Running -> {
            AfText("$what: render လုပ်နေတယ်… ${(s.fraction * 100).toInt().coerceIn(0, 100)} %", Modifier.testTag("export-status"))
            AfButton(tr(UiText.CANCEL), model::cancel)
        }
        is RenderState.Done -> {
            // Inline video preview (D-57 / 5A-8): tap play to watch the render without leaving the app.
            io.github.alalkipgen.autoflow.ui.components.VideoPreview(s.output)
            AfText(
                "$what ပြီးပြီ ✓ · ${InputModel.formatDuration(s.durationMs)} · ${s.wallMs / 1000} s ကြာ",
                Modifier.testTag("export-status"),
                color = AutoFlowColors.Green,
            )
            AfText(s.output.substringAfterLast('/').let { "exports/$it" }, muted = true, mono = true)
            // Audit F09: say how the loudness target was met — never claim two-pass when it fell back.
            s.loudness?.let { AfText(loudnessLine(it), Modifier.testTag("export-loudness"), muted = true, size = 11.sp) }
            WrapRow {
                AfButton(tr(UiText.OPEN), model::open, kind = ButtonKind.Primary)
                AfButton(SHARE, model::share)
                AfButton(if (saving) "သိမ်းနေတယ်…" else SAVE_BUTTON, model::saveToGallery, enabled = !saving)
            }
        }
        is RenderState.Failed -> {
            AfText("$what မအောင်မြင်ပါ: ${s.reason}", Modifier.testTag("export-status"), color = AutoFlowColors.Amber)
            if (s.tail.isNotEmpty()) AfText(s.tail.takeLast(6).joinToString("\n"), muted = true, mono = true, size = 11.sp)
        }
        RenderState.Cancelled -> AfText("$what ပယ်ဖျက်ပြီး။", Modifier.testTag("export-status"), muted = true)
    }
}

/**
 * Exact-frame thumbnails (5B-9, D-42): up to six stills of the recap made by the export compiler (blur boxes,
 * background, looks, layers, burned-in captions exactly as rendered) at caption times; any one can become the
 * Publish Kit thumbnail. Marked out of date when the recap changes after they were made.
 */
@Composable
private fun ExactFramesCard(thumbs: ExportThumbs?, canMake: Boolean, model: ExportModel) {
    AfCard("အတိအကျ ပုံများ") {
        AfText("စာတန်းချိန်တွေမှာ ထုတ်ယူမှုကိုယ်တိုင် လုပ်တဲ့ ရုပ်ငြိမ်ပုံများ — ဗွီဒီယိုမှာ တကယ် ပေါ်မယ့်အတိုင်း။", muted = true, size = 12.sp)
        val working = thumbs?.working == true
        val label = when {
            thumbs == null -> "အတိအကျ ပုံများ လုပ်မယ်"
            working -> "${thumbs.times.size} ပုံအနက် ${minOf(thumbs.frames.size + 1, thumbs.times.size)} ပုံ လုပ်နေတယ်…"
            else -> "ပြန်လုပ်မယ်"
        }
        AfButton(label, { model.makeThumbnails() }, Modifier.testTag("export-thumbs-make"), enabled = canMake && !working)
        if (thumbs == null) return@AfCard
        if (thumbs.stale) AfText("⚠ ${tr(UiText.STALE)}: ဒီပုံတွေ လုပ်ပြီးနောက် recap ပြောင်းသွားတယ်။", Modifier.testTag("export-thumbs-stale"), color = AutoFlowColors.Amber, size = 12.sp)
        thumbs.failed?.let { AfText(it, Modifier.testTag("export-thumbs-failed"), color = AutoFlowColors.Amber, size = 12.sp) }
        WrapRow {
            thumbs.frames.forEachIndexed { i, f ->
                Column(Modifier.width(176.dp).testTag("export-thumb-$i"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val bmp = remember(f) { decodeImage(f.bytes) }
                    if (bmp != null) {
                        Image(bmp, "Exact frame at ${PublishKitText.timestamp(f.atMs)}", Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).clip(AutoFlowShapes.Card))
                    } else {
                        AfText("ဒီပုံကို ပြလို့ မရပါ။", color = AutoFlowColors.Amber, size = 11.sp)
                    }
                    AfText(RecapModel.seconds(f.atMs), muted = true, size = 11.sp)
                    AfButton("Thumbnail အတွက် သုံးမယ်", { model.makePublishThumbnail(f.atMs) }, Modifier.testTag("export-thumb-use-$i"), enabled = !thumbs.stale)
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
private fun PublishKitCard(kit: PublishKit, chapters: List<io.github.alalkipgen.autoflow.core.recap.PublishChapter>, thumb: PublishThumb?, model: ExportModel) {
    val scope = rememberCoroutineScope()
    var title by remember(kit.title) { mutableStateOf(kit.title) }
    var description by remember(kit.description) { mutableStateOf(kit.description) }
    var hashtags by remember(kit.hashtags) { mutableStateOf(kit.hashtags.joinToString(" ")) }
    AfCard("Publish Kit") {
        AfText("ခေါင်းစဉ်", muted = true, size = 12.sp)
        AfTextField(title, { title = it }, "ဗွီဒီယို ခေါင်းစဉ် (မြန်မာလို)", Modifier.fillMaxWidth())
        AfButton("ခေါင်းစဉ် ကူးမယ်", { model.copyPublishText(title, "title") })
        AfText("ဖော်ပြချက်", muted = true, size = 12.sp)
        val posted = PublishKitText.withChapters(kit, chapters)
        val chapterLines = PublishKitText.chapterLines(posted.chapters)
        AfText(
            if (chapterLines.isEmpty()) "Chapters: မရှိသေးပါ (script အတည်ပြုပြီးရင် script အပိုင်းတွေကနေ ရမယ်)။"
            else "Chapters (script အပိုင်းများမှ):\n" + chapterLines.joinToString("\n"),
            Modifier.testTag("publish-chapters"), muted = true, size = 11.sp,
        )
        AfTextField(description, { description = it }, "ဖော်ပြချက် (မြန်မာလို)", Modifier.fillMaxWidth(), lines = 4, mono = false)
        AfButton("ဖော်ပြချက် ကူးမယ်", { model.copyPublishText(PublishKitText.description(posted.copy(description = description)), "description") })
        AfText("Hashtag များ (space ခြား၊ # မပါလည်း ရ)", muted = true, size = 12.sp)
        AfTextField(hashtags, { hashtags = it }, "#မြန်မာ #movie", Modifier.fillMaxWidth())
        WrapRow {
            AfButton(tr(UiText.SAVE), {
                val parsedHashtags = hashtags.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                model.setPublishKit(kit.copy(title = title, description = description, hashtags = parsedHashtags))
            }, kind = ButtonKind.Primary)
            AfButton("ဖိုင် မျှဝေမယ်…", { scope.launch { model.sharePublishFile("export") } })
        }
        PublishThumbSection(kit, thumb, model)
    }
}

/** Thumbnail (5B-10): make it from the saved title (and the chosen Exact frame time), see it, share it. */
@Composable
private fun PublishThumbSection(kit: PublishKit, thumb: PublishThumb?, model: ExportModel) {
    AfText("Thumbnail (ပုံ + ခေါင်းစဉ်)", muted = true, size = 12.sp)
    val working = thumb?.working == true
    WrapRow {
        AfButton(
            if (working) "Thumbnail လုပ်နေတယ်…" else if (thumb?.frame == null) "Thumbnail လုပ်မယ်" else "Thumbnail ပြန်လုပ်မယ်",
            { model.makePublishThumbnail() }, Modifier.testTag("publish-thumb-make"), enabled = !working && kit.title.isNotBlank(),
        )
        AfButton("Thumbnail မျှဝေမယ်…", { model.sharePublishThumbnail() }, Modifier.testTag("publish-thumb-share"), enabled = !working && thumb?.frame != null)
    }
    if (kit.title.isBlank()) AfText("Thumbnail လုပ်ဖို့ ခေါင်းစဉ် ရေးပြီး ${tr(UiText.SAVE)} ကို နှိပ်ပါ။", muted = true, size = 11.sp)
    thumb ?: return
    thumb.failed?.let { AfText(it, Modifier.testTag("publish-thumb-failed"), color = AutoFlowColors.Amber, size = 12.sp) }
    thumb.frame?.let { f ->
        val bmp = remember(f) { decodeImage(f.bytes) }
        if (bmp != null) {
            Image(bmp, "Thumbnail", Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).clip(AutoFlowShapes.Card).testTag("publish-thumb-image"))
        }
        AfText(
            "${PublishKitText.thumbPath(ExportModel.EXPORT_NAME)} · ${bmp?.let { "${it.width}×${it.height} · " }.orEmpty()}frame at ${RecapModel.seconds(thumb.atMs)}",
            Modifier.testTag("publish-thumb-info"), muted = true, size = 11.sp,
        )
        if (thumb.stale && !working) {
            AfText("⚠ ${tr(UiText.STALE)}: ဒီ thumbnail လုပ်ပြီးနောက် ခေါင်းစဉ် ဒါမှမဟုတ် ပုံ ပြောင်းသွားတယ်။", Modifier.testTag("publish-thumb-stale"), color = AutoFlowColors.Amber, size = 12.sp)
        }
    }
}

/**
 * Pre-flight checklist card (5B-9, studio-v2.md §9): blockers and warnings shown before export.
 * Each row has a "Fix →" label pointing to the step to fix it.
 */
@Composable
private fun PreflightCard(items: List<io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Item>) {
    AfCard("မထုတ်ခင် စစ်ဆေးချက်") {
        items.forEach { item ->
            val color = when (item.kind) {
                io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Kind.BLOCKER -> AutoFlowColors.Amber
                io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Kind.WARNING -> AutoFlowColors.Amber.copy(alpha = 0.8f)
                io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Kind.INFO -> null
            }
            val prefix = when (item.kind) {
                io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Kind.BLOCKER -> "⛔ "
                io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Kind.WARNING -> "⚠ "
                io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Kind.INFO -> "ℹ "
            }
            val text = prefix + item.message + item.fix?.let { " — ပြင်ရန် → $it" }.orEmpty()
            AfText(text, color = color, size = 12.sp)
        }
        val blockers = items.count { it.kind == io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Kind.BLOCKER }
        val warnings = items.count { it.kind == io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Kind.WARNING }
        if (blockers == 0 && warnings == 0) AfText("✓ စစ်ဆေးချက် အားလုံး အောင်မြင်တယ်", color = AutoFlowColors.Green)
    }
}
