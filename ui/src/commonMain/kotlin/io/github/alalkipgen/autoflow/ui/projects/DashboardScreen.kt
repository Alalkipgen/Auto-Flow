package io.github.alalkipgen.autoflow.ui.projects

import io.github.alalkipgen.autoflow.ui.components.paneScroll
import io.github.alalkipgen.autoflow.ui.i18n.*
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.platform.ProjectFolderDeletion
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfCard
import io.github.alalkipgen.autoflow.ui.components.AfSwitch
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.AfTextField
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.FieldRow
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.components.SectionCard
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
    var showRemoved by remember { mutableStateOf(false) }
    if (showRemoved && state.removed.isNotEmpty()) RemovedProjectsDialog(state.removed, model) { showRemoved = false }
    LaunchedEffect(model) { model.refresh() }
    Column(
        modifier.fillMaxSize().background(AutoFlowColors.Background).paneScroll(rememberScrollState()).padding(screenPadding).widthIn(max = 900.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // With a projects home (Android, D-27) every project lives there; there is no folder to open.
        val openFolder: @Composable () -> Unit = {
            if (model.projectsHome == null) AfButton(tr(UiText.OPEN_FOLDER), { scope.launch { if (model.openFolder()) onOpened() } })
        }
        val newMusicVideo: @Composable () -> Unit = {
            AfButton(tr(UiText.NEW_MUSIC_VIDEO), { setCreating(Mode.MUSIC_VIDEO) }, kind = ButtonKind.Primary, enabled = creating == null)
        }
        // F-7 (D-96): a render package (or backup zip) from another phone or computer.
        val openPackage: @Composable () -> Unit = {
            if (model.opensPackages) AfButton(My.breakSyllables(tr(UiText.PACKAGE_OPEN)), { scope.launch { if (model.openPackage()) onOpened() } },
                Modifier.testTag("projects-open-package"), enabled = creating == null)
        }
        val newMovieRecap: @Composable () -> Unit = { AfButton(tr(UiText.NEW_MOVIE_RECAP), { setCreating(Mode.MOVIE_RECAP) }, enabled = creating == null) }
        // Long translations / large fonts need wrapping on desktop too, not just in the phone layout.
        AfText(tr(UiText.PROJECTS), Modifier.testTag("projects-heading"), size = 20.sp, bold = true)
        SectionCard(tr(UiText.V3_PROJECT_CREATE), Modifier.testTag("projects-create")) {
            WrapRow {
                if (compact) { newMusicVideo(); openFolder(); newMovieRecap(); openPackage() }
                else { openFolder(); newMovieRecap(); newMusicVideo(); openPackage() }
            }
        }
        state.message?.let { AfText(My.breakSyllables(state.notice?.text(LocalUiLanguage.current) ?: it), color = AutoFlowColors.Amber) }
        // Keep the existing form mounted when its action section is collapsed or translated.
        creating?.let { mode ->
            NewProjectCard(model, mode, onCancel = { setCreating(null) }, onCreated = { setCreating(null); onOpened() })
        }
        if (state.removed.isNotEmpty()) AfButton(My.breakSyllables(tr(UiText.OPEN_REMOVED_PROJECT)), { showRemoved = true }, Modifier.testTag("projects-open-removed"))
        SectionCard(tr(UiText.HOME_RECENT), Modifier.testTag("projects-recent")) {
            if (state.rows.isEmpty()) {
                AfText(
                    if (model.projectsHome == null) tr(UiText.PROJECTS_EMPTY_DESKTOP)
                    else tr(UiText.PROJECTS_EMPTY),
                    muted = true,
                )
            }
            val now = clock.now()
            state.rows.forEach { row ->
                key(row.entry.path) {
                    ProjectRow(row, now, onOpen = { if (model.open(row.entry.path)) onOpened() },
                        onRemove = { model.remove(row.entry.path) }, managed = model.projectsHome != null)
                }
            }
            state.newer.forEach { row ->
                key("newer:" + row.path) {
                    AfCard(row.name, Modifier.fillMaxWidth().testTag("projects-newer-${row.name}")) {
                        AfText(My.breakSyllables(tr(UiText.NEEDS_NEWER_APP)), muted = true)
                    }
                }
            }
        }
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
        WrapRow {
            AfButton(tr(UiText.CREATE), {
                val ok = if (recap) model.createMovieRecap(parent, name, rights) else model.createMusicVideo(parent, name)
                if (ok) onCreated()
            }, kind = ButtonKind.Primary, enabled = name.isNotBlank() && parent.isNotBlank() && (!recap || rights))
            AfButton(tr(UiText.CANCEL), onCancel)
        }
    }
}

@Composable
private fun RemovedProjectsDialog(rows: List<RemovedProject>, model: DashboardModel, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<RemovedProject?>(null) }
    var inspection by remember { mutableStateOf<ProjectFolderDeletion.Result?>(null) }
    var target by remember { mutableStateOf<ProjectFolderDeletion.Target?>(null) }
    var error by remember { mutableStateOf<UiText?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val row = selected
    val confirm = target
    fun back() {
        if (deleting) return
        error = null
        when {
            confirm != null -> target = null
            row != null -> selected = null
            else -> close()
        }
    }
    Dialog(onDismissRequest = ::back) {
        val title = when {
            confirm != null -> tr(UiText.PROJECT_DELETE_TITLE, "name" to confirm.name)
            row != null -> row.name
            else -> tr(UiText.OPEN_REMOVED_PROJECT)
        }
        AfCard(My.breakSyllables(title)) {
            key(row, confirm) {
                Column(Modifier.fillMaxWidth().heightIn(max = 380.dp).paneScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        confirm != null -> {
                            AfText(confirm.folder, mono = true)
                            AfText(My.breakSyllables(tr(UiText.PROJECT_DELETE_BYTES, "n" to confirm.bytes)))
                            if (confirm.backups.isNotEmpty()) AfText(My.breakSyllables(tr(UiText.PROJECT_DELETE_KEEP_BACKUPS)), muted = true)
                            AfText(My.breakSyllables(tr(UiText.PROJECT_DELETE_PERMANENT)), color = AutoFlowColors.Failed)
                            AfButton(My.breakSyllables(tr(UiText.PROJECT_DELETE_CONFIRM)), {
                                deleting = true
                                scope.launch {
                                    try {
                                        error = model.deleteProject(confirm)
                                        if (error == null) { target = null; selected = null; close() }
                                    } finally { deleting = false }
                                }
                            }, Modifier.testTag("project-delete-confirm"), kind = ButtonKind.Danger, enabled = !deleting)
                        }
                        row != null -> {
                            AfText(row.dir, mono = true)
                            val ready = inspection as? ProjectFolderDeletion.Result.Ready
                            val refused = inspection as? ProjectFolderDeletion.Result.Refused
                            refused?.let { AfText(My.breakSyllables(tr(it.reason.label())), muted = true) }
                            AfButton(My.breakSyllables(tr(UiText.PROJECT_RESTORE)), {
                                error = model.restoreRemoved(row)
                                if (error == null) close()
                            }, Modifier.testTag("project-restore"))
                            if (ready != null) AfButton(My.breakSyllables(tr(UiText.PROJECT_DELETE_MENU)), {
                                val latest = model.inspectDeletion(row)
                                inspection = latest
                                target = (latest as? ProjectFolderDeletion.Result.Ready)?.target
                                error = null
                            }, Modifier.testTag("project-delete-action"), kind = ButtonKind.Danger)
                        }
                        else -> rows.forEach { removed ->
                            AfButton(My.breakSyllables(removed.name + " · " + removed.dir.substringAfterLast('/')), {
                                selected = removed
                                inspection = model.inspectDeletion(removed)
                            })
                        }
                    }
                    error?.let { AfText(My.breakSyllables(tr(it)), color = AutoFlowColors.Failed) }
                    AfButton(My.breakSyllables(tr(UiText.CANCEL)), ::back, Modifier.testTag("removed-projects-cancel"), enabled = !deleting)
                }
            }
        }
    }
}

@Composable
private fun ProjectRow(row: DashboardRow, now: Instant, onOpen: () -> Unit, onRemove: () -> Unit, managed: Boolean) {
    var options by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    if (options) Dialog(onDismissRequest = { options = false }) {
        AfCard(tr(UiText.PROJECT_ACTIONS)) {
            if (!managed) AfText(My.breakSyllables(tr(UiText.PROJECT_DELETE_UNSUPPORTED)), muted = true)
            WrapRow {
                AfButton(tr(UiText.REMOVE_FROM_LIST), { options = false; confirmRemove = true }, Modifier.testTag("project-remove-action"))
                AfButton(tr(UiText.CANCEL), { options = false })
            }
        }
    }
    if (confirmRemove) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { confirmRemove = false }) {
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
                    AfButton(tr(UiText.REMOVE), { onRemove(); confirmRemove = false }, kind = ButtonKind.Danger)
                    AfButton(tr(UiText.CANCEL), { confirmRemove = false })
                }
            }
        }
    }
    val e = row.entry
    val box = Modifier
        .fillMaxWidth()
        .combinedClickable(
            enabled = !row.missing,
            role = Role.Button,
            onClick = onOpen,
            onLongClick = { options = true },
        )
    val mode = tr(e.mode.label())
    val shots = tr(UiText.PROJECT_SHOTS, "acceptedShots" to (e.acceptedShots), "totalShots" to (e.totalShots))
    val shotsColor = if (e.totalShots > 0 && e.acceptedShots == e.totalShots) AutoFlowColors.Green else AutoFlowColors.Muted
    AfCard(e.name, box) {
        // Same row actions at every width. Facts wrap instead of competing with a weighted title.
        if (!LocalCompact.current || row.missing) {
            AfText(e.path, muted = true, size = 11.sp, mono = true)
        }
        if (row.missing) {
            WrapRow {
                AfText(tr(UiText.FOLDER_NOT_FOUND), color = AutoFlowColors.Amber)
                AfButton(tr(UiText.REMOVE_FROM_LIST), onRemove, kind = ButtonKind.Danger)
            }
        } else {
            WrapRow(spacing = 10.dp) {
                AfText(mode, muted = true)
                AfText(e.ratio.label, muted = true)
                if (e.mode.hasShots()) AfText(shots, color = shotsColor)
                AfText(relativeTime(e.lastOpenedAt, now, LocalUiLanguage.current), muted = true)
            }
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
