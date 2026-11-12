package io.github.alalkipgen.autoflow.ui.input

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.lyrics.LyricTimings
import io.github.alalkipgen.autoflow.core.lyrics.TapSync
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfCard
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.SongPlayer
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.components.rememberSongPlayer
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import kotlinx.coroutines.delay

const val SYNC_BUTTON = "Sync lyrics (tap)"
const val SYNC_TAP_BUTTON = "⤵ Line starts"
const val SYNC_END_BUTTON = "⏹ Last line ends"
const val SYNC_HELP = "သီချင်းဖွင့်ပြီး စာကြောင်းတစ်ကြောင်း စဆိုတိုင်း \"$SYNC_TAP_BUTTON\" ကို နှိပ်ပါ။ နောက်ဆုံးစာကြောင်း " +
    "ဆိုပြီးရင် \"$SYNC_END_BUTTON\" ကို နှိပ်ပါ။ (Play the song; tap when each line starts, then once when the last ends.)"
const val SYNC_NO_PLAYER = "Playing the song needs the Android app — desktop has no audio player yet."

/** Lyrics timing (PLAN Q-11): status of the saved line timings and the tap-to-sync panel. */
@Composable
internal fun LyricSyncCard(model: InputModel, songFile: String, locked: Boolean) {
    val state by model.sync.state.collectAsState()
    AfCard("Lyrics timing") {
        val sync = state.sync
        if (sync == null) {
            AfText(statusText(state), color = if (state.status == TimingStatus.OUTDATED) AutoFlowColors.Amber else null, muted = state.status == TimingStatus.NONE)
            WrapRow {
                AfButton(
                    if (state.status == TimingStatus.NONE) SYNC_BUTTON else "Sync again (tap)",
                    { model.startSync() },
                    kind = if (state.status == TimingStatus.SYNCED) ButtonKind.Secondary else ButtonKind.Primary,
                    enabled = !locked,
                )
                if (state.status != TimingStatus.NONE) AfButton("Clear timings", { model.sync.clear() }, kind = ButtonKind.Danger, enabled = !locked)
            }
        } else {
            val path = remember(songFile) { model.songPath() }
            val player = path?.let { rememberSongPlayer(it) }
            if (player == null) {
                AfText(SYNC_NO_PLAYER, color = AutoFlowColors.Amber)
                AfButton("Close", model.sync::cancel)
            } else {
                TapPanel(sync, player, model.sync)
            }
        }
        state.message?.let { AfText(it, color = if (state.messageOk) AutoFlowColors.Green else AutoFlowColors.Amber, size = 12.sp) }
    }
}

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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AfText(SYNC_HELP, muted = true, size = 12.sp)
        WrapRow {
            AfButton(if (playing) "⏸ Pause" else "▶ Play", { if (player.playing) player.pause() else player.play() })
            AfText("${InputModel.formatDuration(position)} / ${duration?.let(InputModel::formatDuration) ?: "–:––"}", mono = true)
            AfText("Line ${minOf(sync.starts.size + 1, sync.lines.size)} / ${sync.lines.size}", muted = true)
        }
        AfText(sync.currentLine ?: "—", size = 22.sp, bold = true)
        AfText(sync.nextLine?.let { "Next: $it" } ?: if (sync.lastEndMs == null) "Next: (end of the last line)" else "All lines done ✓", muted = true)
        val canTap = playing && (!sync.complete || sync.lastEndMs == null)
        AfButton(
            if (!sync.complete) SYNC_TAP_BUTTON else SYNC_END_BUTTON,
            { model.tap(player.positionMs) },
            Modifier.fillMaxWidth(),
            kind = ButtonKind.Primary,
            enabled = canTap,
        )
        WrapRow {
            AfButton("↶ Undo", {
                model.undo()?.let { removed -> player.seekTo(maxOf(0, removed - UNDO_REWIND_MS)) }
            }, enabled = sync.starts.isNotEmpty())
            AfButton("⟲ Restart", {
                player.pause()
                player.seekTo(0)
                model.restart()
            })
            AfButton("Save", {
                player.pause()
                model.save(duration)
            }, kind = ButtonKind.Primary, enabled = sync.complete)
            AfButton("Cancel", {
                player.pause()
                model.cancel()
            })
        }
    }
}

internal fun statusText(state: LyricSyncUiState): String = when (state.status) {
    TimingStatus.NONE -> "Not synced: each shot's lyrics show for the whole shot."
    TimingStatus.SYNCED -> "✓ ${state.savedLines} lines synced" +
        (if (state.savedSource == LyricTimings.SOURCE_GEMINI) " (suggested by Gemini)" else " (tap)") +
        ": Export shows each line when it is sung."
    TimingStatus.OUTDATED -> "⚠ The lyrics changed after the sync — sync again. Until then Export shows each shot's lyrics."
}

private const val POLL_MS = 50L
private const val UNDO_REWIND_MS = 3_000L
