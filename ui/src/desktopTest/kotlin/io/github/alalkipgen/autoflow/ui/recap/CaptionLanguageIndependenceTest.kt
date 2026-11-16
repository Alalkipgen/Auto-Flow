package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.media.render.RenderPresets
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.bitmapFromArgb
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlin.test.*

/** The real output-frame painter renders Burmese captions, independently of app locale and accessibility scale. */
@OptIn(ExperimentalTestApi::class)
class CaptionLanguageIndependenceTest {
    private fun ImageBitmap.pixels() = IntArray(width * height).also { readPixels(it) }

    @Test fun nativeBurmeseCaptionPixelsAndSizeNeverFollowUiLanguageOrFontScale() {
        for (bundled in listOf(true, false)) runDesktopComposeUiTest(width = 320, height = 180) {
            val source = bitmapFromArgb(320, 180, IntArray(320 * 180) { 0xFF0000FF.toInt() })
            val cue = SrtCue(0, 2000, "မြန်မာစာတန်း မပြောင်းရ")
            var language by mutableStateOf(UiLanguage.ENGLISH)
            var scale by mutableFloatStateOf(1f)
            var burn by mutableStateOf(true)
            setContent { CompositionLocalProvider(LocalUiLanguage provides language, LocalDensity provides Density(1f, scale),
                LocalUiFontFamily provides if (bundled) BurmeseTestFont.family else FontFamily.Default) {
                // Reconstruct the painter/frame for every variant; equality cannot be a cached old bitmap.
                key(language, scale) {
                    NativeOutputPicture(source, RecapV2(captions = RecapCaptions(burn = burn)), RenderPresets.preview(Ratio.R16_9), 1000,
                        listOf(cue), emptyMap(), Modifier.size(320.dp, 180.dp))
                }
            } }
            fun capture() = onNodeWithContentDescription("Native output-frame proxy").captureToImage()
            val baseline = capture()
            val baselineSize = baseline.width to baseline.height
            // Snapshot immediately: a platform capture may share a mutable graphics backing store.
            val baselinePixels = baseline.pixels()
            for (nextLanguage in UiLanguage.entries) for (nextScale in listOf(1f, 1.3f, 2f)) {
                runOnIdle { language = nextLanguage; scale = nextScale }
                val image = capture()
                assertEquals(baselineSize, image.width to image.height)
                assertContentEquals(baselinePixels, image.pixels(), "$bundled/${nextLanguage.code}/$nextScale")
            }
            runOnIdle { burn = false }
            val withoutCaption = capture().pixels()
            val changedPixels = baselinePixels.indices.count { baselinePixels[it] != withoutCaption[it] }
            assertTrue(changedPixels > 40,
                "the unchanged result must actually contain rendered Burmese caption ink: bundled=$bundled, changed=$changedPixels")
            assertEquals("မြန်မာစာတန်း မပြောင်းရ", cue.text)
        }
    }
    @Test fun missingImageNoticeRetranslatesAndPreservesTheRawFileIdentity() = runDesktopComposeUiTest(width = 412, height = 760) {
        val file = "artifacts/owner-မြန်မာ-{n}.png"
        val recap = RecapV2(layers = listOf(Layer("owner-image", LayerType.IMAGE, params = LayerParams(image = file))))
        var language by mutableStateOf(UiLanguage.ENGLISH)
        setContent { CompositionLocalProvider(LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) {
            NativeOutputPicture(bitmapFromArgb(4, 4, IntArray(16) { -1 }), recap, RenderPresets.preview(Ratio.R1_1), 0, emptyList(), emptyMap())
        } }
        for (next in listOf(UiLanguage.ENGLISH, UiLanguage.BURMESE, UiLanguage.ENGLISH)) {
            runOnIdle { language = next }
            onNodeWithText(UiText.NATIVE_IMAGE_UNAVAILABLE.text(next, "file" to file)).assertIsDisplayed()
            onNodeWithContentDescription("Native output-frame proxy").assertDoesNotExist()
            assertEquals(file, recap.layers.single().params.image)
        }
    }

}
