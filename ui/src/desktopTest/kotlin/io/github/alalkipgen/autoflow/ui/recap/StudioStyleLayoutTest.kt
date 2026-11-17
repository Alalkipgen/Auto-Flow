package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.*
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.*
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.export.ExportHarness
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.syllableCuts
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.test.*

private class StyleBack : NavigationEventInput() { fun press() = dispatchOnBackCompleted() }

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.reachStyle(tag: String): SemanticsNodeInteraction {
    val node = onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    val bounds = node.getUnclippedBoundsInRoot()
    val bar = onNodeWithTag("sticky-action-bar").getUnclippedBoundsInRoot()
    assertTrue(bounds.top.value >= 0 && bounds.bottom <= bar.top, "$tag must be fully uncovered: $bounds, bar=$bar")
    assertTrue((bounds.bottom - bounds.top).value >= 48f, "$tag needs >=48dp")
    return node
}

/** Exact rendered catalog text (no U+200B is added by AfText/AfButton): scrolled into view, displayed, no mid-syllable line break. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.reachText(text: String) {
    onNodeWithText(text).performScrollTo().assertIsDisplayed()
    val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
    onNode(hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
    assertEquals(emptyList(), syllableCuts(layouts.single()), "line break inside a Burmese syllable: $text")
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.styleCapture(name: String, width: Int, font: Float) {
    assertEquals(emptyList(), textFitProblems(width, font))
    val next = onNodeWithTag("recap-next").assertIsDisplayed().getUnclippedBoundsInRoot()
    val bar = onNodeWithTag("sticky-action-bar").getUnclippedBoundsInRoot()
    assertTrue(next.top >= bar.top && next.bottom <= bar.bottom && (next.bottom - next.top).value >= 48f)
    val dir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "style").apply { mkdirs() }
    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
}

/** Native fontconfig v7 sidecars made by the existing Linux ffmpeg, not arbitrary .cache files. */
private fun nativeFontconfigSidecar(path: String, bytes: ByteArray): Boolean {
    if (path == ".cache/fontconfig/CACHEDIR.TAG")
        return bytes.decodeToString().startsWith("Signature: 8a477f597d28d172789f06886806bc55\n")
    val name = Regex("\\.cache/fontconfig/(?:[0-9a-f]{32}|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})-le64\\.cache-7")
    if (!name.matches(path) || bytes.size < 16) return false
    val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return header.int == 0xfc02fc04.toInt() && header.int == 7 && header.long == bytes.size.toLong()
}

@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class StudioStyleLayoutTest(val width: Int, val font: Float, val language: UiLanguage, val bundled: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}px/{1}/{2}/bundled={3}")
        fun cases() = StudioStoryLayoutTest.cases()
    }
    @Test fun wrappingGroupsExactValuesDraftBackAndClassicUseVisibleControlsWithoutWriting() =
        runDesktopComposeUiTest(width = width, height = if (width == 1440) 900 else 760) {
            ExportHarness().use { h ->
                val before = h.bytes(); var back = 0; var next = 0; var classic = 0
                val platformBack = StyleBack()
                setContent { CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                    LocalUiLanguage provides language, LocalUiFontFamily provides if (bundled) BurmeseTestFont.family else FontFamily.Default) {
                    val dispatcher = requireNotNull(LocalNavigationEventDispatcherOwner.current).navigationEventDispatcher
                    DisposableEffect(dispatcher) { dispatcher.addInput(platformBack); onDispose { dispatcher.removeInput(platformBack) } }
                    StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat()) {
                        StudioRecapScreen(h.recap, RecapStep.STYLE, width.toFloat(), { next++ }, RecapNavigation({ back++ }, {}, { classic++ },
                            handleBack = { enabled, action -> NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None),
                                isBackEnabled = enabled, onBackCompleted = action) }))
                    }
                } }
                assertProjectPanesAndStickyActions(width, "recap-next")
                fun click(tag: String) = reachStyle(tag).assertIsEnabled().performMouseInput { click() }
                fun expand(tag: String) = onNode(hasContentDescription(UiText.V3_MORE_OPTIONS.text(language)) and
                    hasAnyAncestor(hasTestTag(tag))).performScrollTo().assertIsDisplayed().performMouseInput { click() }
                val selected = bundled && width == 412 && font in listOf(1f, 2f) || width in listOf(360, 1440) ||
                    !bundled && language == UiLanguage.BURMESE && font == 2f && width in listOf(412, 840)
                fun capture(state: String) { if (selected) styleCapture("05-style-$state-$width-${language.code}-$font-$bundled", width, font) }
                repeat(5) { onNodeWithTag("progress-segment-$it").assertExists() }
                onNodeWithTag("project-progress").assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
                    androidx.compose.ui.semantics.ProgressBarRangeInfo(4f, 0f..5f, 4)))
                onNodeWithTag("recap-next").assertIsDisplayed().assertIsEnabled()
                reachStyle("timeline-play")
                reachStyle("layer-edit-qa-blur"); capture("layers")
                expand("style-layer-options")
                reachStyle("style-ratio-R16_9")
                onNodeWithText(UiText.SET_WATERMARK.text(language)).performScrollTo().assertIsDisplayed()
                onNodeWithText(UiText.SAVE_BRAND_KIT.text(language)).performScrollTo().assertIsDisplayed()
                click("style-section-CAPTIONS")
                reachStyle("caption-size-M"); capture("captions")
                onNodeWithText(UiText.SAVE_CAPTION_PRESET.text(language)).performScrollTo().assertIsDisplayed()
                reachText(UiText.CAPTIONS_SRT_ALWAYS.text(language))
                click("style-section-LOOK"); reachStyle("style-look-MIRROR")
                if (width == 412 && bundled && (language == UiLanguage.ENGLISH && font == 1f || language == UiLanguage.BURMESE && font == 2f)) capture("look")
                click("style-section-AUDIO")
                reachText(UiText.ADD_MUSIC.text(language))
                if (width == 412 && bundled && (language == UiLanguage.ENGLISH && font == 1f || language == UiLanguage.BURMESE && font == 2f)) capture("audio")
                click("style-section-LAYERS"); click("layer-edit-qa-blur")
                onNodeWithTag("recap-next").assertIsNotEnabled()
                onNodeWithTag("style-section-CAPTIONS").assertIsNotEnabled()
                reachStyle("layer-percent-0")
                if (selected && (width == 412 || width == 360)) capture("draft")
                expand("layer-exact-options")
                val x = onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("layer-field-x")))
                x.performScrollTo().assertIsDisplayed().performTextReplacement("NaN")
                reachStyle("layer-save").assertIsNotEnabled()
                x.performScrollTo().performTextReplacement("0.12")
                reachStyle("layer-save").assertIsEnabled()
                runOnIdle { platformBack.press() }; waitForIdle()
                assertNull(h.recap.layerEditor.state.value.draft); assertEquals(0, back)
                click("layer-edit-qa-text")
                onNodeWithTag("project-back").assertIsDisplayed().performMouseInput { click() }
                assertNull(h.recap.layerEditor.state.value.draft); assertEquals(0, back)
                onNodeWithTag("recap-next").assertIsDisplayed().assertIsEnabled().performMouseInput { click() }; assertEquals(1, next)
                onNodeWithTag("project-back").assertIsDisplayed().performMouseInput { click() }; assertEquals(1, back)
                onNodeWithTag("project-overflow").assertIsDisplayed().performMouseInput { click() }
                onNodeWithTag("classic-recap").assertIsDisplayed().performMouseInput { click() }; assertEquals(1, classic)
                assertEquals(before, h.bytes(), "browsing + invalid/valid draft cancellation must change no project/artifact bytes")
            }
        }
}

@OptIn(ExperimentalTestApi::class)
class StudioStyleWorkflowTest {
    @Test fun fontconfigSidecarsRequireNativeNamesSignaturesVersionAndSize() {
        val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0xfc02fc04.toInt()).putInt(7).putLong(16).array()
        val digest = ".cache/fontconfig/4c599c202bc5c08e2d34565a40eac3b2-le64.cache-7"
        val uuid = ".cache/fontconfig/ec436be7-5540-43fd-8afe-0621da12243b-le64.cache-7"
        assertTrue(nativeFontconfigSidecar(digest, header))
        assertTrue(nativeFontconfigSidecar(uuid, header))
        assertTrue(nativeFontconfigSidecar(".cache/fontconfig/CACHEDIR.TAG",
            "Signature: 8a477f597d28d172789f06886806bc55\n# native cache tag\n".encodeToByteArray()))
        for (path in listOf("project.json", "artifacts/story.json", ".cache/fontconfig/project.json",
            ".cache/fontconfig/nested/${digest.substringAfterLast('/')}", "$digest.extra", digest.replace("cache-7", "cache-8")))
            assertFalse(nativeFontconfigSidecar(path, header), path)
        assertFalse(nativeFontconfigSidecar(digest, "{}".encodeToByteArray()))
        assertFalse(nativeFontconfigSidecar(digest, header.copyOf().also { it[0] = 0 }))
        assertFalse(nativeFontconfigSidecar(digest, header.copyOf().also { it[4] = 8 }))
        assertFalse(nativeFontconfigSidecar(digest, header.copyOf().also { it[8] = 15 }))
        assertFalse(nativeFontconfigSidecar(".cache/fontconfig/CACHEDIR.TAG", "{}".encodeToByteArray()))
    }

    @Test fun percentSaveReopenDiscardAndProjectSwitchKeepStableLayersTakesAndArtifacts() =
        runDesktopComposeUiTest(width = 412, height = 760) {
            ExportHarness().use { h ->
                setContent { CompositionLocalProvider(LocalCompact provides true, LocalUiFontFamily provides BurmeseTestFont.family) {
                    StudioRecapScreen(h.recap, RecapStep.STYLE, 412f, {}, RecapNavigation({}, {}, {}))
                } }
                fun click(tag: String) = reachStyle(tag).assertIsEnabled().performMouseInput { click() }
                val before = h.session.open!!.project.recap!!
                val artifacts = h.bytes().filterKeys { it.replace('\\', '/').startsWith("artifacts/") }
                click("layer-edit-qa-blur")
                reachStyle("layer-percent-0").performMouseInput { click(androidx.compose.ui.geometry.Offset(width * .4f, height / 2f)) }
                reachStyle("layer-percent-2").performMouseInput { click(androidx.compose.ui.geometry.Offset(width * .5f, height / 2f)) }
                click("layer-earlier"); click("layer-save"); waitForIdle()
                val reopened = (h.store.open(h.fixture.dir.path) as CoreResult.Ok).value
                val after = reopened.recap!!; val blur = after.layers.first()
                assertEquals("qa-blur", blur.id); assertNotEquals(before.layers[1].rect, blur.rect); assertNull(LayerEdits.problem(blur))
                assertEquals(before.layers.map { it.id }.toSet(), after.layers.map { it.id }.toSet())
                assertEquals(before.takes, after.takes); assertEquals(before.chosenTakes, after.chosenTakes)
                assertEquals(before.visual, after.visual); assertEquals(before.storyApproved, after.storyApproved)
                assertEquals(before.story, after.story)
                runOnIdle { h.current.set(OpenProject(h.fixture.dir.path, reopened)) }; waitForIdle()
                val savedBytes = h.bytes()
                click("layer-edit-qa-blur"); reachStyle("layer-percent-1").performMouseInput { click() }; click("layer-cancel")
                assertEquals(savedBytes, h.bytes())
                click("style-section-CAPTIONS"); click("caption-size-S")
                assertEquals(CaptionSize.S, h.session.open!!.project.recap!!.captionStyle.size)
                click("style-section-LOOK"); click("style-look-MIRROR")
                assertTrue(Look.MIRROR in h.session.open!!.project.recap!!.looks)
                click("style-section-AUDIO")
                onNodeWithText(UiText.VOICE_POLISH.en).performScrollTo().assertIsDisplayed().performMouseInput { click() }
                assertNotEquals(before.audio.polish, h.session.open!!.project.recap!!.audio.polish)
                click("style-section-LAYERS"); click("layer-edit-qa-blur")
                val aBytes = h.bytes()
                val b = QaRecapFixture.create(h.root, "QA Other")
                val bBytes = File(b.dir, "project.json").readBytes()
                runOnIdle { h.current.set(OpenProject(b.dir.path, b.project)); h.recap.refresh() }
                waitUntil { h.recap.state.value.projectDir == b.dir.path }; waitForIdle()
                assertNull(h.recap.layerEditor.state.value.draft)
                onNodeWithTag("layer-save").assertDoesNotExist()
                assertEquals(aBytes, h.bytes()); assertContentEquals(bBytes, File(b.dir, "project.json").readBytes())
                assertEquals(artifacts, h.bytes().filterKeys { it.replace('\\', '/').startsWith("artifacts/") })
            }
        }

    @Test fun realExactFrameIsReachableAfterTheExistingPlayerReportsVlcUnavailable() =
        runDesktopComposeUiTest(width = 412, height = 760) {
            ExportHarness().use { h ->
                h.settings.update { it.copy(vlcPath = File(h.root, "no-vlc-installed-here").path) }
                setContent { CompositionLocalProvider(LocalCompact provides true, LocalUiFontFamily provides BurmeseTestFont.family) {
                    StudioRecapScreen(h.recap, RecapStep.STYLE, 412f, {}, RecapNavigation({}, {}, {}))
                } }
                val before = h.bytes()
                reachStyle("timeline-scrubber").performMouseInput { click(androidx.compose.ui.geometry.Offset(width * .15f, height / 2f)) }
                reachStyle("timeline-play").performMouseInput { click() }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("VLC path does not contain", substring = true).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("VLC path does not contain", substring = true).performScrollTo().assertIsDisplayed()
                onNodeWithTag("timeline-video").assertDoesNotExist()
                styleCapture("05-style-vlc-unavailable-412-en-1.0-true", 412, 1f)
                onNodeWithText(UiText.EXACT_FRAME.en).performScrollTo().assertIsDisplayed().performMouseInput { click() }
                waitUntil(timeoutMillis = 180_000) { onAllNodesWithContentDescription(UiText.EXACT_FRAME_DESCRIPTION.en).fetchSemanticsNodes().isNotEmpty() }
                val image = onNodeWithContentDescription(UiText.EXACT_FRAME_DESCRIPTION.en).performScrollTo().assertIsDisplayed()
                val bitmap = image.captureToImage().toAwtImage(); assertTrue(bitmap.width > 100 && bitmap.height > 50)
                styleCapture("05-style-real-exact-412-en-1.0-true", 412, 1f)
                val after = h.bytes()
                assertEquals(before, after.filterKeys { it in before }, "every original file must be byte-identical")
                val added = (after.keys - before.keys).map { it.replace('\\', '/') }
                assertTrue(added.any { it.matches(Regex("\\.cache/frames/\\d+\\.png")) }, "real cached frame required: $added")
                assertTrue(added.all { it == ".cache/fonts/NotoSansMyanmar.ttf" ||
                    it.matches(Regex("\\.cache/frames/\\d+\\.png(?:\\.ass(?:\\.layer-\\d+\\.ass)?)?")) ||
                    nativeFontconfigSidecar(it, File(h.fixture.dir, it).readBytes()) },
                    "only existing Exact Frame font/still/ASS and validated native fontconfig sidecars may be added: $added")
                if (System.getenv("AUTOFLOW_EXPECT_FONTCONFIG_CACHE") == "1")
                    assertTrue(added.any { it.endsWith(".cache-7") &&
                        nativeFontconfigSidecar(it, File(h.fixture.dir, it).readBytes()) },
                        "cold native fontconfig sidecars must actually be produced")
                println("STYLE_EXACT_FRAME_CACHE_EVIDENCE: ${before.size} original file hashes unchanged; " +
                    "real cached PNG required; validated native sidecars=${added.filter { it.startsWith(".cache/fontconfig/") }.sorted()}")
            }
        }

    @Test fun emptyStyleExplainsRealPrerequisitesAndNeverEnablesExport() = runDesktopComposeUiTest(width = 360, height = 760) {
        ExportHarness().use { h ->
            val dir = File(h.root, "Empty.autoflow").path
            val created = (h.store.create(dir, "QA Empty", Mode.MOVIE_RECAP) as CoreResult.Ok).value
            val empty = (h.store.save(dir, created.copy(recap = RecapV2())) as CoreResult.Ok).value
            h.current.set(OpenProject(dir, empty)); h.recap.refresh()
            var language by mutableStateOf(UiLanguage.ENGLISH); var font by mutableStateOf(1f)
            setContent { CompositionLocalProvider(LocalCompact provides true, LocalDensity provides Density(1f, font),
                LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) {
                StudioRecapScreen(h.recap, RecapStep.STYLE, 360f, {}, RecapNavigation({}, {}, {}))
            } }
            val file = File(h.root, "Empty.autoflow/project.json"); val before = file.readBytes()
            onNodeWithTag("recap-next").assertIsDisplayed().assertIsNotEnabled()
            styleCapture("05-style-empty-360-en-1.0-true", 360, 1f)
            runOnIdle { language = UiLanguage.BURMESE; font = 2f }; waitForIdle()
            onNodeWithTag("recap-next").assertIsDisplayed().assertIsNotEnabled()
            styleCapture("05-style-empty-360-my-2.0-true", 360, 2f)
            assertContentEquals(before, file.readBytes())
        }
    }

    @Test fun percentConveniencesStayInsideTheExistingOutputRectForEveryRatioAndLooksCombination() {
        for (ratio in listOf(Ratio.R16_9, Ratio.R9_16, Ratio.R1_1)) for (looks in listOf(emptyList(), listOf(Look.MIRROR), listOf(Look.ZOOM, Look.FILL))) {
            val original = listOf(.1f, .2f, .3f, .4f)
            for (index in 0..3) for (fraction in listOf(0f, .37f, 1f)) {
                val rect = layerPercentRect(original, index, fraction)
                assertNull(LayerEdits.problem(Layer("stable", LayerType.BLUR, rect)), "$ratio/$looks/$index/$fraction: $rect")
                original.indices.filter { it != index }.forEach { assertEquals(original[it], rect[it]) }
                val maximum = 1f - original[if (index < 2) index + 2 else index - 2]
                assertEquals((maximum * fraction).coerceAtLeast(if (index < 2) 0f else .001f), rect[index], .000001f)
            }
        }
    }
}
