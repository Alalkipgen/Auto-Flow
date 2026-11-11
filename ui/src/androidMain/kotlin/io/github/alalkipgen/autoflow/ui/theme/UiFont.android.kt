package io.github.alalkipgen.autoflow.ui.theme

import android.graphics.Typeface
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import java.io.File

internal actual fun platformFont(path: String, bytes: ByteArray, weight: FontWeight): Font? {
    val file = File(path)
    // Android loads fonts from files; check it opens so text layout never meets a bad font.
    val ok = runCatching { Typeface.Builder(file).build() }.getOrNull()?.let { it != Typeface.DEFAULT } ?: false
    return if (ok) Font(file, weight) else null
}
