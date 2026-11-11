package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Platform-provided video preview composable.
 * Android: ExoPlayer with surface view.
 * Desktop: placeholder box (desktop has no video preview yet).
 */
@Composable
expect fun VideoPreview(path: String, modifier: Modifier = Modifier)
