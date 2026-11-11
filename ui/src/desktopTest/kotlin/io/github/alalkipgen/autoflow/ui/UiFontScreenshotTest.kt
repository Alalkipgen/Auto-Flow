package io.github.alalkipgen.autoflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import io.github.alalkipgen.autoflow.ui.theme.UiFont
import java.awt.image.BufferedImage
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * UI Burmese font (DESIGN.md §1) with the bundled Noto Sans Myanmar subset: Burmese text drawn with the Settings font
 * differs from the system fallback (boxes on headless Linux, Myanmar Text on Windows). PNGs go to
 * `AUTOFLOW_SCREENSHOTS_DIR` when set.
 */
@OptIn(ExperimentalTestApi::class)
class UiFontScreenshotTest {
    private val font: ByteArray = Base64.getMimeDecoder().decode(
        javaClass.getResourceAsStream("/fonts/NotoSansMyanmar-subset.ttf.b64")!!.readBytes(),
    )

    @Test
    fun settingsFontIsUsedForUiText() {
        val family = assertNotNull(UiFont.load(listOf("/fonts/a.ttf", "/fonts/noto.ttf")) { if (it.endsWith("noto.ttf")) font else null })
        val withFont = render(family)
        val fallback = render(FontFamily.Default)
        System.getenv("AUTOFLOW_SCREENSHOTS_DIR")?.let(::File)?.let { dir ->
            dir.mkdirs()
            ImageIO.write(withFont, "png", File(dir, "ui-font-noto.png"))
            ImageIO.write(fallback, "png", File(dir, "ui-font-fallback.png"))
        }
        assertFalse(same(withFont, fallback), "Burmese text looks the same with and without the Settings font")
    }

    private fun render(family: FontFamily): BufferedImage {
        var image: BufferedImage? = null
        runDesktopComposeUiTest(width = 400, height = 120) {
            setContent {
                CompositionLocalProvider(LocalUiFontFamily provides family) {
                    Column(Modifier.background(AutoFlowColors.Background).padding(12.dp)) {
                        AfText("မိုးရေစက် (Rain drops)", size = 20.sp)
                        AfText("ကျွန်တော် မိုးရွာနေတဲ့ ညမှာ", bold = true, size = 20.sp)
                    }
                }
            }
            waitForIdle()
            image = onRoot().captureToImage().toAwtImage()
        }
        return image!!
    }

    private fun same(a: BufferedImage, b: BufferedImage): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        for (y in 0 until a.height) for (x in 0 until a.width) if (a.getRGB(x, y) != b.getRGB(x, y)) return false
        return true
    }
}
