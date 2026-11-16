package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** No video player on the desktop without a new dependency (VLCJ, D-65): the preview stays a still frame. */
actual val moviePlaybackSupported: Boolean = false

@Composable
actual fun MoviePlayback(path: String, startMs: Long, endMs: Long, modifier: Modifier) {
    Box(modifier)
}
