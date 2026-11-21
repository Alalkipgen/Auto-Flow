package io.github.alalkipgen.autoflow.ui.export

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.media.render.Quality
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.recap.*
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/** UI-only step 5; the shared Export controls still call the same model/queue/compiler. */
@Composable
fun StudioExportScreen(model: ExportModel, recapModel: RecapModel, windowWidthDp: Float, navigation: RecapNavigation,
    onPreflightFix: ((RecapStep) -> Unit)? = null,
) {
    val state by model.state.collectAsState()
    val open by model.projects.collectAsState()
    val current = open != null && state.projectDir == open?.dir
    val wide = windowWidthDp >= 840f
    // F-5: the live preview (picture + voice + music) needs the Recap state of this project, before any render.
    LaunchedEffect(recapModel, open?.dir, open?.project?.updatedAt) { recapModel.refresh() }
    val phonePreview: (@Composable () -> Unit)? =
        if (wide) null else ({ RecapTimelinePreview(recapModel, studio = true) })
    key(open?.dir, open?.project?.id) {
        navigation.handleBack(true, navigation.back)
        Column(Modifier.fillMaxSize().background(AutoFlowColors.Background)) {
            ProjectTopBar(open?.project?.name.orEmpty(), 5, tr(UiText.EXPORT), navigation.back,
                steps = recapStepLabels(), options = listOf(StudioAction(tr(UiText.CLASSIC_RECAP), navigation.classic, tag = "classic-recap")))
            Box(Modifier.weight(1f)) {
                ProjectPanes(windowWidthDp, list = {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
                        AfText(tr(UiText.EXPORT), bold = true)
                        AfText(tr(UiText.SHELL_EXPORT_HINT), muted = true)
                    }
                }, editor = { ExportScreen(model, studio = true, onPreflightFix = onPreflightFix,
                    livePreview = phonePreview) },
                    preview = { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        RecapTimelinePreview(recapModel, studio = true)
                        ExactFrameCard(recapModel)
                    } })
            }
            StickyActionBar(
                StudioAction(state.recap?.let { tr(UiText.EXPORT_WITH_QUALITY, "quality" to it.quality.uiLabel()) }
                    ?: tr(UiText.EXPORT_BUTTON), { model.render(Quality.EXPORT) }, current && state.canRender, "export-render"),
                StudioAction(tr(UiText.PREVIEW_BUTTON), { model.render(Quality.PREVIEW) }, current && state.canRender, "export-preview"),
            )
        }
    }
}