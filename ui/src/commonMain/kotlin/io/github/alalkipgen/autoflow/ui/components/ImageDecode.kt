package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.ui.graphics.ImageBitmap

/** PNG / JPEG bytes → a Compose image (scene thumbnails, D-33); null when they cannot be decoded. */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?

/** A fresh, caller-owned ARGB bitmap. Pixel processing never mutates decoder/artifact buffers. */
internal expect fun bitmapFromArgb(width: Int, height: Int, pixels: IntArray): ImageBitmap
