import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Method C (Q-02): render the overlay text with Compose/Skia (same shaping engine as the UI) into a transparent
 * 1080x1920 PNG per font, for compositing with the ffmpeg `overlay` filter. Set S4_PNG_OUT and S4_FONTS_DIR.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun renderPngs(outDir: File, fontsDir: File, lines: List<String>) {
    outDir.mkdirs()
    fontsDir.listFiles { f -> f.extension.lowercase() == "ttf" }!!.sorted().forEach { ttf ->
        val key = ttf.nameWithoutExtension.substringBefore('-').lowercase()
        val family = FontFamily(Font(ttf))
        val scene = ImageComposeScene(1080, 1920, Density(1f)) {
            Box(Modifier.fillMaxSize()) {
                lines.forEachIndexed { i, line ->
                    BasicText(line, Modifier.align(Alignment.TopCenter).offset(y = (820 + i * 120).dp), softWrap = false,
                        // 44 px ≈ the visual size of ASS font size 64 (ASS size = line height incl. Myanmar ascent/descent)
                        style = TextStyle(color = Color.White, fontFamily = family, fontSize = 44.sp, textAlign = TextAlign.Center))
                }
            }
        }
        val img = scene.render()
        File(outDir, "$key-skia.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        println("C: wrote $key-skia.png")
    }
}

fun main() {
    System.getenv("S4_PNG_OUT")?.let { out ->
        val lines = File(System.getenv("S4_LINES") ?: "methods/lines.txt").readLines().filter { it.isNotBlank() }
        renderPngs(File(out), File(System.getenv("S4_FONTS_DIR") ?: error("Set S4_FONTS_DIR")), lines)
        return
    }
    val noto = System.getenv("NOTO_MYANMAR_FONT") ?: error("Set NOTO_MYANMAR_FONT to a TTF file")
    val pyidaungsu = System.getenv("PYIDAUNGSU_FONT") ?: error("Set PYIDAUNGSU_FONT to a TTF file")
    val fonts = listOf("Noto Sans Myanmar" to noto, "Pyidaungsu" to pyidaungsu)
        .map { (name, path) -> name to FontFamily(Font(File(path))) }
    application {
        Window(onCloseRequest = ::exitApplication, title = "S4 Burmese rendering") {
            Column(Modifier.fillMaxSize().background(Color(0xff151515)).padding(30.dp)) {
                fonts.forEach { (name, family) ->
                    BasicText(name, style = TextStyle(color = Color.LightGray, fontSize = 20.sp))
                    Spacer(Modifier.height(10.dp))
                    BasicText("မိုးရွာနေတဲ့ ညမှာ တစ်ယောက်တည်း", style = TextStyle(color = Color.White, fontFamily = family, fontSize = 36.sp))
                    BasicText("မြန်မာ စာလုံးဆင့် ကမ္ဘာ", style = TextStyle(color = Color.White, fontFamily = family, fontSize = 36.sp))
                    Spacer(Modifier.height(36.dp))
                }
            }
        }
    }
}
