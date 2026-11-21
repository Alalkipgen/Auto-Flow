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
    val PrimaryFill = Color(0xFF7C3AED) // white action text exceeds WCAG AA 4.5:1

    /** Flow / external. */
    val Cyan = Color(0xFF22D3EE)

    /** Accepted. */
    val Green = Color(0xFF22C55E)

    /** Stale / warning. */
    val Amber = Color(0xFFF59E0B)
    val Failed = Color(0xFFF87171)

    /** Title bar background, taken from docs/mockups/autoflow-ui.html; also the Phase 9 bar (bottom nav, sticky bar). */
    val TitleBar = Color(0xFF0E1428)

    // Phase 9 roles (docs/DESIGN.md §9.2): only the ones UI v3 lacks. WCAG contrast in brackets.
    /** Selected segment, neutral icon tile (Text on it 10.98:1). */
    val Surface3 = Color(0xFF222E55)

    /** Outline buttons and filter chips. */
    val LineStrong = Color(0xFF3A4878)

    /** Group labels, inactive step labels — on [Background] only (4.60:1; on [Panel] it fails AA). */
    val Faint = Color(0xFF717CA2)

    /** Primary-coloured text and icons on dark surfaces (6.99:1 on Panel; [Purple] as text is 4.08:1). */
    val PrimaryText = Color(0xFFA996FF)

    /** Info tone: hints, "uses the pause" (9.29:1 on its container). */
    val Info = Color(0xFF2DD4BF)

    /** Tone text on a 12–13 % container (success 8.10:1, warning 8.37:1, info 9.29:1 on Panel). */
    val SuccessText = Color(0xFF4ADE80)
    val WarningText = Color(0xFFFBBF24)
    val InfoText = Color(0xFF5EEAD4)

    /** Stale shot badge (was inline in ShotsScreen). */
    val Stale = Color(0xFFEF4444)

    /** Teleprompter well (was inline in VoiceBlockCard). */
    val Well = Color(0xFF0D1526)
}

/** Radius 12, subtle 1px borders — docs/DESIGN.md §1. */
object AutoFlowShapes {
    val Radius = 12.dp
    val Border = 1.dp
    val Card = RoundedCornerShape(Radius)
}
