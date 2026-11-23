package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.media.render.RecapTimeline
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.icons.afTooltip
import io.github.alalkipgen.autoflow.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Group 2 A §1 (PR 2): one Recap step's link between its phone top preview card and the rest of the page — the mini bar
 * (while playing and the card is scrolled out of view) and the list thumbnails that seek the card (`block-preview-n`,
 * `scene-preview-n`) and mark the block that is playing. Only the card writes it; nothing here changes the project.
 */
@Stable
internal class TopPreviewLink {
    var attached by mutableStateOf(false); private set
    var playing by mutableStateOf(false); private set
    var outputMs by mutableLongStateOf(0L); private set
    var durationMs by mutableLongStateOf(0L); private set
    var blockId by mutableStateOf<String?>(null); private set
    var blockNumber by mutableStateOf<Int?>(null); private set
    /** Movie time under the card's output time (scene rows), or null between clips. */
    var movieMs by mutableStateOf<Long?>(null); private set
    /** Less than a quarter of the card is inside the page's viewport (it is scrolled away above). */
    var cardHidden by mutableStateOf(false); internal set
    val requester = BringIntoViewRequester()
    private var control: Control? = null

    private class Control(val timeline: RecapTimeline, val isPlaying: () -> Boolean, val togglePlay: () -> Unit, val seek: (Long) -> Unit)

    internal fun attach(timeline: RecapTimeline, isPlaying: () -> Boolean, togglePlay: () -> Unit, seek: (Long) -> Unit) {
        control = Control(timeline, isPlaying, togglePlay, seek); attached = true
    }
    internal fun detach() { control = null; attached = false; playing = false; cardHidden = false }
    internal fun report(playing: Boolean, outputMs: Long, durationMs: Long, blockId: String?, blockNumber: Int?, movieMs: Long?) {
        this.playing = playing; this.outputMs = outputMs; this.durationMs = durationMs
        this.blockId = blockId; this.blockNumber = blockNumber; this.movieMs = movieMs
    }

    /** Output start of [id] on the card's timeline, or null when the block has no clip there. */
    fun blockStart(id: String): Long? = control?.timeline?.clips?.firstOrNull { it.blockId == id }?.outputFromMs

    /** The first output time that shows movie time [fromMs]–[toMs] (a picked scene), or null when no clip uses it. */
    fun movieStart(fromMs: Long, toMs: Long): Long? = control?.timeline?.clips?.firstNotNullOfOrNull { c ->
        val end = c.sourceFromMs + (c.durationMs * c.sourceRate).toLong()
        if (c.sourceFromMs < toMs && end > fromMs) c.outputAt(maxOf(fromMs, c.sourceFromMs)) else null
    }

    /** The thumbnail action: pause when [current] is what plays now, else seek the card to [outputMs] and play. */
    fun playAt(outputMs: Long, current: Boolean) {
        val c = control ?: return
        if (current && c.isPlaying()) { c.togglePlay(); return }
        c.seek(outputMs)
        if (!c.isPlaying()) c.togglePlay()
    }

    fun togglePlay() { control?.togglePlay?.invoke() }
}

internal val LocalTopPreviewLink = staticCompositionLocalOf<TopPreviewLink?> { null }

/**
 * The phone Recap step's content area: [content] with one [TopPreviewLink] for its top card, and the mini bar pinned at
 * the top (over the page, so showing it never moves the card it reports on). Wide windows: [content] alone.
 */
@Composable
internal fun TopPreviewHost(model: RecapModel, state: RecapUiState, phone: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    if (!phone) { Box(modifier) { content() }; return }
    val link = remember(state.projectDir) { TopPreviewLink() }
    CompositionLocalProvider(LocalTopPreviewLink provides link) {
        Box(modifier) {
            content()
            RecapMiniBar(model, state, link, Modifier.align(Alignment.TopCenter))
        }
    }
}

/**
 * A §1 mini bar (0-preview-states 4): 72 dp thumbnail of the playing block · Block n · mono time · Pause · caret-up back to
 * the card; a 3 dp progress line at the bottom. Shown only while playing with the card out of view.
 */
@Composable
internal fun RecapMiniBar(model: RecapModel, state: RecapUiState, link: TopPreviewLink, modifier: Modifier = Modifier) {
    if (!(link.attached && link.playing && link.cardHidden)) return
    val scope = rememberCoroutineScope()
    val clip = link.blockId?.let { id -> state.story?.blocks?.firstOrNull { it.id == id }?.clips?.firstOrNull() }
    // Opaque, and a tap on its empty part stays on the bar (never opens the block card under it).
    Column(modifier.fillMaxWidth().testTag("recap-mini-bar").background(AutoFlowColors.Panel).pointerInput(Unit) {}) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(MiniThumb).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(Color.Black)) {
                clip?.let { RecapThumbnail(model, state, it.inMs, it.outMs, "recap-mini-bar-thumbnail", size = MiniThumb) }
            }
            Column(Modifier.weight(1f)) {
                link.blockNumber?.let { AfText(tr(UiText.V3_BLOCK, "n" to it), type = GlassSectionType, weight = FontWeight.Bold) }
                AfText("${RecapModel.clock(link.outputMs)} / ${RecapModel.clock(link.durationMs)}", mono = true, size = 12.sp,
                    color = AutoFlowColors.Muted)
            }
            GlassIconButton(AfIcons.Fill.Pause, tr(UiText.PAUSE), { link.togglePlay() }, size = GlassShapes.IconButtonHeader)
            GlassIconButton(AfIcons.Regular.CaretUp, tr(UiText.PROJECT_PAGE_PREVIEW), { scope.launch { link.requester.bringIntoView() } },
                size = GlassShapes.IconButtonHeader)
        }
        val last = (link.durationMs - 1).coerceAtLeast(1)
        Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = .08f))) {
            Box(Modifier.fillMaxWidth((link.outputMs.toFloat() / last).coerceIn(0f, 1f)).fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(AutoFlowColors.Cyan, AutoFlowColors.Purple))))
        }
    }
}

/** Cyan 1.5 dp outline of the block that is playing in the top card (A §1 "sync with the lists"). */
internal val PlayingOutline = 1.5.dp

/**
 * A block's list thumbnail (Story / Voice, phone) with its small play badge: tapping it seeks the top card to the block and
 * plays (Pause badge = the block playing now; tap pauses). The badge is left out when the step has no top card.
 */
@Composable
internal fun BlockPreviewThumb(model: RecapModel, state: RecapUiState, index: Int, blockId: String, fromMs: Long, toMs: Long) {
    val link = LocalTopPreviewLink.current?.takeIf { it.attached }
    val start = link?.blockStart(blockId)
    val current = link != null && link.playing && link.blockId == blockId
    if (link == null || start == null) { RecapThumbnail(model, state, fromMs, toMs, "block-thumbnail-$blockId"); return }
    val label = tr(if (current) UiText.PAUSE else UiText.PLAY)
    Box(Modifier.size(AfSize.ThumbScene).clip(androidx.compose.foundation.shape.RoundedCornerShape(AfRadius.Thumb))
        .testTag("block-preview-$index").afTooltip(label)
        .clickable(role = Role.Button, onClickLabel = label) { link.playAt(start, current) }
        .semanticsLabel(label), contentAlignment = Alignment.Center) {
        RecapThumbnail(model, state, fromMs, toMs, "block-thumbnail-$blockId")
        PlayBadge(current)
    }
}

/**
 * A Source scene row's thumbnail: the play badge is its own 32 dp target (`scene-preview-n`) that seeks the top card to
 * the scene; the rest of the row still toggles the pick. No badge when the card's timeline does not show this scene.
 */
@Composable
internal fun ScenePreviewThumb(model: RecapModel, state: RecapUiState, index: Int, fromMs: Long, toMs: Long) {
    val link = LocalTopPreviewLink.current?.takeIf { it.attached }
    val start = link?.movieStart(fromMs, toMs)
    Box(Modifier.size(AfSize.ThumbScene), contentAlignment = Alignment.Center) {
        RecapThumbnail(model, state, fromMs, toMs, "scene-thumbnail-$index")
        if (link != null && start != null) {
            val current = link.playing && link.movieMs?.let { it in fromMs until toMs } == true
            val label = tr(if (current) UiText.PAUSE else UiText.PLAY)
            Box(Modifier.size(32.dp).clip(CircleShape).testTag("scene-preview-$index").afTooltip(label)
                .clickable(role = Role.Button, onClickLabel = label) { link.playAt(start, current) }
                .semanticsLabel(label), contentAlignment = Alignment.Center) { PlayBadge(current) }
        }
    }
}

/** 28 dp dark glass circle, 1.5 dp White .55 ring, Play or Pause (the small sibling of the card's big Play). */
@Composable
private fun PlayBadge(pause: Boolean) {
    Box(Modifier.size(28.dp).clip(CircleShape).background(AutoFlowColors.Background.copy(alpha = .55f))
        .border(1.5.dp, Color.White.copy(alpha = .55f), CircleShape), contentAlignment = Alignment.Center) {
        AfIcon(if (pause) AfIcons.Fill.Pause else AfIcons.Fill.Play, tint = Color.White, size = 13.dp)
    }
}

private fun Modifier.semanticsLabel(label: String) = semantics { contentDescription = label }

private val MiniThumb = DpSize(72.dp, 40.5.dp)
