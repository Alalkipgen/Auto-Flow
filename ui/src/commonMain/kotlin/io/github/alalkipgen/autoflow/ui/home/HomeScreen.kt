package io.github.alalkipgen.autoflow.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.screenPadding

/**
 * Studio v2 Home screen (5A-6, DESIGN.md §7 "Home").
 * Shows quick-access entry points: New Movie Recap, recent projects, Quick Recap wizard.
 * Full implementation deferred to 5B-11 (Quick Recap) and 5A-7 (Library).
 */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(screenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AfText("Home", size = 20.sp, bold = true)
        AfText("Quick Recap wizard and recent projects will appear here (5B-11).", muted = true)
    }
}
