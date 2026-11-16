package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

internal actual fun bitmapFromArgb(width: Int, height: Int, pixels: IntArray): ImageBitmap {
    val bytes = ByteArray(width * height * 4)
    pixels.forEachIndexed { i, p ->
        bytes[i * 4] = p.toByte(); bytes[i * 4 + 1] = (p ushr 8).toByte()
        bytes[i * 4 + 2] = (p ushr 16).toByte(); bytes[i * 4 + 3] = (p ushr 24).toByte()
    }
    return org.jetbrains.skia.Image.makeRaster(org.jetbrains.skia.ImageInfo(width, height,
        org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.UNPREMUL), bytes, width * 4).toComposeImageBitmap()
}
