package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.media.MediaThumbnails
import io.github.alalkipgen.autoflow.ui.theme.AfRadius
import io.github.alalkipgen.autoflow.ui.theme.AfSize
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import io.github.alalkipgen.autoflow.ui.theme.afTextStyle

/** The app's thumbnail cache (DESIGN.md §9.5); null (tests, previews) = placeholders only. */
val LocalMediaThumbnails = staticCompositionLocalOf<MediaThumbnails?> { null }

/**
 * [source]'s real frame once the thumbnail is on screen (made off the main thread by [LocalMediaThumbnails]; released
 * off screen), else the placeholder — see the other overload.
 */
@Composable
fun MediaThumb(source: MediaThumbnails.Source?, icon: ImageVector, modifier: Modifier = Modifier, size: DpSize = AfSize.ThumbList,
    durationMs: Long? = null, play: Boolean = false, contentDescription: String? = null) {
    val thumbs = LocalMediaThumbnails.current
    var visible by remember(source) { mutableStateOf(false) }
    val image by produceState<ImageBitmap?>(null, thumbs, source, visible) {
        value = null
        if (visible && thumbs != null && source != null) value = thumbs.load(source)
    }
    MediaThumb(image, icon, modifier.onGloballyPositioned { visible = it.isAttached && !it.boundsInWindow().isEmpty }, size, durationMs, play,
        contentDescription)
}

/**
 * A thumbnail (bundle §4.9, radius 10, `AfSize.Thumb*`): [image] cropped to [size], or a gradient placeholder with the
 * Duotone type [icon] — never a fake picture. [durationMs] adds a bottom-right badge (pass only a real duration; the
 * badge is left out when it would not fit or would cover the play circle, e.g. large fonts on the 64 × 40 size); [play]
 * adds the play overlay.
 * Decorative unless [contentDescription]. Tags `media-thumb`, `media-thumb-image` / `media-thumb-placeholder` and
 * `media-thumb-play`.
 */
@Composable
fun MediaThumb(image: ImageBitmap?, icon: ImageVector, modifier: Modifier = Modifier, size: DpSize = AfSize.ThumbList,
    durationMs: Long? = null, play: Boolean = false, contentDescription: String? = null) {
    val shape = RoundedCornerShape(AfRadius.Thumb)
    Box(modifier.size(size).clip(shape).testTag("media-thumb").then(
        if (contentDescription == null) Modifier else Modifier.semantics { this.contentDescription = contentDescription }),
        contentAlignment = Alignment.Center) {
        if (image != null) Image(image, null, Modifier.fillMaxSize().testTag("media-thumb-image"), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(AutoFlowColors.Surface3, AutoFlowColors.Card)))
            .testTag("media-thumb-placeholder"), contentAlignment = Alignment.Center) {
            AfIcon(icon, tint = AutoFlowColors.Faint, size = minOf(size.height * .45f, AfSize.IconLarge))
        }
        val playSize = minOf(size.height * .5f, 40.dp)
        if (play) Box(Modifier.size(playSize).clip(CircleShape).background(Scrim).testTag("media-thumb-play"), contentAlignment = Alignment.Center) {
            AfIcon(AfIcons.Fill.Play, tint = Color.White, size = minOf(size.height * .25f, AfSize.IconSmall))
        }
        durationMs?.takeIf { it > 0 }?.let { ms ->
            val label = durationLabel(ms)
            val text = rememberTextMeasurer().measure(label, afTextStyle(Color.White, AfType.Micro.size, AfType.Micro.weight,
                LocalUiFontFamily.current), maxLines = 1).size
            // Drawn only when it fits inside the thumbnail with its 4 dp inset (never cut) and, with [play], beside or
            // below the centred play circle (never covering it).
            val fits = with(LocalDensity.current) {
                val w = text.width + (BadgePadX * 2).roundToPx(); val h = text.height + (BadgePadY * 2).roundToPx()
                val inset = BadgeInset.roundToPx(); val tw = size.width.roundToPx(); val th = size.height.roundToPx()
                val p = playSize.roundToPx()
                w + inset * 2 <= tw && h + inset * 2 <= th &&
                    (!play || tw - inset - w >= (tw + p) / 2 || th - inset - h >= (th + p) / 2)
            }
            if (fits) AfText(label, Modifier.align(Alignment.BottomEnd).padding(BadgeInset).background(Scrim, RoundedCornerShape(6.dp))
                .padding(horizontal = BadgePadX, vertical = BadgePadY), color = Color.White, type = AfType.Micro, maxLines = 1)
        }
    }
}

/** 0:42 · 12:48 · 1:02:05. */
internal fun durationLabel(ms: Long): String {
    val s = ms / 1000
    val mmss = "${(s / 60) % 60}:${(s % 60).toString().padStart(2, '0')}"
    return if (s >= 3600) "${s / 3600}:${mmss.padStart(5, '0')}" else mmss
}

private val Scrim = Color.Black.copy(alpha = .62f)
private val BadgePadX = 5.dp
private val BadgePadY = 1.dp
private val BadgeInset = 4.dp
