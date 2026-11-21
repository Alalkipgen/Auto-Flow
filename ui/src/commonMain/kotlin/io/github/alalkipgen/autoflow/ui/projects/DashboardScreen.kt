package io.github.alalkipgen.autoflow.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.platform.ProjectFolderDeletion
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.icons.IconTile
import io.github.alalkipgen.autoflow.ui.jobs.JobRow
import io.github.alalkipgen.autoflow.ui.media.MediaThumbnails
import io.github.alalkipgen.autoflow.ui.theme.*
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.launch

/** Rights notice of New Movie Recap (docs/specs/recap.md §1); repeated on Movie Recap and Export. */
const val RIGHTS_NOTICE = "Use only footage you own or have permission to use. Recaps of other people's movies can be " +
    "blocked, muted or demonetized by YouTube / Facebook / TikTok."
const val RIGHTS_TICK = "I own this footage or have permission to use it"

/**
 * Projects (DESIGN.md §9.7, panel 2): title with the project count and "+" (opens the create card — open by itself
 * while the list is empty), filter chips All + one per mode with projects, thumbnail rows (mode chip, a status chip
 * only for a running / failed job of that project, relative date · ratio · shots, ⋮ = the existing options; long-press
 * and right-click open them too), the removed-projects row and Open package at the bottom. One column ≤ 720 dp.
 * [createOpen] / [onCreateOpenChange] let the shell open the create card (sidebar "New project"); [jobs] are the
 * Jobs rows; [thumbSource] reads a project's recorded source for its thumbnail (read-only, off the main thread).
 */
@Composable
fun DashboardScreen(model: DashboardModel, onOpened: () -> Unit, modifier: Modifier = Modifier, clock: Clock = Clock.System,
    createMode: Mode? = null, onCreateModeChange: ((Mode?) -> Unit)? = null,
    createOpen: Boolean = false, onCreateOpenChange: ((Boolean) -> Unit)? = null,
    jobs: List<JobRow> = emptyList(),
    thumbSource: (suspend (dir: String) -> MediaThumbnails.Source?)? = null,
) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    var localCreating by remember { mutableStateOf<Mode?>(null) }
    val creating = if (onCreateModeChange == null) localCreating else createMode
    val setCreating: (Mode?) -> Unit = onCreateModeChange ?: { localCreating = it }
    var localOpen by remember { mutableStateOf(false) }
    val open = if (onCreateOpenChange == null) localOpen else createOpen
    val setOpen: (Boolean) -> Unit = onCreateOpenChange ?: { localOpen = it }
    val compact = LocalCompact.current
    var showRemoved by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf<Mode?>(null) }
    if (showRemoved && state.removed.isNotEmpty()) RemovedProjectsDialog(state.removed, model) { showRemoved = false }
    LaunchedEffect(model) { model.refresh() }
    val empty = state.rows.isEmpty() && state.newer.isEmpty()
    val modes = Mode.entries.filter { m -> state.rows.any { it.entry.mode == m } }
    val active = filter?.takeIf { it in modes }
    Box(modifier.fillMaxSize().background(AutoFlowColors.Background), contentAlignment = Alignment.TopCenter) {
      Column(Modifier.widthIn(max = AfSize.ContentMax).fillMaxSize().paneScroll(rememberScrollState()).padding(screenPadding),
        verticalArrangement = Arrangement.spacedBy(AfSpace.L),
      ) {
        // With a projects home (Android, D-27) every project lives there; there is no folder to open.
        val openFolder: @Composable () -> Unit = {
            if (model.projectsHome == null) AfButton(tr(UiText.OPEN_FOLDER), { scope.launch { if (model.openFolder()) onOpened() } })
        }
        val newMusicVideo: @Composable () -> Unit = {
            AfButton(tr(UiText.NEW_MUSIC_VIDEO), { setCreating(Mode.MUSIC_VIDEO) }, kind = ButtonKind.Primary, enabled = creating == null)
        }
        val newMovieRecap: @Composable () -> Unit = { AfButton(tr(UiText.NEW_MOVIE_RECAP), { setCreating(Mode.MOVIE_RECAP) }, enabled = creating == null) }
        ProjectsHeader(state.rows.size, showCreate = !empty, createOpen = open) { setOpen(!open) }
        // The create card: "+" opens it; with no project yet it is the first thing on the screen.
        if (open || empty) AfCard(tr(UiText.V3_PROJECT_CREATE), Modifier.testTag("projects-create")) {
            WrapRow {
                if (compact) { newMusicVideo(); openFolder(); newMovieRecap() }
                else { openFolder(); newMovieRecap(); newMusicVideo() }
            }
        }
        state.message?.let { AfText(My.breakSyllables(state.notice?.text(LocalUiLanguage.current) ?: it), color = AutoFlowColors.Amber) }
        // Keep the existing form mounted when the create card is closed or translated.
        creating?.let { mode ->
            NewProjectCard(model, mode, onCancel = { setCreating(null) }, onCreated = { setCreating(null); onOpened() })
        }
        if (state.rows.isNotEmpty()) {
            val chips = listOf(FilterChipItem(tr(UiText.PROJECTS_FILTER_ALL), state.rows.size)) +
                modes.map { m -> FilterChipItem(tr(m.label()), state.rows.count { it.entry.mode == m }) }
            FilterChipRow(chips, active?.let { modes.indexOf(it) + 1 } ?: 0, { i -> filter = modes.getOrNull(i - 1) },
                Modifier.testTag("projects-filters"))
        }
        Column(Modifier.fillMaxWidth().testTag("projects-recent"), verticalArrangement = Arrangement.spacedBy(AfSpace.CardGap)) {
            if (state.rows.isEmpty()) {
                EmptyState(AfIcons.Duotone.Folder, My.breakSyllables(
                    if (model.projectsHome == null) tr(UiText.PROJECTS_EMPTY_DESKTOP) else tr(UiText.PROJECTS_EMPTY)), null)
            }
            val now = clock.now()
            state.rows.filter { active == null || it.entry.mode == active }.forEach { row ->
                key(row.entry.path) {
                    ProjectRow(row, now, projectStatus(row.entry.path, jobs), thumbSource,
                        onOpen = { if (model.open(row.entry.path)) onOpened() },
                        onRemove = { model.remove(row.entry.path) }, managed = model.projectsHome != null)
                }
            }
            if (active == null) state.newer.forEach { row ->
                key("newer:" + row.path) {
                    AfCard(row.name, Modifier.fillMaxWidth().testTag("projects-newer-${row.name}")) {
                        AfText(My.breakSyllables(tr(UiText.NEEDS_NEWER_APP)), muted = true)
                    }
                }
            }
        }
        if (state.removed.isNotEmpty()) RemovedRow(state.removed.size) { showRemoved = true }
        // F-7 (D-96): a render package (or backup zip) from another phone or computer.
        if (model.opensPackages) AfButton(My.breakSyllables(tr(UiText.PACKAGE_OPEN)), { scope.launch { if (model.openPackage()) onOpened() } },
            Modifier.fillMaxWidth().testTag("projects-open-package"), enabled = creating == null)
      }
    }
}

/** Title + "<n> projects" and the "+" that opens / closes the create card (hidden while the list is empty). */
@Composable
private fun ProjectsHeader(count: Int, showCreate: Boolean, createOpen: Boolean, onToggleCreate: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("projects-header"), verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(AfSpace.M)) {
        Column(Modifier.weight(1f)) {
            AfText(tr(UiText.PROJECTS), Modifier.testTag("projects-heading"), type = AfType.ScreenTitle)
            if (count > 0) AfText(My.breakSyllables(tr(UiText.MANY_PROJECTS, "projects" to count)), Modifier.testTag("projects-count"),
                color = AutoFlowColors.Muted, type = AfType.Caption)
        }
        if (showCreate) AfIconButton(AfIcons.Regular.Plus, tr(UiText.V3_PROJECT_CREATE), onToggleCreate, Modifier.testTag("projects-new"),
            selected = createOpen)
    }
}

/** Status chip from a real job of the project (Jobs rows): running, queued, failed or paused; none otherwise. */
internal fun projectStatus(dir: String, jobs: List<JobRow>): StudioStatus? {
    val mine = jobs.filter { it.projectDir == dir }.map { it.status }
    return listOf(StudioStatus.RUNNING, StudioStatus.QUEUED, StudioStatus.FAILED, StudioStatus.PAUSED).firstOrNull { it in mine }
}

/** Dashed row that opens the existing removed-projects list (no retention text: nothing is purged by time). */
@Composable
private fun RemovedRow(count: Int, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(AfRadius.ListCard)
    val label = My.breakSyllables(tr(UiText.OPEN_REMOVED_PROJECT))
    Row(Modifier.fillMaxWidth().heightIn(min = AfSize.Touch).clip(shape)
        .clickable(interaction, null, role = Role.Button, onClick = onClick)
        .drawBehind {
            val dash = AfSpace.S.toPx() * .75f
            drawRoundRect(AutoFlowColors.LineStrong, cornerRadius = CornerRadius(AfRadius.ListCard.toPx()),
                style = Stroke(AutoFlowShapes.Border.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * .67f))))
        }
        .interactionOverlay(interaction, shape).padding(AfSpace.M).testTag("projects-open-removed"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AfSpace.M)) {
        IconTile(AfIcons.Regular.Redo, AutoFlowColors.Surface3, size = AfSize.TileSmall, radius = AfRadius.TileSmall, ink = AutoFlowColors.Text)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AfText(label, type = AfType.RowTitle)
            AfText(My.breakSyllables(tr(UiText.MANY_PROJECTS, "projects" to count)), color = AutoFlowColors.Muted, type = AfType.Caption)
        }
        RowChevron()
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

/**
 * A project row: thumbnail 100 × 62 (the recorded duration as badge), 1-line title, mode chip + [status], relative date
 * · ratio · shots, and ⋮ (the existing options; long-press and right-click open them too). A tap opens the project; a
 * missing folder shows "Folder not found" with Remove from list instead. Large fonts: thumbnail and ⋮ above the text.
 */
@Composable
private fun ProjectRow(row: DashboardRow, now: Instant, status: StudioStatus?, thumbSource: (suspend (String) -> MediaThumbnails.Source?)?,
    onOpen: () -> Unit, onRemove: () -> Unit, managed: Boolean) {
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
        Dialog(onDismissRequest = { confirmRemove = false }) {
            Column(
                Modifier
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
    val source by produceState<MediaThumbnails.Source?>(null, e.path, e.lastOpenedAt, row.missing, thumbSource) {
        value = if (row.missing) null else thumbSource?.invoke(e.path)
    }
    val openOptions = { options = true }
    val thumb = @Composable { MediaThumb(source, e.mode.icon(duotone = true), size = AfSize.ThumbList, durationMs = source?.durationMs) }
    val more = @Composable {
        AfIconButton(AfIcons.Regular.More, tr(UiText.PROJECT_ACTIONS), openOptions, Modifier.testTag("project-more-${e.name}"))
    }
    val facts = @Composable { m: Modifier ->
        Column(m, verticalArrangement = Arrangement.spacedBy(AfSpace.Xs)) {
            AfText(e.name, Modifier.semantics { contentDescription = e.name }, type = AfType.RowTitle, maxLines = 1, ellipsis = true)
            if (row.missing) {
                AfText(e.path, muted = true, size = 11.sp, mono = true)
                WrapRow {
                    AfText(tr(UiText.FOLDER_NOT_FOUND), color = AutoFlowColors.Amber)
                    AfButton(tr(UiText.REMOVE_FROM_LIST), onRemove, kind = ButtonKind.Danger)
                }
            } else {
                WrapRow(spacing = AfSpace.Xs + 2.dp) {
                    ModeChip(e.mode, Modifier.testTag("project-mode-${e.mode.name}"))
                    status?.let { StatusChip(it) }
                }
                val shots = tr(UiText.PROJECT_SHOTS, "acceptedShots" to (e.acceptedShots), "totalShots" to (e.totalShots))
                val shotsColor = if (e.totalShots > 0 && e.acceptedShots == e.totalShots) AutoFlowColors.Green else AutoFlowColors.Muted
                WrapRow(spacing = AfSpace.S) {
                    AfText(relativeTime(e.lastOpenedAt, now, LocalUiLanguage.current), color = AutoFlowColors.Muted, type = AfType.Caption)
                    AfText(e.ratio.label, color = AutoFlowColors.Muted, type = AfType.Caption)
                    if (e.mode.hasShots()) AfText(My.breakSyllables(shots), color = shotsColor, type = AfType.Caption)
                }
            }
        }
    }
    // The whole card opens the project (a click layer under the content, so the texts stay separate nodes).
    Box(Modifier.fillMaxWidth().testTag("project-row-${e.name}")) {
        AfCard(Modifier.matchParentSize().onSecondaryClick(openOptions), onClick = if (row.missing) null else onOpen,
            onLongClick = openOptions) {}
        BoxWithConstraints(Modifier.padding(AfSpace.M)) {
            val gap = AfSpace.M
            if (maxWidth - AfSize.ThumbList.width - AfSize.Touch - gap * 2 >= AfSize.TileCardMin * LocalDensity.current.fontScale) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
                    thumb(); facts(Modifier.weight(1f)); more()
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    thumb(); more()
                }
                facts(Modifier.fillMaxWidth())
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
