package io.github.alalkipgen.autoflow.ui.shell

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.*
import io.github.alalkipgen.autoflow.ui.settings.KeyState
import io.github.alalkipgen.autoflow.ui.settings.providerStatus
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlin.test.*

/** Shipped Classic navigation: real pointer targets, scroll reachability and live open-lock retranslation. */
@OptIn(ExperimentalTestApi::class)
class ClassicLanguageLayoutTest {
    @Test fun portrait100() = matrix(412, 1f)
    @Test fun portrait130() = matrix(412, 1.3f)
    @Test fun portrait200() = matrix(412, 2f)
    @Test fun wide100() = matrix(840, 1f)
    @Test fun wide130() = matrix(840, 1.3f)
    @Test fun wide200() = matrix(840, 2f)

    private fun matrix(width: Int, scale: Float) {
        for (initial in UiLanguage.entries) for (bundled in listOf(true, false))
            navigationCase(width, scale, initial, bundled)
    }

    private fun navigationCase(width: Int, scale: Float, initial: UiLanguage, bundled: Boolean) = runDesktopComposeUiTest(width = width, height = 760) {
        val fs = InMemoryFileSystem()
        val current = CurrentProject()
        val model = ShellModel(ProjectSession(fs, ProjectStore(fs), ProjectIndex(fs, "/app/projects.json"), current))
        var language by mutableStateOf(initial)
        var selected by mutableStateOf("Dashboard")
        val routes = mutableListOf<String>()
        setContent {
            CompositionLocalProvider(LocalUiLanguage provides language, LocalDensity provides Density(1f, scale),
                LocalUiFontFamily provides if (bundled) BurmeseTestFont.family else FontFamily.Default) {
                val locks = model.current(language).locks
                val content: @Composable () -> Unit = {
                    if (selected == MORE) MoreScreen(locks, { selected = it; routes += it })
                    else AfText("route:$selected")
                }
                val status = providerStatus(true, KeyState.MISSING, language)
                if (width < 600) PhoneShell(status, selected = selected, onSelect = { selected = it; routes += it }, locks = locks, content = content)
                else AppShell(status, selected = selected, onSelect = { selected = it; routes += it }, locks = locks, content = content)
            }
        }
        fun tab(rawLabel: String) = onNode(hasContentDescription(My.screen(rawLabel, language)) and isSelectable())
        fun lockNote() = if (width >= 600) onNode(hasText(UiText.LOCK_NO_PROJECT.text(language)) and hasTestTag("classic-lock-Shots"))
            else onNodeWithText(UiText.LOCK_NO_PROJECT.text(language))
        val locked = tab("Shots")
        if (width >= 600) locked.performScrollTo()
        locked.assertIsDisplayed().performMouseInput { click() }
        waitForIdle()
        assertEquals("Dashboard", selected)
        assertTrue(routes.isEmpty(), "a locked destination must never dispatch a translated or raw route")
        lockNote().assertIsDisplayed()
        assertEquals(emptyList<String>(), textFitProblems(width, scale), "$initial/bundled=$bundled")
        // Retranslate an already-open note without clicking the gate again.
        runOnIdle { language = if (initial == UiLanguage.ENGLISH) UiLanguage.BURMESE else UiLanguage.ENGLISH }
        lockNote().assertIsDisplayed()
        onNodeWithText(UiText.LOCK_NO_PROJECT.text(initial)).assertDoesNotExist()
        assertEquals(emptyList<String>(), textFitProblems(width, scale), "live $language/bundled=$bundled")
        if (width < 600) {
            val more = tab(MORE).assertIsDisplayed()
            assertTrue(more.fetchSemanticsNode().boundsInRoot.height >= 48f)
            more.performMouseInput { click() }
            waitForIdle()
            onNodeWithText(UiText.SETTINGS_TITLE.text(language)).performScrollTo().assertIsDisplayed().performMouseInput { click() }
        } else tab("Settings").performScrollTo().assertIsDisplayed().performMouseInput { click() }
        waitForIdle()
        assertEquals("Settings", selected)
        assertEquals(if (width < 600) listOf(MORE, "Settings") else listOf("Settings"), routes)
        assertNull(current.state.value)
        assertEquals(emptyList<String>(), textFitProblems(width, scale))
    }
}
