package io.github.alalkipgen.autoflow.ui.inbox

import io.github.alalkipgen.autoflow.ui.components.paneScroll
import io.github.alalkipgen.autoflow.ui.i18n.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.CardRemoval
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes

/** Inbox screen: lists `<project>/inbox/` files; lets the user attach a video or image to a shot. */
@Composable
fun InboxScreen(model: InboxModel, modifier: Modifier = Modifier, shared: SharedInbox? = null) {
    val state by model.state.collectAsState(model.current())
    val project = state.inboxDir to state.projectId
    var scanned by remember(model, project) { mutableStateOf(InboxUiState()) }
    var deletedNotice by remember(model, shared, project) { mutableStateOf<UiNotice?>(null) }
    val sharedState = shared?.state?.collectAsState()?.value

    fun refresh() {
        shared?.refresh()
        scanned = model.refresh()
    }
    fun deletionResult(name: String, deleted: Boolean) {
        refresh()
        deletedNotice = UiNotice(if (deleted) UiText.INBOX_DELETE_DONE else UiText.INBOX_DELETE_FAILED, "name" to name)
    }
    // Refresh on first composition, when the project changes and when a share arrives (share-sheet import).
    LaunchedEffect(model, shared, project, sharedState?.arrivals) { refresh() }

    Column(
        modifier.fillMaxSize().paneScroll(rememberScrollState()).padding(screenPadding).widthIn(max = 860.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WrapRow {
            AfText(tr(UiText.INBOX), size = 18.sp, bold = true)
            AfButton(tr(UiText.REFRESH), ::refresh)
        }
        state.inboxDir?.let { AfText(tr(UiText.FOLDER_PATH, "it" to (it)), muted = true, size = 11.sp) }
        AfText(tr(UiText.INBOX_HELP), muted = true, size = 12.sp)

        scanned.message?.let { AfText(scanned.notice?.text(LocalUiLanguage.current) ?: it, color = AutoFlowColors.Amber) }
        deletedNotice?.let {
            AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (shared != null && sharedState != null) {
            key(shared, project) {
                SharedCard(shared, sharedState, state.projectId != null, ::deletionResult, ::refresh)
            }
        }

        when {
            state.projectId == null -> AfText(tr(UiText.OPEN_PROJECT_FIRST), muted = true)
            scanned.items.isEmpty() -> AfText(tr(UiText.INBOX_EMPTY), muted = true)
            else -> {
                scanned.items.forEach { item ->
                    key(model, item.projectId, item.path) {
                        InboxRemoval("inbox-file-${item.name}", item.name,
                            prepare = if (model.canDelete) ({ model.deleteRequest(item) }) else null,
                            onResult = ::deletionResult,
                        ) { modifier ->
                            InboxItemRow(item, state.shotNumbers, modifier) { shotNumber ->
                                model.attach(item, shotNumber)
                                refresh()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxRemoval(
    tag: String,
    name: String,
    prepare: (() -> InboxDeleteRequest?)?,
    onResult: (String, Boolean) -> Unit,
    card: @Composable (Modifier) -> Unit,
) {
    val tagged = Modifier.testTag(tag)
    if (prepare == null) {
        card(tagged)
        return
    }
    val title = tr(UiText.INBOX_DELETE_TITLE, "name" to name)
    CardRemoval(
        tag, UiText.CARD_DELETE, title, UiText.INBOX_DELETE_BODY,
        prepareRemoval = {
            val request = prepare()
            if (request == null) { onResult(name, false); null }
            else ({ onResult(request.name, request.confirm()) })
        },
        accessibilityLabel = title,
    ) { card(tagged.then(it)) }
}

@Composable
private fun InboxItemRow(
    item: InboxItem,
    shotNumbers: List<Int>,
    modifier: Modifier,
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
        modifier
            .fillMaxWidth()
            .clip(AutoFlowShapes.Card)
            .border(AutoFlowShapes.Border, borderColor, AutoFlowShapes.Card)
            .background(AutoFlowColors.Card)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfText(kindLabel, size = 14.sp)
            AfText(My.breakSyllables(item.name), Modifier.weight(1f), size = 13.sp, bold = true)
        }
        WrapRow(spacing = 6.dp) {
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
private fun SharedCard(
    shared: SharedInbox, st: SharedInboxState, projectOpen: Boolean,
    onDeleteResult: (String, Boolean) -> Unit, onMoved: () -> Unit,
) {
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
            st.waiting.forEach { name ->
                key(name) {
                    InboxRemoval("shared-file-$name", name,
                        prepare = if (shared.canDelete) ({ shared.deleteRequest(name) }) else null,
                        onResult = onDeleteResult,
                    ) { modifier ->
                        Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            AfText(My.breakSyllables(name), muted = true, size = 11.sp)
                        }
                    }
                }
            }
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
