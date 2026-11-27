package io.github.alalkipgen.autoflow.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.textFitProblems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DESIGN.md §9.6: the strict no-clipping rule finds a text cut by a fixed height and passes free Burmese text at font 2.0. */
@OptIn(ExperimentalTestApi::class)
class TextFitStrictTest {
    private val lines = listOf("ပရောဂျက်အသစ်", "အလုပ်များ", "ဆက်တင်များ", "ဗီဒီယို", "စာတန်းထိုး", "ဂီတဗီဒီယို")

    @Test fun strictFindsAFixedHeightCutAndTheDefaultKeepsTheOldRule() = runDesktopComposeUiTest(width = 360, height = 640) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f), LocalUiFontFamily provides BurmeseTestFont.family) {
                Column { lines.forEach { AfText(it) }; Box(Modifier.height(12.dp).clipToBounds()) { AfText("ဖျက်မည်") } }
            }
        }
        val strict = textFitProblems(360, 2f, strict = true)
        assertTrue(strict.size == 1 && "ဖျက်မည်" in strict[0] && ("overflows its box" in strict[0] || "clipped by a parent" in strict[0]), "$strict")
        assertEquals(emptyList(), textFitProblems(360, 2f))
    }

    @Test fun freeBurmeseTextPassesStrictAtFont2() = runDesktopComposeUiTest(width = 360, height = 640) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f), LocalUiFontFamily provides BurmeseTestFont.family) {
                Column { lines.forEach { AfText(it) } }
            }
        }
        assertEquals(emptyList(), textFitProblems(360, 2f, strict = true))
    }
}
