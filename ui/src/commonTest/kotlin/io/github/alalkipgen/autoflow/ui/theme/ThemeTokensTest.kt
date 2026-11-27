package io.github.alalkipgen.autoflow.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

/** DESIGN.md §9.2: text roles reach WCAG AA (4.5:1) on the backgrounds they are allowed on. */
class ThemeTokensTest {
    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + .05f) / (lo + .05f)
    }

    private fun assertAa(name: String, ink: Color, background: Color) {
        val ratio = contrast(ink, background)
        assertTrue(ratio >= 4.5f, "$name is $ratio:1")
    }

    @Test fun textRolesPassAaWhereTheyAreUsed() = with(AutoFlowColors) {
        assertAa("Faint on Background", Faint, Background)
        for (surface in listOf(Panel, Card)) {
            assertAa("Muted on $surface", Muted, surface)
            assertAa("PrimaryText on $surface", PrimaryText, surface)
            assertAa("Text on $surface", Text, surface)
        }
        assertAa("Text on Surface3", Text, Surface3)
        assertAa("white on PrimaryFill", Color.White, PrimaryFill)
    }

    @Test fun everyToneWordIsReadableOnItsContainer() {
        for (tone in Tone.entries) for (surface in listOf(AutoFlowColors.Panel, AutoFlowColors.Card)) {
            assertAa("$tone on its container over $surface", tone.text, tone.container.compositeOver(surface))
        }
    }

    @Test fun typeScaleKeepsTheFontsOwnLineHeightAndReadableSizes() {
        assertTrue(AfType.entries.all { it.size.value >= 11f })
        assertTrue(AfType.Body.size.value >= 14f && AfType.ScreenTitle.size > AfType.CardTitle.size)
        assertTrue(AfType.entries.single { it.numbers } == AfType.Numbers)
    }

    @Test fun desktopShellFitsTheWidthClasses() = with(AfDesktop) {
        assertTrue(Medium < Expanded && Expanded < Wide)
        // At the smallest expanded width the rail, both panes and the gaps fit.
        assertTrue(Rail + ListPaneMin + PaneGap + DetailPane + ContentSide * 2 <= Wide)
        assertTrue(Sidebar + ListPaneMax + PaneGap + DetailPane + ContentSide * 2 <= 1440.dp)
        assertTrue(RailItem >= AfSize.Touch) // the rail is also used at medium (touch) widths
        assertTrue(AfSize.ContentMaxMedium + Rail <= Expanded)
    }
}
