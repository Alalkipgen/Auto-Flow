package io.github.alalkipgen.autoflow.ui.shell

import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.COMPACT_WIDTH
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.input.InputScreen
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.ui.home.HomeScreen
import io.github.alalkipgen.autoflow.ui.jobs.JobsScreen
import io.github.alalkipgen.autoflow.ui.jobs.jobsBadgeValue
import io.github.alalkipgen.autoflow.ui.library.LibraryModel
import io.github.alalkipgen.autoflow.ui.library.LibraryScreen
import io.github.alalkipgen.autoflow.ui.projects.DashboardScreen
import io.github.alalkipgen.autoflow.ui.settings.AppInfo
import io.github.alalkipgen.autoflow.ui.settings.BackupModel
import io.github.alalkipgen.autoflow.ui.settings.ProviderStatusModel
import io.github.alalkipgen.autoflow.ui.settings.SettingsScreen
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardJobs
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardScreen
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import io.github.alalkipgen.autoflow.ui.theme.UiFont
import androidx.compose.ui.window.Dialog
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import org.koin.compose.getKoin
import org.koin.compose.koinInject

/** The Movie Recap workspace (docs/specs/recap.md §7): sidebar "Mode → Movie Recap", phone More. */
const val RECAP_SCREEN = "Movie Recap"

/** Sidebar sections in pipeline order — docs/DESIGN.md §2. */
val SidebarSections: List<Pair<String, List<String>>> = listOf(
    "Project" to listOf(
        "Dashboard", "Input", "Storyboard", "Shots", "References", "Flow Handoff", "Timeline", "Export",
    ),
    "Mode" to listOf("Music Video", "Movie Recap"),
    "System" to listOf("Settings"),
)

/**
 * Window content wired to the Koin graph (see `uiModule`). Starts on the Dashboard. A window narrower than
 * [COMPACT_WIDTH] (a phone) gets [PhoneShell] with bottom tabs, a wider one [AppShell] with the sidebar
 * (DESIGN.md §2, §4). [backHandler] lets Android's Back button step back (see [backTarget]); the desktop has none.
 */
@Composable
fun App(backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> }) {
    val model = koinInject<ProviderStatusModel>()
    val status by model.status.collectAsState(model.current())
    val project by koinInject<CurrentProject>().state.collectAsState()
    val shell = koinInject<ShellModel>()
    val shellState by shell.state.collectAsState(shell.current())
    val jobs = koinInject<StoryboardJobs>()
    var screen by remember { mutableStateOf("Dashboard") }
    // A share from another app (Android share sheet) lands in the Inbox: open it.
    val shared = koinInject<io.github.alalkipgen.autoflow.ui.inbox.SharedInbox>()
    val arrivals = shared.state.collectAsState().value.arrivals
    LaunchedEffect(arrivals) { if (arrivals > 0) screen = "Inbox" }
    val koin = getKoin()
    val appInfo = remember(koin) { koin.getOrNull<AppInfo>() }
    val currentProject = koinInject<CurrentProject>()
    val backup = koinInject<BackupModel>().takeIf { it.available }
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.state.collectAsState()
    val displayShell = remember(shellState, settings.uiLanguage) { shell.current(UiLanguage.fromCode(settings.uiLanguage)) }
    val bundledFeatures = remember(koin) { koin.get<io.github.alalkipgen.autoflow.ui.settings.BundledConfig>().featuresJson() }
    val features = remember(settings.featureOverrides, bundledFeatures) {
        io.github.alalkipgen.autoflow.core.flags.FeatureFlags.load(bundledFeatures, settings.featureOverrides)
    }
    val fontPaths = settings.fontPaths
    val fs = koinInject<io.github.alalkipgen.autoflow.core.io.FileSystem>()
    val builtInFonts = remember(koin) { koin.getOrNull<io.github.alalkipgen.autoflow.ui.settings.BuiltInFonts>() }
    val uiFont = remember(fontPaths, builtInFonts) { UiFont.load(io.github.alalkipgen.autoflow.ui.settings.BuiltInFonts.withBuiltIns(fontPaths, builtInFonts)) { runCatching { fs.read(it) }.getOrNull() } ?: FontFamily.Default }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < COMPACT_WIDTH
        // MORE is a phone tab; a window widened while on it shows the Dashboard.
        val current = if (!compact && screen == MORE) "Dashboard" else screen
        val back = if (compact) backTarget(current) else null
        backHandler(back != null) { back?.let { screen = it } }
        val top: @Composable () -> Unit = {
            JobBanner(jobs)
            if (current in ShellModel.SCREEN_STEPS && displayShell.steps.isNotEmpty()) Stepper(displayShell.steps, viewing = ShellModel.SCREEN_STEPS[current])
        }
        val content: @Composable () -> Unit = {
            when (current) {
                "Dashboard" -> DashboardScreen(koinInject(), onOpened = {
                    val mode = currentProject.state.value?.project?.mode
                    screen = if (mode == io.github.alalkipgen.autoflow.core.model.Mode.MOVIE_RECAP) RECAP_SCREEN else "Input"
                })
                RECAP_SCREEN -> io.github.alalkipgen.autoflow.ui.recap.RecapScreen(koinInject(), onGoToExport = { screen = "Export" })
                "Input" -> InputScreen(koinInject(), koinInject(), onGenerated = { screen = "Storyboard" })
                "Storyboard" -> StoryboardScreen(koinInject(), koinInject(), onGoToInput = { screen = "Input" })
                "Shots" -> io.github.alalkipgen.autoflow.ui.shots.ShotsScreen(koinInject())
                "References" -> {
                    val fs = koinInject<io.github.alalkipgen.autoflow.core.io.FileSystem>()
                    io.github.alalkipgen.autoflow.ui.references.ReferencesScreen(
                        model = koinInject(),
                        readBytes = { path -> try { fs.read(path) } catch (e: Exception) { null } },
                    )
                }
                "Flow Handoff" -> {
                    val fs = koinInject<io.github.alalkipgen.autoflow.core.io.FileSystem>()
                    io.github.alalkipgen.autoflow.ui.handoff.FlowHandoffScreen(
                        model = koinInject(),
                        readBytes = { path -> try { fs.read(path) } catch (e: Exception) { null } },
                    )
                }
                "Inbox" -> io.github.alalkipgen.autoflow.ui.inbox.InboxScreen(koinInject(), shared = koinInject())
                "Export" -> io.github.alalkipgen.autoflow.ui.export.ExportScreen(koinInject())
                "Settings" -> SettingsScreen(koinInject(), info = appInfo, backup = backup)
                MORE -> MoreScreen(displayShell.locks, onSelect = { screen = it })
                else -> ScreenPlaceholder(current)
            }
        }
        CompositionLocalProvider(io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage provides io.github.alalkipgen.autoflow.ui.i18n.UiLanguage.fromCode(settings.uiLanguage), LocalCompact provides compact, LocalUiFontFamily provides uiFont) {
            if (features.studioV2) {
                StudioV2App(
                    status = status,
                    projectName = project?.project?.name,
                    windowWidthDp = maxWidth.value,
                    appInfo = appInfo,
                    backup = backup,
                    backHandler = backHandler,
                )
            } else if (compact) {
                PhoneShell(status, selected = current, onSelect = { screen = it }, projectName = project?.project?.name,
                    locks = displayShell.locks, top = top, content = content)
            } else {
                AppShell(status, selected = current, onSelect = { screen = it }, projectName = project?.project?.name,
                    locks = displayShell.locks, top = top, content = content)
            }
        }
    }
}

/** Where Studio v2 is: tab, project screen (null = the project list) and whether Settings is open over it. */
data class V2Nav(val dest: StudioV2Dest = StudioV2Dest.HOME, val projectScreen: String? = null, val settings: Boolean = false, val recapStep: io.github.alalkipgen.autoflow.ui.recap.RecapStep = io.github.alalkipgen.autoflow.ui.recap.RecapStep.SOURCE, val createMode: io.github.alalkipgen.autoflow.core.model.Mode? = null, val libraryLexicon: Boolean = false)

/** Android Back in Studio v2: closes Settings, then steps project screen → list, then any tab → Home; null = leave. */
fun v2Back(nav: V2Nav, recap: Boolean): V2Nav? = when {
    nav.settings -> nav.copy(settings = false)
    nav.createMode != null -> nav.copy(createMode = null)
    nav.dest == StudioV2Dest.PROJECTS && nav.projectScreen == "Export" && recap -> nav.copy(projectScreen = "Recap")
    nav.dest == StudioV2Dest.PROJECTS && nav.projectScreen == "Classic Recap" && recap -> nav.copy(projectScreen = "Recap")
    nav.dest == StudioV2Dest.PROJECTS && nav.projectScreen == "Recap" && recap && nav.recapStep.previous() != null -> nav.copy(recapStep = nav.recapStep.previous()!!)
    nav.dest == StudioV2Dest.PROJECTS && nav.projectScreen in MV_CLASSIC_SCREENS && !recap -> nav.copy(projectScreen = MV_CLASSIC)
    nav.dest == StudioV2Dest.PROJECTS && nav.projectScreen == MV_CLASSIC && !recap -> nav.copy(projectScreen = SHOT_SHEET)
    nav.dest == StudioV2Dest.PROJECTS && nav.projectScreen != null -> nav.copy(projectScreen = null)
    nav.dest != StudioV2Dest.HOME -> nav.copy(dest = StudioV2Dest.HOME)
    else -> null
}

/** Tab switches retain the actual open step; selecting Projects again still returns to its list. */
fun v2Select(nav: V2Nav, dest: StudioV2Dest): V2Nav = nav.copy(dest = dest, createMode = null, libraryLexicon = false,
    projectScreen = if (dest == StudioV2Dest.PROJECTS && nav.dest == dest) null else nav.projectScreen)

/** Music Video in Studio v2 (5B-12): one shot sheet instead of the Storyboard / Shots / References / Flow Handoff tabs. */
const val SHOT_SHEET = "Shot sheet"

/** The classic Music Video steps, kept for one release behind this entry (like Classic Recap, D-41). */
const val MV_CLASSIC = "Classic steps"
val MV_CLASSIC_SCREENS = listOf("Storyboard", "Shots", "References", "Flow Handoff")

/** Project screens reachable in Studio v2, per project mode. */
fun v2ProjectScreens(recap: Boolean): List<String> =
    if (recap) listOf("Recap", "Export") else listOf("Input", SHOT_SHEET, "Inbox", "Export", MV_CLASSIC)

/**
 * Studio v2 root (5A-6): Home · Projects · Library · Jobs with adaptive shell.
 * Bottom nav < 600 dp, rail ≥ 600 dp, three-pane ≥ 840 dp.
 */
@Composable
private fun StudioV2App(
    status: String,
    projectName: String?,
    windowWidthDp: Float,
    appInfo: AppInfo?,
    backup: BackupModel?,
    backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit,
) {
    val rows = io.github.alalkipgen.autoflow.ui.jobs.rememberJobRows()
    val badge = jobsBadgeValue(rows)
    var nav by remember { mutableStateOf(V2Nav()) }
    var showExitDialog by remember { mutableStateOf(false) }
    val currentProject = koinInject<io.github.alalkipgen.autoflow.ui.projects.CurrentProject>()
    val open by currentProject.state.collectAsState()
    val isRecap = open?.project?.mode == io.github.alalkipgen.autoflow.core.model.Mode.MOVIE_RECAP
    val scope = rememberCoroutineScope()
    // Bug fix: share-sheet → Inbox navigate (D-52)
    val shared = koinInject<io.github.alalkipgen.autoflow.ui.inbox.SharedInbox>()
    val arrivals by shared.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(arrivals.arrivals) {
        if (arrivals.arrivals > 0) nav = V2Nav(StudioV2Dest.PROJECTS, "Inbox")
    }
    val back = v2Back(nav, isRecap)
    // Bug fix: Home Back → "Exit app?" dialog instead of immediate exit (D-52)
    backHandler(true) {
        if (back != null) nav = back
        else showExitDialog = true
    }
    fun openProjectScreen(screen: String?) { nav = V2Nav(StudioV2Dest.PROJECTS, screen,
        recapStep = if (screen == "Export" && isRecap) io.github.alalkipgen.autoflow.ui.recap.RecapStep.STYLE else io.github.alalkipgen.autoflow.ui.recap.RecapStep.SOURCE) }

    val content: @Composable () -> Unit = {
        if (nav.settings) {
            Column(Modifier.fillMaxSize()) {
                V2SubBar(tr(UiText.BACK), status) { nav = nav.copy(settings = false) }
                Box(Modifier.fillMaxWidth().weight(1f)) { SettingsScreen(koinInject(), info = appInfo, backup = backup,
                    onOpenLexicon = { nav = nav.copy(settings = false, dest = StudioV2Dest.LIBRARY, libraryLexicon = true) }) }
            }
        } else when (nav.dest) {
            StudioV2Dest.HOME -> {
                val dashboard = koinInject<io.github.alalkipgen.autoflow.ui.projects.DashboardModel>()
                val dashboardState by dashboard.state.collectAsState()
                HomeScreen(
                    onNewRecap = { nav = V2Nav(StudioV2Dest.PROJECTS, createMode = io.github.alalkipgen.autoflow.core.model.Mode.MOVIE_RECAP) },
                    onNewMusicVideo = { nav = V2Nav(StudioV2Dest.PROJECTS, createMode = io.github.alalkipgen.autoflow.core.model.Mode.MUSIC_VIDEO) },
                    onQuickRecapStart = { targetMs, style ->
                        if (dashboard.createQuickRecap(targetMs, style)) openProjectScreen("Recap")
                    },
                    onOpenSettings = { nav = nav.copy(settings = true) },
                    status = status,
                    notice = dashboardState.notice?.text(LocalUiLanguage.current) ?: dashboardState.message,
                    jobs = rows,
                    onViewJobs = { nav = v2Select(nav, StudioV2Dest.JOBS) },
                    backHandler = backHandler,
                    continueProjectPath = open?.dir,
                    continueStep = if (open != null && isRecap && nav.projectScreen in listOf("Recap", "Export")) {
                        val step = if (nav.projectScreen == "Export") io.github.alalkipgen.autoflow.ui.recap.RecapStep.EXPORT else nav.recapStep
                        tr(UiText.V3_STEP, "step" to tr(step.textKey), "n" to step.ordinal + 1)
                    } else null,
                    recent = dashboardState.rows.filter { !it.missing }.map { it.entry },
                    onOpenProject = { dir ->
                        if (open?.dir == dir && nav.projectScreen != null) nav = v2Select(nav, StudioV2Dest.PROJECTS)
                        else if (dashboard.open(dir)) {
                            val mode = currentProject.state.value?.project?.mode
                            openProjectScreen(if (mode == io.github.alalkipgen.autoflow.core.model.Mode.MOVIE_RECAP) "Recap" else "Input")
                        }
                    },
                )
            }
            StudioV2Dest.PROJECTS -> {
                val screen = nav.projectScreen
                if (screen == null) {
                    DashboardScreen(koinInject(), createMode = nav.createMode, onCreateModeChange = { nav = nav.copy(createMode = it) }, onOpened = {
                        val mode = currentProject.state.value?.project?.mode
                        openProjectScreen(if (mode == io.github.alalkipgen.autoflow.core.model.Mode.MOVIE_RECAP) "Recap" else "Input")
                    })
                } else {
                    Column(Modifier.fillMaxSize()) {
                        if (isRecap && screen in listOf("Recap", "Export")) {
                            val voiceList = screen == "Recap" && nav.recapStep == io.github.alalkipgen.autoflow.ui.recap.RecapStep.VOICE
                            if (!voiceList) {
                            io.github.alalkipgen.autoflow.ui.components.WrapRow {
                                io.github.alalkipgen.autoflow.ui.components.AfButton(tr(UiText.PROJECTS_BACK), { openProjectScreen(null) }, Modifier.heightIn(min = 48.dp))
                                projectName?.let { AfText(it, bold = true) }
                            }
                            io.github.alalkipgen.autoflow.ui.recap.RecapStepHeader(
                                if (screen == "Export") io.github.alalkipgen.autoflow.ui.recap.RecapStep.EXPORT else nav.recapStep,
                                onBack = { nav = v2Back(nav, true) ?: nav },
                                onNext = {
                                    val next = nav.recapStep.next()
                                    if (next == io.github.alalkipgen.autoflow.ui.recap.RecapStep.EXPORT) nav = nav.copy(projectScreen = "Export")
                                    else if (next != null) nav = nav.copy(recapStep = next)
                                },
                                onClassic = { nav = nav.copy(projectScreen = "Classic Recap") },
                            )
                            }
                            Box(Modifier.fillMaxWidth().weight(1f)) {
                                if (screen == "Recap") io.github.alalkipgen.autoflow.ui.recap.StudioRecapScreen(koinInject(), nav.recapStep, windowWidthDp,
                                    onGoToExport = { nav = nav.copy(projectScreen = "Export", recapStep = io.github.alalkipgen.autoflow.ui.recap.RecapStep.STYLE) },
                                    navigation = io.github.alalkipgen.autoflow.ui.recap.RecapNavigation(
                                        back = { nav = v2Back(nav, true) ?: nav },
                                        next = { nav = nav.copy(recapStep = io.github.alalkipgen.autoflow.ui.recap.RecapStep.STYLE) },
                                        classic = { nav = nav.copy(projectScreen = "Classic Recap") },
                                        handleBack = backHandler,
                                    ),
                                ) else {
                                    val recapModel = koinInject<io.github.alalkipgen.autoflow.ui.recap.RecapModel>()
                                    io.github.alalkipgen.autoflow.ui.recap.ProjectPanes(windowWidthDp,
                                        list = { Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp)) { AfText(tr(UiText.EXPORT), bold = true); AfText(tr(UiText.SHELL_EXPORT_HINT), muted = true) } },
                                        editor = { io.github.alalkipgen.autoflow.ui.export.ExportScreen(koinInject()) },
                                        preview = { Column(Modifier.verticalScroll(rememberScrollState())) { io.github.alalkipgen.autoflow.ui.recap.ExactFrameCard(recapModel) } },
                                    )
                                }
                            }
                        } else {
                            V2ProjectBar(projectName, v2ProjectScreens(isRecap), screen, onBack = { openProjectScreen(null) }, onSelect = { openProjectScreen(it) })
                            Box(Modifier.fillMaxWidth().weight(1f)) { V2ProjectScreen(screen, windowWidthDp) { openProjectScreen(it) } }
                        }
                    }
                }
            }
            StudioV2Dest.LIBRARY -> LibraryScreen(koinInject<LibraryModel>(), focusLexicon = nav.libraryLexicon)
            StudioV2Dest.JOBS -> io.github.alalkipgen.autoflow.ui.jobs.JobsScreen(rows, scope, onOpen = { source ->
                when (source) {
                    io.github.alalkipgen.autoflow.ui.jobs.JobSource.RECAP -> openProjectScreen("Recap")
                    io.github.alalkipgen.autoflow.ui.jobs.JobSource.RENDER -> openProjectScreen("Export")
                    io.github.alalkipgen.autoflow.ui.jobs.JobSource.STORYBOARD -> openProjectScreen(SHOT_SHEET)
                    io.github.alalkipgen.autoflow.ui.jobs.JobSource.AI -> Unit
                }
            })
        }
    }

    StudioV2Shell(
        selected = nav.dest,
        // Projects again while on Projects = back to the list; other tabs keep the open project screen for later.
        onSelect = { nav = v2Select(nav, it) },
        jobsBadge = badge,
        windowWidthDp = windowWidthDp,
        content = content,
    )
    // Exit confirmation dialog (D-52)
    if (showExitDialog) {
        val koin = getKoin()
        ExitDialog(
            onExit = { koin.getOrNull<io.github.alalkipgen.autoflow.platform.AppExit>()?.exit(); showExitDialog = false },
            onStay = { showExitDialog = false },
        )
    }
}

/** One project screen inside Studio v2; [go] moves to another one. */
@Composable
private fun V2ProjectScreen(screen: String, windowWidthDp: Float, go: (String) -> Unit) {
    when (screen) {
        "Recap", "Classic Recap" -> io.github.alalkipgen.autoflow.ui.recap.RecapScreen(koinInject(), onGoToExport = { go("Export") })
        "Input" -> InputScreen(koinInject(), koinInject(), onGenerated = { go(SHOT_SHEET) })
        SHOT_SHEET -> {
            val fs = koinInject<io.github.alalkipgen.autoflow.core.io.FileSystem>()
            io.github.alalkipgen.autoflow.ui.shotsheet.ShotSheetScreen(
                storyboard = koinInject(), jobs = koinInject(), shots = koinInject(), refs = koinInject(), handoff = koinInject(),
                readBytes = { path -> runCatching { fs.read(path) }.getOrNull() },
                windowWidthDp = windowWidthDp,
                onGoToInput = { go("Input") },
            )
        }
        MV_CLASSIC -> MvClassicSteps(go)
        "Storyboard" -> StoryboardScreen(koinInject(), koinInject(), onGoToInput = { go("Input") })
        "Shots" -> io.github.alalkipgen.autoflow.ui.shots.ShotsScreen(koinInject())
        "References" -> {
            val fs = koinInject<io.github.alalkipgen.autoflow.core.io.FileSystem>()
            io.github.alalkipgen.autoflow.ui.references.ReferencesScreen(model = koinInject(), readBytes = { path -> runCatching { fs.read(path) }.getOrNull() })
        }
        "Flow Handoff" -> {
            val fs = koinInject<io.github.alalkipgen.autoflow.core.io.FileSystem>()
            io.github.alalkipgen.autoflow.ui.handoff.FlowHandoffScreen(model = koinInject(), readBytes = { path -> runCatching { fs.read(path) }.getOrNull() })
        }
        "Inbox" -> io.github.alalkipgen.autoflow.ui.inbox.InboxScreen(koinInject(), shared = koinInject())
        "Export" -> io.github.alalkipgen.autoflow.ui.export.ExportScreen(koinInject())
        else -> ScreenPlaceholder(screen)
    }
}

/** The classic Music Video steps for one release (5B-12): each opens its old screen; Back returns here. */
@Composable
private fun MvClassicSteps(go: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AfText(io.github.alalkipgen.autoflow.ui.i18n.My.screen(MV_CLASSIC), bold = true)
        AfText(tr(UiText.SHELL_CLASSIC_HINT), muted = true)
        io.github.alalkipgen.autoflow.ui.components.WrapRow {
            MV_CLASSIC_SCREENS.forEach { s ->
                io.github.alalkipgen.autoflow.ui.components.AfButton(io.github.alalkipgen.autoflow.ui.i18n.My.screen(s), { go(s) }, Modifier.heightIn(min = 48.dp))
            }
        }
    }
}

/** Top bar of a Studio v2 sub-screen: a back button and [status] (AI provider) on the right. */
@Composable
internal fun V2SubBar(backLabel: String, status: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(AutoFlowColors.Panel).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        io.github.alalkipgen.autoflow.ui.components.AfButton("←", onBack, Modifier.testTag("subscreen-back")
            .semantics { contentDescription = backLabel })
        AfText(My.breakSyllables(status), Modifier.weight(1f), muted = true, size = 12.sp)
    }
}

/** Project header in Studio v2: ← Projects, the project name, and chips for the project's screens. */
@Composable
private fun V2ProjectBar(projectName: String?, screens: List<String>, selected: String, onBack: () -> Unit, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(AutoFlowColors.Panel).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            io.github.alalkipgen.autoflow.ui.components.AfButton(tr(UiText.PROJECTS_BACK), onBack)
            projectName?.let { AfText(it, Modifier.weight(1f), bold = true) }
        }
        io.github.alalkipgen.autoflow.ui.components.WrapRow(spacing = 6.dp) {
            screens.forEach { s -> io.github.alalkipgen.autoflow.ui.components.AfChip(io.github.alalkipgen.autoflow.ui.i18n.My.screen(s), s == selected, onClick = { onSelect(s) }) }
        }
        Box(Modifier.fillMaxWidth().height(AutoFlowShapes.Border).background(AutoFlowColors.Line))
    }
}

/**
 * Desktop window content: title bar with [providerStatus] (DESIGN.md §2), pipeline sidebar with [selected]
 * highlighted and [locks] (entry → what must be done first) dimmed, [top] (job banner, stepper) and [content].
 */
@Composable
fun AppShell(
    providerStatus: String,
    modifier: Modifier = Modifier,
    selected: String? = null,
    onSelect: (String) -> Unit = {},
    projectName: String? = null,
    locks: Map<String, String> = emptyMap(),
    top: @Composable () -> Unit = {},
    content: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxSize().background(AutoFlowColors.Background)) {
        TitleBar(providerStatus, projectName)
        Row(Modifier.fillMaxSize()) {
            Sidebar(selected, locks, onSelect)
            Column(Modifier.fillMaxSize()) {
                top()
                Box(Modifier.fillMaxWidth().weight(1f)) { content() }
            }
        }
    }
}

/** "Exit Auto Flow?" confirmation (D-52). */
@Composable
private fun ExitDialog(onExit: () -> Unit, onStay: () -> Unit) {
    Dialog(onDismissRequest = onStay) {
        Column(
            Modifier
                .fillMaxWidth(0.85f)
                .clip(AutoFlowShapes.Card)
                .background(AutoFlowColors.Panel)
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            AfText(tr(UiText.EXIT_TITLE), bold = true, size = 20.sp)
            AfText(tr(UiText.EXIT_HINT), muted = true)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AfButton(tr(UiText.EXIT), onExit, Modifier.weight(1f), kind = ButtonKind.Danger)
                AfButton(tr(UiText.STAY), onStay, Modifier.weight(1f))
            }
        }
    }
}

/** Content for a sidebar entry whose screen is not built yet. */
@Composable
fun ScreenPlaceholder(name: String) {
    Column(Modifier.padding(screenPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AfText(My.screen(name), size = 18.sp, bold = true)
        AfText(tr(UiText.NOT_BUILT_YET), muted = true)
    }
}

@Composable
private fun TitleBar(providerStatus: String, projectName: String?) {
    Column(Modifier.fillMaxWidth()) {
        WrapRow(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .background(AutoFlowColors.TitleBar)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            spacing = 10.dp,
        ) {
            AutoFlowLogo(22.dp)
            BasicText(
                text = "Auto Flow",
                style = TextStyle(
                    color = AutoFlowColors.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                ),
            )
            projectName?.let { AfText("·  $it", muted = true) }
            BasicText(providerStatus, style = TextStyle(color = AutoFlowColors.Muted, fontSize = 13.sp, fontFamily = LocalUiFontFamily.current))
        }
        Box(Modifier.fillMaxWidth().height(AutoFlowShapes.Border).background(AutoFlowColors.Line))
    }
}

@Composable
private fun Sidebar(selected: String?, locks: Map<String, String>, onSelect: (String) -> Unit) {
    var tapped by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxHeight()) {
        BoxWithConstraints(Modifier.width(190.dp).fillMaxHeight().background(AutoFlowColors.Panel)) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
                    .padding(horizontal = 10.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SidebarSections.forEach { (section, items) ->
                    // DESIGN.md §2 / mockup: the System section sits at the bottom of the sidebar.
                    if (section == "System") Spacer(Modifier.weight(1f))
                    BasicText(
                        text = My.breakSyllables(My.screen(section).uppercase()),
                        modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp),
                        style = TextStyle(color = AutoFlowColors.Muted, fontSize = 10.sp, letterSpacing = 1.sp, fontFamily = LocalUiFontFamily.current),
                    )
                    items.forEach { item ->
                        val lock = locks[item]
                        SidebarItem(item, item == selected, lock, showLock = tapped == item) {
                            if (lock == null) {
                                tapped = null
                                onSelect(item)
                            } else {
                                tapped = item
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.width(AutoFlowShapes.Border).fillMaxHeight().background(AutoFlowColors.Line))
    }
}

/**
 * Selected: purple left bar + fading purple background, white text (mockup `.side a.on`). Locked ([lock] != null):
 * dimmed with a lock glyph; hovering or clicking shows [lock] instead of opening the screen (DESIGN.md §2).
 */
@Composable
private fun SidebarItem(name: String, selected: Boolean, lock: String?, showLock: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val lockedLabel = tr(UiText.LOCKED)
    val displayLabel = My.screen(name)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .then(
                    if (selected) {
                        Modifier.background(Brush.horizontalGradient(listOf(AutoFlowColors.Purple.copy(alpha = 0.25f), Color.Transparent)))
                    } else {
                        Modifier
                    },
                )
                .hoverable(hover)
                .heightIn(min = 48.dp)
                .semantics { contentDescription = displayLabel }
                .selectable(selected = selected, role = Role.Tab, onClick = onClick)
                .then(if (lock != null) Modifier.semantics { stateDescription = lockedLabel } else Modifier)
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) Box(Modifier.width(3.dp).fillMaxHeight().background(AutoFlowColors.Purple))
            val ink = when {
                selected -> Color.White
                lock != null -> AutoFlowColors.Muted.copy(alpha = 0.5f)
                else -> AutoFlowColors.Muted
            }
            BasicText(
                text = My.breakSyllables(displayLabel),
                modifier = Modifier.weight(1f).padding(start = if (selected) 9.dp else 12.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
                style = TextStyle(color = ink, fontSize = 13.sp, fontFamily = LocalUiFontFamily.current),
            )
            if (lock != null) BasicText(LOCK_GLYPH, style = TextStyle(color = ink, fontSize = 11.sp))
        }
        if (lock != null && showLock) {
            AfText(lock, Modifier.testTag("classic-lock-$name").padding(start = 12.dp, end = 8.dp, bottom = 6.dp), size = 11.sp, color = AutoFlowColors.Amber)
        }
        // A hover hint must not move the rows under a stationary pointer while the sidebar scrolls.
        if (lock != null && hovered && !showLock) {
            Popup(alignment = Alignment.TopStart, offset = IntOffset(with(LocalDensity.current) { 190.dp.roundToPx() }, 0)) {
                AfText(My.breakSyllables(lock), Modifier.width(260.dp).background(AutoFlowColors.Panel)
                    .border(AutoFlowShapes.Border, AutoFlowColors.Line).padding(10.dp), size = 11.sp, color = AutoFlowColors.Amber)
            }
        }
    }
}

const val LOCK_GLYPH = "🔒"

/** Logo — gradient purple→cyan rounded square with a white play triangle (docs/DESIGN.md §1). */
@Composable
fun AutoFlowLogo(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        drawRoundRect(
            brush = Brush.linearGradient(listOf(AutoFlowColors.Purple, AutoFlowColors.Cyan)),
            cornerRadius = CornerRadius(this.size.minDimension * 0.27f),
        )
        val w = this.size.width
        val h = this.size.height
        val triangle = Path().apply {
            moveTo(w * 0.38f, h * 0.28f)
            lineTo(w * 0.38f, h * 0.72f)
            lineTo(w * 0.74f, h * 0.50f)
            close()
        }
        drawPath(triangle, Color.White)
    }
}
