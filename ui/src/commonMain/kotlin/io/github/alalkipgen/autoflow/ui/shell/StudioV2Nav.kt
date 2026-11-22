package io.github.alalkipgen.autoflow.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.clickable
import io.github.alalkipgen.autoflow.ui.components.MediumContent
import io.github.alalkipgen.autoflow.ui.components.ambientBackground
import io.github.alalkipgen.autoflow.ui.theme.GlassColors
import io.github.alalkipgen.autoflow.ui.components.paneScroll
import io.github.alalkipgen.autoflow.ui.i18n.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.icons.IconTile
import io.github.alalkipgen.autoflow.ui.icons.TooltipHost
import io.github.alalkipgen.autoflow.ui.icons.afTooltip
import io.github.alalkipgen.autoflow.ui.theme.AfDesktop
import io.github.alalkipgen.autoflow.ui.theme.AfRadius
import io.github.alalkipgen.autoflow.ui.theme.AfSize
import io.github.alalkipgen.autoflow.ui.theme.AfSpace
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import io.github.alalkipgen.autoflow.ui.theme.afTextStyle


/** Studio v2 top-level destinations (5A-6, DESIGN.md §7); [label] is the Burmese nav text (5B-13), never saved. */
enum class StudioV2Dest(val textKey: UiText) {
    HOME(UiText.HOME),
    PROJECTS(UiText.PROJECTS),
    LIBRARY(UiText.LIBRARY),
    JOBS(UiText.JOBS),
    ;
    val label: String get() = textKey.en
    companion object {
        val ALL = entries
    }
}

/** Phosphor icon of a destination (DESIGN.md §9.3): Fill when active, Regular otherwise. */
internal fun StudioV2Dest.icon(active: Boolean): ImageVector = when (this) {
    StudioV2Dest.HOME -> if (active) AfIcons.Fill.Home else AfIcons.Regular.Home
    StudioV2Dest.PROJECTS -> if (active) AfIcons.Fill.Folder else AfIcons.Regular.Folder
    StudioV2Dest.LIBRARY -> if (active) AfIcons.Fill.Book else AfIcons.Regular.Book
    StudioV2Dest.JOBS -> if (active) AfIcons.Fill.Jobs else AfIcons.Regular.Jobs
}

/** A create shortcut in the desktop sidebar ("ဖန်တီးမယ်"), only for a mode the app can open today. */
class ShellShortcut(val label: UiText, val icon: ImageVector, val colour: Color, val tag: String, val onClick: () -> Unit)

/** Sidebar / rail extras (DESIGN.md §9.8); each shows only when the app passes a real action. */
class ShellActions(
    val onNewProject: (() -> Unit)? = null,
    val onOpenSettings: (() -> Unit)? = null,
    val create: List<ShellShortcut> = emptyList(),
)

/** Which nav the shell shows (DESIGN.md §9.8). */
enum class NavStyle { BOTTOM, RAIL, SIDEBAR }

/** Bottom nav < 600 dp; the 72 dp rail in editors and below 1200 dp; the 244 dp sidebar from 1200 dp. */
fun navStyle(windowWidthDp: Float, editor: Boolean): NavStyle = when {
    windowWidthDp < AfDesktop.Medium.value -> NavStyle.BOTTOM
    editor || windowWidthDp < AfDesktop.Wide.value -> NavStyle.RAIL
    else -> NavStyle.SIDEBAR
}

/**
 * Studio v2 shell (5A-6, DESIGN.md §7 and §9.8): phone bottom nav, desktop rail or sidebar ([navStyle]); [editor] =
 * a project editor is open (it keeps the rail for room). [jobsBadge]: negative Needs-you count, else positive
 * running count, else none. One set of composables for every width (D-22).
 */
@Composable
fun StudioV2Shell(
    selected: StudioV2Dest,
    onSelect: (StudioV2Dest) -> Unit,
    jobsBadge: Int,            // > 0: running/queued; < 0: paused/failed; 0: none
    windowWidthDp: Float,
    modifier: Modifier = Modifier,
    editor: Boolean = false,
    actions: ShellActions = ShellActions(),
    ambient: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Glass tab screens (DESIGN.md §9.10) draw no background of their own: the ambient sits behind them (and behind
    // the phone nav, which turns translucent); it does not scroll.
    val behind = if (ambient) Modifier.ambientBackground() else Modifier
    TooltipHost(modifier.fillMaxSize()) {
        when (navStyle(windowWidthDp, editor)) {
            NavStyle.BOTTOM -> Column(Modifier.fillMaxSize().then(behind)) {
                Box(Modifier.fillMaxWidth().weight(1f)) { content() }
                V2BottomNav(selected, onSelect, jobsBadge, glass = ambient)
            }
            NavStyle.RAIL -> Row(Modifier.fillMaxSize()) {
                V2NavRail(selected, onSelect, jobsBadge, actions)
                Box(Modifier.fillMaxSize().then(behind)) { MediumContent(windowWidthDp, content) }
            }
            NavStyle.SIDEBAR -> Row(Modifier.fillMaxSize()) {
                V2Sidebar(selected, onSelect, jobsBadge, actions)
                Box(Modifier.fillMaxSize().then(behind)) { content() }
            }
        }
    }
}

/** A nav destination: one 48 dp selectable tab named by its label (tests and screen readers use the label). */
private fun Modifier.navItem(dest: StudioV2Dest, on: Boolean, label: String, onSelect: (StudioV2Dest) -> Unit) =
    semantics { contentDescription = label }.selectable(selected = on, role = Role.Tab) { onSelect(dest) }

@Composable
private fun navLabel(text: String, on: Boolean, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start, sidebar: Boolean = false) {
    val type = if (sidebar) AfType.RowTitle else AfType.Micro
    BasicText(My.breakSyllables(text), modifier,
        style = afTextStyle(if (on) AutoFlowColors.PrimaryText else if (sidebar) AutoFlowColors.Text else AutoFlowColors.Muted,
            type.size, type.weight, LocalUiFontFamily.current).copy(textAlign = align))
}

/**
 * Phone: icon in a pill (active = primary container + Fill icon), micro label below, Jobs badge on the icon. [glass] =
 * over the ambient background: Base .72 with a White .08 top line.
 */
@Composable
private fun V2BottomNav(selected: StudioV2Dest, onSelect: (StudioV2Dest) -> Unit, jobsBadge: Int, glass: Boolean = false) {
    Column(Modifier.fillMaxWidth().background(if (glass) GlassColors.NavFill else AutoFlowColors.TitleBar)) {
        Box(Modifier.fillMaxWidth().height(AutoFlowShapes.Border).background(if (glass) GlassColors.NavLine else AutoFlowColors.Line))
        Row(Modifier.fillMaxWidth().padding(horizontal = AfSpace.Xs, vertical = 6.dp)) {
            StudioV2Dest.ALL.forEach { dest ->
                val on = dest == selected
                val label = tr(dest.textKey)
                Column(Modifier.weight(1f).heightIn(min = AfSize.Touch).navItem(dest, on, label, onSelect).padding(vertical = AfSpace.Xs),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box {
                        Box(Modifier.clip(CircleShape).background(if (on) AutoFlowColors.PrimaryFill.copy(alpha = .15f) else Color.Transparent)
                            .padding(horizontal = 16.dp, vertical = 3.dp)) {
                            AfIcon(dest.icon(on), tint = if (on) AutoFlowColors.PrimaryText else AutoFlowColors.Muted)
                        }
                        if (dest == StudioV2Dest.JOBS) JobsBadge(jobsBadge, Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-5).dp))
                    }
                    navLabel(label, on, Modifier.fillMaxWidth().padding(horizontal = 2.dp), TextAlign.Center)
                }
            }
        }
    }
}

/** Brand tile: the Fill sparkle on the primary colour (sidebar and rail top). */
@Composable
private fun BrandTile() = IconTile(AfIcons.Fill.Spark, AutoFlowColors.PrimaryFill, size = 36.dp, radius = AfRadius.ButtonSmall)

/** 72 dp icon rail (editors, windows below 1200 dp and 600–839 dp): 48 dp items with tooltips and the Jobs badge. */
@Composable
private fun V2NavRail(selected: StudioV2Dest, onSelect: (StudioV2Dest) -> Unit, jobsBadge: Int, actions: ShellActions) {
    @Composable fun item(label: String, on: Boolean, icon: ImageVector, modifier: Modifier, badge: Int = 0) =
        Box(modifier.afTooltip(label).size(AfDesktop.RailItem).clip(RoundedCornerShape(AfRadius.Button))
            .background(if (on) AutoFlowColors.PrimaryFill.copy(alpha = .15f) else Color.Transparent), contentAlignment = Alignment.Center) {
            AfIcon(icon, tint = if (on) AutoFlowColors.PrimaryText else AutoFlowColors.Muted)
            if (badge != 0) JobsBadge(badge, Modifier.align(Alignment.TopEnd).offset(x = (-3).dp, y = 3.dp))
        }
    Row(Modifier.fillMaxHeight()) {
        Column(Modifier.width(AfDesktop.Rail).fillMaxHeight().background(AutoFlowColors.Background).padding(vertical = AfSpace.M),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AfSpace.S)) {
            BrandTile()
            Spacer(Modifier.height(AfSpace.S))
            StudioV2Dest.ALL.forEach { dest ->
                val on = dest == selected
                val label = tr(dest.textKey)
                item(label, on, dest.icon(on), Modifier.navItem(dest, on, label, onSelect), if (dest == StudioV2Dest.JOBS) jobsBadge else 0)
            }
            Spacer(Modifier.weight(1f))
            actions.onOpenSettings?.let { open ->
                val label = tr(UiText.SETTINGS_TITLE)
                item(label, false, AfIcons.Regular.Gear, Modifier.testTag("nav-settings").semantics { contentDescription = label }
                    .clickable(role = Role.Button, onClick = open))
            }
        }
        Box(Modifier.width(AutoFlowShapes.Border).fillMaxHeight().background(AutoFlowColors.Line))
    }
}

/** 244 dp sidebar (expanded windows from 1200 dp): brand, new project, nav rows, create shortcuts, Settings. */
@Composable
private fun V2Sidebar(selected: StudioV2Dest, onSelect: (StudioV2Dest) -> Unit, jobsBadge: Int, actions: ShellActions) {
    @Composable fun row(modifier: Modifier, on: Boolean = false, content: @Composable () -> Unit) =
        Row(modifier.fillMaxWidth().heightIn(min = AfDesktop.SidebarRow).clip(RoundedCornerShape(AfDesktop.SidebarRowRadius))
            .background(if (on) AutoFlowColors.PrimaryFill.copy(alpha = .15f) else Color.Transparent)
            .padding(horizontal = AfSpace.M, vertical = AfSpace.S - 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AfSpace.M)) { content() }
    Row(Modifier.fillMaxHeight()) {
        Column(Modifier.width(AfDesktop.Sidebar).fillMaxHeight().background(AutoFlowColors.Background).padding(AfSpace.L)) {
            Column(Modifier.weight(1f).paneScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AfSpace.Xs)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AfSpace.M)) {
                    BrandTile()
                    navLabel(tr(UiText.V3_APP_NAME), false, sidebar = true)
                }
                Spacer(Modifier.height(AfSpace.M))
                actions.onNewProject?.let { create ->
                    Row(Modifier.fillMaxWidth().heightIn(min = AfSize.Touch).clip(RoundedCornerShape(AfRadius.Button))
                        .background(AutoFlowColors.PrimaryFill).testTag("nav-new-project").clickable(role = Role.Button, onClick = create)
                        .padding(horizontal = AfSpace.M, vertical = AfSpace.S),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AfSpace.S, Alignment.CenterHorizontally)) {
                        AfIcon(AfIcons.Regular.Plus, tint = Color.White, size = AfSize.IconSmall)
                        BasicText(My.breakSyllables(tr(UiText.V3_PROJECT_CREATE)), Modifier.widthIn(max = 180.dp),
                            style = afTextStyle(Color.White, AfType.Button.size, AfType.Button.weight, LocalUiFontFamily.current))
                    }
                    Spacer(Modifier.height(AfSpace.S))
                }
                StudioV2Dest.ALL.forEach { dest ->
                    val on = dest == selected
                    val label = tr(dest.textKey)
                    row(Modifier.navItem(dest, on, label, onSelect), on) {
                        AfIcon(dest.icon(on), tint = if (on) AutoFlowColors.PrimaryText else AutoFlowColors.Muted)
                        navLabel(label, on, Modifier.weight(1f), sidebar = true)
                        if (dest == StudioV2Dest.JOBS) JobsBadge(jobsBadge)
                    }
                }
                if (actions.create.isNotEmpty()) {
                    Spacer(Modifier.height(AfSpace.M))
                    BasicText(My.breakSyllables(tr(UiText.CREATE)), Modifier.padding(horizontal = AfSpace.M, vertical = AfSpace.Xs),
                        style = afTextStyle(AutoFlowColors.Muted, AfType.Caption.size, AfType.Micro.weight, LocalUiFontFamily.current))
                    actions.create.forEach { shortcut ->
                        row(Modifier.testTag(shortcut.tag).clickable(role = Role.Button, onClick = shortcut.onClick)) {
                            IconTile(shortcut.icon, shortcut.colour, size = AfDesktop.SidebarTile, radius = 8.dp)
                            navLabel(tr(shortcut.label), false, Modifier.weight(1f), sidebar = true)
                        }
                    }
                }
            }
            actions.onOpenSettings?.let { open ->
                row(Modifier.testTag("nav-settings").clickable(role = Role.Button, onClick = open)) {
                    AfIcon(AfIcons.Regular.Gear)
                    navLabel(tr(UiText.SETTINGS_TITLE), false, Modifier.weight(1f), sidebar = true)
                }
            }
        }
        Box(Modifier.width(AutoFlowShapes.Border).fillMaxHeight().background(AutoFlowColors.Line))
    }
}

/**
 * Jobs badge (DESIGN.md §7 rule, kept in Phase 9): amber count of jobs that need you, else cyan running + queued;
 * hidden at 0, "9+" above 9. Tag `jobs-badge`, state = the words, never colour alone.
 */
@Composable
private fun JobsBadge(value: Int, modifier: Modifier = Modifier) {
    if (value == 0) return
    val description = jobsBadgeDescription(value, LocalUiLanguage.current)
    val n = kotlin.math.abs(value)
    Box(modifier.heightIn(min = AfSize.Badge).widthIn(min = AfSize.Badge).clip(CircleShape).background(jobsBadgeColor(value))
        .testTag("jobs-badge").semantics { stateDescription = description }.padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center) {
        BasicText(if (n > 9) "9+" else n.toString(),
            style = afTextStyle(AutoFlowColors.Background, AfType.Micro.size, AfType.Micro.weight, LocalUiFontFamily.current, numbers = true))
    }
}

internal fun jobsBadgeColor(value: Int): Color = if (value < 0) AutoFlowColors.Amber else AutoFlowColors.Cyan
internal fun jobsBadgeDescription(value: Int, language: UiLanguage): String =
    (if (value < 0) UiText.V3_JOBS_NEEDS_YOU else UiText.JOB_RUNNING).text(language) + " ${kotlin.math.abs(value)}"
