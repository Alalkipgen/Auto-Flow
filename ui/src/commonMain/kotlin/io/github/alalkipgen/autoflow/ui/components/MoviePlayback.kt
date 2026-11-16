package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.alalkipgen.autoflow.media.playback.MovieRequest
import io.github.alalkipgen.autoflow.media.playback.MovieSnapshot

/** Platform surface only; native decoder ownership lives in :media, never in common screen/business logic. */
expect val moviePlaybackSupported: Boolean

/** Source-clock controlled, muted movie under Compose overlays. A changed seekRevision applies one seek. */
@Composable
expect fun MoviePlayback(
    path: String,
    request: MovieRequest,
    modifier: Modifier = Modifier,
    vlcDir: String? = null,
    onSnapshot: (MovieSnapshot) -> Unit = {},
)
