package io.github.alalkipgen.autoflow.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.ui.shell.AutoFlowLogo
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/** Android start screen. Empty until the project list exists (Phase 3, docs/DESIGN.md §4). */
@Composable
fun ProjectsScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AutoFlowColors.Background)
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AutoFlowLogo(22.dp)
            BasicText(
                text = "Auto Flow",
                style = TextStyle(color = AutoFlowColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold),
            )
        }
        BasicText("Projects", style = TextStyle(color = AutoFlowColors.Text, fontSize = 18.sp))
    }
}
