package io.github.alalkipgen.autoflow.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfCard
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.AfTextField
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.FieldRow
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.launch

/** Dashboard (DESIGN.md §3): project list, New Music Video, New Movie Recap (Phase 4), Open folder. */
@Composable
fun DashboardScreen(model: DashboardModel, onOpened: () -> Unit, modifier: Modifier = Modifier, clock: Clock = Clock.System) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    val compact = LocalCompact.current
    LaunchedEffect(model) { model.refresh() }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPadding).widthIn(max = 900.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // With a projects home (Android, D-27) every project lives there; there is no folder to open.
        val openFolder: @Composable () -> Unit = {
            if (model.projectsHome == null) AfButton("Open folder…", { scope.launch { if (model.openFolder()) onOpened() } })
        }
        val newMusicVideo: @Composable () -> Unit = {
            AfButton("New Music Video", { creating = true }, kind = ButtonKind.Primary, enabled = !creating)
        }
        val newMovieRecap: @Composable () -> Unit = { AfButton("New Movie Recap (Phase 4)", {}, enabled = false) }
        if (compact) {
            // Phone: the title on its own line, the main action first, the rest wrapping below it.
            AfText("Projects", size = 18.sp, bold = true)
            WrapRow { newMusicVideo(); openFolder(); newMovieRecap() }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AfText("Projects", size = 18.sp, bold = true)
                Spacer(Modifier.weight(1f))
                openFolder()
                newMovieRecap()
                newMusicVideo()
            }
        }
        state.message?.let { AfText(it, color = AutoFlowColors.Amber) }
        if (creating) {
            NewProjectCard(model, onCancel = { creating = false }, onCreated = { creating = false; onOpened() })
        }
        if (state.rows.isEmpty()) {
            AfText(
                if (model.projectsHome == null) "No projects yet. Create a music video, or open an existing .autoflow folder."
                else "No projects yet. Create a music video.",
                muted = true,
            )
        }
        val now = clock.now()
        state.rows.forEach { row -> ProjectRow(row, now, onOpen = { if (model.open(row.entry.path)) onOpened() }, onRemove = { model.remove(row.entry.path) }) }
    }
}

@Composable
private fun NewProjectCard(model: DashboardModel, onCancel: () -> Unit, onCreated: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var parent by remember { mutableStateOf(model.lastParent.orEmpty()) }
    val home = model.projectsHome
    AfCard("New Music Video") {
        AfTextField(name, { name = it }, "Project name")
        if (home == null) {
            FieldRow(
                field = { AfTextField(parent, { parent = it }, "Folder to save it in", it) },
                actions = { AfButton("Browse…", { scope.launch { model.pickParent()?.let { parent = it } } }) },
            )
        } else {
            AfText("Saved in this app's storage. Back it up in Settings → Backup before uninstalling.", muted = true, size = 12.sp)
        }
        if (home == null && name.isNotBlank() && parent.isNotBlank()) {
            AfText("Creates ${joinPath(parent.trimEnd('/'), projectFolderName(name))}", muted = true, size = 12.sp, mono = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfButton("Create", { if (model.createMusicVideo(parent, name)) onCreated() }, kind = ButtonKind.Primary,
                enabled = name.isNotBlank() && parent.isNotBlank())
            AfButton("Cancel", onCancel)
        }
    }
}

@Composable
private fun ProjectRow(row: DashboardRow, now: Instant, onOpen: () -> Unit, onRemove: () -> Unit) {
    val e = row.entry
    val box = Modifier
        .fillMaxWidth()
        .clip(AutoFlowShapes.Card)
        .background(AutoFlowColors.Panel)
        .border(AutoFlowShapes.Border, AutoFlowColors.Line, AutoFlowShapes.Card)
        .clickable(enabled = !row.missing, role = Role.Button, onClick = onOpen)
        .padding(horizontal = 16.dp, vertical = 12.dp)
    val mode = if (e.mode == Mode.MUSIC_VIDEO) "Music Video" else "Movie Recap"
    val shots = "${e.acceptedShots}/${e.totalShots} shots"
    val shotsColor = if (e.totalShots > 0 && e.acceptedShots == e.totalShots) AutoFlowColors.Green else AutoFlowColors.Muted
    if (LocalCompact.current) {
        // Phone: name, then one wrapped line of facts; the folder path is left out (it is app storage, D-27).
        Column(box, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AfText(e.name, bold = true, muted = row.missing, size = 15.sp)
            if (row.missing) {
                AfText(e.path, muted = true, size = 11.sp, mono = true)
                WrapRow {
                    AfText("Folder not found", color = AutoFlowColors.Amber)
                    AfButton("Remove from list", onRemove, kind = ButtonKind.Danger)
                }
            } else {
                WrapRow(spacing = 10.dp) {
                    AfText(mode, muted = true)
                    AfText(e.ratio.label, muted = true)
                    AfText(shots, color = shotsColor)
                    AfText(relativeTime(e.lastOpenedAt, now), muted = true)
                }
            }
        }
        return
    }
    Row(box, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AfText(e.name, bold = true, muted = row.missing)
            AfText(e.path, muted = true, size = 11.sp, mono = true)
        }
        if (row.missing) {
            AfText("Folder not found", color = AutoFlowColors.Amber)
            AfButton("Remove from list", onRemove, kind = ButtonKind.Danger)
        } else {
            AfText(mode, muted = true)
            AfText(e.ratio.label, muted = true)
            AfText(shots, color = shotsColor)
            AfText(relativeTime(e.lastOpenedAt, now), muted = true)
        }
    }
}

/** "just now", "5 min ago", "3 h ago", "2 days ago", else the UTC date; no time-zone library needed. */
fun relativeTime(then: Instant, now: Instant): String {
    val d: Duration = now - then
    return when {
        d.inWholeMinutes < 1 -> "just now"
        d.inWholeHours < 1 -> "${d.inWholeMinutes} min ago"
        d.inWholeDays < 1 -> "${d.inWholeHours} h ago"
        d.inWholeDays < 30 -> if (d.inWholeDays == 1L) "yesterday" else "${d.inWholeDays} days ago"
        else -> then.toString().take(10)
    }
}
