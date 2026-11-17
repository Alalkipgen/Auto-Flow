package io.github.alalkipgen.autoflow.ui.projects

import io.github.alalkipgen.autoflow.ui.i18n.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import io.github.alalkipgen.autoflow.ui.components.AfSwitch
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

/** Rights notice of New Movie Recap (docs/specs/recap.md §1); repeated on Movie Recap and Export. */
const val RIGHTS_NOTICE = "Use only footage you own or have permission to use. Recaps of other people's movies can be " +
    "blocked, muted or demonetized by YouTube / Facebook / TikTok."
const val RIGHTS_TICK = "I own this footage or have permission to use it"

/** Dashboard (DESIGN.md §3): project list, New Music Video, New Movie Recap, Open folder. */
@Composable
fun DashboardScreen(model: DashboardModel, onOpened: () -> Unit, modifier: Modifier = Modifier, clock: Clock = Clock.System,
    createMode: Mode? = null, onCreateModeChange: ((Mode?) -> Unit)? = null,
) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    var localCreating by remember { mutableStateOf<Mode?>(null) }
    val creating = if (onCreateModeChange == null) localCreating else createMode
    val setCreating: (Mode?) -> Unit = onCreateModeChange ?: { localCreating = it }
    val compact = LocalCompact.current
    LaunchedEffect(model) { model.refresh() }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPadding).widthIn(max = 900.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // With a projects home (Android, D-27) every project lives there; there is no folder to open.
        val openFolder: @Composable () -> Unit = {
            if (model.projectsHome == null) AfButton(tr(UiText.OPEN_FOLDER), { scope.launch { if (model.openFolder()) onOpened() } })
        }
        val newMusicVideo: @Composable () -> Unit = {
            AfButton(tr(UiText.NEW_MUSIC_VIDEO), { setCreating(Mode.MUSIC_VIDEO) }, kind = ButtonKind.Primary, enabled = creating == null)
        }
        val newMovieRecap: @Composable () -> Unit = { AfButton(tr(UiText.NEW_MOVIE_RECAP), { setCreating(Mode.MOVIE_RECAP) }, enabled = creating == null) }
        // Long translations / large fonts need wrapping on desktop too, not just in the phone layout.
        AfText(tr(UiText.PROJECTS), size = 18.sp, bold = true)
        WrapRow {
            if (compact) { newMusicVideo(); openFolder(); newMovieRecap() }
            else { openFolder(); newMovieRecap(); newMusicVideo() }
        }
        state.message?.let { AfText(state.notice?.text(LocalUiLanguage.current) ?: it, color = AutoFlowColors.Amber) }
        creating?.let { mode ->
            NewProjectCard(model, mode, onCancel = { setCreating(null) }, onCreated = { setCreating(null); onOpened() })
        }
        if (state.rows.isEmpty()) {
            AfText(
                if (model.projectsHome == null) tr(UiText.PROJECTS_EMPTY_DESKTOP)
                else tr(UiText.PROJECTS_EMPTY),
                muted = true,
            )
        }
        val now = clock.now()
        state.rows.forEach { row -> ProjectRow(row, now, onOpen = { if (model.open(row.entry.path)) onOpened() }, onRemove = { model.remove(row.entry.path) }) }
    }
}

@Composable
private fun NewProjectCard(model: DashboardModel, mode: Mode, onCancel: () -> Unit, onCreated: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var parent by remember { mutableStateOf(model.lastParent.orEmpty()) }
    var rights by remember { mutableStateOf(false) }
    val home = model.projectsHome
    val recap = mode == Mode.MOVIE_RECAP
    AfCard(if (recap) tr(UiText.NEW_MOVIE_RECAP) else tr(UiText.NEW_MUSIC_VIDEO)) {
        AfTextField(name, { name = it }, tr(UiText.PROJECT_NAME))
        if (recap) {
            AfText(tr(UiText.RIGHTS_NOTICE), color = AutoFlowColors.Amber, size = 12.sp)
            AfSwitch(tr(UiText.RIGHTS_TICK), rights, { rights = it })
        }
        if (home == null) {
            FieldRow(
                field = { AfTextField(parent, { parent = it }, tr(UiText.PROJECT_PARENT), it) },
                actions = { AfButton(tr(UiText.BROWSE), { scope.launch { model.pickParent()?.let { parent = it } } }) },
            )
        } else {
            AfText(tr(UiText.APP_STORAGE_BACKUP), muted = true, size = 12.sp)
        }
        if (home == null && name.isNotBlank() && parent.isNotBlank()) {
            AfText(tr(UiText.CREATES_PATH, "trimEndprojectFolder" to (joinPath(parent.trimEnd('/'), projectFolderName(name)))), muted = true, size = 12.sp, mono = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfButton(tr(UiText.CREATE), {
                val ok = if (recap) model.createMovieRecap(parent, name, rights) else model.createMusicVideo(parent, name)
                if (ok) onCreated()
            }, kind = ButtonKind.Primary, enabled = name.isNotBlank() && parent.isNotBlank() && (!recap || rights))
            AfButton(tr(UiText.CANCEL), onCancel)
        }
    }
}

@Composable
private fun ProjectRow(row: DashboardRow, now: Instant, onOpen: () -> Unit, onRemove: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { confirmDelete = false }) {
            androidx.compose.foundation.layout.Column(
                androidx.compose.ui.Modifier
                    .fillMaxWidth(0.85f)
                    .clip(AutoFlowShapes.Card)
                    .background(AutoFlowColors.Panel)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AfText(tr(UiText.REMOVE_PROJECT_QUESTION, "name" to (row.entry.name)), bold = true)
                AfText(tr(UiText.REMOVE_PROJECT_HINT), muted = true)
                WrapRow {
                    AfButton(tr(UiText.REMOVE), { onRemove(); confirmDelete = false }, kind = ButtonKind.Danger)
                    AfButton(tr(UiText.CANCEL), { confirmDelete = false })
                }
            }
        }
    }
    val e = row.entry
    val box = Modifier
        .fillMaxWidth()
        .clip(AutoFlowShapes.Card)
        .background(AutoFlowColors.Panel)
        .border(AutoFlowShapes.Border, AutoFlowColors.Line, AutoFlowShapes.Card)
        .combinedClickable(
            enabled = !row.missing,
            role = Role.Button,
            onClick = onOpen,
            onLongClick = { confirmDelete = true },
        )
        .padding(horizontal = 16.dp, vertical = 12.dp)
    val mode = if (e.mode == Mode.MUSIC_VIDEO) tr(UiText.MUSIC_VIDEO) else tr(UiText.MOVIE_RECAP)
    val shots = tr(UiText.PROJECT_SHOTS, "acceptedShots" to (e.acceptedShots), "totalShots" to (e.totalShots))
    val shotsColor = if (e.totalShots > 0 && e.acceptedShots == e.totalShots) AutoFlowColors.Green else AutoFlowColors.Muted
    if (LocalCompact.current) {
        // Phone: name, then one wrapped line of facts; the folder path is left out (it is app storage, D-27).
        Column(box, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AfText(e.name, bold = true, muted = row.missing, size = 15.sp)
            if (row.missing) {
                AfText(e.path, muted = true, size = 11.sp, mono = true)
                WrapRow {
                    AfText(tr(UiText.FOLDER_NOT_FOUND), color = AutoFlowColors.Amber)
                    AfButton(tr(UiText.REMOVE_FROM_LIST), onRemove, kind = ButtonKind.Danger)
                }
            } else {
                WrapRow(spacing = 10.dp) {
                    AfText(mode, muted = true)
                    AfText(e.ratio.label, muted = true)
                    AfText(shots, color = shotsColor)
                    AfText(relativeTime(e.lastOpenedAt, now, LocalUiLanguage.current), muted = true)
                    // Long-press the row to remove. No visible delete button — cleaner UI.
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
            AfText(tr(UiText.FOLDER_NOT_FOUND), color = AutoFlowColors.Amber)
            AfButton(tr(UiText.REMOVE_FROM_LIST), onRemove, kind = ButtonKind.Danger)
        } else {
            AfText(mode, muted = true)
            AfText(e.ratio.label, muted = true)
            AfText(shots, color = shotsColor)
            AfText(relativeTime(e.lastOpenedAt, now, LocalUiLanguage.current), muted = true)
        }
    }
}

/** "just now", "5 min ago", "3 h ago", "2 days ago", else the UTC date; no time-zone library needed. */
fun relativeTime(then: Instant, now: Instant, language: UiLanguage = UiLanguage.ENGLISH): String {
    val d: Duration = now - then
    return when {
        d.inWholeMinutes < 1 -> UiText.JUST_NOW.text(language)
        d.inWholeHours < 1 -> UiText.MINUTES_AGO.text(language, "n" to d.inWholeMinutes)
        d.inWholeDays < 1 -> UiText.HOURS_AGO.text(language, "n" to d.inWholeHours)
        d.inWholeDays < 30 -> if (d.inWholeDays == 1L) UiText.YESTERDAY.text(language) else UiText.DAYS_AGO.text(language, "n" to d.inWholeDays)
        else -> then.toString().take(10)
    }
}
