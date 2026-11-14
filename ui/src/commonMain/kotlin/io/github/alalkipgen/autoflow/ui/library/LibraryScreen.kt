package io.github.alalkipgen.autoflow.ui.library

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
 * Studio v2 Library screen (5A-7, DESIGN.md §7 "Library").
 * Sections: Brand Kits · Voices · Lexicon · Music · Fonts · Presets.
 * Full implementation deferred to 5A-7 (LibraryStore).
 */
@Composable
fun LibraryScreen(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(screenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AfText("Library", size = 20.sp, bold = true)
        AfText("Brand Kits · Voices · Lexicon · Music · Fonts · Presets (5A-7).", muted = true)
    }
}
