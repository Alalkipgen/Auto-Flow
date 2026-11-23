package io.github.alalkipgen.autoflow.ui.pack

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.CardTile
import io.github.alalkipgen.autoflow.ui.components.JobCard
import io.github.alalkipgen.autoflow.ui.components.StudioStatus
import io.github.alalkipgen.autoflow.ui.components.SectionCard
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/**
 * F-7 (D-96): "Render package" on every export screen — only the files the render needs, in one zip, to open in
 * Auto Flow elsewhere or to render on a Linux server (`AutoFlow --render <zip>`). [plan] = this screen's choices now,
 * for the open project.
 * Nothing is shown when the app has no [LocalRenderPackages].
 */
@Composable
fun RenderPackageCard(plan: () -> RenderPlan, modifier: Modifier = Modifier, tile: CardTile? = null) {
    val model = LocalRenderPackages.current?.takeIf { it.available } ?: return
    val all by model.state.collectAsState()
    val open by model.projects.collectAsState()
    val s = all.takeIf { it.dir == open?.dir } ?: RenderPackageUi()
    SectionCard(tr(UiText.PACKAGE_TITLE), modifier.testTag("render-package"), initiallyExpanded = false, tile = tile) {
        AfText(My.breakSyllables(tr(UiText.PACKAGE_HINT)), muted = true)
        if (s.busy) JobCard(tr(UiText.PACKAGE_TITLE), StudioStatus.RUNNING,
            My.breakSyllables(tr(UiText.PACKAGE_MAKING, "percent" to (s.progress * 100).toInt())), Modifier.testTag("render-package-busy"),
            progress = s.progress)
        else AfButton(My.breakSyllables(tr(UiText.PACKAGE_MAKE)), { model.pack(plan()) }, Modifier.testTag("render-package-make"))
        s.notice?.let { AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.testTag("render-package-notice"),
            color = if (s.ok) AutoFlowColors.Green else AutoFlowColors.Amber) }
        if (s.zip != null && !s.busy) WrapRow {
            val title = tr(UiText.PACKAGE_TITLE)
            if (model.sharesFiles) AfButton(My.breakSyllables(tr(UiText.SHARE)), { model.share(title) }, Modifier.testTag("render-package-share"))
            else AfButton(My.breakSyllables(tr(UiText.OPEN_FOLDER)), { model.openFolder() }, Modifier.testTag("render-package-folder"))
        }
    }
}
