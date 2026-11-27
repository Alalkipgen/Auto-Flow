package io.github.alalkipgen.autoflow.ui.dubbing

import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.model.DubOriginal
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.ui.pack.RenderPackageCard
import io.github.alalkipgen.autoflow.ui.pack.RenderPlan
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.media.audio.DubMixer
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.subtitles.BurnFitChips
import io.github.alalkipgen.autoflow.ui.subtitles.BurnSizeChips
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import kotlinx.coroutines.launch

/**
 * The Export step (D-3b): what still blocks it with Go to, the original sound chosen on Voice, Burmese subtitles and
 * the `.wav` options, then the steps of the mix with progress / Cancel and the finished files with Play · Share ·
 * Save to Gallery (phone) or Open folder (desktop).
 */
@Composable
internal fun ExportStep(model: DubExportModel, open: OpenProject?, doc: SubtitleDoc, go: (DubStep) -> Unit,
    preview: @Composable () -> Unit = {}) {
    val all by model.state.collectAsState()
    val runs by model.runs.collectAsState()
    val s = all.takeIf { it.dir == open?.dir } ?: DubExportUi()
    val check = remember(open?.project, doc) { model.check(open, doc) }
    val run = remember(runs, s.runs) { model.current() }
    val tool = open?.project?.dubbing
    Column(Modifier.widthIn(max = 900.dp).fillMaxSize()) {
        Column(Modifier.fillMaxWidth().weight(1f).paneScroll(rememberScrollState()).padding(screenPadding).testTag("dub-export"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            preview() // F-6: scrolls with the step
            if (check != null && !check.ok) AfCard(My.breakSyllables(tr(UiText.DUB_EXPORT_NOT_READY)), Modifier.testTag("dub-export-blocks")) {
                if (!check.approved) Block(tr(UiText.DUB_LOCK_NOT_APPROVED), "approval", DubStep.SCRIPT, go)
                if (check.noVoice.isNotEmpty()) Block(tr(UiText.DUB_EXPORT_NO_VOICE, "n" to check.noVoice.size), "voice", DubStep.VOICE, go)
                if (check.tooLong.isNotEmpty()) Block(tr(UiText.DUB_SHORTEN_N, "n" to check.tooLong.size), "long", DubStep.VOICE, go)
            }
            AfCard(My.breakSyllables(tr(UiText.DUB_STEP_EXPORT)), Modifier.testTag("dub-export-options")) {
                val mute = tool?.original?.mode == DubOriginal.MUTE
                AfText(My.breakSyllables(tr(UiText.DUB_ORIGINAL) + ": " + tr(if (mute) UiText.DUB_MUTE else UiText.DUB_LOWER)), Modifier.testTag("dub-export-original"))
                WrapRow {
                    val burn = tool?.burnSubtitles == true
                    AfChip(My.breakSyllables(tr(UiText.DUB_BURN)), burn, { model.setBurn(!burn) }, Modifier.testTag("dub-burn"), enabled = !s.busy)
                    AfChip(My.breakSyllables(tr(UiText.DUB_SAVE_WAV)), s.wav, { model.setWav(!s.wav) }, Modifier.testTag("dub-save-wav"), enabled = !s.busy)
                }
                AfText(My.breakSyllables(tr(UiText.DUB_EXPORT_KEEPS)), muted = true)
                if (tool?.burnSubtitles == true) {
                    BurnSizeChips(s.size, model::setSize, "dub-burn-size", enabled = !s.busy)
                    BurnFitChips(model.fit(open), model::setFit, "dub-burn", enabled = !s.busy)
                }
            }
            if (run != null) Progress(model, s, run.kind, run.state)
            // F-7 (D-96): this export's choices and script in a render package, to render it elsewhere.
            RenderPackageCard({
                val fit = model.fit(open)
                RenderPlan(mode = Mode.DUBBING, size = s.size.name, lines = fit.lines, length = fit.length.name, wav = s.wav,
                    docVersion = open?.project?.subtitles?.current?.value, doc = doc)
            })
            s.notice?.let { AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.testTag("dub-export-notice"),
                color = if (s.ok) AutoFlowColors.Green else AutoFlowColors.Amber) }
        }
        StickyActionBar(StudioAction(My.breakSyllables(tr(UiText.DUB_EXPORT_GO)), { model.start(doc) }, enabled = check?.ok == true && !s.busy,
            tag = "dub-export-go"))
    }
}

@Composable
private fun Block(why: String, tag: String, to: DubStep, go: (DubStep) -> Unit) {
    AfText(My.breakSyllables(why), Modifier.testTag("dub-export-block-$tag"), color = AutoFlowColors.Amber)
    AfButton(My.breakSyllables(tr(UiText.DUB_GO_TO, "step" to tr(to.text))), { go(to) }, Modifier.testTag("dub-export-go-$tag"))
}

@Composable
private fun Progress(model: DubExportModel, s: DubExportUi, kind: String, state: RenderState) {
    val scope = rememberCoroutineScope()
    val k = s.runs.size
    val last = k == s.total
    when {
        state is RenderState.Done && last -> AfCard(My.breakSyllables(tr(UiText.DUB_EXPORT_DONE, "path" to state.output.substringAfterLast('/'))),
            Modifier.testTag("dub-export-done")) {
            s.srt?.let { AfText(My.breakSyllables(tr(UiText.DUB_EXPORT_SRT, "path" to it.substringAfterLast('/'))), Modifier.testTag("dub-export-srt"), muted = true) }
            s.voice?.let { AfText(My.breakSyllables(tr(UiText.DUB_EXPORT_WAV, "path" to it.substringAfterLast('/'))), Modifier.testTag("dub-export-wav"), muted = true) }
            val title = tr(UiText.DUB_STEP_EXPORT)
            WrapRow {
                AfButton(My.breakSyllables(tr(UiText.PLAY)), { model.open() }, Modifier.testTag("dub-export-play"), icon = AfIcons.Fill.Play)
                if (model.sharesFiles) {
                    AfButton(My.breakSyllables(tr(UiText.SHARE)), { model.share(title) }, Modifier.testTag("dub-export-share"))
                    AfButton(My.breakSyllables(tr(UiText.SAVE_GALLERY)), { scope.launch { model.saveToGallery() } }, Modifier.testTag("dub-export-gallery"))
                } else AfButton(My.breakSyllables(tr(UiText.OPEN_FOLDER)), { model.openFolder() }, Modifier.testTag("dub-export-folder"))
            }
        }
        state is RenderState.Failed || state is RenderState.Cancelled -> AfText(My.breakSyllables(tr(UiText.DUB_EXPORT_STOPPED, "k" to k,
            "detail" to ((state as? RenderState.Failed)?.reason?.let { "$it." } ?: tr(UiText.CANCEL) + "."))),
            Modifier.testTag("dub-export-stopped"), color = AutoFlowColors.Failed)
        else -> {
            val what = tr(if (kind == DubMixer.EXPORT) UiText.DUB_EXPORT_MAKING else UiText.DUB_EXPORT_MIXING)
            val fraction = (state as? RenderState.Running)?.fraction ?: if (state is RenderState.Done) 1.0 else 0.0
            JobCard(tr(UiText.DUB_STEP_EXPORT), if (state is RenderState.Queued) StudioStatus.QUEUED else StudioStatus.RUNNING,
                My.breakSyllables(tr(UiText.DUB_EXPORT_STEP, "k" to k, "n" to s.total, "what" to what, "percent" to (fraction * 100).toInt())),
                Modifier.testTag("dub-export-job"), progress = fraction.toFloat(), indeterminate = state is RenderState.Queued) {
                AfButton(My.breakSyllables(tr(UiText.CANCEL)), model::cancel, Modifier.testTag("dub-export-cancel"))
            }
        }
    }
}
