package io.github.alalkipgen.autoflow.ui.theme

import android.os.Build

/** `Modifier.blur` needs RenderEffect (Android 12, API 31); Android 11 and older use the colour tint. */
internal actual val GlassBlurSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
