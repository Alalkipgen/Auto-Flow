package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.runtime.Composable

/** Desktop has no audio player yet (no VLC binding in the app); tap-to-sync says so. */
@Composable
actual fun rememberSongPlayer(path: String): SongPlayer? = null
