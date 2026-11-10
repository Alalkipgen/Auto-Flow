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
import java.io.File

fun main() {
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
