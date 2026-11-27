package io.github.alalkipgen.autoflow.ui.library

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.library.*
import io.github.alalkipgen.autoflow.core.recap.LexiconEntry
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Dest
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Shell
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.*

/**
 * P9-16 Library (Exact UI v4): filter chips with counts (a chip shows only its section), glass list rows (swatch /
 * soft tile, one-line title, caption, trailing action that moves under the text when it would squeeze it), the
 * lexicon card, kind labels and Planned chips for presets — with strict no-clipping at phone and desktop widths, font
 * 1.0 / 1.3 / 2.0, English and Burmese.
 */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class LibraryListTest(val width: Int, val font: Float, val language: UiLanguage) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}px/{1}/{2}")
        fun cases() = listOf(412).flatMap { w -> listOf(1f, 1.3f, 2f).flatMap { f -> UiLanguage.entries.map { l -> arrayOf<Any>(w, f, l) } } } +
            listOf(arrayOf<Any>(360, 2f, UiLanguage.BURMESE), arrayOf<Any>(360, 1f, UiLanguage.BURMESE),
                arrayOf<Any>(760, 1f, UiLanguage.BURMESE), arrayOf<Any>(1440, 2f, UiLanguage.ENGLISH))
    }

    @Test fun chipsFilterSectionsAndEveryRowFitsWithoutClipping() = runDesktopComposeUiTest(width = width, height = 760) {
        LibraryTestFixture().use { f ->
            setContent {
                CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                    LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) {
                    StudioV2Shell(StudioV2Dest.LIBRARY, {}, 0, width.toFloat(), ambient = true) { LibraryScreen(f.model) }
                }
            }
            fun t(key: UiText, vararg args: Pair<String, Any?>) = key.text(language, *args)
            fun inRow(tag: String, matcher: SemanticsMatcher) = onNode(matcher and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
            fun chip(key: UiText) = onNode(hasText(t(key)) and hasAnyAncestor(hasTestTag("library-categories")))
            // Header: the title and the copy hint; the old jump line is gone.
            onNodeWithTag("library-heading").assertTextEquals(t(UiText.LIBRARY))
            onNode(hasText(My.breakSyllables(t(UiText.LIBRARY_COPY_HINT, "APPLY" to t(UiText.APPLY)))) and hasAnyAncestor(hasTestTag("library-header")))
                .assertIsDisplayed()
            onNodeWithText(t(UiText.V3_LIBRARY_JUMP)).assertDoesNotExist()
            // "All" + six chips with their counts (1 kit, 1 voice, 1 lexicon entry, 1 music, 1 font, 3 presets = 8).
            chip(UiText.PROJECTS_FILTER_ALL).assert(hasText("8"))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "selected"))
            listOf(UiText.V3_LIBRARY_KITS to 1, UiText.V3_LIBRARY_VOICES to 1, UiText.V3_LIBRARY_LEXICON to 1, UiText.V3_LIBRARY_MUSIC to 1,
                UiText.V3_LIBRARY_FONTS to 1, UiText.V3_LIBRARY_PRESETS to 3).forEach { (key, n) -> chip(key).assert(hasText("$n")) }
            // Brand kit: colour swatch (its colours as the description), "Caption style: saved · Watermark: <file>", Apply.
            inRow("library-kit-kit", hasTestTag("library-kit-swatch"))
                .assertContentDescriptionEquals(t(UiText.LIBRARY_COLOURS, "joinToString" to "#FFFF00"))
            inRow("library-kit-kit", hasText(My.breakSyllables(t(UiText.LIBRARY_CAPTION_SAVED) + " · " +
                t(UiText.LIBRARY_WATERMARK, "name" to "qa-watermark.png")))).assertExists()
            onNodeWithText(t(UiText.LIBRARY_COLOURS, "joinToString" to "#FFFF00")).assertDoesNotExist()
            // Voice: provider · model · voice · lang. Music: the duration as m:ss. Presets: kind labels, Apply or Planned.
            inRow("library-voice-voice", hasText("qa · offline-fixture · fixture · my")).assertExists()
            inRow("library-music-music", hasText("0:01")).assertExists()
            inRow("library-preset-caption", hasText(My.breakSyllables(t(UiText.V3_STYLE_CAPTIONS)))).assertExists()
            inRow("library-preset-style", hasText(My.breakSyllables(t(UiText.STYLE)))).assertExists()
            inRow("library-preset-export", hasText(My.breakSyllables(t(UiText.EXPORT)))).assertExists()
            inRow("library-preset-style", hasTestTag("status-PLANNED")).assertExists()
            inRow("library-preset-export", hasTestTag("status-PLANNED")).assertExists()
            onNodeWithTag("library-apply-preset-style").assertDoesNotExist()
            onNodeWithTag("library-apply-preset-caption").assertExists()
            onNodeWithTag("library-preset-unsupported").assertExists()
            // Lexicon: written → spoken in the lexicon card.
            inRow("library-lexicon-0", hasText("ရေးပုံ")).assertExists()
            inRow("library-lexicon-0", hasText(My.breakSyllables("အသံထွက်"))).assertExists()
            // Every row: at least 52 dp; its trailing action inside the row and beside or under the title, never over it.
            val rows = listOf(Triple("library-kit-kit", "library-apply-kit-kit", f.kit.name),
                Triple("library-voice-voice", "library-apply-voice-voice", f.voice.name),
                Triple("library-music-music", "library-copy-music-music", "QA tone"),
                Triple("library-font-font", "library-apply-font-font", "Pyidaungsu QA"),
                Triple("library-preset-caption", "library-apply-preset-caption", "QA caption"))
            fun bounds(m: SemanticsNodeInteraction) = m.fetchSemanticsNode().let { Rect(it.positionInRoot,
                androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat())) }
            rows.forEach { (row, action, name) ->
                val r = bounds(onNodeWithTag(row))
                assertTrue(r.height >= 52f * 1f - .5f, "$row height ${r.height}")
                val a = bounds(onNodeWithTag(action))
                assertTrue(a.left >= r.left - .5f && a.right <= r.right + .5f && a.top >= r.top - .5f && a.bottom <= r.bottom + .5f,
                    "$action inside $row: $a in $r")
                val title = bounds(inRow(row, hasText(name)))
                assertTrue(a.left >= title.right - .5f || a.top >= title.bottom - .5f, "$action beside or under its title: $a / $title")
            }
            assertEquals(emptyList(), textFitProblems(width, font, strict = true), "all sections")
            // All: every section is labelled with its chip's name (a heading), without "(n)" — the chips have the counts.
            val tags = LibrarySection.entries.map { it.tag }
            LibrarySection.entries.forEach { section ->
                onNode(hasText(My.breakSyllables(t(section.chip))) and isHeading() and hasAnyAncestor(hasTestTag(section.tag))).assertExists()
            }
            onNodeWithText(My.breakSyllables(t(UiText.LIBRARY_KITS, "size" to 1))).assertDoesNotExist()
            // A chip shows only its section, without a drawn label (the selected chip names it) but as a TalkBack heading.
            listOf(UiText.V3_LIBRARY_KITS, UiText.V3_LIBRARY_VOICES, UiText.V3_LIBRARY_LEXICON, UiText.V3_LIBRARY_MUSIC,
                UiText.V3_LIBRARY_FONTS, UiText.V3_LIBRARY_PRESETS).forEachIndexed { i, key ->
                chip(key).performScrollTo().performMouseInput { moveTo(center); click(); exit() }
                tags.forEachIndexed { j, tag -> if (i == j) onNodeWithTag(tag).assertExists() else onNodeWithTag(tag).assertDoesNotExist() }
                onNodeWithTag(tags[i]).assert(isHeading()).assertContentDescriptionEquals(t(key))
                onNode(hasText(My.breakSyllables(t(key))) and hasAnyAncestor(hasTestTag(tags[i]))).assertDoesNotExist()
                assertEquals(emptyList(), textFitProblems(width, font, strict = true), key.name)
            }
            chip(UiText.PROJECTS_FILTER_ALL).performScrollTo().performMouseInput { moveTo(center); click(); exit() }
            tags.forEach { onNodeWithTag(it).assertExists() }
        }
    }
}

@OptIn(ExperimentalTestApi::class)
class LibraryEdgeCasesTest {
    private fun model(library: Library): Pair<LibraryModel, CoroutineScope> {
        val fs = InMemoryFileSystem()
        val store = LibraryStore(fs, "/h/library").apply { save(library) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = ProjectSession(fs, ProjectStore(fs), ProjectIndex(fs, "/h/index.json"), CurrentProject())
        return LibraryModel(store, session, scope, fs) to scope
    }

    @Test fun lexiconShowsTenThenMoreKitWithoutColoursUnknownDurationAndNoUnsupportedHint() = runDesktopComposeUiTest(width = 360, height = 760) {
        val lexicon = (1..12).map { LexiconEntry("w$it", "spoken $it") }
        val (model, scope) = model(Library(brandKits = listOf(BrandKit("plain", "Plain kit")), lexicon = lexicon,
            music = listOf(MusicItem("m", "Untimed", "files/m.wav")), presets = listOf(Preset("c", "Caption only", PresetKind.CAPTION))))
        setContent { CompositionLocalProvider(LocalCompact provides true, LocalUiFontFamily provides BurmeseTestFont.family) { LibraryScreen(model) } }
        // First 10 entries, then "… 2 more".
        (0 until 10).forEach { onNodeWithTag("library-lexicon-$it").assertExists() }
        onNodeWithTag("library-lexicon-10").assertDoesNotExist()
        onNodeWithTag("library-lexicon-more").assertTextEquals(UiText.LIBRARY_MORE.text(UiLanguage.ENGLISH, "size10" to 2))
        // A kit without colours shows the swatches tile and no subtitle; a music item of unknown length has no duration.
        onNode(hasTestTag("library-kit-swatch") and hasAnyAncestor(hasTestTag("library-kit-plain")), useUnmergedTree = true).assertDoesNotExist()
        onNode(hasText("0:00") and hasAnyAncestor(hasTestTag("library-music-m")), useUnmergedTree = true).assertDoesNotExist()
        // Only caption presets: no Planned chip and no unsupported hint.
        onNodeWithTag("status-PLANNED").assertDoesNotExist()
        onNodeWithTag("library-preset-unsupported").assertDoesNotExist()
        assertEquals(emptyList(), textFitProblems(360, 1f, strict = true))
        scope.cancel()
    }

    private val sections = listOf("kits", "voices", "lexicon", "music", "fonts", "presets")
    private val empties = listOf(UiText.LIBRARY_KITS_EMPTY, UiText.LIBRARY_VOICES_EMPTY, UiText.LIBRARY_LEXICON_EMPTY, UiText.LIBRARY_MUSIC_EMPTY,
        UiText.LIBRARY_FONTS_EMPTY, UiText.LIBRARY_PRESETS_EMPTY)
    private val hints = listOf(UiText.V3_LIBRARY_KIT_SCOPE, UiText.LIBRARY_LEXICON_HINT, UiText.V3_LIBRARY_MUSIC_COPY_HINT, UiText.V3_LIBRARY_FONT_COPY_HINT)
    private val phones = listOf(1f, 2f).flatMap { f -> UiLanguage.entries.map { f to it } }
    private fun has(text: String) = hasText(text) or hasText(My.breakSyllables(text))
    /** Unclipped bounds (the node may be scrolled out of the viewport). */
    private fun ComposeUiTest.bounds(matcher: SemanticsMatcher) = onNode(matcher).fetchSemanticsNode().let {
        Rect(it.positionInRoot, androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat()))
    }.also { assertTrue(it.height > 0f, "empty bounds") }
    private fun ComposeUiTest.show(model: LibraryModel, font: Float, language: UiLanguage, focusLexicon: Boolean = false) = setContent {
        CompositionLocalProvider(LocalCompact provides true, LocalDensity provides Density(1f, font), LocalUiLanguage provides language,
            LocalUiFontFamily provides BurmeseTestFont.family) { LibraryScreen(model, focusLexicon = focusLexicon) }
    }

    /**
     * Library follow-up README §1: an empty Library — no chips, no copy hint, no section hints; all six sections with
     * their labels, each one small empty row; Brand Kits: label → Save → empty row ("the button above"); Presets: label
     * → empty row → Save. Font 1.0 / 2.0 × English / Burmese at 360 dp, nothing clipped.
     */
    @Test fun emptyLibraryHasNoChipsNoHintsAndSixLabelledEmptyRows() = phones.forEach { (font, language) ->
        runDesktopComposeUiTest(width = 360, height = 760) {
            val (model, scope) = model(Library())
            show(model, font, language)
            fun t(key: UiText) = key.text(language, "APPLY" to UiText.APPLY.text(language))
            onNodeWithTag("library-heading").assertIsDisplayed()
            onNodeWithTag("library-categories").assertDoesNotExist()
            onNode(has(t(UiText.LIBRARY_COPY_HINT))).assertDoesNotExist()
            hints.forEach { onNode(has(t(it))).assertDoesNotExist() }
            LibrarySection.entries.forEachIndexed { i, section ->
                onNode(has(t(section.chip)) and isHeading() and hasAnyAncestor(hasTestTag(section.tag))).performScrollTo().assertIsDisplayed()
                onNode(has(t(empties[i])) and hasAnyAncestor(hasTestTag("library-empty-${sections[i]}"))).performScrollTo().assertIsDisplayed()
                onNodeWithTag("library-empty-${sections[i]}").assertHeightIsAtLeast(48.dp)
            }
            onNodeWithText(My.breakSyllables(UiText.LIBRARY_KITS.text(language, "size" to 0))).assertDoesNotExist()
            onNodeWithTag("library-preset-unsupported").assertDoesNotExist()
            val kitLabel = bounds(has(t(UiText.V3_LIBRARY_KITS)) and isHeading())
            val kitSave = bounds(has(t(UiText.LIBRARY_SAVE_KIT)))
            val kitEmpty = bounds(hasTestTag("library-empty-kits"))
            assertTrue(kitLabel.bottom <= kitSave.top + .5f && kitSave.bottom <= kitEmpty.top + .5f, "kits: $kitLabel / $kitSave / $kitEmpty")
            val presetLabel = bounds(has(t(UiText.V3_LIBRARY_PRESETS)) and isHeading())
            val presetEmpty = bounds(hasTestTag("library-empty-presets"))
            val presetSave = bounds(has(t(UiText.LIBRARY_SAVE_PRESET)))
            assertTrue(presetLabel.bottom <= presetEmpty.top + .5f && presetEmpty.bottom <= presetSave.top + .5f,
                "presets: $presetLabel / $presetEmpty / $presetSave")
            assertEquals(emptyList(), textFitProblems(360, font, strict = true), "$font ${language.code}")
            scope.cancel()
        }
    }

    /** One empty section among filled ones: only its label and empty row (no hint), its chip shows 0; the others keep their hints. */
    @Test fun oneEmptySectionAmongFilledShowsItsLabelAndEmptyRowOnly() = phones.forEach { (font, language) ->
        runDesktopComposeUiTest(width = 360, height = 760) {
            val (model, scope) = model(Library(brandKits = listOf(BrandKit("k", "QA kit", colours = listOf("#FF0000"))),
                voices = listOf(SavedVoice("v", "Voice", VoiceLock("p", "m", "v", "my"))), lexicon = listOf(LexiconEntry("AI", "အေအိုင်")),
                fonts = listOf(FontItem("f", "QA font", "files/f.ttf")), presets = listOf(Preset("c", "QA caption", PresetKind.CAPTION))))
            show(model, font, language)
            fun t(key: UiText) = key.text(language, "APPLY" to UiText.APPLY.text(language))
            fun chip(key: UiText) = onNode(hasText(t(key)) and hasAnyAncestor(hasTestTag("library-categories")))
            chip(UiText.PROJECTS_FILTER_ALL).assert(hasText("5"))
            chip(UiText.V3_LIBRARY_MUSIC).assert(hasText("0"))
            onNode(has(t(UiText.V3_LIBRARY_MUSIC)) and isHeading()).performScrollTo().assertIsDisplayed()
            onNode(has(t(UiText.LIBRARY_MUSIC_EMPTY)) and hasAnyAncestor(hasTestTag("library-empty-music"))).performScrollTo().assertIsDisplayed()
            onNode(has(t(UiText.V3_LIBRARY_MUSIC_COPY_HINT))).assertDoesNotExist()
            listOf("kits", "voices", "lexicon", "fonts", "presets").forEach { onNodeWithTag("library-empty-$it").assertDoesNotExist() }
            listOf(UiText.V3_LIBRARY_KIT_SCOPE, UiText.LIBRARY_LEXICON_HINT, UiText.V3_LIBRARY_FONT_COPY_HINT).forEach { onNode(has(t(it))).assertExists() }
            onNode(has(t(UiText.LIBRARY_COPY_HINT))).assertExists()
            assertEquals(emptyList(), textFitProblems(360, font, strict = true), "$font ${language.code}")
            // The Music chip: no label, the empty row; a TalkBack heading names the section.
            onNodeWithTag("library-categories").performScrollTo()
            chip(UiText.V3_LIBRARY_MUSIC).performScrollTo().performMouseInput { moveTo(center); click(); exit() }
            onNodeWithTag("library-music").assert(isHeading()).assertContentDescriptionEquals(t(UiText.V3_LIBRARY_MUSIC))
            onNode(has(t(UiText.V3_LIBRARY_MUSIC)) and hasAnyAncestor(hasTestTag("library-music"))).assertDoesNotExist()
            onNodeWithTag("library-empty-music").assertIsDisplayed()
            assertEquals(emptyList(), textFitProblems(360, font, strict = true), "music chip $font ${language.code}")
            scope.cancel()
        }
    }

    /** Settings → Pronunciation on an empty Library: every section, no chips, and the Lexicon brought into view. */
    @Test fun focusLexiconOnAnEmptyLibraryShowsAllAndBringsLexiconIntoView() = runDesktopComposeUiTest(width = 360, height = 480) {
        val (model, scope) = model(Library())
        show(model, 2f, UiLanguage.BURMESE, focusLexicon = true)
        waitForIdle()
        onNodeWithTag("library-categories").assertDoesNotExist()
        LibrarySection.entries.forEach { onNodeWithTag(it.tag).assertExists() }
        onNodeWithTag("library-empty-lexicon").assertIsDisplayed()
        onNodeWithTag("library-heading").assertIsNotDisplayed()
        scope.cancel()
    }

    /** Saving the first Brand Kit from an empty Library opened on the Lexicon shows All (the new kit stays in view). */
    @Test fun savingTheFirstKitFromAnEmptyLibraryShowsAll() = runDesktopComposeUiTest(width = 360, height = 760) {
        LibraryTestFixture().use { f ->
            f.libraryStore.save(Library()); f.model.refresh()
            show(f.model, 1f, UiLanguage.ENGLISH, focusLexicon = true)
            onNodeWithText(UiText.LIBRARY_SAVE_KIT.en).performScrollTo().performMouseInput { moveTo(center); click(); exit() }
            onNodeWithContentDescription(UiText.LIBRARY_KIT_NAME.en).performScrollTo().performTextReplacement("QA first kit")
            onNodeWithText(UiText.SAVE.en).performScrollTo().performMouseInput { moveTo(center); click(); exit() }
            assertEquals("selected", onNode(hasText(UiText.PROJECTS_FILTER_ALL.en) and hasAnyAncestor(hasTestTag("library-categories")))
                .fetchSemanticsNode().config[SemanticsProperties.StateDescription])
            LibrarySection.entries.forEach { onNodeWithTag(it.tag).assertExists() }
            onNodeWithText("QA first kit").assertExists()
        }
    }

    /** The written column: 84 dp at font 1.0; with larger text only as wide as the widest written form (at most 84 × scale). */
    @Test fun lexiconWrittenColumnFitsItsTextAtLargeFonts() = listOf(
        Triple(1f, listOf("AI", "km"), 84f..84f), Triple(2f, listOf("AI", "km"), 84f..84f),
        Triple(2f, listOf("AI", "Interstellar"), 84.5f..167.5f), Triple(2f, listOf("AI", "a very long written form"), 168f..168f),
    ).forEach { (font, written, range) ->
        runDesktopComposeUiTest(width = 360, height = 760) {
            val (model, scope) = model(Library(lexicon = written.map { LexiconEntry(it, "ကီလိုမီတာ") }))
            setContent {
                CompositionLocalProvider(LocalCompact provides true, LocalDensity provides Density(1f, font),
                    LocalUiFontFamily provides BurmeseTestFont.family) { LibraryScreen(model, focusLexicon = true) }
            }
            val widths = written.indices.map { i ->
                onNode(hasContentDescription(written[i]) and hasAnyAncestor(hasTestTag("library-lexicon-$i")), useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot.width
            }
            assertEquals(1, widths.distinct().size, "one column: $widths")
            assertTrue(widths[0] in range, "font $font $written: ${widths[0]} in $range")
            scope.cancel()
        }
    }

    @Test fun focusLexiconSelectsTheLexiconChipAndShowsOnlyThatSection() = runDesktopComposeUiTest(width = 360, height = 760) {
        val (model, scope) = model(Library(voices = listOf(SavedVoice("v", "Voice", VoiceLock("p", "m", "v", "my"))),
            lexicon = listOf(LexiconEntry("AI", "အေအိုင်"))))
        setContent {
            CompositionLocalProvider(LocalCompact provides true, LocalUiLanguage provides UiLanguage.BURMESE,
                LocalUiFontFamily provides BurmeseTestFont.family) { LibraryScreen(model, focusLexicon = true) }
        }
        onNodeWithTag("library-lexicon").assertIsDisplayed()
        onNodeWithTag("library-voices").assertDoesNotExist()
        assertEquals("selected", onNode(hasText(UiText.V3_LIBRARY_LEXICON.my!!) and hasAnyAncestor(hasTestTag("library-categories")))
            .fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        scope.cancel()
    }
}
