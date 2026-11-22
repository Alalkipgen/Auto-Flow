package io.github.alalkipgen.autoflow.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.platform.ProjectFolderDeletion
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.icons.afTooltip
import io.github.alalkipgen.autoflow.ui.jobs.JobRow
import io.github.alalkipgen.autoflow.ui.media.MediaThumbnails
import io.github.alalkipgen.autoflow.ui.settings.BackupModel
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
 * From [AfDesktop.GridWide] (D2, §9.8) the projects are a card grid: a click selects (the detail pane on the right),
 * a double-click or Continue editing opens. [createOpen] / [onCreateOpenChange] let the shell open the create card
 * (sidebar "New project"); [jobs] are the Jobs rows; [thumbSource] reads a project's recorded source for its
 * thumbnail (read-only, off the main thread); [onShowFolder] shows a project folder in the file manager (desktop
 * only) and returns the reason when it cannot.
 */
@Composable
fun DashboardScreen(model: DashboardModel, onOpened: () -> Unit, modifier: Modifier = Modifier, clock: Clock = Clock.System,
    createMode: Mode? = null, onCreateModeChange: ((Mode?) -> Unit)? = null,
    createOpen: Boolean = false, onCreateOpenChange: ((Boolean) -> Unit)? = null,
    jobs: List<JobRow> = emptyList(),
    thumbSource: (suspend (dir: String) -> MediaThumbnails.Source?)? = null,
    onShowFolder: ((dir: String) -> String?)? = null,
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
    var selected by remember { mutableStateOf<String?>(null) }
    // One options / remove dialog for rows, cards and the detail pane.
    var optionsFor by remember { mutableStateOf<DashboardRow?>(null) }
    var confirmFor by remember { mutableStateOf<DashboardRow?>(null) }
    if (showRemoved && state.removed.isNotEmpty()) RemovedProjectsDialog(state.removed, model) { showRemoved = false }
    optionsFor?.let { row ->
        ProjectOptionsDialog(row.entry.name, managed = model.projectsHome != null, onRemove = { optionsFor = null; confirmFor = row }) { optionsFor = null }
    }
    confirmFor?.let { row -> RemoveProjectDialog(row.entry.name, onRemove = { model.remove(row.entry.path); confirmFor = null }) { confirmFor = null } }
    LaunchedEffect(model) { model.refresh() }
    val empty = state.rows.isEmpty() && state.newer.isEmpty()
    val modes = Mode.entries.filter { m -> state.rows.any { it.entry.mode == m } }
    val active = filter?.takeIf { it in modes }
    val visible = state.rows.filter { active == null || it.entry.mode == active }
    val now = clock.now()
    val openProject: (DashboardRow) -> Unit = { row -> if (model.open(row.entry.path)) onOpened() }
    val scroll = rememberScrollState()
    // No background of its own: the shell's ambient shows through the glass cards (DESIGN.md §9.10).
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
      val wide = maxWidth >= AfDesktop.GridWide
      val picked = if (wide) visible.firstOrNull { it.entry.path == selected && !it.missing } else null
      val list: @Composable (Modifier) -> Unit = { listModifier -> Column(listModifier, verticalArrangement = Arrangement.spacedBy(GlassShapes.SectionGap)) {
        // With a projects home (Android, D-27) every project lives there; there is no folder to open.
        val openFolder: @Composable () -> Unit = {
            if (model.projectsHome == null) GlassButton(tr(UiText.OPEN_FOLDER), { scope.launch { if (model.openFolder()) onOpened() } })
        }
        val newMusicVideo: @Composable () -> Unit = {
            GlassButton(tr(UiText.NEW_MUSIC_VIDEO), { setCreating(Mode.MUSIC_VIDEO) }, kind = ButtonKind.Primary, enabled = creating == null)
        }
        val newMovieRecap: @Composable () -> Unit = { GlassButton(tr(UiText.NEW_MOVIE_RECAP), { setCreating(Mode.MOVIE_RECAP) }, enabled = creating == null) }
        ProjectsHeader(state.rows.size, showCreate = !empty, createOpen = open) { setOpen(!open) }
        // The create card: "+" opens it; with no project yet it is the first thing on the screen.
        if (open || empty) GlassCard(tr(UiText.V3_PROJECT_CREATE), Modifier.testTag("projects-create")) {
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
                Modifier.testTag("projects-filters"), glass = true)
        }
        Column(Modifier.fillMaxWidth().testTag("projects-recent"), verticalArrangement = Arrangement.spacedBy(GlassShapes.BetweenCards)) {
            if (state.rows.isEmpty()) {
                EmptyState(AfIcons.Duotone.Folder, My.breakSyllables(
                    if (model.projectsHome == null) tr(UiText.PROJECTS_EMPTY_DESKTOP) else tr(UiText.PROJECTS_EMPTY)), null)
            }
            if (wide) ProjectGrid(visible, back = { row ->
                ProjectCardLayer(row, selected = row == picked, onSelect = { selected = row.entry.path }, onOpen = { openProject(row) },
                    onOptions = { optionsFor = row })
            }) { row, thumb ->
                ProjectCardContent(row, now, projectStatus(row.entry.path, jobs), thumbSource, thumb, onOptions = { optionsFor = row },
                    onRemove = { model.remove(row.entry.path) })
            } else visible.forEach { row ->
                key(row.entry.path) {
                    ProjectRow(row, now, projectStatus(row.entry.path, jobs), thumbSource, onOpen = { openProject(row) },
                        onOptions = { optionsFor = row }, onRemove = { model.remove(row.entry.path) })
                }
            }
            if (active == null) state.newer.forEach { row ->
                key("newer:" + row.path) {
                    GlassCard(row.name, Modifier.fillMaxWidth().testTag("projects-newer-${row.name}")) {
                        AfText(My.breakSyllables(tr(UiText.NEEDS_NEWER_APP)), muted = true)
                    }
                }
            }
        }
        if (state.removed.isNotEmpty()) RemovedRow(state.removed.size) { showRemoved = true }
        // F-7 (D-96): a render package (or backup zip) from another phone or computer.
        if (model.opensPackages) GlassButton(My.breakSyllables(tr(UiText.PACKAGE_OPEN)), { scope.launch { if (model.openPackage()) onOpened() } },
            Modifier.fillMaxWidth().testTag("projects-open-package"), enabled = creating == null, icon = AfIcons.Regular.Box)
      } }
      if (wide) Row(Modifier.fillMaxSize()) {
          // The viewport includes the padding: glass buttons' 48 dp targets overflow into it (GlassScrollColumn).
          Box(Modifier.weight(1f).fillMaxHeight().paneScroll(scroll)) { list(Modifier.fillMaxWidth().padding(screenPadding)) }
          picked?.let { row ->
              ProjectDetailPane(row, now, projectStatus(row.entry.path, jobs), thumbSource, onOpen = { openProject(row) },
                  onClose = { selected = null }, onRemove = { confirmFor = row }, onShowFolder = onShowFolder)
          }
      } else Box(Modifier.widthIn(max = AfSize.ContentMax).fillMaxSize().paneScroll(scroll)) { list(Modifier.fillMaxWidth().padding(screenPadding)) }
    }
}

/** Title + "1 project" / "<n> projects" and the 40 dp glass "+" that opens / closes the create card (hidden while the list is empty). */
@Composable
private fun ProjectsHeader(count: Int, showCreate: Boolean, createOpen: Boolean, onToggleCreate: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("projects-header"), verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(AfSpace.M)) {
        Column(Modifier.weight(1f)) {
            AfText(tr(UiText.PROJECTS), Modifier.testTag("projects-heading"), type = AfType.ScreenTitle)
            if (count > 0) AfText(My.breakSyllables(projectCount(count)), Modifier.testTag("projects-count"),
                color = AutoFlowColors.Muted, type = AfType.Caption)
        }
        if (showCreate) GlassIconButton(AfIcons.Regular.Plus, tr(UiText.V3_PROJECT_CREATE), onToggleCreate, Modifier.testTag("projects-new"),
            size = GlassShapes.IconButtonHeader, selected = createOpen)
    }
}

/** Status chip from a real job of the project (Jobs rows): running, queued, failed or paused; none otherwise. */
internal fun projectStatus(dir: String, jobs: List<JobRow>): StudioStatus? {
    val mine = jobs.filter { it.projectDir == dir }.map { it.status }
    return listOf(StudioStatus.RUNNING, StudioStatus.QUEUED, StudioStatus.FAILED, StudioStatus.PAUSED).firstOrNull { it in mine }
}

/**
 * The flat glass row that opens the existing removed-projects list: soft Redo tile, title, count and chevron (no
 * retention text: nothing is purged by time).
 */
@Composable
private fun RemovedRow(count: Int, onClick: () -> Unit) {
    GlassCard(Modifier.heightIn(min = AfSize.Touch).testTag("projects-open-removed"), onClick = onClick, flat = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AfSpace.M)) {
            SoftTile(AfIcons.Fill.Redo, AutoFlowColors.PrimaryText, size = AfSize.TileSmall)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AfText(My.breakSyllables(tr(UiText.OPEN_REMOVED_PROJECT)), type = AfType.RowTitle)
                AfText(My.breakSyllables(projectCount(count)), color = AutoFlowColors.Muted, type = AfType.Caption)
            }
            AfIcon(AfIcons.Regular.Chevron, tint = AutoFlowColors.Muted, size = 16.dp)
        }
    }
}

/** "1 project" / "<n> projects": ONE_PROJECT for one (the header count, the removed row and the removed-projects dialog). */
@Composable
private fun projectCount(count: Int): String = if (count == 1) tr(UiText.ONE_PROJECT) else tr(UiText.MANY_PROJECTS, "projects" to count)

@Composable
private fun NewProjectCard(model: DashboardModel, mode: Mode, onCancel: () -> Unit, onCreated: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var parent by remember { mutableStateOf(model.lastParent.orEmpty()) }
    var rights by remember { mutableStateOf(false) }
    val home = model.projectsHome
    val recap = mode == Mode.MOVIE_RECAP
    GlassCard(if (recap) tr(UiText.NEW_MOVIE_RECAP) else tr(UiText.NEW_MUSIC_VIDEO)) {
        AfTextField(name, { name = it }, tr(UiText.PROJECT_NAME))
        if (recap) {
            AfText(tr(UiText.RIGHTS_NOTICE), color = AutoFlowColors.Amber, size = 12.sp)
            AfSwitch(tr(UiText.RIGHTS_TICK), rights, { rights = it })
        }
        if (home == null) {
            FieldRow(
                field = { AfTextField(parent, { parent = it }, tr(UiText.PROJECT_PARENT), it) },
                actions = { GlassButton(tr(UiText.BROWSE), { scope.launch { model.pickParent()?.let { parent = it } } }) },
            )
        } else {
            AfText(tr(UiText.APP_STORAGE_BACKUP), muted = true, size = 12.sp)
        }
        if (home == null && name.isNotBlank() && parent.isNotBlank()) {
            AfText(tr(UiText.CREATES_PATH, "trimEndprojectFolder" to (joinPath(parent.trimEnd('/'), projectFolderName(name)))), muted = true, size = 12.sp, mono = true)
        }
        WrapRow {
            GlassButton(tr(UiText.CREATE), {
                val ok = if (recap) model.createMovieRecap(parent, name, rights) else model.createMusicVideo(parent, name)
                if (ok) onCreated()
            }, kind = ButtonKind.Primary, enabled = name.isNotBlank() && parent.isNotBlank() && (!recap || rights))
            GlassButton(tr(UiText.CANCEL), onCancel)
        }
    }
}

/** A dialog text, broken into Burmese syllables so it wraps. */
@Composable
private fun dt(key: UiText, vararg args: Pair<String, Any?>): String = My.breakSyllables(tr(key, *args))

/** A removed project's folder name: the last path segment (after `/` or `\`). */
private fun folderName(dir: String): String = dir.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

/**
 * Removed projects (DESIGN.md §9.10 dialogs): the list (3) → one removed project (4: Restore / Delete permanently… when
 * Ready) → the delete confirm (5). One glass dialog; Cancel, Back and a tap outside step back one level, never while
 * deleting.
 */
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
    // Reads the current step when called: the dialog window may keep an earlier reference to this function.
    fun back() {
        if (deleting) return
        error = null
        when {
            target != null -> target = null
            selected != null -> selected = null
            else -> close()
        }
    }
    GlassDialog(onDismissRequest = ::back) {
        key(row, confirm) {
            val cancel = GlassDialogAction(dt(UiText.CANCEL_KEEP), ::back, enabled = !deleting, tag = "removed-projects-cancel")
            val failure = error
            when {
                confirm != null -> GlassDialogSurface(AfIcons.Fill.Trash, AutoFlowColors.Failed, dt(UiText.PROJECT_DELETE_TITLE, "name" to confirm.name)) {
                    GlassPathBox(confirm.folder)
                    GlassFact(AfIcons.Regular.Drive, dt(UiText.PROJECT_DELETE_BYTES, "n" to BackupModel.fileSize(confirm.bytes)))
                    if (confirm.backups.isNotEmpty()) GlassCallout(AfIcons.Fill.Archive, dt(UiText.PROJECT_DELETE_KEEP_BACKUPS), AutoFlowColors.Info)
                    GlassCallout(AfIcons.Fill.Warn, dt(UiText.PROJECT_DELETE_PERMANENT), AutoFlowColors.Failed)
                    failure?.let { GlassCallout(AfIcons.Fill.XCircle, dt(it), AutoFlowColors.Failed) }
                    GlassDialogButtons(cancel, GlassDialogAction(dt(UiText.PROJECT_DELETE_CONFIRM), {
                        deleting = true
                        scope.launch {
                            try {
                                error = model.deleteProject(confirm)
                                if (error == null) { target = null; selected = null; close() }
                            } finally { deleting = false }
                        }
                    }, ButtonKind.Danger, AfIcons.Regular.Trash, enabled = !deleting, tag = "project-delete-confirm"))
                }
                row != null -> GlassDialogSurface(AfIcons.Fill.Folder, GlassColors.Neutral, My.breakSyllables(row.name)) {
                    GlassPathBox(row.dir)
                    val ready = inspection as? ProjectFolderDeletion.Result.Ready
                    val refused = inspection as? ProjectFolderDeletion.Result.Refused
                    refused?.let { GlassCallout(AfIcons.Fill.Warn, dt(it.reason.label()), AutoFlowColors.Amber) }
                    failure?.let { GlassCallout(AfIcons.Fill.XCircle, dt(it), AutoFlowColors.Failed) }
                    val restore = GlassDialogAction(dt(UiText.PROJECT_RESTORE), {
                        error = model.restoreRemoved(row)
                        if (error == null) close()
                    }, ButtonKind.Primary, AfIcons.Regular.Redo, tag = "project-restore")
                    val delete = if (ready == null) null else GlassDialogAction(dt(UiText.PROJECT_DELETE_MENU), {
                        val latest = model.inspectDeletion(row)
                        inspection = latest
                        target = (latest as? ProjectFolderDeletion.Result.Ready)?.target
                        error = null
                    }, ButtonKind.Danger, AfIcons.Regular.Trash, tag = "project-delete-action")
                    GlassDialogButtons(cancel, *listOfNotNull(restore, delete).toTypedArray())
                }
                else -> GlassDialogSurface(AfIcons.Fill.Redo, AutoFlowColors.Info, My.breakSyllables(tr(UiText.OPEN_REMOVED_PROJECT).trimEnd('…')),
                    subtitle = My.breakSyllables(projectCount(rows.size))) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        rows.forEachIndexed { index, removed ->
                            GlassMenuRow(AfIcons.Fill.Folder, GlassColors.Neutral, removed.name, {
                                selected = removed
                                inspection = model.inspectDeletion(removed)
                            }, Modifier.testTag("removed-project-$index"), caption = folderName(removed.dir), mono = true)
                        }
                    }
                    failure?.let { GlassCallout(AfIcons.Fill.XCircle, dt(it), AutoFlowColors.Failed) }
                    GlassDialogButtons(cancel)
                }
            }
        }
    }
}

/** Project actions (1): Remove from list (→ the remove confirm); deleting files stays on the removed-projects list. */
@Composable
private fun ProjectOptionsDialog(name: String, managed: Boolean, onRemove: () -> Unit, onDismiss: () -> Unit) {
    GlassDialog(onDismiss, AfIcons.Fill.More, GlassColors.Neutral, dt(UiText.PROJECT_ACTIONS), subtitle = My.breakSyllables(name)) {
        if (!managed) GlassCallout(AfIcons.Fill.Info, dt(UiText.PROJECT_DELETE_UNSUPPORTED), AutoFlowColors.Info)
        GlassMenuRow(AfIcons.Fill.FolderMinus, AutoFlowColors.Amber, dt(UiText.REMOVE_FROM_LIST), onRemove, Modifier.testTag("project-remove-action"),
            caption = dt(UiText.REMOVE_PROJECT_HINT))
        GlassDialogButtons(GlassDialogAction(dt(UiText.CANCEL_KEEP), onDismiss, tag = "project-actions-cancel"))
    }
}

/** Remove "<name>" from the list? (2): the folder stays; Remove = [DashboardModel.remove]. */
@Composable
private fun RemoveProjectDialog(name: String, onRemove: () -> Unit, onDismiss: () -> Unit) {
    GlassDialog(onDismiss, AfIcons.Fill.FolderMinus, AutoFlowColors.Amber, dt(UiText.REMOVE_PROJECT_QUESTION, "name" to name)) {
        GlassFact(AfIcons.Regular.Info, dt(UiText.REMOVE_PROJECT_HINT))
        GlassDialogButtons(GlassDialogAction(dt(UiText.CANCEL_KEEP), onDismiss, tag = "remove-project-cancel"),
            GlassDialogAction(dt(UiText.REMOVE), onRemove, ButtonKind.Danger, AfIcons.Regular.FolderMinus, tag = "remove-project-confirm"))
    }
}

/** The recorded source of a project for its thumbnail (none for a missing folder). */
@Composable
private fun projectThumb(row: DashboardRow, thumbSource: (suspend (String) -> MediaThumbnails.Source?)?): State<MediaThumbnails.Source?> =
    produceState(null, row.entry.path, row.entry.lastOpenedAt, row.missing, thumbSource) {
        value = if (row.missing) null else thumbSource?.invoke(row.entry.path)
    }

/** Title, mode chip + [status], relative date · ratio · shots; a missing folder: its path, "Folder not found", Remove. */
@Composable
private fun ProjectFacts(row: DashboardRow, now: Instant, status: StudioStatus?, onRemove: () -> Unit, modifier: Modifier,
    caption: Color = AutoFlowColors.Muted) {
    val e = row.entry
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AfSpace.Xs)) {
        AfText(e.name, Modifier.semantics { contentDescription = e.name }, type = AfType.RowTitle, maxLines = 1, ellipsis = true)
        if (row.missing) {
            AfText(e.path, muted = true, size = 11.sp, mono = true)
            WrapRow {
                AfText(tr(UiText.FOLDER_NOT_FOUND), color = AutoFlowColors.Amber)
                GlassButton(tr(UiText.REMOVE_FROM_LIST), onRemove, kind = ButtonKind.Danger)
            }
        } else {
            WrapRow(spacing = AfSpace.Xs + 2.dp) {
                GlassModeChip(e.mode, Modifier.testTag("project-mode-${e.mode.name}"))
                status?.let { StatusChip(it) }
            }
            val shots = tr(UiText.PROJECT_SHOTS, "acceptedShots" to (e.acceptedShots), "totalShots" to (e.totalShots))
            val shotsColor = if (e.totalShots > 0 && e.acceptedShots == e.totalShots) AutoFlowColors.SuccessText else caption
            WrapRow(spacing = AfSpace.S) {
                AfText(relativeTime(e.lastOpenedAt, now, LocalUiLanguage.current), color = caption, type = AfType.Caption)
                AfText(e.ratio.label, color = caption, type = AfType.Caption)
                if (e.mode.hasShots()) AfText(My.breakSyllables(shots), color = shotsColor, type = AfType.Caption)
            }
        }
    }
}

@Composable
private fun MoreButton(name: String, onOptions: () -> Unit) =
    GlassIconButton(AfIcons.Regular.More, tr(UiText.PROJECT_ACTIONS), onOptions, Modifier.testTag("project-more-$name"))

/**
 * A project row on a glass card tinted with its own frame (no frame: the mode colour, and the placeholder in that
 * colour): thumbnail 100 × 62 (the recorded duration as badge), 1-line title, mode chip + [status], relative date
 * · ratio · shots, and ⋮ (the existing options; long-press and right-click open them too). A tap opens the project; a
 * missing folder shows "Folder not found" with Remove from list instead. Large fonts: thumbnail and ⋮ above the text.
 */
@Composable
private fun ProjectRow(row: DashboardRow, now: Instant, status: StudioStatus?, thumbSource: (suspend (String) -> MediaThumbnails.Source?)?,
    onOpen: () -> Unit, onOptions: () -> Unit, onRemove: () -> Unit) {
    val e = row.entry
    val source by projectThumb(row, thumbSource)
    var art by remember(source) { mutableStateOf<ImageBitmap?>(null) }
    val thumb = @Composable {
        MediaThumb(source, e.mode.icon(duotone = true), Modifier.glassThumb(), size = AfSize.ThumbList, durationMs = source?.durationMs,
            placeholderColour = e.mode.glassColour, onImage = { art = it })
    }
    // The whole card opens the project (a click layer under the content, so the texts stay separate nodes).
    Box(Modifier.fillMaxWidth().testTag("project-row-${e.name}")) {
        GlassCard(Modifier.matchParentSize().onSecondaryClick(onOptions), onClick = if (row.missing) null else onOpen,
            onLongClick = onOptions, tint = e.mode.glassColour, tintAlpha = GlassColors.TintProjects, thumbnail = art) {}
        BoxWithConstraints(Modifier.padding(GlassShapes.CardPadding)) {
            val gap = AfSpace.M
            if (maxWidth - AfSize.ThumbList.width - GlassShapes.IconButtonCard - gap * 2 >= AfSize.TileCardMin * LocalDensity.current.fontScale) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
                    thumb(); ProjectFacts(row, now, status, onRemove, Modifier.weight(1f), glassCaptionColour(art)); MoreButton(e.name, onOptions)
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    thumb(); MoreButton(e.name, onOptions)
                }
                ProjectFacts(row, now, status, onRemove, Modifier.fillMaxWidth(), glassCaptionColour(art))
            }
        }
    }
}

/**
 * D2 grid: as many equal columns as fit [AfDesktop.ProjectCardMin] × the font scale. Each row measures its cards'
 * content first and then sizes every card's click layer to the tallest, so a row's cards are equally tall (no
 * intrinsic measuring — a wrapped chip row would be dropped by it).
 */
@Composable
private fun ProjectGrid(rows: List<DashboardRow>, back: @Composable (DashboardRow) -> Unit,
    front: @Composable (DashboardRow, DpSize) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("projects-grid")) {
        val gap = AfSpace.CardGap
        val columns = ((maxWidth + gap) / (AfDesktop.ProjectCardMin * LocalDensity.current.fontScale + gap)).toInt().coerceAtLeast(1)
        val width = (maxWidth - gap * (columns - 1)) / columns
        val thumb = (width - AfSpace.L * 2).let { DpSize(it, it * 9f / 16f) }
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            rows.chunked(columns).forEach { chunk ->
                Layout(listOf({ chunk.forEach { row -> key(row.entry.path) { back(row) } } },
                    { chunk.forEach { row -> key(row.entry.path) { front(row, thumb) } } })) { (backs, fronts), constraints ->
                    val w = width.roundToPx(); val g = gap.roundToPx()
                    val shown = fronts.map { it.measure(Constraints(minWidth = w, maxWidth = w)) }
                    val h = shown.maxOfOrNull { it.height } ?: 0
                    val layers = backs.map { it.measure(Constraints.fixed(w, h)) }
                    // Each click layer is placed (so drawn and hit-tested) under its card's content.
                    layout(constraints.maxWidth, h) { shown.indices.forEach { i -> layers[i].place(i * (w + g), 0); shown[i].place(i * (w + g), 0) } }
                }
            }
        }
    }
}

/**
 * The click layer of a D2 card: selected = primary border. Click selects, double-click or Enter opens; long-press,
 * right-click (and ⋮ on the card) open the options. A missing folder cannot be selected or opened.
 */
@Composable
private fun ProjectCardLayer(row: DashboardRow, selected: Boolean, onSelect: () -> Unit, onOpen: () -> Unit, onOptions: () -> Unit) {
    val name = row.entry.name
    // Enter opens the focused card (a click / Space selects it).
    val enterOpens = Modifier.onPreviewKeyEvent { event ->
        val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
        if (enter && !row.missing && event.type == KeyEventType.KeyDown) onOpen()
        enter && !row.missing
    }
    GlassCard(Modifier.onSecondaryClick(onOptions).semantics { this.selected = selected }.then(enterOpens)
        .testTag("project-card-click-$name"), selected = selected,
        onClick = if (row.missing) null else onSelect, onLongClick = onOptions, onDoubleClick = if (row.missing) null else onOpen) {}
}

/** A D2 card's content (`project-card-<name>`): 16:9 thumbnail (duration badge), the row's facts and ⋮. */
@Composable
private fun ProjectCardContent(row: DashboardRow, now: Instant, status: StudioStatus?, thumbSource: (suspend (String) -> MediaThumbnails.Source?)?,
    thumb: DpSize, onOptions: () -> Unit, onRemove: () -> Unit) {
    val e = row.entry
    val source by projectThumb(row, thumbSource)
    Column(Modifier.testTag("project-card-${e.name}").padding(AfSpace.L), verticalArrangement = Arrangement.spacedBy(AfSpace.S)) {
        MediaThumb(source, e.mode.icon(duotone = true), Modifier.glassThumb(), size = thumb, durationMs = source?.durationMs,
            placeholderColour = e.mode.glassColour)
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(AfSpace.Xs)) {
            ProjectFacts(row, now, status, onRemove, Modifier.weight(1f))
            MoreButton(e.name, onOptions)
        }
    }
}

/**
 * D2 detail pane of the selected project: big thumbnail, title, chips, the facts the index has (last opened, ratio,
 * shots, folder), Continue editing, Show in folder (desktop) and Remove from list (the confirm dialog). No player,
 * steps list or per-project package (render packages live on Export).
 */
@Composable
private fun ProjectDetailPane(row: DashboardRow, now: Instant, status: StudioStatus?,
    thumbSource: (suspend (String) -> MediaThumbnails.Source?)?, onOpen: () -> Unit, onClose: () -> Unit, onRemove: () -> Unit,
    onShowFolder: ((String) -> String?)?) {
    val e = row.entry
    val source by projectThumb(row, thumbSource)
    var folderProblem by remember(e.path) { mutableStateOf<String?>(null) }
    val line = AutoFlowColors.Line
    Column(Modifier.width(AfDesktop.DetailPane).fillMaxHeight().background(AutoFlowColors.Panel)
        .drawBehind { drawLine(line, Offset.Zero, Offset(0f, size.height), AutoFlowShapes.Border.toPx()) }
        .paneScroll(rememberScrollState()).padding(AfSpace.Xl).testTag("project-detail-pane"),
        verticalArrangement = Arrangement.spacedBy(AfSpace.L)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AfSpace.M)) {
            AfText(My.breakSyllables(tr(UiText.PROJECTS_SELECTED)), Modifier.weight(1f), color = AutoFlowColors.Muted, type = AfType.Caption)
            AfIconButton(AfIcons.Regular.Close, tr(UiText.CLOSE), onClose, Modifier.testTag("project-detail-close"))
        }
        val w = AfDesktop.DetailPane - AfSpace.Xl * 2
        MediaThumb(source, e.mode.icon(duotone = true), size = DpSize(w, w * 9f / 16f), durationMs = source?.durationMs)
        Column(verticalArrangement = Arrangement.spacedBy(AfSpace.S)) {
            AfText(e.name, Modifier.testTag("project-detail-title"), type = AfType.CardTitle)
            WrapRow(spacing = AfSpace.Xs + 2.dp) { ModeChip(e.mode); status?.let { StatusChip(it) } }
        }
        val shape = RoundedCornerShape(AfRadius.ListCard)
        Column(Modifier.fillMaxWidth().clip(shape).border(AutoFlowShapes.Border, line, shape).padding(AfSpace.M),
            verticalArrangement = Arrangement.spacedBy(AfSpace.S)) {
            Fact(tr(UiText.PROJECT_LAST_OPENED), relativeTime(e.lastOpenedAt, now, LocalUiLanguage.current))
            Fact(tr(UiText.RATIO), e.ratio.label)
            if (e.mode.hasShots()) Fact(tr(UiText.SHOTS), "${e.acceptedShots}/${e.totalShots}")
            // One line (a path has no good break); the full path stays readable as tooltip and description.
            val folder = tr(UiText.FOLDER_PATH, "it" to e.path)
            AfText(folder, Modifier.afTooltip(e.path).semantics { contentDescription = folder }.testTag("project-detail-folder-path"),
                color = AutoFlowColors.Muted, type = AfType.Caption, maxLines = 1, ellipsis = true)
        }
        AfButton(My.breakSyllables(tr(UiText.V3_CONTINUE)), onOpen, Modifier.fillMaxWidth().testTag("project-detail-open"),
            kind = ButtonKind.Primary, icon = AfIcons.Fill.Play)
        onShowFolder?.let { show ->
            AfButton(My.breakSyllables(tr(UiText.PROJECT_SHOW_FOLDER)), { folderProblem = show(e.path) },
                Modifier.fillMaxWidth().testTag("project-detail-folder"), icon = AfIcons.Regular.Folder)
        }
        folderProblem?.let { AfText(My.breakSyllables(it), color = AutoFlowColors.Amber, type = AfType.Caption) }
        AfButton(My.breakSyllables(tr(UiText.REMOVE_FROM_LIST)), onRemove, Modifier.fillMaxWidth().testTag("project-detail-remove"),
            kind = ButtonKind.Danger)
    }
}

/** A label (muted, wraps) and its short value on one line. */
@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(AfSpace.M)) {
        AfText(My.breakSyllables(label), Modifier.weight(1f), color = AutoFlowColors.Muted, type = AfType.Caption)
        AfText(My.breakSyllables(value), type = AfType.Caption)
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
