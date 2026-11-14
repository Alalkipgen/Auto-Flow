package io.github.alalkipgen.autoflow.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily


/** Studio v2 top-level destinations (5A-6, DESIGN.md §7). */
enum class StudioV2Dest(val label: String) {
    HOME("Home"),
    PROJECTS("Projects"),
    LIBRARY("Library"),
    JOBS("Jobs"),
    ;
    companion object {
        val ALL = entries
    }
}

/** Width breakpoints (dp) for Studio v2 adaptive layout (5A-6). */
private val RAIL_WIDTH = 600.dp
private val THREE_PANE_WIDTH = 840.dp

/**
 * Studio v2 shell: bottom nav < 600 dp, nav rail ≥ 600 dp, three-pane ≥ 840 dp (5A-6, DESIGN.md §7).
 * [jobsBadge]: running job count, negative = at least one failed.
 * Uses one set of composables for every width — no desktop-only layout (D-22).
 */
@Composable
fun StudioV2Shell(
    selected: StudioV2Dest,
    onSelect: (StudioV2Dest) -> Unit,
    jobsBadge: Int,            // > 0: running count; < 0: failure; 0: none
    windowWidthDp: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val useRail = windowWidthDp >= RAIL_WIDTH.value
    if (useRail) {
        Row(modifier.fillMaxSize()) {
            V2NavRail(selected, onSelect, jobsBadge)
            Box(Modifier.fillMaxSize()) { content() }
        }
    } else {
        Column(modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f)) { content() }
            V2BottomNav(selected, onSelect, jobsBadge)
        }
    }
}

@Composable
private fun V2NavRail(
    selected: StudioV2Dest,
    onSelect: (StudioV2Dest) -> Unit,
    jobsBadge: Int,
) {
    Column(
        Modifier
            .width(80.dp)
            .fillMaxHeight()
            .background(AutoFlowColors.Panel)
            .border(AutoFlowShapes.Border, AutoFlowColors.Line),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        StudioV2Dest.ALL.forEach { dest ->
            val on = dest == selected
            val ink = if (on) Color.White else AutoFlowColors.Muted
            Column(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = on, role = Role.Tab) { onSelect(dest) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (on) Box(Modifier.height(2.dp).fillMaxWidth().background(AutoFlowColors.Purple))
                JobsBadgeLabel(dest, jobsBadge, ink)
            }
        }
    }
}

@Composable
private fun V2BottomNav(
    selected: StudioV2Dest,
    onSelect: (StudioV2Dest) -> Unit,
    jobsBadge: Int,
) {
    Box(Modifier.fillMaxWidth().height(AutoFlowShapes.Border).background(AutoFlowColors.Line))
    Row(Modifier.fillMaxWidth().background(AutoFlowColors.Panel)) {
        StudioV2Dest.ALL.forEach { dest ->
            val on = dest == selected
            val ink = if (on) Color.White else AutoFlowColors.Muted
            Column(
                Modifier
                    .weight(1f)
                    .selectable(selected = on, role = Role.Tab) { onSelect(dest) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.fillMaxWidth().height(3.dp).background(if (on) AutoFlowColors.Purple else Color.Transparent))
                JobsBadgeLabel(dest, jobsBadge, ink, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun JobsBadgeLabel(
    dest: StudioV2Dest,
    jobsBadge: Int,
    ink: Color,
    fontSize: androidx.compose.ui.unit.TextUnit = 12.sp,
) {
    val showBadge = dest == StudioV2Dest.JOBS && jobsBadge != 0
    val badgeColor = if (jobsBadge < 0) AutoFlowColors.Amber else AutoFlowColors.Green
    val label = if (showBadge) {
        val n = kotlin.math.abs(jobsBadge)
        "${dest.label} ($n)"
    } else {
        dest.label
    }
    BasicText(
        label,
        Modifier.padding(horizontal = 2.dp, vertical = 10.dp),
        style = TextStyle(
            color = if (showBadge && jobsBadge < 0) badgeColor else ink,
            fontWeight = if (dest == StudioV2Dest.JOBS && jobsBadge != 0) FontWeight.Bold else if (ink == Color.White) FontWeight.Bold else FontWeight.Normal,
            fontSize = fontSize,
            fontFamily = LocalUiFontFamily.current,
        ),
        maxLines = 1,
        overflow = TextOverflow.Clip,
        autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = fontSize, stepSize = 0.5.sp),
    )
}
