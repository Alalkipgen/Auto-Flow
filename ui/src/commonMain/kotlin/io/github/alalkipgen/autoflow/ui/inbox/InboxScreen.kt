package io.github.alalkipgen.autoflow.ui.inbox

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
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.input.NO_PROJECT_TEXT
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes

/** Inbox screen: lists `<project>/inbox/` files; lets the user attach a video or image to a shot. */
@Composable
fun InboxScreen(model: InboxModel, modifier: Modifier = Modifier) {
    val state by model.state.collectAsState(model.current())
    var items by remember { mutableStateOf(emptyList<InboxItem>()) }
    var localMsg by remember { mutableStateOf<String?>(null) }

    // Refresh on first composition and when project changes
    LaunchedEffect(state.projectName) {
        val refreshed = model.refresh()
        items = refreshed.items
        localMsg = refreshed.message
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPadding).widthIn(max = 860.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AfText("Inbox", size = 18.sp, bold = true)
            AfButton("↻ Refresh", {
                val r = model.refresh(); items = r.items; localMsg = r.message
            }, kind = ButtonKind.Secondary)
        }
        state.inboxDir?.let { AfText("Folder: $it", muted = true, size = 11.sp) }
        AfText("Drop MP4/MOV videos or PNG/JPG images here, then attach them to a shot.", muted = true, size = 12.sp)

        localMsg?.let { AfText(it, color = AutoFlowColors.Amber) }

        when {
            state.projectName == null -> AfText(NO_PROJECT_TEXT, muted = true)
            items.isEmpty() -> AfText("Inbox is empty. Copy files into the inbox folder shown above.", muted = true)
            else -> {
                items.forEach { item ->
                    InboxItemRow(item, state.shotNumbers) { shotNumber ->
                        model.attach(item, shotNumber)
                        val r = model.refresh(); items = r.items; localMsg = r.message
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
                AfButton(if (expanded) "Cancel" else "Attach to Shot…",
                    { expanded = !expanded },
                    kind = ButtonKind.Secondary)
            } else {
                AfText("Cannot attach", muted = true, size = 12.sp)
            }
        }
        if (expanded && shotNumbers.isNotEmpty()) {
            AfText("Choose shot:", muted = true, size = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                shotNumbers.forEach { n ->
                    AfButton("Shot $n", { expanded = false; onAttach(n) }, kind = ButtonKind.Secondary)
                }
            }
        } else if (expanded) {
            AfText("No shots yet — generate a storyboard first.", muted = true, size = 12.sp)
        }
    }
}
