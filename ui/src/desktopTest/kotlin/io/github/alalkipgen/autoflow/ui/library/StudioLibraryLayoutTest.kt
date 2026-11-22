package io.github.alalkipgen.autoflow.ui.library

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.recap.StudioVoiceLayoutTest
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class StudioLibraryLayoutTest(val width: Int, val font: Float, val language: UiLanguage, val bundled: Boolean) {
    companion object { @JvmStatic @Parameterized.Parameters(name = "{0}px/{1}/{2}/bundled={3}") fun cases() = StudioVoiceLayoutTest.cases() }
    @Test fun sectionFiltersDraftsVisibleActionsConfirmCancelCopyAndLocaleNoticesRetainProjectBytes() =
        runDesktopComposeUiTest(width = width, height = if (width == 1440) 900 else 760) {
            LibraryTestFixture().use { f ->
                val before = LibraryTestFixture.bytes(File(f.a.dir)); val bBefore = LibraryTestFixture.bytes(File(f.b.dir))
                val libraryBefore = LibraryTestFixture.bytes(f.libraryDir)
                var locale by mutableStateOf(language)
                setContent {
                    CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                        LocalUiLanguage provides locale, LocalUiFontFamily provides if (bundled) BurmeseTestFont.family else FontFamily.Default) {
                        StudioV2Shell(StudioV2Dest.LIBRARY, {}, 0, width.toFloat()) { LibraryScreen(f.model) }
                    }
                }
                fun click(tag: String) = onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                    .performMouseInput { moveTo(center); click(); exit() }
                fun text(key: UiText) = onNodeWithText(key.text(locale)).performScrollTo().assertIsDisplayed()
                fun clickText(key: UiText) = text(key).performMouseInput { moveTo(center); click(); exit() }
                // performScrollTo scrolls only the nearest scroller (the chip row): bring the row into view first.
                fun chip(key: UiText) = onNodeWithTag("library-categories").performScrollTo().let {
                    onNode(hasText(key.text(locale)) and hasAnyAncestor(hasTestTag("library-categories")))
                        .performScrollTo().assertIsDisplayed().performMouseInput { moveTo(center); click(); exit() }
                }
                val tags = listOf("library-kits", "library-voices", "library-lexicon", "library-music", "library-fonts", "library-presets")
                val images = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "library").apply { mkdirs() }
                fun capture(name: String) {
                    assertEquals(emptyList(), textFitProblems(width, font))
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(images, "$name-$width-${language.code}-$font-$bundled.png"))
                }
                onNodeWithTag("library-heading").assertIsDisplayed(); capture("library-top")
                listOf(UiText.V3_LIBRARY_KITS, UiText.V3_LIBRARY_VOICES, UiText.V3_LIBRARY_LEXICON, UiText.V3_LIBRARY_MUSIC,
                    UiText.V3_LIBRARY_FONTS, UiText.V3_LIBRARY_PRESETS).forEachIndexed { i, key ->
                    // A chip shows only its section — without a drawn label (the chip names it), as a TalkBack heading.
                    chip(key)
                    onNodeWithTag(tags[i]).assert(isHeading()).assertContentDescriptionEquals(key.text(locale))
                    onNode(hasText(My.breakSyllables(key.text(locale))) and hasAnyAncestor(hasTestTag(tags[i]))).assertDoesNotExist()
                    tags.forEachIndexed { j, tag -> if (j == i) onNodeWithTag(tag).assertExists() else onNodeWithTag(tag).assertDoesNotExist() }
                    assertEquals(emptyList(), textFitProblems(width, font), key.name)
                }
                chip(UiText.PROJECTS_FILTER_ALL)
                tags.forEach { onNodeWithTag(it).assertExists() }
                // All: each section's label = the chip's name, without "(n)".
                listOf(UiText.V3_LIBRARY_KITS, UiText.V3_LIBRARY_PRESETS).forEach { key ->
                    onNode(hasText(My.breakSyllables(key.text(locale))) and isHeading()).performScrollTo().assertIsDisplayed()
                }
                onNodeWithText(My.breakSyllables(UiText.LIBRARY_PRESETS.text(locale, "size" to 3))).assertDoesNotExist()
                // Style / export presets: a Planned chip instead of a disabled Apply.
                onNodeWithTag("library-apply-preset-style").assertDoesNotExist()
                onNodeWithTag("library-apply-preset-export").assertDoesNotExist()
                listOf("style", "export").forEach { id ->
                    onNode(hasTestTag("status-PLANNED") and hasAnyAncestor(hasTestTag("library-preset-$id"))).performScrollTo().assertIsDisplayed()
                }
                onNodeWithTag("library-apply-preset-caption").performScrollTo().assertIsDisplayed().assertIsEnabled()
                onNodeWithTag("library-apply-voice-voice").performScrollTo().assertIsDisplayed().assertIsEnabled()
                clickText(UiText.LIBRARY_SAVE_KIT)
                val name = UiText.LIBRARY_KIT_NAME.text(locale)
                onNodeWithContentDescription(name).performScrollTo().performTextReplacement("")
                onNodeWithContentDescription(name).assertExists()
                text(UiText.SAVE).assertIsNotEnabled()
                onNodeWithContentDescription(name).performScrollTo().performTextInput("QA retained draft")
                // Showing another section and then all of them keeps the draft.
                chip(UiText.V3_LIBRARY_VOICES); onNodeWithContentDescription(name).assertDoesNotExist(); chip(UiText.PROJECTS_FILTER_ALL)
                onNodeWithContentDescription(name).performScrollTo().assertTextContains("QA retained draft")
                clickText(UiText.CANCEL)
                val card = onNodeWithTag("library-kit-kit")
                onNodeWithText(UiText.LIBRARY_DELETE_HOLD.text(locale)).assertDoesNotExist()
                onNodeWithTag("library-delete-kit-kit").assertDoesNotExist()
                card.performScrollTo().assertIsDisplayed().performMouseInput { rightClick() }
                click("library-kit-kit-remove-menu")
                onNodeWithText(My.breakSyllables(UiText.LIBRARY_DELETE_KIT.text(locale, "name" to f.kit.name))).assertIsDisplayed()
                click("library-kit-kit-remove-cancel")
                onNodeWithText(f.kit.name).performScrollTo().assertIsDisplayed().performTouchInput { longClick() }
                click("library-kit-kit-remove-menu")
                onNodeWithTag("library-kit-kit-remove-confirm").assertIsDisplayed().assertIsEnabled()
                click("library-kit-kit-remove-cancel")
                clickText(UiText.LIBRARY_SAVE_PRESET)
                onNodeWithContentDescription(UiText.LIBRARY_PRESET_NAME.text(locale)).performScrollTo().assertIsDisplayed()
                    .performTextReplacement("QA caption draft")
                clickText(UiText.CANCEL)
                assertEquals(before, LibraryTestFixture.bytes(File(f.a.dir)), "browse/jump/drafts/delete-cancel write nothing")
                assertEquals(libraryBefore, LibraryTestFixture.bytes(f.libraryDir))
                click("library-copy-music-music")
                onNodeWithTag("library-notice").performScrollTo().assertIsDisplayed()
                    .assertTextEquals(My.breakSyllables(UiText.V3_LIBRARY_COPIED.text(locale, "name" to "QA tone")))
                capture("library-copy-notice")
                locale = if (locale == UiLanguage.ENGLISH) UiLanguage.BURMESE else UiLanguage.ENGLISH
                waitForIdle()
                onNodeWithTag("library-notice").performScrollTo().assertIsDisplayed()
                    .assertTextEquals(My.breakSyllables(UiText.V3_LIBRARY_COPIED.text(locale, "name" to "QA tone")))
                click("library-apply-font-font")
                assertTrue(File(f.a.dir, "source/qa-tone.wav").isFile); assertTrue(File(f.a.dir, "library/qa-font.ttf").isFile)
                val after = LibraryTestFixture.bytes(File(f.a.dir))
                assertEquals(before, after.filterKeys { it !in setOf("source/qa-tone.wav", "library/qa-font.ttf") }, "copy-only action must not save project/artifacts")
                assertEquals(bBefore, LibraryTestFixture.bytes(File(f.b.dir)))
            }
        }
}


@OptIn(ExperimentalTestApi::class)
class LibraryActionsPointerTest {
    @Test fun realDisplayedSaveApplyDeleteAndReopenUseExistingLibraryOperations() = runDesktopComposeUiTest(width = 412, height = 760) {
        LibraryTestFixture().use { f ->
            val other = LibraryTestFixture.bytes(File(f.b.dir))
            setContent { CompositionLocalProvider(LocalCompact provides true, LocalUiFontFamily provides BurmeseTestFont.family) { LibraryScreen(f.model) } }
            fun click(tag: String) = onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performMouseInput { moveTo(center); click(); exit() }
            fun text(key: UiText) = onNodeWithText(key.en).performScrollTo().assertIsDisplayed().performMouseInput { moveTo(center); click(); exit() }
            click("library-apply-kit-kit")
            assertEquals(f.style, f.saved(f.a).recap!!.captionStyle)
            click("library-apply-voice-voice"); assertEquals(f.voice.lock, f.saved(f.a).recap!!.voiceLock)
            click("library-apply-preset-caption"); assertEquals(io.github.alalkipgen.autoflow.core.recap.CaptionSize.S, f.saved(f.a).recap!!.captionStyle.size)
            text(UiText.LIBRARY_SAVE_KIT)
            onNodeWithContentDescription(UiText.LIBRARY_KIT_NAME.en).performScrollTo().performTextReplacement("QA pointer kit")
            text(UiText.SAVE); assertTrue(f.libraryStore.load().brandKits.any { it.name == "QA pointer kit" })
            text(UiText.LIBRARY_SAVE_PRESET)
            onNodeWithContentDescription(UiText.LIBRARY_PRESET_NAME.en).performScrollTo().performTextReplacement("QA pointer caption")
            text(UiText.SAVE); assertTrue(f.libraryStore.load().presets.any { it.name == "QA pointer caption" })
            // Step 1 = the kit (its 44 dp swatch, name, "Brand Kits"); a tap outside closes it and deletes nothing.
            onNodeWithText(f.kit.name).performScrollTo().performTouchInput { longClick() }
            onNodeWithTag("glass-dialog-title").assertTextEquals(f.kit.name)
            onNodeWithTag("glass-dialog-subtitle").assertTextEquals(UiText.V3_LIBRARY_KITS.en)
            onNode(hasTestTag("library-kit-swatch") and hasAnyAncestor(hasTestTag("glass-dialog-lead"))).assertWidthIsEqualTo(44.dp)
            onAllNodes(isRoot())[1].performMouseInput { click(androidx.compose.ui.geometry.Offset(5f, 5f)) }
            onNodeWithTag("glass-dialog").assertDoesNotExist()
            assertTrue(f.libraryStore.load().brandKits.any { it.id == "kit" })
            onNodeWithText(f.kit.name).performScrollTo().performTouchInput { longClick() }
            click("library-kit-kit-remove-menu")
            click("library-kit-kit-remove-confirm")
            assertFalse(f.libraryStore.load().brandKits.any { it.id == "kit" })
            assertEquals(other, LibraryTestFixture.bytes(File(f.b.dir)))
        }
    }

    @Test fun duplicateNamesDeleteByIdAndReopenKeepsAppliedStyle() = runDesktopComposeUiTest(width = 412, height = 760) {
        LibraryTestFixture().use { f ->
            val first = f.library.presets.first()
            f.libraryStore.save(f.library.copy(presets = listOf(first, first.copy(id = "same-name"))))
            f.model.refresh()
            setContent { LibraryScreen(f.model) }
            val card = onNodeWithTag("library-preset-caption").performScrollTo()
            onAllNodesWithText(first.name).onFirst().performScrollTo().performTouchInput { click() }
            assertEquals(io.github.alalkipgen.autoflow.core.recap.CaptionSize.S, f.saved(f.a).recap!!.captionStyle.size)
            val before = LibraryTestFixture.bytes(File(f.a.dir))
            val bBefore = LibraryTestFixture.bytes(File(f.b.dir))
            val action = card.fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
            runOnIdle { assertTrue(action.action()) }
            fun click(tag: String) = onNodeWithTag(tag).assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("glass-dialog-title").assertTextEquals(first.name)
            onNodeWithTag("glass-dialog-subtitle").assertTextEquals(UiText.V3_LIBRARY_PRESETS.en)
            click("library-preset-caption-remove-menu")
            click("library-preset-caption-remove-confirm")
            onNodeWithTag("library-preset-caption").assertDoesNotExist()
            onNodeWithTag("library-preset-same-name").assertExists()
            val reopened = io.github.alalkipgen.autoflow.core.library.LibraryStore(f.fs, f.libraryDir.path)
            assertEquals(listOf("same-name"), reopened.load().presets.map { it.id })
            assertEquals(before, LibraryTestFixture.bytes(File(f.a.dir)))
            assertEquals(bBefore, LibraryTestFixture.bytes(File(f.b.dir)))
        }
    }
}


@OptIn(ExperimentalTestApi::class)
class LibraryReducedViewportTest {
    @Test fun largeFontEmptyKitDraftAndActionsRemainReachableInReducedViewport() = runDesktopComposeUiTest(width = 360, height = 440) {
        LibraryTestFixture().use { f ->
            val before = LibraryTestFixture.bytes(File(f.a.dir))
            setContent { CompositionLocalProvider(LocalCompact provides true, LocalDensity provides Density(1f, 2f),
                LocalUiLanguage provides UiLanguage.BURMESE, LocalUiFontFamily provides BurmeseTestFont.family) { LibraryScreen(f.model) } }
            onNodeWithText(UiText.LIBRARY_SAVE_KIT.my!!).performScrollTo().assertIsDisplayed().performMouseInput { moveTo(center); click(); exit() }
            val field = onNodeWithContentDescription(UiText.LIBRARY_KIT_NAME.my!!).performScrollTo().assertIsDisplayed()
            field.performTextReplacement(""); field.assertExists()
            onNodeWithText(UiText.SAVE.my!!).performScrollTo().assertIsDisplayed().assertIsNotEnabled()
            field.performScrollTo().performTextInput("ပိုင်ရှင် စမ်းသပ်မှု")
            onNodeWithText(UiText.SAVE.my!!).performScrollTo().assertIsDisplayed().assertIsEnabled()
            onNodeWithText(UiText.CANCEL.my!!).performScrollTo().assertIsDisplayed().performMouseInput { moveTo(center); click(); exit() }
            assertEquals(before, LibraryTestFixture.bytes(File(f.a.dir)))
        }
    }
}
