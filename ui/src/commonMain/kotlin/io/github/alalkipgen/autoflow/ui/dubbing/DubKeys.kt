package io.github.alalkipgen.autoflow.ui.dubbing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/**
 * Desktop shortcuts (Group 4 🆕-6), only for actions that already exist: ↑ / ↓ choose the previous / next line of the step
 * (the same as a head click: chosen + the preview seeks there), Enter edits the chosen line (Dub script: its spoken field
 * gets the focus; Voice: Dub script opens at it, = Edit line 🆕-5). Space (play / pause) is the preview's own button,
 * registered with [io.github.alalkipgen.autoflow.ui.components.PlayPauseShortcut]. The keys work only while the step's
 * [io.github.alalkipgen.autoflow.ui.components.ProjectShortcutHost] has the focus itself (never while typing in a field).
 * [selected] is the chosen line shared by Dub script and Voice (desktop), so a line chosen in one step stays chosen in the other.
 */
@Stable
internal class DubLineKeys {
    var selected by mutableStateOf<String?>(null)
    /** Bumped by ↑ / ↓: the step scrolls the chosen line into view (a head click does not move the list). */
    var moved by mutableIntStateOf(0)
        private set
    private var owner: Any? = null
    private var lines: List<Pair<String, Long>> = emptyList()
    private var chosen: String? = null
    private var seek: ((Long) -> Unit)? = null
    private var edit: ((String) -> Unit)? = null

    fun bind(owner: Any, lines: List<Pair<String, Long>>, chosen: String?, seek: ((Long) -> Unit)?, edit: (String) -> Unit) {
        this.owner = owner; this.lines = lines; this.chosen = chosen; this.seek = seek; this.edit = edit
    }

    fun unbind(owner: Any) { if (this.owner === owner) { this.owner = null; lines = emptyList(); chosen = null; seek = null; edit = null } }

    fun handle(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed || event.isShiftPressed) return false
        when (event.key) {
            Key.DirectionUp, Key.DirectionDown -> {
                if (lines.isEmpty()) return false
                val at = lines.indexOfFirst { it.first == chosen }
                val to = if (at < 0) 0 else (at + if (event.key == Key.DirectionUp) -1 else 1).coerceIn(0, lines.lastIndex)
                // At the first / last line the key is still taken: no wrap, no focus jump.
                if (to != at) { selected = lines[to].first; chosen = lines[to].first; seek?.invoke(lines[to].second); moved++ }
            }
            Key.Enter, Key.NumPadEnter -> edit?.invoke(chosen ?: return false) ?: return false
            else -> return false
        }
        return true
    }
}

/** A step's lines for [DubLineKeys] while it is shown (the owner guard keeps a leaving step from clearing the next one's). */
@Composable
internal fun BindLineKeys(keys: DubLineKeys, lines: List<Pair<String, Long>>, chosen: String?, seek: ((Long) -> Unit)?, edit: (String) -> Unit) {
    val owner = remember { Any() }
    SideEffect { keys.bind(owner, lines, chosen, seek, edit) }
    DisposableEffect(keys) { onDispose { keys.unbind(owner) } }
}

/** After ↑ / ↓: scroll [list] so the chosen line ([index] in the list) is in view, only when it is not fully shown. */
@Composable
internal fun FollowLineKeys(keys: DubLineKeys, list: LazyListState, index: Int?) {
    LaunchedEffect(keys.moved) {
        if (keys.moved == 0 || index == null || index < 0) return@LaunchedEffect
        val info = list.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        val inView = item != null && item.offset >= info.viewportStartOffset && item.offset + item.size <= info.viewportEndOffset
        if (!inView) list.animateScrollToItem(index)
    }
}

/** The desktop hint row (🆕-6): `keyboard` + V3_DUB_SHORTCUTS, muted 12 sp, in the sticky bar's keys slot (`dub-shortcuts`). */
@Composable
internal fun DubShortcutsHint() = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    AfIcon(AfIcons.Regular.Keyboard, tint = AutoFlowColors.Muted, size = 16.dp)
    AfText(My.breakSyllables(tr(UiText.V3_DUB_SHORTCUTS)), Modifier.testTag("dub-shortcuts"), muted = true, size = 12.sp)
}
