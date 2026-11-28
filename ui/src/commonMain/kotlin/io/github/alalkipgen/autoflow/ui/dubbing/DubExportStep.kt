package io.github.alalkipgen.autoflow.ui.dubbing

import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.dubbing.DubExportCheck
import io.github.alalkipgen.autoflow.core.model.DubOriginal
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.ui.pack.RenderPackageCard
import io.github.alalkipgen.autoflow.ui.pack.RenderPlan
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.media.audio.DubMixer
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.GlassColors
import kotlinx.coroutines.launch

/**
 * The Export step (D-3b, Group 4 §G): the checklist of the three real checks (green rows, or amber with Go to), the
 * options card (the original sound chosen on Voice, whether the Burmese subtitles go in the video — chosen on Style
 * (D-105), with Go to Style — and the `.wav` switch), the mix's job card with progress / Cancel, the finished files with Play · Share · Save to Gallery (phone)
 * or Open folder (desktop), and the sticky Make the dubbed video with its reason while a check fails.
 */
@Composable
internal fun ExportStep(model: DubExportModel, open: OpenProject?, doc: SubtitleDoc, go: (DubStep) -> Unit, wide: Boolean = false,
    preview: @Composable () -> Unit = {}) {
    val all by model.state.collectAsState()
    val runs by model.runs.collectAsState()
    val s = all.takeIf { it.dir == open?.dir } ?: DubExportUi()
    val check = remember(open?.project, doc) { model.check(open, doc) }
    val spoken = remember(doc) { model.spokenLines(doc) }
    val run = remember(runs, s.runs) { model.current() }
    val tool = open?.project?.dubbing
    // Desktop (Group 4 H, sample 12c): the checklist, options and package on the left; the preview and the job / done card on the right.
    val left = @Composable { modifier: Modifier -> Column(modifier.paneScroll(rememberScrollState()).padding(screenPadding).testTag("dub-export"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!wide) preview() // F-6: scrolls with the step
            if (check != null) Checklist(check, spoken, go)
            TileCard(My.breakSyllables(tr(UiText.DUB_STEP_EXPORT)), CardTile(AfIcons.Fill.SlidersHorizontal, AutoFlowColors.Purple),
                Modifier.testTag("dub-export-options")) {
                val original = tool?.original ?: DubOriginal()
                val mute = original.mode == DubOriginal.MUTE
                val percent = kotlin.math.round(original.volume * 100).toInt()
                GlassFact(if (mute) AfIcons.Regular.SpeakerMuted else AfIcons.Regular.SpeakerLow,
                    My.breakSyllables(tr(UiText.DUB_ORIGINAL) + ": " + (if (mute) tr(UiText.V3_DUB_MUTE) else tr(UiText.V3_DUB_LOWER, "percent" to percent))),
                    textModifier = Modifier.testTag("dub-export-original"))
                Divider()
                // D-105: the Burmese subtitles are chosen on Style; here only what was chosen, and the way back there.
                val burn = tool?.burnSubtitles == true
                GlassFact(AfIcons.Regular.Subtitles, My.breakSyllables(tr(if (burn) UiText.DUB_BURN_ON else UiText.DUB_BURN_OFF)),
                    textModifier = Modifier.testTag("dub-export-burn"))
                GlassButton(My.breakSyllables(tr(UiText.DUB_GO_TO, "step" to tr(UiText.DUB_STEP_STYLE))), { go(DubStep.STYLE) },
                    Modifier.padding(start = 25.dp).testTag("dub-export-go-style"), icon = DubStep.STYLE.icon,
                    trailingIcon = AfIcons.Regular.ArrowRight, enabled = !s.busy)
                Divider()
                GlassSwitchRow(My.breakSyllables(tr(UiText.DUB_SAVE_WAV)), s.wav, { model.setWav(it) }, Modifier.testTag("dub-save-wav"),
                    icon = AfIcons.Regular.FileAudio, enabled = !s.busy)
                GlassFact(AfIcons.Regular.Info, My.breakSyllables(tr(UiText.DUB_EXPORT_KEEPS)), colour = AutoFlowColors.Muted, caption = true)
            }
            if (run != null && !wide) Progress(model, s, run.kind, run.state)
            // F-7 (D-96): this export's choices and script in a render package, to render it elsewhere.
            RenderPackageCard({
                val fit = model.fit(open)
                RenderPlan(mode = Mode.DUBBING, size = s.size.name, lines = fit.lines, length = fit.length.name, wav = s.wav,
                    docVersion = open?.project?.subtitles?.current?.value, doc = doc)
            }, tile = CardTile(AfIcons.Fill.Box, GlassColors.ExportTeal))
            s.notice?.let {
                val tone = if (s.ok) AutoFlowColors.Green else AutoFlowColors.Amber
                GlassCallout(if (s.ok) AfIcons.Fill.CheckCircle else AfIcons.Fill.Warn, My.breakSyllables(it.text(LocalUiLanguage.current)), tone,
                    textModifier = Modifier.testTag("dub-export-notice"))
            }
        } }
    Column(if (wide) Modifier.fillMaxSize() else Modifier.widthIn(max = 900.dp).fillMaxSize()) {
        if (wide) DubPanes({ left(Modifier.fillMaxSize()) }, {
            preview()
            if (run != null) Progress(model, s, run.kind, run.state)
        }, Modifier.weight(1f))
        else left(Modifier.fillMaxWidth().weight(1f))
        val blocked = check != null && !check.ok
        StickyActionBar(StudioAction(My.breakSyllables(tr(UiText.DUB_EXPORT_GO)), { model.start(doc) }, enabled = check?.ok == true && !s.busy,
            tag = "dub-export-go", icon = AfIcons.Regular.Export), glass = true,
            caption = if (blocked) My.breakSyllables(tr(UiText.V3_DUB_EXPORT_REASON)) else null,
            captionIcon = if (blocked) AfIcons.Regular.Warn else null, captionColour = if (blocked) AutoFlowColors.Amber else null,
            captionTag = if (blocked) "dub-export-reason" else null)
    }
}

/** The White .08 1 dp line between rows. */
@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = .08f)))

/**
 * The checklist card (sample 07 / 08 §1): list-checks tile Green when all three pass (Ready to export) or Amber (Before the
 * export:), the k / 3 pill, then one row per check: green check-circle, or amber warning with the reason and Go to.
 */
@Composable
private fun Checklist(check: DubExportCheck, spoken: Int, go: (DubStep) -> Unit) {
    val rows = listOf(
        CheckRow("approval", check.approved, UiText.V3_DUB_CHECK_APPROVED, tr(UiText.DUB_LOCK_NOT_APPROVED), DubStep.SCRIPT),
        CheckRow("voice", check.noVoice.isEmpty(), UiText.V3_DUB_CHECK_VOICED, tr(UiText.DUB_EXPORT_NO_VOICE, "n" to check.noVoice.size), DubStep.VOICE),
        CheckRow("long", check.tooLong.isEmpty(), UiText.V3_DUB_CHECK_FITS, tr(UiText.DUB_SHORTEN_N, "n" to check.tooLong.size), DubStep.VOICE),
    )
    val passed = rows.count { it.ok }
    val tone = if (check.ok) AutoFlowColors.Green else AutoFlowColors.Amber
    val card = @Composable {
        TileCard(My.breakSyllables(tr(if (check.ok) UiText.V3_DUB_READY else UiText.DUB_EXPORT_NOT_READY)), CardTile(AfIcons.Fill.Jobs, tone),
            Modifier.testTag("dub-export-checks"), action = {
                io.github.alalkipgen.autoflow.ui.shots.TonePill(tone, if (check.ok) AfIcons.Fill.CheckCircle else AfIcons.Fill.Warn, "$passed/${rows.size}")
            }) {
            rows.forEachIndexed { i, row ->
                if (i > 0) Divider()
                if (row.ok) Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AfIcon(AfIcons.Fill.CheckCircle, tint = AutoFlowColors.Green, size = 18.dp)
                    AfText(My.breakSyllables(if (row.key == "voice") tr(row.okText, "n" to spoken) else tr(row.okText)),
                        Modifier.weight(1f).testTag("dub-export-check-${row.key}"))
                } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AfIcon(AfIcons.Fill.Warn, tint = AutoFlowColors.Amber, size = 18.dp)
                        AfText(My.breakSyllables(row.why), Modifier.weight(1f).testTag("dub-export-block-${row.key}"), color = AutoFlowColors.Amber)
                    }
                    GlassButton(My.breakSyllables(tr(UiText.DUB_GO_TO, "step" to tr(row.to.text))), { go(row.to) },
                        Modifier.padding(start = 28.dp).testTag("dub-export-go-${row.key}"), icon = row.to.icon,
                        trailingIcon = AfIcons.Regular.ArrowRight)
                }
            }
        }
    }
    if (check.ok) card() else Box(Modifier.testTag("dub-export-blocks")) { card() }
}

private class CheckRow(val key: String, val ok: Boolean, val okText: UiText, val why: String, val to: DubStep)

/** One finished file: 17 dp Muted icon + its name in mono, Muted. */
@Composable
private fun FileFact(icon: ImageVector, text: String, tag: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AfIcon(icon, Modifier.padding(top = 3.dp), tint = AutoFlowColors.Muted, size = 17.dp)
        Box(Modifier.weight(1f).testTag(tag)) { MonoText(text, 12.5.sp, AutoFlowColors.Muted) }
    }
}

@Composable
private fun Progress(model: DubExportModel, s: DubExportUi, kind: String, state: RenderState) {
    val scope = rememberCoroutineScope()
    val k = s.runs.size
    val last = k == s.total
    when {
        state is RenderState.Done && last -> GlassCard(tint = AutoFlowColors.Green, tintAlpha = .12f, outline = AutoFlowColors.Green.copy(alpha = .45f),
            outlineWidth = 1.dp, gap = 10.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftTile(AfIcons.Fill.CheckCircle, AutoFlowColors.Green, size = 30.dp)
                AfText(My.breakSyllables(tr(UiText.DUB_EXPORT_DONE, "path" to state.output.substringAfterLast('/'))),
                    Modifier.weight(1f).padding(top = 4.dp).testTag("dub-export-done"), type = GlassSectionType, weight = FontWeight.Bold)
            }
            s.srt?.let { FileFact(AfIcons.Regular.Subtitles, tr(UiText.DUB_EXPORT_SRT, "path" to it.substringAfterLast('/')), "dub-export-srt") }
            s.voice?.let { FileFact(AfIcons.Regular.FileAudio, tr(UiText.DUB_EXPORT_WAV, "path" to it.substringAfterLast('/')), "dub-export-wav") }
            val title = tr(UiText.DUB_STEP_EXPORT)
            WrapRow {
                GlassButton(My.breakSyllables(tr(UiText.PLAY)), { model.open() }, Modifier.testTag("dub-export-play"), kind = ButtonKind.Primary,
                    icon = AfIcons.Fill.Play)
                if (model.sharesFiles) {
                    GlassButton(My.breakSyllables(tr(UiText.SHARE)), { model.share(title) }, Modifier.testTag("dub-export-share"), icon = AfIcons.Regular.Share)
                    GlassButton(My.breakSyllables(tr(UiText.SAVE_GALLERY)), { scope.launch { model.saveToGallery() } }, Modifier.testTag("dub-export-gallery"),
                        icon = AfIcons.Regular.DownloadSimple)
                } else GlassButton(My.breakSyllables(tr(UiText.OPEN_FOLDER)), { model.openFolder() }, Modifier.testTag("dub-export-folder"),
                    icon = AfIcons.Regular.FolderOpen)
            }
        }
        // §8.3: a cancelled export says Cancelled (the job state), not the Cancel button.
        state is RenderState.Failed || state is RenderState.Cancelled -> GlassCallout(AfIcons.Fill.XCircle, My.breakSyllables(tr(UiText.DUB_EXPORT_STOPPED, "k" to k,
            "detail" to ((state as? RenderState.Failed)?.reason?.let { "$it." } ?: tr(UiText.JOB_CANCELLED) + "."))), AutoFlowColors.Failed,
            textModifier = Modifier.testTag("dub-export-stopped"))
        else -> {
            val what = tr(if (kind == DubMixer.EXPORT) UiText.DUB_EXPORT_MAKING else UiText.DUB_EXPORT_MIXING)
            val fraction = (state as? RenderState.Running)?.fraction ?: if (state is RenderState.Done) 1.0 else 0.0
            JobCard(tr(UiText.DUB_STEP_EXPORT), if (state is RenderState.Queued) StudioStatus.QUEUED else StudioStatus.RUNNING,
                tr(UiText.DUB_EXPORT_STEP, "k" to k, "n" to s.total, "what" to what, "percent" to (fraction * 100).toInt()),
                Modifier.testTag("dub-export-job"), progress = fraction.toFloat(), indeterminate = state is RenderState.Queued,
                tile = CardTile(AfIcons.Fill.Export, AutoFlowColors.Purple)) {
                GlassButton(My.breakSyllables(tr(UiText.CANCEL)), model::cancel, Modifier.testTag("dub-export-cancel"), kind = ButtonKind.Danger,
                    icon = AfIcons.Regular.Close)
            }
        }
    }
}
