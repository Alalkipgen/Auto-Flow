package io.github.alalkipgen.autoflow.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.input.InputScreen
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.DashboardScreen
import io.github.alalkipgen.autoflow.ui.settings.ProviderStatusModel
import io.github.alalkipgen.autoflow.ui.settings.SettingsScreen
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes
import org.koin.compose.koinInject

/** Sidebar sections in pipeline order — docs/DESIGN.md §2. */
val SidebarSections: List<Pair<String, List<String>>> = listOf(
    "Project" to listOf(
        "Dashboard", "Input", "Storyboard", "Shots", "References", "Flow Handoff", "Timeline", "Export",
    ),
    "Mode" to listOf("Music Video", "Movie Recap"),
    "System" to listOf("Settings"),
)

/** Desktop window content wired to the Koin graph (see `uiModule`). Starts on the Dashboard. */
@Composable
fun App() {
    val model = koinInject<ProviderStatusModel>()
    val status by model.status.collectAsState(model.current())
    val project by koinInject<CurrentProject>().state.collectAsState()
    var screen by remember { mutableStateOf("Dashboard") }
    AppShell(status, selected = screen, onSelect = { screen = it }, projectName = project?.project?.name) {
        when (screen) {
            "Dashboard" -> DashboardScreen(koinInject(), onOpened = { screen = "Input" })
            "Input" -> InputScreen(koinInject(), koinInject(), onGenerated = { screen = "Storyboard" })
            "Settings" -> SettingsScreen(koinInject())
            else -> ScreenPlaceholder(screen)
        }
    }
}

/**
 * Desktop window content: title bar with [providerStatus] (DESIGN.md §2), pipeline sidebar with [selected] highlighted,
 * and [content]. Lock state comes with the project screens (gates from WorkflowRules).
 */
@Composable
fun AppShell(
    providerStatus: String,
    modifier: Modifier = Modifier,
    selected: String? = null,
    onSelect: (String) -> Unit = {},
    projectName: String? = null,
    content: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxSize().background(AutoFlowColors.Background)) {
        TitleBar(providerStatus, projectName)
        Row(Modifier.fillMaxSize()) {
            Sidebar(selected, onSelect)
            Box(Modifier.fillMaxSize()) { content() }
        }
    }
}

/** Content for a sidebar entry whose screen is not built yet. */
@Composable
fun ScreenPlaceholder(name: String) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AfText(name, size = 18.sp, bold = true)
        AfText("This screen is not built yet.", muted = true)
    }
}

@Composable
private fun TitleBar(providerStatus: String, projectName: String?) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(AutoFlowColors.TitleBar)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
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
            Spacer(Modifier.weight(1f))
            BasicText(providerStatus, style = TextStyle(color = AutoFlowColors.Muted, fontSize = 13.sp))
        }
        Box(Modifier.fillMaxWidth().height(AutoFlowShapes.Border).background(AutoFlowColors.Line))
    }
}

@Composable
private fun Sidebar(selected: String?, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .width(190.dp)
                .fillMaxHeight()
                .background(AutoFlowColors.Panel)
                .padding(horizontal = 10.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SidebarSections.forEach { (section, items) ->
                // DESIGN.md §2 / mockup: the System section sits at the bottom of the sidebar.
                if (section == "System") Spacer(Modifier.weight(1f))
                BasicText(
                    text = section.uppercase(),
                    modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp),
                    style = TextStyle(color = AutoFlowColors.Muted, fontSize = 10.sp, letterSpacing = 1.sp),
                )
                items.forEach { item -> SidebarItem(item, item == selected) { onSelect(item) } }
            }
        }
        Box(Modifier.width(AutoFlowShapes.Border).fillMaxHeight().background(AutoFlowColors.Line))
    }
}

/** Selected: purple left bar + fading purple background, white text (mockup `.side a.on`). */
@Composable
private fun SidebarItem(name: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
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
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .height(IntrinsicSize.Min),
    ) {
        if (selected) Box(Modifier.width(3.dp).fillMaxHeight().background(AutoFlowColors.Purple))
        BasicText(
            text = name,
            modifier = Modifier.padding(start = if (selected) 9.dp else 12.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
            style = TextStyle(color = if (selected) Color.White else AutoFlowColors.Muted, fontSize = 13.sp),
        )
    }
}

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
