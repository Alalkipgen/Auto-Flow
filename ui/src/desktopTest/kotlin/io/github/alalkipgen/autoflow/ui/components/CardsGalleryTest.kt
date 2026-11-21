package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.icons.IconTile
import io.github.alalkipgen.autoflow.ui.projects.label
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.AfRadius
import io.github.alalkipgen.autoflow.ui.theme.AfSize
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import io.github.alalkipgen.autoflow.ui.theme.Tone
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P9-4 gallery (DESIGN.md §9.4): cards, rows, section header, attention card, progress, empty state — EN + MY, font 1.0 / 2.0, phone + desktop. */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class CardsGalleryTest(val width: Int, val font: Float, val language: UiLanguage) {
    companion object {
        const val LONG = "Movie recap — a very long project name that cannot fit on one row of a phone screen at all"

        @JvmStatic @Parameterized.Parameters(name = "{0}dp/{1}/{2}")
        fun cases() = listOf(360 to 1f, 360 to 2f, 1440 to 1f, 1440 to 2f).flatMap { (w, f) ->
            UiLanguage.entries.map { arrayOf<Any>(w, f, it) }
        }
    }

    @Composable private fun Tile(mode: Mode) =
        IconTile(mode.icon(duotone = true), Tone.Primary.container, size = AfSize.TileSmall, radius = AfRadius.TileSmall, ink = Tone.Primary.text)

    @Composable private fun Gallery() = Box(Modifier.fillMaxSize().background(AutoFlowColors.Background).padding(14.dp)) { Column(
        Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(tr(UiText.HOME_RECENT)) { AfButton(tr(UiText.OPEN), {}, kind = ButtonKind.Text, small = true) }
        AfCard {
            ListRow(tr(Mode.MOVIE_RECAP.label()), subtitle = tr(UiText.EXIT_HINT), onClick = {}, leading = { Tile(Mode.MOVIE_RECAP) },
                trailing = { RowChevron() })
            ListRow(LONG, Modifier.testTag("long"), subtitle = tr(UiText.HOME_QUICK_HINT), selected = true, onClick = {},
                leading = { Tile(Mode.SUBTITLES) }, trailing = { StatusChip(StudioStatus.DONE) })
            ListRow(tr(Mode.VOICE_AUDIO.label()), leading = { Tile(Mode.VOICE_AUDIO) })
        }
        AfCard(title = tr(UiText.V3_JOBS_NEEDS_YOU)) {
            TableRow(onClick = {}) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    WrapRow { ModeChip(Mode.DUBBING); StatusChip(StudioStatus.RUNNING) }
                    AfProgress(.4f, Modifier.testTag("determinate"))
                }
            }
            TableRow(selected = true) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    WrapRow { ModeChip(Mode.MUSIC_VIDEO); StatusChip(StudioStatus.QUEUED) }
                    AfProgress(null, Modifier.testTag("indeterminate"))
                }
            }
        }
        AttentionCard(tr(UiText.JOB_FAILED), tr(UiText.EXIT_HINT), tone = Tone.Error, icon = AfIcons.Fill.XCircle) {
            AfButton(tr(UiText.OPEN), {}, kind = ButtonKind.Secondary, small = true)
            AfButton(tr(UiText.RETRY), {}, small = true, icon = AfIcons.Regular.Redo)
        }
        AttentionCard(tr(UiText.JOB_PAUSED), tr(UiText.HOME_QUICK_HINT), tone = Tone.Warning, icon = AfIcons.Fill.Pause)
        AfCard(onClick = {}) {
            GroupLabel(tr(UiText.V3_JOBS_NEEDS_YOU), onBackground = false)
            AfText(tr(UiText.HOME_EMPTY), type = io.github.alalkipgen.autoflow.ui.theme.AfType.Caption)
        }
        EmptyState(AfIcons.Duotone.Folder, tr(UiText.HOME_RECENT), tr(UiText.HOME_EMPTY), action = StudioAction(tr(UiText.V3_PROJECT_CREATE), {}))
    } }

    @Test fun cardsAndRowsFitAndKeep48dpTargets() = runDesktopComposeUiTest(width = width, height = if (width >= 600) (if (font > 1f) 2400 else 1400) else if (font > 1f) 3400 else 1900) {
        setContent {
            CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) { Gallery() }
        }
        // Long titles end in an ellipsis but keep the full text for screen readers and tooltips (D-100).
        assertEquals(emptyList(), textFitProblems(width, font, strict = true))
        onNodeWithContentDescription(LONG, useUnmergedTree = true).assertExists()
        onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach {
            assertTrue(it.size.width >= 48 && it.size.height >= 48, "48 dp target at $width/$font/$language: ${it.size}")
        }
        onNodeWithTag("long", useUnmergedTree = true).assertIsSelected()
        // Determinate only with a real value; no value = indeterminate, never a fake percentage.
        onNodeWithTag("determinate", useUnmergedTree = true).assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(.4f, 0f..1f)))
        onNodeWithTag("indeterminate", useUnmergedTree = true).assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
        val dir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "gallery").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "cards-$width-$font-${language.name.lowercase()}.png"))
        System.getenv("AUTOFLOW_PR_SHOTS_DIR")?.let { if (language == UiLanguage.BURMESE)
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(File(it).apply { mkdirs() }, "cards-${if (width < 600) "phone-$font" else "desktop-$font"}.png")) }
    }
}
