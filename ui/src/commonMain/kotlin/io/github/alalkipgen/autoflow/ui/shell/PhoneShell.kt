package io.github.alalkipgen.autoflow.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes

/** Bottom navigation on a phone (DESIGN.md §4): screen name → tab label. The rest sits behind [MORE]. */
val PhoneTabs: List<Pair<String, String>> = listOf(
    "Dashboard" to "Projects", "Storyboard" to "Storyboard", "Shots" to "Shots", "Inbox" to "Inbox", MORE to MORE,
)

/** The "More" list: the pipeline screens without a tab (Input first, it starts every project), then Settings. */
val MoreScreens: List<String> = listOf("Input", "References", "Flow Handoff", "Timeline", "Export", "Settings")

const val MORE = "More"

/** Where Android's Back goes from [screen]: More's screens → More, the other tabs → Projects, Projects → null (leave). */
fun backTarget(screen: String): String? = when (screen) {
    "Dashboard" -> null
    in MoreScreens -> MORE
    else -> "Dashboard"
}

/**
 * Phone shell: top bar with the project name and [providerStatus], [top] (job banner, stepper), [content], and the
 * bottom tabs. Tapping a locked tab ([locks]) explains what to do first instead of opening it.
 */
@Composable
fun PhoneShell(
    providerStatus: String,
    modifier: Modifier = Modifier,
    selected: String? = null,
    onSelect: (String) -> Unit = {},
    projectName: String? = null,
    locks: Map<String, String> = emptyMap(),
    top: @Composable () -> Unit = {},
    content: @Composable () -> Unit = {},
) {
    var note by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxSize().background(AutoFlowColors.Background)) {
        PhoneTopBar(providerStatus, projectName)
        top()
        Box(Modifier.fillMaxWidth().weight(1f)) { content() }
        note?.let { AfText(it, Modifier.fillMaxWidth().background(AutoFlowColors.Panel).padding(horizontal = 14.dp, vertical = 8.dp), color = AutoFlowColors.Amber, size = 12.sp) }
        BottomTabs(selected, locks) { screen ->
            val lock = locks[screen]
            note = lock
            if (lock == null) onSelect(screen)
        }
    }
}

@Composable
private fun PhoneTopBar(providerStatus: String, projectName: String?) {
    Column(Modifier.fillMaxWidth().background(AutoFlowColors.TitleBar).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AutoFlowLogo(22.dp)
            BasicText(
                "Auto Flow",
                style = TextStyle(color = AutoFlowColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
            )
            projectName?.let {
                BasicText("·  $it", Modifier.weight(1f), style = TextStyle(color = AutoFlowColors.Muted, fontSize = 13.sp, fontFamily = LocalUiFontFamily.current), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        BasicText(providerStatus, Modifier.padding(top = 2.dp), style = TextStyle(color = AutoFlowColors.Muted, fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Box(Modifier.fillMaxWidth().height(AutoFlowShapes.Border).background(AutoFlowColors.Line))
}

@Composable
private fun BottomTabs(selected: String?, locks: Map<String, String>, onTap: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().height(AutoFlowShapes.Border).background(AutoFlowColors.Line))
    Row(Modifier.fillMaxWidth().background(AutoFlowColors.Panel)) {
        PhoneTabs.forEach { (screen, label) ->
            val on = selected == screen || (screen == MORE && selected in MoreScreens)
            val locked = locks[screen] != null
            val ink = when {
                on -> Color.White
                locked -> AutoFlowColors.Muted.copy(alpha = 0.5f)
                else -> AutoFlowColors.Muted
            }
            Column(
                Modifier
                    .weight(1f)
                    .selectable(selected = on, role = Role.Tab) { onTap(screen) }
                    .then(if (locked) Modifier.semantics { stateDescription = "locked" } else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.fillMaxWidth().height(3.dp).background(if (on) AutoFlowColors.Purple else Color.Transparent))
                // Shrinks instead of "Storyboa…" when the phone's font size is large (owner's v0.1.6 screenshot).
                BasicText(
                    if (locked) "$label $LOCK_GLYPH" else label,
                    Modifier.padding(horizontal = 1.dp, vertical = 14.dp),
                    style = TextStyle(color = ink, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal),
                    maxLines = 1,
                    // Clip, not Ellipsis: autoSize detects an ellipsized line only on Android, a clipped one everywhere.
                    overflow = TextOverflow.Clip,
                    autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = 12.sp, stepSize = 0.5.sp),
                )
            }
        }
    }
}

/** The "More" tab: one row per [MoreScreens] entry; a locked row says what to do first. */
@Composable
fun MoreScreen(locks: Map<String, String>, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AfText(MORE, size = 18.sp, bold = true)
        MoreScreens.forEach { screen ->
            val lock = locks[screen]
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(AutoFlowShapes.Card)
                    .background(AutoFlowColors.Panel)
                    .border(AutoFlowShapes.Border, AutoFlowColors.Line, AutoFlowShapes.Card)
                    .clickable(enabled = lock == null, role = Role.Button) { onSelect(screen) }
                    .then(if (lock != null) Modifier.semantics { stateDescription = "locked" } else Modifier)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AfText(if (lock != null) "$screen $LOCK_GLYPH" else screen, size = 15.sp, muted = lock != null)
                lock?.let { AfText(it, color = AutoFlowColors.Amber, size = 12.sp) }
            }
        }
    }
}
