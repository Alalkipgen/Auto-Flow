package io.github.alalkipgen.autoflow.ui.shell

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.settings.ProviderStatusModel
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

/** Desktop window content wired to the Koin graph (see `uiModule`). */
@Composable
fun App() {
    val model = koinInject<ProviderStatusModel>()
    val status by model.status.collectAsState(model.current())
    AppShell(providerStatus = status)
}

/**
 * Desktop window content: title bar with [providerStatus] (DESIGN.md §2), pipeline sidebar and an empty content area.
 * No navigation, selection or lock state yet (Phase 1C, gates from WorkflowRules).
 */
@Composable
fun AppShell(providerStatus: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(AutoFlowColors.Background)) {
        TitleBar(providerStatus)
        Row(Modifier.fillMaxSize()) {
            Sidebar()
            Box(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun TitleBar(providerStatus: String) {
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
            Spacer(Modifier.weight(1f))
            BasicText(providerStatus, style = TextStyle(color = AutoFlowColors.Muted, fontSize = 13.sp))
        }
        Box(Modifier.fillMaxWidth().height(AutoFlowShapes.Border).background(AutoFlowColors.Line))
    }
}

@Composable
private fun Sidebar() {
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
                items.forEach { item ->
                    BasicText(
                        text = item,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                        style = TextStyle(color = AutoFlowColors.Muted, fontSize = 13.sp),
                    )
                }
            }
        }
        Box(Modifier.width(AutoFlowShapes.Border).fillMaxHeight().background(AutoFlowColors.Line))
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
