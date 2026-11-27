package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Whether the movie can play under the Recap preview's boxes and caption (5A-8, D-42): Android plays it with the
 * ExoPlayer the app already ships; the desktop has no video player without a new dependency (D-65), so it keeps
 * the still frame and Exact frame.
 */
expect val moviePlaybackSupported: Boolean

/**
 * Plays the movie file [path] from [startMs] to [endMs] in a loop, muted, stretched to this box (the preview box has
 * the movie's shape, like the still frame it replaces). Only called when [moviePlaybackSupported].
 */
@Composable
expect fun MoviePlayback(path: String, startMs: Long, endMs: Long, modifier: Modifier = Modifier)
