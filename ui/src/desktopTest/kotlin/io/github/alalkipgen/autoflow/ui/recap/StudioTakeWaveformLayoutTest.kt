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
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files
import java.util.Collections
import javax.imageio.ImageIO
import kotlin.test.*

@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class StudioTakeWaveformLayoutTest(val width: Int, val font: Float, val language: UiLanguage, val bundled: Boolean) {
    companion object {
        private val fixture by lazy { TakeWaveformFixture.create(Files.createTempDirectory("waveform-layout").toFile()) }
        @JvmStatic @Parameterized.Parameters(name = "{0}px/{1}/{2}/bundled={3}")
        fun cases() = StudioVoiceLayoutTest.cases()
    }

    @Test fun realSelectedWaveformHistoryPlayAndBlockNavigationStayReachableAndNeverSaveOnBrowse() =
        runDesktopComposeUiTest(width = width, height = if (width == 1440) 900 else 760) {
            val f = fixture; val before = TakeWaveformFixture.bytes(f.dir); val (session, _) = TakeWaveformFixture.session(f)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val commands = Collections.synchronizedList(mutableListOf<List<String>>())
            val model = RecapModel(session, JvmFs(), TakeWaveformFixture.media(f.dir.parentFile, commands), null, scope,
                thumbnailCacheDir = File(f.dir.parentFile, "cache-$width-$font-${language.code}-$bundled").path)
            try {
                setContent { CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                    LocalUiLanguage provides language, LocalUiFontFamily provides if (bundled) BurmeseTestFont.family else FontFamily.Default) {
                    StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat()) {
                        StudioRecapScreen(model, RecapStep.VOICE, width.toFloat(), {}, RecapNavigation({}, {}, {}))
                    }
                } }
                fun click(tag: String) = onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performMouseInput { click() }
                fun ready() {
                    waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("take-waveform-slot").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("take-waveform-slot").performScrollTo().assertIsDisplayed()
                    waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("take-waveform-ready").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("take-waveform-caption").performScrollTo().assertIsDisplayed()
                        .assertTextEquals(UiText.V3_WAVEFORM_MONO.text(language, "duration" to RecapModel.seconds(1_000)))
                    val chart = onNodeWithTag("take-waveform-ready").assertIsDisplayed()
                    val config = chart.fetchSemanticsNode().config
                    assertFalse(SemanticsActions.OnClick in config); assertFalse(SemanticsActions.SetProgress in config)
                    assertFalse(SemanticsProperties.ProgressBarRangeInfo in config, "amplitude is not a seek control")
                    val b = chart.getUnclippedBoundsInRoot()
                    assertEquals(80f, (b.bottom - b.top).value)
                    assertTrue(b.left.value >= 0 && b.right.value <= width)
                    assertTrue(b.bottom <= onNodeWithTag("sticky-action-bar").getUnclippedBoundsInRoot().top)
                    val bar = onNodeWithTag("voice-block-request").assertIsDisplayed().assertIsEnabled().getUnclippedBoundsInRoot()
                    assertTrue((bar.right - bar.left).value >= 48 && (bar.bottom - bar.top).value >= 48)
                }
                val images = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "take-waveform").apply { mkdirs() }
                fun capture(name: String) {
                    assertEquals(emptyList(), textFitProblems(width, font))
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png",
                        File(images, "$name-$width-${language.code}-$font-$bundled.png"))
                }
                click("voice-open-0"); ready(); capture("04-selected")
                onNodeWithTag("voice-take-1").assertDoesNotExist()
                onNodeWithContentDescription(UiText.V3_TAKE_HISTORY.text(language, "n" to 1))
                    .performScrollTo().assertIsDisplayed().performMouseInput { click() }
                onNodeWithTag("voice-take-1").performScrollTo().assertIsDisplayed()
                onNodeWithTag("voice-take-play-1").performScrollTo().assertIsDisplayed()
                    .assert(hasAnyDescendant(hasClickAction()))
                onAllNodesWithTag("take-waveform-slot").assertCountEquals(1)
                ready(); capture("04-history-selected")
                onNodeWithTag("voice-take-play-2").performScrollTo().assertIsDisplayed()
                    .assert(hasAnyDescendant(hasClickAction()))
                click("voice-detail-next"); ready(); capture("04-next-block")
                assertTrue(commands.filter { "s16le" in it }.all { it[it.indexOf("-i") + 1].contains("/artifacts/") })
                assertEquals(setOf(f.project.recap!!.takeFor("stable-a")!!.audio.value,
                    f.project.recap!!.takeFor("stable-b")!!.audio.value),
                    commands.filter { "s16le" in it }.map { File(it[it.indexOf("-i") + 1]).nameWithoutExtension }.toSet())
                click("voice-detail-previous"); ready()
                // Header Back is outside the content scroller; assert visibility, then ordinary pointer input.
                onNodeWithTag("project-back").assertIsDisplayed().assertIsEnabled().performMouseInput { click() }
                onNodeWithTag("voice-remaining").assertIsDisplayed()
                assertEquals(before, TakeWaveformFixture.bytes(f.dir), "all project/artifact files remain unchanged")
            } finally { scope.cancel() }
        }
}