package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * True in a phone-sized window (narrower than [COMPACT_WIDTH]): bottom navigation, single column, rows that wrap
 * (DESIGN.md §4). `App` provides it from the window width, so a narrow desktop window gets the phone layout too.
 */
val LocalCompact = staticCompositionLocalOf { false }

val COMPACT_WIDTH: Dp = 600.dp

/** Padding around a screen's content: 24 dp on the desktop, 14 dp on a phone. */
val screenPadding: Dp
    @Composable @ReadOnlyComposable
    get() = if (LocalCompact.current) 14.dp else 24.dp

/** Buttons or chips side by side that continue on the next line when the screen is too narrow. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WrapRow(modifier: Modifier = Modifier, spacing: Dp = 8.dp, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalArrangement = Arrangement.spacedBy(spacing),
        itemVerticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * A text field with its buttons: one row on the desktop (the field takes the free width), on a phone the field on
 * its own line and the buttons wrapping below it. [field] must apply the modifier it is given.
 */
@Composable
fun FieldRow(field: @Composable (Modifier) -> Unit, actions: @Composable () -> Unit) {
    if (LocalCompact.current) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            field(Modifier.fillMaxWidth())
            WrapRow { actions() }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            field(Modifier.weight(1f))
            actions()
        }
    }
}
