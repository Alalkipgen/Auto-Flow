package io.github.alalkipgen.autoflow.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr

internal actual fun platformFont(path: String, bytes: ByteArray, weight: FontWeight): Font? {
    // Skia rejects damaged data here instead of failing later during text layout.
    FontMgr.default.makeFromData(Data.makeFromBytes(bytes)) ?: return null
    return androidx.compose.ui.text.platform.Font(identity = path, data = bytes, weight = weight)
}
