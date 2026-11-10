package io.github.alalkipgen.autoflow.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Dark studio palette — docs/DESIGN.md §1. */
object AutoFlowColors {
    val Background = Color(0xFF0B1020)
    val Panel = Color(0xFF131A2E)
    val Card = Color(0xFF1A2340)
    val Line = Color(0xFF263153)
    val Text = Color(0xFFE6EAF5)
    val Muted = Color(0xFF8A94B3)

    /** Primary actions. */
    val Purple = Color(0xFF8B5CF6)

    /** Flow / external. */
    val Cyan = Color(0xFF22D3EE)

    /** Accepted. */
    val Green = Color(0xFF22C55E)

    /** Stale / warning. */
    val Amber = Color(0xFFF59E0B)

    /** Title bar background, taken from docs/mockups/autoflow-ui.html. */
    val TitleBar = Color(0xFF0E1428)
}

/** Radius 12, subtle 1px borders — docs/DESIGN.md §1. */
object AutoFlowShapes {
    val Radius = 12.dp
    val Border = 1.dp
    val Card = RoundedCornerShape(Radius)
}
