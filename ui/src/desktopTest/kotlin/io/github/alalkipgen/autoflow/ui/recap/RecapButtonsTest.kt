package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.recap.RecapScene
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.export.ExportHarness
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.*
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

/** The 21 labels whose leading glyph / emoji P9-11b replaced with a Phosphor icon. */
internal val ICON_LABELS = listOf(UiText.FIND_SCENES_AGAIN, UiText.TRANSCRIBE_SCENES, UiText.START_PLAY, UiText.SPEAK_BLOCK,
    UiText.CLEAN_TAKE, UiText.IMPORT_MOVIE, UiText.FIND_SCENES, UiText.WRITE_SCRIPT, UiText.WRITE_MYSELF, UiText.APPROVE_SCRIPT,
    UiText.SPEAK_ALL, UiText.RECORD, UiText.STOP, UiText.OUTPUT_CARD, UiText.BACKGROUND_CARD, UiText.WATERMARK_CARD,
    UiText.CAPTIONS_CARD, UiText.HIDE_THINGS_CARD, UiText.PAUSE, UiText.PLAY, UiText.CLOSE_PLAYER)

/** P9-11b: the catalog keeps no decorative glyph in front of these labels, in English or Burmese. */
class RecapButtonLabelsTest {
    @Test fun iconLabelsStartWithAWordInBothLanguages() {
        UiLanguage.entries.forEach { language ->
            ICON_LABELS.forEach { key ->
                val text = key.text(language)
                assertTrue(text.first().isLetterOrDigit(), "$key/${language.code} starts with a glyph: $text")
                assertEquals(text.trim(), text, "$key/${language.code}")
            }
        }
    }
}

/**
 * P9-11b: Recap buttons and card titles draw a Phosphor icon (DESIGN.md §9.3) instead of a text glyph: the label alone
 * in the button, one 18 dp icon before it inside a ≥ 48 dp target, an icon before each picture card's title, EN + MY,
 * strict text fit (360 dp × font 2.0 included) and no project file written.
 */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class RecapButtonsTest(val width: Int, val font: Float, val language: UiLanguage) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}px/{1}/{2}")
        fun cases() = listOf(arrayOf<Any>(360, 1f, UiLanguage.BURMESE), arrayOf<Any>(360, 2f, UiLanguage.BURMESE),
            arrayOf<Any>(412, 1f, UiLanguage.ENGLISH), arrayOf<Any>(412, 1.3f, UiLanguage.BURMESE), arrayOf<Any>(840, 1.3f, UiLanguage.BURMESE),
            arrayOf<Any>(1440, 1f, UiLanguage.ENGLISH), arrayOf<Any>(1440, 1f, UiLanguage.BURMESE))
        private val storyFixture by lazy {
            val f = QaRecapFixture.create(Files.createTempDirectory("recap-buttons").toFile())
            f.copy(project = (ProjectStore(JvmFs()).save(f.dir.path, f.project.copy(recap = f.project.recap!!.copy(
                scenes = listOf(RecapScene(2_000, 4_000, true), RecapScene(6_000, 8_000, true))))) as CoreResult.Ok).value)
        }
    }

    private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
    private fun Rect.holds(r: Rect) = r.left >= left - .5f && r.right <= right + .5f && r.top >= top - .5f && r.bottom <= bottom + .5f

    /** The button labelled [key]: exactly its catalog label, ≥ 48 × 48, one 18 dp icon inside it, left of the text. */
    private fun ComposeUiTest.iconButton(key: UiText, tag: String? = null) {
        val label = key.text(language)
        val button = onNode((tag?.let(::hasTestTag) ?: isButton) and hasText(label)).performScrollTo().assertIsDisplayed()
        val node = button.fetchSemanticsNode()
        assertEquals(listOf(label), node.config[SemanticsProperties.Text].map { it.text }, "label only, no glyph")
        val b = node.boundsInRoot
        assertTrue(b.width >= 48f && b.height >= 48f, "$key: 48 dp target $b")
        val text = onAllNodes(hasText(label), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }.single { b.holds(it) }
        val icons = onAllNodes(hasTestTag("button-icon"), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }.filter { b.holds(it) }
        assertEquals(1, icons.size, "$key: one icon inside the button")
        assertEquals(18f, icons.single().width, .5f)
        assertTrue(icons.single().right <= text.left, "$key: icon before the label")
    }

    /** The icon-only button [tag]: labelled [key] for TalkBack / tooltips, no text (so no glyph), ≥ 48 × 48. */
    private fun ComposeUiTest.iconOnly(key: UiText, tag: String) {
        val node = onNodeWithTag(tag).performScrollTo().assertIsDisplayed().fetchSemanticsNode()
        assertEquals(listOf(key.text(language)), node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }, "$key label")
        assertTrue(node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.isEmpty(), "$key: icon only, no glyph text")
        assertTrue(node.boundsInRoot.width >= 48f && node.boundsInRoot.height >= 48f, "$key: 48 dp target ${node.boundsInRoot}")
    }

    /**
     * The card titled [key] (inside the [card] tag when the word also names a tab): one [size] dp icon / soft tile ([tag]) right
     * before the title. Group 2 C 15–16: Studio's inner cards = a 26 dp `card-tile`, the Captions tile card = a 30 dp one.
     */
    private fun ComposeUiTest.iconCard(key: UiText, card: String? = null, tag: String = "card-tile", size: Float = 26f) {
        val title = onAllNodes(hasText(key.text(language)) and (card?.let { hasAnyAncestor(hasTestTag(it)) } ?: hasText(key.text(language))),
            useUnmergedTree = true)[0].performScrollTo().assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val icon = onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
            .filter { it.right <= title.left && title.left - it.right <= 12f && it.top < title.bottom && title.top < it.bottom }
        assertEquals(1, icon.size, "$key: one icon before the card title $title")
        assertEquals(size, icon.single().width, .5f)
    }

    @Composable private fun Recap(model: RecapModel, step: RecapStep) =
        CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
            LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) {
            StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat()) {
                StudioRecapScreen(model, step, width.toFloat(), {}, RecapNavigation({}, {}, {}))
            }
        }

    private fun ComposeUiTest.capture(name: String) {
        assertEquals(emptyList(), textFitProblems(width, font, strict = true))
        val dir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "recap-buttons").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name-$width-${language.code}-$font.png"))
    }

    /** Story options with picked scenes (the harness has none, so its writing buttons are hidden): Write script = spark, Write it myself = pencil. */
    @Test fun storyWritingButtonsShowAnIcon() = runDesktopComposeUiTest(width = width, height = if (width == 1440) 900 else 760) {
        fun bytes() = storyFixture.dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(storyFixture.dir).path to it.readBytes().toList() }
        val before = bytes()
        val fs = JvmFs(); val current = CurrentProject().apply { set(OpenProject(storyFixture.dir.path, storyFixture.project)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val model = RecapModel(ProjectSession(fs, ProjectStore(fs), ProjectIndex(fs,
            File(storyFixture.dir.parentFile, "index-buttons-$width-$font-${language.code}.json").path), current), fs, null, null, scope)
        try {
            setContent { Recap(model, RecapStep.STORY) }
            // The options section is closed while a story exists.
            if (onAllNodes(isButton and hasText(UiText.WRITE_SCRIPT.text(language))).fetchSemanticsNodes().isEmpty())
                onNodeWithContentDescription(My.breakSyllables(UiText.V3_STORY_OPTIONS.text(language))).performScrollTo().performMouseInput { click() }
            iconButton(UiText.WRITE_SCRIPT)
            iconButton(UiText.WRITE_MYSELF)
            capture("03-story-options")
            assertEquals(before, bytes(), "looking at the buttons must not change any project file")
        } finally { scope.cancel() }
    }

    @Test fun recapButtonsAndCardsShowAnIconInsteadOfAGlyph() = runDesktopComposeUiTest(width = width, height = if (width == 1440) 900 else 760) {
        ExportHarness().use { h ->
            val before = h.bytes()
            var step by mutableStateOf(RecapStep.SOURCE)
            setContent { Recap(h.recap, step) }

            // Source (no scenes yet): Find scenes = search icon.
            iconButton(UiText.FIND_SCENES, "source-scan")
            capture("02-source")

            // Style: the preview's Play (fill play) and Close (x), the exact-frame card and the picture cards.
            step = RecapStep.STYLE; waitForIdle()
            if (width >= 840) {
                iconButton(UiText.PLAY, "timeline-play")
                iconButton(UiText.CLOSE_PLAYER, "timeline-close")
                iconButton(UiText.EXACT_FRAME)
            } else {
                // Group 2 A §1: the phone top card's controls are labelled icon buttons; Close player leaves the phone card.
                iconOnly(UiText.PLAY, "timeline-play")
                iconOnly(UiText.EXACT_FRAME, "timeline-exact")
                iconOnly(UiText.PREVIEW_FULLSCREEN, "timeline-fullscreen")
                onNodeWithTag("timeline-close").assertDoesNotExist()
            }
            onNode(hasContentDescription(UiText.V3_MORE_OPTIONS.text(language)) and hasAnyAncestor(hasTestTag("style-layer-options")))
                .performScrollTo().performMouseInput { click() }
            listOf(UiText.OUTPUT_CARD, UiText.HIDE_THINGS_CARD, UiText.BACKGROUND_CARD, UiText.WATERMARK_CARD).forEach { iconCard(it) }
            capture("05-style-cards")
            onNodeWithTag("style-section-CAPTIONS").performScrollTo().performMouseInput { click() }
            iconCard(UiText.CAPTIONS_CARD, "caption-controls", size = 30f)
            assertEquals(before, h.bytes(), "looking at the buttons must not change any project file")
        }
    }
}
