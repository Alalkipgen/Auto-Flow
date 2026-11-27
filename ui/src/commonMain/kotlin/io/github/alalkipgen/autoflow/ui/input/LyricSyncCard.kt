package io.github.alalkipgen.autoflow.ui.input

import io.github.alalkipgen.autoflow.ui.i18n.*
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
import androidx.compose.runtime.rememberCoroutineScope
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
import io.github.alalkipgen.autoflow.ui.storyboard.JobState
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardResult
import io.github.alalkipgen.autoflow.ui.storyboard.message
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val SYNC_BUTTON = "Sync lyrics (tap)"
const val SYNC_TAP_BUTTON = "⤵ Line starts"
const val SYNC_END_BUTTON = "⏹ Last line ends"
const val SYNC_HELP = "သီချင်းဖွင့်ပြီး စာကြောင်းတစ်ကြောင်း စဆိုတိုင်း \"$SYNC_TAP_BUTTON\" ကို နှိပ်ပါ။ နောက်ဆုံးစာကြောင်း " +
    "ဆိုပြီးရင် \"$SYNC_END_BUTTON\" ကို နှိပ်ပါ။ (Play the song; tap when each line starts, then once when the last ends.)"
const val SUGGEST_BUTTON = "✨ Suggest timings (Gemini)"
const val SYNC_NO_PLAYER = "Playing the song needs the Android app — desktop has no audio player yet."

/** Lyrics timing (PLAN Q-11): status of the saved line timings and the tap-to-sync panel. */
@Composable
internal fun LyricSyncCard(model: InputModel, songFile: String, locked: Boolean, job: JobState) {
    val state by model.sync.state.collectAsState()
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    val run = job.gemini?.takeIf { it.action == UiText.JOB_LYRIC_TIMINGS && it.belongsTo(model.openProject) }
    LaunchedEffect(model, run?.id, job.last) {
        if (run != null && job.last == StoryboardResult.Done) model.sync.refresh(model.state.value.lyrics)
    }
    AfCard(tr(UiText.LYRICS_TIMING)) {
        val sync = state.sync
        if (confirming && !locked) {
            AfText(tr(UiText.SUGGEST_TIMINGS_CONFIRM), muted = true)
            WrapRow {
                AfButton(tr(UiText.SEND), { confirming = false; scope.launch { model.suggestTimings() } }, kind = ButtonKind.Primary)
                AfButton(tr(UiText.BACK), { confirming = false })
            }
        } else if (sync == null) {
            AfText(statusText(state, LocalUiLanguage.current), color = if (state.status == TimingStatus.OUTDATED) AutoFlowColors.Amber else null, muted = state.status == TimingStatus.NONE)
            WrapRow {
                AfButton(
                    if (state.status == TimingStatus.NONE) tr(UiText.SYNC_LYRICS) else tr(UiText.SYNC_AGAIN),
                    { model.startSync() },
                    kind = if (state.status == TimingStatus.SYNCED) ButtonKind.Secondary else ButtonKind.Primary,
                    enabled = !locked,
                )
                AfButton(tr(UiText.SUGGEST_TIMINGS), { confirming = true }, enabled = !locked)
                if (state.status != TimingStatus.NONE) AfButton(tr(UiText.CLEAR_TIMINGS), { model.sync.clear() }, kind = ButtonKind.Danger, enabled = !locked)
            }
        } else {
            val path = remember(songFile) { model.songPath() }
            val player = path?.let { rememberSongPlayer(it) }
            if (player == null) {
                AfText(tr(UiText.SYNC_NO_PLAYER), color = AutoFlowColors.Amber)
                AfButton(tr(UiText.CLOSE), model.sync::cancel)
            } else {
                TapPanel(sync, player, model.sync)
            }
        }
        val language = LocalUiLanguage.current
        val problem = run?.let { job.last?.takeIf { it != StoryboardResult.Done }?.message(language) }
        val message = problem ?: state.notice?.text(language) ?: state.message
        message?.let { AfText(it, color = if (problem == null && state.messageOk) AutoFlowColors.Green else AutoFlowColors.Amber, size = 12.sp) }
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
        AfText(tr(UiText.SYNC_HELP, "start" to tr(UiText.SYNC_LINE_START), "end" to tr(UiText.SYNC_LINE_END)), muted = true, size = 12.sp)
        WrapRow {
            AfButton(if (playing) tr(UiText.PAUSE) else tr(UiText.PLAY), { if (player.playing) player.pause() else player.play() })
            AfText("${InputModel.formatDuration(position)} / ${duration?.let(InputModel::formatDuration) ?: "–:––"}", mono = true)
            AfText(tr(UiText.LYRIC_LINE_COUNT, "size" to (minOf(sync.starts.size + 1, sync.lines.size)), "value2" to (sync.lines.size)), muted = true)
        }
        AfText(sync.currentLine ?: "—", size = 22.sp, bold = true)
        AfText(sync.nextLine?.let { tr(UiText.NEXT_LYRIC, "it" to (it)) } ?: if (sync.lastEndMs == null) tr(UiText.NEXT_LAST_LINE_END) else tr(UiText.ALL_LINES_DONE), muted = true)
        val canTap = playing && (!sync.complete || sync.lastEndMs == null)
        AfButton(
            if (!sync.complete) tr(UiText.SYNC_LINE_START) else tr(UiText.SYNC_LINE_END),
            { model.tap(player.positionMs) },
            Modifier.fillMaxWidth(),
            kind = ButtonKind.Primary,
            enabled = canTap,
        )
        WrapRow {
            AfButton(tr(UiText.UNDO), {
                model.undo()?.let { removed -> player.seekTo(maxOf(0, removed - UNDO_REWIND_MS)) }
            }, enabled = sync.starts.isNotEmpty())
            AfButton(tr(UiText.RESTART), {
                player.pause()
                player.seekTo(0)
                model.restart()
            })
            AfButton(tr(UiText.SAVE), {
                player.pause()
                model.save(duration)
            }, kind = ButtonKind.Primary, enabled = sync.complete)
            AfButton(tr(UiText.CANCEL), {
                player.pause()
                model.cancel()
            })
        }
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
