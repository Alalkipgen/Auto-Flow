package io.github.alalkipgen.autoflow.ui.pack

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.GlassButton
import io.github.alalkipgen.autoflow.ui.components.GlassCallout
import io.github.alalkipgen.autoflow.ui.components.GlassFact
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
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
    RenderPackageContent(s, model.sharesFiles, { model.pack(plan()) }, { model.share(it) }, { model.openFolder() }, modifier, tile)
}

/**
 * The Render package card in state [s]: on a phone ([sharesFiles]) Share, on a computer Open folder. [tile] = the glass
 * card of the Movie Recap Export step (Group 2 D, 23 · 5–8): the hint as a Muted info row, a small Make button
 * (`package`), the packing job as the glass job card, the result as a green / amber callout (a long path wraps at its
 * separators), small Share (`share-network`) / Open folder (`folder-simple`).
 */
@Composable
internal fun RenderPackageContent(s: RenderPackageUi, sharesFiles: Boolean, onPack: () -> Unit, onShare: (String) -> Unit,
    onOpenFolder: () -> Unit, modifier: Modifier = Modifier, tile: CardTile? = null, initiallyExpanded: Boolean = false) {
    SectionCard(tr(UiText.PACKAGE_TITLE), modifier.testTag("render-package"), initiallyExpanded = initiallyExpanded, tile = tile) {
        val title = tr(UiText.PACKAGE_TITLE)
        if (tile != null) {
            GlassFact(AfIcons.Regular.Info, My.breakSyllables(tr(UiText.PACKAGE_HINT)), colour = AutoFlowColors.Muted, caption = true)
            if (s.busy) JobCard(title, StudioStatus.RUNNING, tr(UiText.PACKAGE_MAKING, "percent" to (s.progress * 100).toInt()),
                Modifier.testTag("render-package-busy"), progress = s.progress, tile = tile)
            else GlassButton(My.breakSyllables(tr(UiText.PACKAGE_MAKE)), onPack, Modifier.testTag("render-package-make"), icon = AfIcons.Regular.Box, small = true)
            s.notice?.let {
                val text = My.breakSyllables(it.text(LocalUiLanguage.current)).replace("/", "/\u200B").replace("\\", "\\\u200B")
                if (s.ok) GlassCallout(AfIcons.Fill.CheckCircle, text, AutoFlowColors.Green, textModifier = Modifier.testTag("render-package-notice"))
                else GlassCallout(AfIcons.Fill.Warn, text, AutoFlowColors.Amber, textModifier = Modifier.testTag("render-package-notice"))
            }
            if (s.zip != null && !s.busy) {
                if (sharesFiles) GlassButton(My.breakSyllables(tr(UiText.SHARE)), { onShare(title) }, Modifier.testTag("render-package-share"),
                    icon = AfIcons.Regular.Share, small = true)
                else GlassButton(My.breakSyllables(tr(UiText.OPEN_FOLDER)), onOpenFolder, Modifier.testTag("render-package-folder"),
                    icon = AfIcons.Regular.Folder, small = true)
            }
            return@SectionCard
        }
        AfText(My.breakSyllables(tr(UiText.PACKAGE_HINT)), muted = true)
        if (s.busy) JobCard(title, StudioStatus.RUNNING,
            My.breakSyllables(tr(UiText.PACKAGE_MAKING, "percent" to (s.progress * 100).toInt())), Modifier.testTag("render-package-busy"),
            progress = s.progress)
        else AfButton(My.breakSyllables(tr(UiText.PACKAGE_MAKE)), onPack, Modifier.testTag("render-package-make"))
        s.notice?.let { AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.testTag("render-package-notice"),
            color = if (s.ok) AutoFlowColors.Green else AutoFlowColors.Amber) }
        if (s.zip != null && !s.busy) WrapRow {
            if (sharesFiles) AfButton(My.breakSyllables(tr(UiText.SHARE)), { onShare(title) }, Modifier.testTag("render-package-share"))
            else AfButton(My.breakSyllables(tr(UiText.OPEN_FOLDER)), onOpenFolder, Modifier.testTag("render-package-folder"))
        }
    }
}
