package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.ui.graphics.ImageBitmap

/** PNG / JPEG bytes → a Compose image (scene thumbnails, D-33); null when they cannot be decoded. */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?
