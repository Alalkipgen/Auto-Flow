package io.github.alalkipgen.autoflow.ui.inbox

import io.github.alalkipgen.autoflow.ui.i18n.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes

/** Inbox screen: lists `<project>/inbox/` files; lets the user attach a video or image to a shot. */
@Composable
fun InboxScreen(model: InboxModel, modifier: Modifier = Modifier, shared: SharedInbox? = null) {
    val state by model.state.collectAsState(model.current())
    var items by remember { mutableStateOf(emptyList<InboxItem>()) }
    var localMsg by remember { mutableStateOf<String?>(null) }
    var localNotice by remember { mutableStateOf<UiNotice?>(null) }
    val sharedState = shared?.state?.collectAsState()?.value

    // Refresh on first composition, when the project changes and when a share arrives (share-sheet import).
    LaunchedEffect(state.projectName, sharedState?.arrivals) {
        shared?.refresh()
        val refreshed = model.refresh()
        items = refreshed.items
        localMsg = refreshed.message
        localNotice = refreshed.notice
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPadding).widthIn(max = 860.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AfText(tr(UiText.INBOX), size = 18.sp, bold = true)
            AfButton(tr(UiText.REFRESH), {
                val r = model.refresh(); items = r.items; localMsg = r.message; localNotice = r.notice
            }, kind = ButtonKind.Secondary)
        }
        state.inboxDir?.let { AfText(tr(UiText.FOLDER_PATH, "it" to (it)), muted = true, size = 11.sp) }
        AfText(tr(UiText.INBOX_HELP), muted = true, size = 12.sp)

        localMsg?.let { AfText(localNotice?.text(LocalUiLanguage.current) ?: it, color = AutoFlowColors.Amber) }
        if (shared != null && sharedState != null) SharedCard(shared, sharedState, projectOpen = state.projectName != null) {
            val r = model.refresh(); items = r.items; localMsg = r.message; localNotice = r.notice
        }

        when {
            state.projectName == null -> AfText(tr(UiText.OPEN_PROJECT_FIRST), muted = true)
            items.isEmpty() -> AfText(tr(UiText.INBOX_EMPTY), muted = true)
            else -> {
                items.forEach { item ->
                    InboxItemRow(item, state.shotNumbers) { shotNumber ->
                        model.attach(item, shotNumber)
                        val r = model.refresh(); items = r.items; localMsg = r.message; localNotice = r.notice
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxItemRow(
    item: InboxItem,
    shotNumbers: List<Int>,
    onAttach: (Int) -> Unit,
) {
    var expanded by remember(item.path) { mutableStateOf(false) }
    val kindLabel = when (item.kind) {
        InboxItemKind.VIDEO -> "🎬"
        InboxItemKind.IMAGE -> "🖼"
        InboxItemKind.OTHER -> "📄"
    }
    val borderColor = when (item.kind) {
        InboxItemKind.VIDEO -> AutoFlowColors.Purple
        InboxItemKind.IMAGE -> AutoFlowColors.Cyan
        InboxItemKind.OTHER -> AutoFlowColors.Line
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(AutoFlowShapes.Card)
            .border(AutoFlowShapes.Border, borderColor, AutoFlowShapes.Card)
            .background(AutoFlowColors.Card)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfText(kindLabel, size = 14.sp)
            AfText(item.name, size = 13.sp, bold = true)
            Box(Modifier.weight(1f)) {}
            if (item.kind != InboxItemKind.OTHER) {
                AfButton(if (expanded) tr(UiText.CANCEL) else tr(UiText.ATTACH_SHOT),
                    { expanded = !expanded },
                    kind = ButtonKind.Secondary)
            } else {
                AfText(tr(UiText.CANNOT_ATTACH), muted = true, size = 12.sp)
            }
        }
        if (expanded && shotNumbers.isNotEmpty()) {
            AfText(tr(UiText.CHOOSE_SHOT), muted = true, size = 12.sp)
            WrapRow(spacing = 6.dp) {
                shotNumbers.forEach { n ->
                    AfButton("Shot $n", { expanded = false; onAttach(n) }, kind = ButtonKind.Secondary)
                }
            }
        } else if (expanded) {
            AfText(tr(UiText.NO_SHOTS_STORYBOARD), muted = true, size = 12.sp)
        }
    }
}

/** Files shared to Auto Flow from another app: what happened, and "Move here" for files still waiting. */
@Composable
private fun SharedCard(shared: SharedInbox, st: SharedInboxState, projectOpen: Boolean, onMoved: () -> Unit) {
    if (st.waiting.isEmpty() && st.message == null) return
    Column(
        Modifier.fillMaxWidth().clip(AutoFlowShapes.Card)
            .border(AutoFlowShapes.Border, AutoFlowColors.Cyan, AutoFlowShapes.Card)
            .background(AutoFlowColors.Card).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AfText(tr(UiText.SHARED_TO_AUTOFLOW), size = 13.sp, bold = true)
        st.message?.let { AfText(st.notice?.text(LocalUiLanguage.current) ?: it, size = 12.sp) }
        if (st.waiting.isNotEmpty()) {
            AfText(st.waiting.joinToString(" · "), muted = true, size = 11.sp)
            if (projectOpen) {
                AfButton(tr(UiText.MOVE_HERE_COUNT, "n" to st.waiting.size), { shared.moveHere(); onMoved() }, kind = ButtonKind.Primary)
            } else {
                AfText(tr(UiText.OPEN_PROJECT_FIRST), muted = true, size = 12.sp)
            }
        } else {
            AfButton(tr(UiText.OK), shared::dismissMessage, kind = ButtonKind.Secondary)
        }
    }
}
