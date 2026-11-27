package io.github.alalkipgen.autoflow.ui.input

import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.icons.afTooltip
import io.github.alalkipgen.autoflow.ui.i18n.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.lyrics.LyricTimings
import io.github.alalkipgen.autoflow.core.lyrics.TapSync
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.GlassButton
import io.github.alalkipgen.autoflow.ui.components.GlassCallout
import io.github.alalkipgen.autoflow.ui.components.GlassDialog
import io.github.alalkipgen.autoflow.ui.components.GlassDialogAction
import io.github.alalkipgen.autoflow.ui.components.GlassDialogButtons
import io.github.alalkipgen.autoflow.ui.components.GlassProgress
import io.github.alalkipgen.autoflow.ui.components.SongPlayer
import io.github.alalkipgen.autoflow.ui.components.TileCard
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.components.interactionOverlay
import io.github.alalkipgen.autoflow.ui.components.primaryBrush
import io.github.alalkipgen.autoflow.ui.components.rememberSongPlayer
import io.github.alalkipgen.autoflow.ui.recap.RecapBusyStrip
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.GlassColors
import io.github.alalkipgen.autoflow.ui.theme.GlassShapes
import io.github.alalkipgen.autoflow.ui.storyboard.JobState
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardResult
import io.github.alalkipgen.autoflow.ui.storyboard.message
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val SYNC_BUTTON = "Sync lyrics (tap)"
const val SYNC_TAP_BUTTON = "Line starts"
const val SYNC_END_BUTTON = "Last line ends"
const val SYNC_HELP = "သီချင်းဖွင့်ပြီး စာကြောင်းတစ်ကြောင်း စဆိုတိုင်း \"$SYNC_TAP_BUTTON\" ကို နှိပ်ပါ။ နောက်ဆုံးစာကြောင်း " +
    "ဆိုပြီးရင် \"$SYNC_END_BUTTON\" ကို နှိပ်ပါ။ (Play the song; tap when each line starts, then once when the last ends.)"
const val SUGGEST_BUTTON = "Suggest timings (Gemini)"
const val SYNC_NO_PLAYER = "Playing the song needs the Android app — desktop has no audio player yet."

/**
 * Lyrics timing (PLAN Q-11, Group 3 B 2): the saved timings' status, the tap-to-sync panel, and "Suggest timings" behind a
 * confirm dialog (🆕-5). While this card's own Gemini job runs it shows the busy strip and stays at full strength; any
 * other job dims it like the other locked cards.
 */
@Composable
internal fun LyricSyncCard(model: InputModel, songFile: String, locked: Boolean, job: JobState) {
    val state by model.sync.state.collectAsState()
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    val run = job.gemini?.takeIf { it.action == UiText.JOB_LYRIC_TIMINGS && it.belongsTo(model.openProject) }
    LaunchedEffect(model, run?.id, job.last) {
        if (run != null && job.last == StoryboardResult.Done) model.sync.refresh(model.state.value.lyrics)
    }
    val running = run != null && job.running != null
    LyricTimingCard(Modifier.dimmed(locked && !running)) {
        val sync = state.sync
        if (sync == null) {
            TimingStatusBody(state, running, locked, onSync = { model.startSync() }, onSuggest = { confirming = true },
                onClear = { model.sync.clear() })
        } else {
            val path = remember(songFile) { model.songPath() }
            val player = path?.let { rememberSongPlayer(it) }
            if (player == null) NoPlayer(model.sync::cancel) else TapPanel(sync, player, model.sync)
        }
        val language = LocalUiLanguage.current
        val problem = run?.let { job.last?.takeIf { it != StoryboardResult.Done }?.message(language) }
        val message = problem ?: state.notice?.text(language) ?: state.message
        message?.let { AfText(My.breakSyllables(it), color = if (problem == null && state.messageOk) AutoFlowColors.Green else AutoFlowColors.Amber, size = 12.sp) }
    }
    if (confirming && !locked) {
        SuggestTimingsDialog(onBack = { confirming = false }, onSend = { confirming = false; scope.launch { model.suggestTimings() } })
    }
}

/** The card frame: the cyan timer tile and LYRICS_TIMING. */
@Composable
internal fun LyricTimingCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    TileCard(tr(UiText.LYRICS_TIMING), MvTiles.LyricsTiming, modifier, content = content)

/**
 * The status line and buttons (panel closed): not synced = Muted dashed circle, synced = green check-circle (SuccessText),
 * outdated = the amber callout; this card's job running = the busy strip. Sync (hand-tap; secondary once synced) ·
 * Suggest (sparkle) · Clear (danger, eraser, once there are timings).
 */
@Composable
internal fun TimingStatusBody(state: LyricSyncUiState, running: Boolean, locked: Boolean, onSync: () -> Unit, onSuggest: () -> Unit,
    onClear: () -> Unit) {
    val language = LocalUiLanguage.current
    val text = My.breakSyllables(statusText(state, language))
    when {
        running -> RecapBusyStrip(My.breakSyllables(tr(UiText.JOB_LYRIC_TIMINGS)), null, Modifier.fillMaxWidth().testTag("lyric-timing-running"))
        state.status == TimingStatus.OUTDATED -> GlassCallout(AfIcons.Fill.Warn, text, AutoFlowColors.Amber)
        state.status == TimingStatus.SYNCED -> StatusFact(AfIcons.Fill.CheckCircle, text, AutoFlowColors.Green, AutoFlowColors.SuccessText)
        else -> StatusFact(AfIcons.Regular.CircleDashed, text, AutoFlowColors.Muted, AutoFlowColors.Muted)
    }
    WrapRow {
        GlassButton(if (state.status == TimingStatus.NONE) tr(UiText.SYNC_LYRICS) else tr(UiText.SYNC_AGAIN), onSync,
            kind = if (state.status == TimingStatus.SYNCED || running) ButtonKind.Secondary else ButtonKind.Primary, enabled = !locked,
            icon = AfIcons.Regular.HandTap, small = true)
        GlassButton(tr(UiText.SUGGEST_TIMINGS), onSuggest, enabled = !locked, icon = AfIcons.Regular.Spark, small = true)
        if (state.status != TimingStatus.NONE) {
            GlassButton(tr(UiText.CLEAR_TIMINGS), onClear, kind = ButtonKind.Danger, enabled = !locked, icon = AfIcons.Regular.Eraser, small = true)
        }
    }
}

/** A 17 dp icon (2 dp down) and caption text, 8 dp apart. */
@Composable
private fun StatusFact(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: Color, colour: Color) {
    Row(Modifier.fillMaxWidth().testTag("lyric-timing-status"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AfIcon(icon, Modifier.padding(top = 2.dp), tint = tint, size = 17.dp)
        AfText(text, Modifier.weight(1f), color = colour, type = AfType.Caption)
    }
}

/** Desktop has no song player: the amber speaker callout and Close. */
@Composable
internal fun NoPlayer(onClose: () -> Unit) {
    GlassCallout(AfIcons.Fill.SpeakerMuted, My.breakSyllables(tr(UiText.SYNC_NO_PLAYER)), AutoFlowColors.Amber, Modifier.testTag("sync-no-player"))
    WrapRow { GlassButton(tr(UiText.CLOSE), onClose, icon = AfIcons.Regular.Close, small = true) }
}

/** "Suggest timings" asks first (🆕-5): what it sends, Back / Send. */
@Composable
internal fun SuggestTimingsDialog(onBack: () -> Unit, onSend: () -> Unit) {
    GlassDialog(onBack, AfIcons.Fill.Spark, AutoFlowColors.Cyan, My.breakSyllables(tr(UiText.SUGGEST_TIMINGS)), Modifier.testTag("confirm-suggest-timings")) {
        AfText(My.breakSyllables(tr(UiText.SUGGEST_TIMINGS_CONFIRM)))
        GlassDialogButtons(GlassDialogAction(My.breakSyllables(tr(UiText.BACK)), onBack, tag = "confirm-suggest-timings-back"),
            GlassDialogAction(My.breakSyllables(tr(UiText.SEND)), onSend, ButtonKind.Primary, AfIcons.Regular.PaperPlaneRight,
                tag = "confirm-suggest-timings-send"))
    }
}

/** The tap panel on a real player: polls its position every [POLL_MS] and passes the taps to [model]. */
@Composable
private fun TapPanel(sync: TapSync, player: SongPlayer, model: LyricSyncModel) {
    var position by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(player) {
        while (true) {
            position = player.positionMs
            playing = player.playing
            duration = player.durationMs
            delay(POLL_MS)
        }
    }
    TapPanelBody(sync, playing, position, duration,
        onPlayPause = { if (player.playing) player.pause() else player.play() },
        onTap = { model.tap(player.positionMs) },
        onUndo = { model.undo()?.let { removed -> player.seekTo(maxOf(0, removed - UNDO_REWIND_MS)) } },
        onRestart = {
            player.pause()
            player.seekTo(0)
            model.restart()
        },
        onSave = {
            player.pause()
            model.save(duration)
        },
        onCancel = {
            player.pause()
            model.cancel()
        })
}

/**
 * The tap panel (Group 3 B 2): Muted help; a dark inner box (radius 14, Black .22 + White .10 border) with the 48 dp
 * play / pause circle, "m:ss / m:ss", the line counter and its bar (the same numbers), the current line (22 sp bold in a
 * cyan box), the next line (or the green "all done" callout) and the big tap button; then Undo · Restart · Save · Cancel.
 */
@Composable
internal fun TapPanelBody(sync: TapSync, playing: Boolean, positionMs: Long, durationMs: Long?, onPlayPause: () -> Unit, onTap: () -> Unit,
    onUndo: () -> Unit, onRestart: () -> Unit, onSave: () -> Unit, onCancel: () -> Unit) {
    AfText(My.breakSyllables(tr(UiText.SYNC_HELP, "start" to tr(UiText.SYNC_LINE_START), "end" to tr(UiText.SYNC_LINE_END))), muted = true, size = 12.sp)
    val box = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().clip(box).background(GlassColors.PathFill).border(1.dp, GlassColors.OptionBorder, box).padding(12.dp)
        .testTag("sync-tap-panel"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val shown = minOf(sync.starts.size + 1, sync.lines.size)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PlayPauseCircle(playing, onPlayPause)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TimeAndCount("${InputModel.formatDuration(positionMs)} / ${durationMs?.let(InputModel::formatDuration) ?: "–:––"}",
                    tr(UiText.LYRIC_LINE_COUNT, "size" to shown, "value2" to sync.lines.size))
                GlassProgress(if (sync.lines.isEmpty()) 0f else shown.toFloat() / sync.lines.size)
            }
        }
        // After the end tap the box keeps the last line (the sample's "all done" state); "—" before the first tap.
        val line = sync.currentLine ?: sync.lines.lastOrNull()?.takeIf { sync.lastEndMs != null } ?: "—"
        val cue = RoundedCornerShape(12.dp)
        Box(Modifier.fillMaxWidth().clip(cue).background(AutoFlowColors.Cyan.copy(alpha = .10f))
            .border(1.5.dp, AutoFlowColors.Cyan.copy(alpha = .55f), cue).padding(12.dp).testTag("sync-current-line"), contentAlignment = Alignment.Center) {
            AfText(My.breakSyllables(line), size = 22.sp, bold = true, color = Color.White, textAlign = TextAlign.Center)
        }
        val next = sync.nextLine
        when {
            next != null -> AfText(My.breakSyllables(tr(UiText.NEXT_LYRIC, "it" to next)), color = AutoFlowColors.Muted, type = AfType.Caption)
            sync.lastEndMs == null -> AfText(My.breakSyllables(tr(UiText.NEXT_LAST_LINE_END)), color = AutoFlowColors.Muted, type = AfType.Caption)
            else -> GlassCallout(AfIcons.Fill.CheckCircle, My.breakSyllables(tr(UiText.ALL_LINES_DONE)), AutoFlowColors.Green)
        }
        BigTapButton(end = sync.complete, enabled = playing && (!sync.complete || sync.lastEndMs == null), onClick = onTap)
    }
    WrapRow {
        GlassButton(tr(UiText.UNDO), onUndo, enabled = sync.starts.isNotEmpty(), icon = AfIcons.Regular.ArrowCounterClockwise, small = true)
        GlassButton(tr(UiText.RESTART), onRestart, icon = AfIcons.Regular.ArrowUUpLeft, small = true)
        GlassButton(tr(UiText.SAVE), onSave, kind = ButtonKind.Primary, enabled = sync.complete, icon = AfIcons.Regular.FloppyDisk, small = true)
        GlassButton(tr(UiText.CANCEL), onCancel, icon = AfIcons.Regular.Close, small = true)
    }
}

/** The mono clock and, at the end of the same line (or under it when both do not fit), the line counter in PrimaryText. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeAndCount(time: String, count: String) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically) {
        AfText(time, mono = true)
        AfText(My.breakSyllables(count), color = AutoFlowColors.PrimaryText, type = AfType.Micro)
    }
}

/** 48 dp primary-gradient circle with the Fill play / pause icon (22 dp); its label is the content description. */
@Composable
private fun PlayPauseCircle(playing: Boolean, onClick: () -> Unit) {
    val label = if (playing) tr(UiText.PAUSE) else tr(UiText.PLAY)
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.padding(horizontal = 4.dp).afTooltip(label).size(48.dp)
        .shadow(GlassShapes.ButtonShadow, CircleShape, ambientColor = GlassColors.PrimaryGlow, spotColor = GlassColors.PrimaryGlow)
        .clip(CircleShape).drawBehind { drawRect(primaryBrush()) }
        .clickable(interaction, null, role = Role.Button, onClick = onClick).semantics { contentDescription = label }
        .interactionOverlay(interaction, CircleShape).testTag("sync-play"), contentAlignment = Alignment.Center) {
        AfIcon(if (playing) AfIcons.Fill.Pause else AfIcons.Fill.Play, tint = Color.White, size = 22.dp)
    }
}

/**
 * The big tap button: full width, at least 96 dp (the sample's `.tap9`), radius 18, the primary gradient with its glow and
 * top highlight; the 24 dp icon (arrow-line-down "Line starts" / flag-checkered "Last line ends") over the label; α .4 off.
 */
@Composable
private fun BigTapButton(end: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val interaction = remember { MutableInteractionSource() }
    Column(Modifier.fillMaxWidth().heightIn(min = 96.dp).alpha(if (enabled) 1f else .4f)
        .then(if (enabled) Modifier.shadow(GlassShapes.ButtonShadow, shape, ambientColor = GlassColors.PrimaryGlow, spotColor = GlassColors.PrimaryGlow) else Modifier)
        .clip(shape).drawBehind { drawRect(primaryBrush()) }
        .drawWithContent {
            drawContent()
            drawRect(GlassColors.PrimaryHighlight, size = Size(size.width, 1.dp.toPx()))
        }
        .clickable(interaction, null, enabled = enabled, role = Role.Button, onClick = onClick)
        .interactionOverlay(interaction, shape).padding(horizontal = 16.dp, vertical = 12.dp).testTag("sync-tap"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
        AfIcon(if (end) AfIcons.Regular.FlagCheckered else AfIcons.Regular.ArrowLineDown, tint = Color.White, size = 24.dp)
        AfText(My.breakSyllables(if (end) tr(UiText.SYNC_LINE_END) else tr(UiText.SYNC_LINE_START)), color = Color.White, type = AfType.Button,
            textAlign = TextAlign.Center)
    }
}

internal fun statusText(state: LyricSyncUiState, language: UiLanguage = UiLanguage.ENGLISH): String = when (state.status) {
    TimingStatus.NONE -> UiText.LYRICS_NOT_SYNCED.text(language)
    TimingStatus.SYNCED -> UiText.LYRICS_SYNCED.text(language, "lines" to state.savedLines,
        "source" to (if (state.savedSource == LyricTimings.SOURCE_GEMINI) UiText.TIMINGS_GEMINI else UiText.TIMINGS_TAP).text(language))
    TimingStatus.OUTDATED -> UiText.LYRICS_OUTDATED.text(language)
}

private const val POLL_MS = 50L
private const val UNDO_REWIND_MS = 3_000L
