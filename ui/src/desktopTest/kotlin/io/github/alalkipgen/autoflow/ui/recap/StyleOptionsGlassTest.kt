package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.recap.FitRow
import io.github.alalkipgen.autoflow.core.recap.FitStatus
import io.github.alalkipgen.autoflow.core.recap.WatermarkAnchor
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.export.ExportHarness
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.projects.*
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * Group 2 C 15 / 16 / 17 · 1–3, 8–9 (PR 9): Style · More options / Captions / Look / Audio in the glass look — the strings
 * lost their glyphs (the icons carry them), each group has its soft tile, the fit rows and watermark positions have their
 * icons, the burn captions line is a switch, the Brand Kit / caption style name forms open in place of their button and
 * close on Cancel and on Save to Library, every text fits on a 360 dp phone and looking writes nothing. D3: the Script
 * dialog's caption field shows the 1.5 dp cyan focus ring when focused (like the other glass fields).
 */
@OptIn(ExperimentalTestApi::class)
class StyleOptionsGlassTest {
    @Test fun stringsCarryNoGlyphsAndIconsCarryThem() {
        val glyphs = listOf("⚠", "💼", "🎵", "🖼", "✓", "↖", "↗", "↙", "↘", "⊕", "◎")
        for (language in UiLanguage.entries) for (key in listOf(UiText.FIT_FITS, UiText.FIT_STALE, UiText.SAVE_BRAND_KIT, UiText.ADD_MUSIC,
            UiText.ANCHOR_TL, UiText.ANCHOR_TR, UiText.ANCHOR_BL, UiText.ANCHOR_BR, UiText.ANCHOR_CENTER)) {
            val text = key.text(language)
            assertTrue(glyphs.none { it in text } && text.first().isLetterOrDigit() || text.startsWith("{"), "$key ${language.code}: '$text'")
        }
        assertEquals("Fits", UiText.FIT_FITS.text(UiLanguage.ENGLISH))
        assertEquals("ကိုက်တယ်", UiText.FIT_FITS.text(UiLanguage.BURMESE))
        assertEquals("Save as Brand Kit…", UiText.SAVE_BRAND_KIT.text(UiLanguage.ENGLISH))
        assertEquals(listOf(AfIcons.Regular.ArrowUpLeft, AfIcons.Regular.ArrowUpRight, AfIcons.Regular.ArrowDownLeft, AfIcons.Regular.ArrowDownRight,
            AfIcons.Regular.Crosshair), WatermarkAnchor.entries.map { it.icon() })
        fun row(status: FitStatus) = FitRow(0, 10_000, 9_000, 9_000, status)
        assertEquals(AfIcons.Regular.Check to AutoFlowColors.Green, row(FitStatus.FITS).statusIcon())
        assertEquals(AfIcons.Fill.Warn to AutoFlowColors.Amber, row(FitStatus.RUNS_ON).statusIcon())
        assertEquals(AfIcons.Fill.Warn to AutoFlowColors.Amber, row(FitStatus.STALE).statusIcon())
        assertEquals(AfIcons.Regular.Wave to AutoFlowColors.Muted, row(FitStatus.NO_VOICE).statusIcon())
    }

    @Test fun styleOptionsKeepTheirTagsShowTilesAndFormsAndWriteNothing() {
        for ((language, font) in listOf(UiLanguage.ENGLISH to 1f, UiLanguage.BURMESE to 2f)) runDesktopComposeUiTest(width = 360, height = 760) {
            ExportHarness().use { h ->
                val before = h.bytes()
                setContent { CompositionLocalProvider(LocalCompact provides true, LocalDensity provides Density(1f, font),
                    LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) {
                    StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, 360f) {
                        StudioRecapScreen(h.recap, RecapStep.STYLE, 360f, {}, RecapNavigation({}, {}, {}))
                    }
                } }
                fun click(tag: String) { onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performMouseInput { click() }; waitForIdle() }
                fun fits(state: String) = assertEquals(emptyList(), textFitProblems(360, font), "$state ${language.code}")
                fun label(key: UiText) = key.text(language).let { hasText(it) or hasText(My.breakSyllables(it)) }
                fun button(key: UiText) = onNode(label(key) and hasClickAction())
                fun tiles(tag: String) = onAllNodes(hasTestTag("card-tile") and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
                    .fetchSemanticsNodes().map { it.size.width.toFloat() }
                fun iconIn(key: UiText) = onAllNodes(hasTestTag("button-icon") and hasAnyAncestor(hasClickAction() and hasAnyDescendant(label(key))), useUnmergedTree = true)
                    .fetchSemanticsNodes().size

                // 15: More options — Output · Hide things · Background · Watermark · timing box = five 26 dp inner card tiles.
                click("style-section-LAYERS")
                onNode(hasContentDescription(UiText.V3_MORE_OPTIONS.text(language)) and hasAnyAncestor(hasTestTag("style-layer-options")))
                    .performScrollTo().performMouseInput { click() }
                waitForIdle()
                val inner = tiles("style-layer-options")
                assertTrue(inner.count { it == 26f } >= 5, "inner card tiles $inner")
                listOf(UiText.OUTPUT_CARD, UiText.HIDE_THINGS_CARD, UiText.BACKGROUND_CARD, UiText.WATERMARK_CARD).forEach {
                    onNode(label(it) and hasAnyAncestor(hasTestTag("style-layer-options"))).performScrollTo().assertIsDisplayed()
                }
                onNodeWithTag("mask-preview").assertExists()
                fits("more options")
                // Save as Brand Kit… (briefcase) → the name form in its place; Cancel brings the button back.
                assertEquals(1, iconIn(UiText.SAVE_BRAND_KIT))
                button(UiText.SAVE_BRAND_KIT).performScrollTo().performMouseInput { click() }; waitForIdle()
                onNode(hasSetTextAction() and hasContentDescription(UiText.BRAND_KIT_NAME.text(language))).performScrollTo().assertIsDisplayed()
                button(UiText.SAVE_BRAND_KIT).assertDoesNotExist()
                assertEquals(1, iconIn(UiText.SAVE_TO_LIBRARY))
                fits("brand kit form")
                button(UiText.CANCEL).performScrollTo().performMouseInput { click() }; waitForIdle()
                button(UiText.SAVE_BRAND_KIT).performScrollTo().assertIsDisplayed()
                // Save to Library goes to the Library (none in this harness: its notice) and closes the form.
                button(UiText.SAVE_BRAND_KIT).performMouseInput { click() }; waitForIdle()
                button(UiText.SAVE_TO_LIBRARY).performScrollTo().performMouseInput { click() }; waitForIdle()
                assertEquals(UiText.LIBRARY_UNAVAILABLE, h.recap.state.value.notice?.key)
                button(UiText.SAVE_BRAND_KIT).performScrollTo().assertIsDisplayed()

                // 16: Captions — the burn line is a switch (on), the Captions tile card (30 dp), Save caption style… form, SRT note.
                click("style-section-CAPTIONS")
                onNode(label(UiText.BURN_CAPTIONS) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertIsOn()
                assertEquals(listOf(30f), tiles("caption-controls").filter { it == 30f }.take(1))
                onNode(label(UiText.CAPTIONS_SRT_ALWAYS)).performScrollTo().assertIsDisplayed()
                assertEquals(1, iconIn(UiText.SAVE_CAPTION_PRESET))
                button(UiText.SAVE_CAPTION_PRESET).performScrollTo().performMouseInput { click() }; waitForIdle()
                onNode(hasSetTextAction() and hasContentDescription(UiText.CAPTION_PRESET_NAME.text(language))).performScrollTo().assertIsDisplayed()
                fits("captions + form")
                button(UiText.CANCEL).performScrollTo().performMouseInput { click() }; waitForIdle()
                button(UiText.SAVE_CAPTION_PRESET).performScrollTo().assertIsDisplayed()

                // 17 · 1: Look tile card + chips; 17 · 3: Audio tile card, Add music… (music-note), three switches.
                click("style-section-LOOK")
                assertEquals(listOf(30f), tiles("style-look"))
                onAllNodes(hasTestTagPrefix("style-look-")).fetchSemanticsNodes().let { assertTrue(it.size >= 4, "look chips ${it.size}") }
                fits("look")
                click("style-section-AUDIO")
                assertEquals(listOf(30f), tiles("style-audio"))
                assertEquals(1, iconIn(UiText.ADD_MUSIC))
                assertEquals(3, onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch) and hasAnyAncestor(hasTestTag("style-audio")))
                    .fetchSemanticsNodes().size)
                fits("audio")
                assertEquals(before, h.bytes(), "${language.code}: looking at the Style options writes nothing")
            }
        }
    }

    /** D3: the Script dialog's caption field (`block-script-input`) — no ring at rest, a 1.5 dp cyan ring while focused. */
    @Test fun scriptCaptionFieldShowsTheCyanFocusRing() = runDesktopComposeUiTest(width = 360, height = 760) {
        val fixture = QaRecapFixture.create(Files.createTempDirectory("g2c-focus").toFile())
        val fs = JvmFs(); val current = CurrentProject().apply { set(OpenProject(fixture.dir.path, fixture.project)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val model = RecapModel(ProjectSession(fs, ProjectStore(fs), ProjectIndex(fs, File(fixture.dir.parentFile, "index.json").path), current), fs, null, null, scope)
        try {
            setContent { CompositionLocalProvider(LocalCompact provides true, LocalDensity provides Density(1f, 1f),
                LocalUiFontFamily provides BurmeseTestFont.family) {
                StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, 360f) {
                    StudioRecapScreen(model, RecapStep.VOICE, 360f, {}, RecapNavigation({}, {}, {}))
                }
            } }
            onNodeWithTag("voice-open-0").performScrollTo().performMouseInput { click() }; waitForIdle()
            onNodeWithTag("block-script-edit").performScrollTo().performMouseInput { click() }; waitForIdle()
            /** The field's left edge, halfway down: its border pixels. */
            fun edge(tag: String) = onNodeWithTag(tag).captureToImage().toPixelMap().let { map -> (0..2).map { map[it, map.height / 2] } }
            fun cyan(tag: String) = edge(tag).any { it.red < .45f && it.green > .6f && it.blue > .7f }
            assertFalse(cyan("block-script-input"), "at rest: ${edge("block-script-input")}")
            onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("block-script-input"))).performSemanticsAction(SemanticsActions.RequestFocus)
            waitForIdle()
            assertTrue(cyan("block-script-input"), "focused: ${edge("block-script-input")}")
            assertFalse(cyan("block-spoken-input"))
            // The spoken field takes the same ring; the caption field loses it.
            onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("block-spoken-input"))).performSemanticsAction(SemanticsActions.RequestFocus)
            waitForIdle()
            assertTrue(cyan("block-spoken-input")); assertFalse(cyan("block-script-input"))
        } finally { scope.cancel() }
    }

    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }
}
