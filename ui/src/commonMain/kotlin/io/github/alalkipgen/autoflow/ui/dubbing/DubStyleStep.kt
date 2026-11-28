package io.github.alalkipgen.autoflow.ui.dubbing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.subtitles.BurnFitChips
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.GlassColors

/**
 * The Style step (D-105): how the dubbed video looks, between Voice and Export as in Movie Recap. The Captions card holds
 * the Burmese subtitles in the picture: the switch, then (D-106) their caption style — look, colour, outline, box — and
 * size, lines and line length, and Save caption style… (the `.srt` is always saved too). Blur and the watermark join
 * this step later (specs/dubbing.md §2). Phone: the preview scrolls with the options; desktop (from 840 dp): the options
 * on the left, the preview on the right. The sticky bar is Next: Export with Previous step (Voice).
 */
@Composable
internal fun StyleStep(model: DubExportModel, open: OpenProject?, go: (DubStep) -> Unit, wide: Boolean = false,
    preview: @Composable () -> Unit = {}) {
    val all by model.state.collectAsState()
    val s = all.takeIf { it.dir == open?.dir } ?: DubExportUi()
    val burn = open?.project?.dubbing?.burnSubtitles == true
    val style = model.style(open)
    val idle = !s.busy
    val options = @Composable { modifier: Modifier -> Column(modifier.paneScroll(rememberScrollState()).padding(screenPadding).testTag("dub-style"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!wide) preview() // F-6: scrolls with the step
            TileCard(My.breakSyllables(tr(UiText.V3_STYLE_CAPTIONS)), CardTile(AfIcons.Fill.Subtitles, GlassColors.RecapSubtitles),
                Modifier.testTag("dub-style-captions")) {
                GlassSwitchRow(My.breakSyllables(tr(UiText.DUB_BURN)), burn, { model.setBurn(it) }, Modifier.testTag("dub-burn"),
                    icon = AfIcons.Regular.Subtitles, enabled = idle)
                if (burn) {
                    DubCaptionLook(model, style, idle, open.dir)
                    InsetOptions(Modifier.testTag("dub-caption-fit")) {
                        DubCaptionSize(model, style, idle, open.dir)
                        BurnFitChips(model.fit(open), model::setFit, "dub-burn", enabled = idle, glass = true)
                    }
                    DubSaveCaptionStyle(model, style, idle, open.dir, s.styleNotice, s.styleOk)
                }
                GlassFact(AfIcons.Regular.FileText, My.breakSyllables(tr(UiText.CAPTIONS_SRT_ALWAYS)), colour = AutoFlowColors.Muted, caption = true)
            }
        } }
    Column(if (wide) Modifier.fillMaxSize() else Modifier.widthIn(max = 900.dp).fillMaxSize()) {
        if (wide) DubPanes({ options(Modifier.fillMaxSize()) }, { preview() }, Modifier.weight(1f))
        else options(Modifier.fillMaxWidth().weight(1f))
        StickyActionBar(StudioAction(My.breakSyllables(tr(UiText.NEXT_STEP, "step" to tr(UiText.DUB_STEP_EXPORT))), { go(DubStep.EXPORT) },
                tag = "dub-next-EXPORT", trailingIcon = AfIcons.Regular.ArrowRight),
            StudioAction(My.breakSyllables(tr(UiText.PREVIOUS_STEP)), { go(DubStep.VOICE) }, tag = "dub-back-VOICE", icon = AfIcons.Regular.Back),
            glass = true)
    }
}
