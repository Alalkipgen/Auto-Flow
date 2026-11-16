package io.github.alalkipgen.autoflow.ui.projects

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class DashboardLanguageLayoutTest {
    @Test fun portrait100() = matrix(412, 1f)
    @Test fun portrait130() = matrix(412, 1.3f)
    @Test fun portrait200() = matrix(412, 2f)
    @Test fun wide100() = matrix(840, 1f)
    @Test fun wide130() = matrix(840, 1.3f)
    @Test fun wide200() = matrix(840, 2f)

    private fun matrix(width: Int, scale: Float) {
        for (language in UiLanguage.entries) for (bundled in listOf(true, false))
            createCase(width, scale, language, bundled)
    }

    private fun createCase(width: Int, scale: Float, language: UiLanguage, bundled: Boolean) = runDesktopComposeUiTest(width = width, height = 760) {
        val fs = InMemoryFileSystem()
        val current = CurrentProject()
        val compact = width < 600
        val model = DashboardModel(fs, ProjectStore(fs), ProjectIndex(fs, "/app/projects.json"),
            SettingsStore(fs, "/app/settings.json"), FakePicker(folder = "/videos"), current,
            projectsHome = if (compact) "/videos" else null)
        var opened = 0
        setContent {
            CompositionLocalProvider(LocalUiLanguage provides language, LocalCompact provides compact,
                LocalDensity provides Density(1f, scale),
                LocalUiFontFamily provides if (bundled) BurmeseTestFont.family else FontFamily.Default) {
                Box { DashboardScreen(model, onOpened = { opened++ }) }
            }
        }
        onNodeWithText(UiText.NEW_MUSIC_VIDEO.text(language)).performScrollTo().assertIsDisplayed().performMouseInput { click() }
        onNodeWithContentDescription(UiText.PROJECT_NAME.text(language)).performScrollTo().assertIsDisplayed().performTextInput("မိုးရွာသောနေ့")
        if (!compact) onNodeWithText(UiText.BROWSE.text(language)).performScrollTo().assertIsDisplayed().performMouseInput { click() }
        waitForIdle()
        onNodeWithText(UiText.CREATE.text(language)).performScrollTo().assertIsDisplayed().performMouseInput { click() }
        waitForIdle()
        assertEquals(1, opened, "$width/$scale/${language.code}/$bundled")
        assertEquals("မိုးရွာသောနေ့", current.state.value!!.project.name)
        assertEquals(Mode.MUSIC_VIDEO, current.state.value!!.project.mode)
        onNodeWithText("မိုးရွာသောနေ့").performScrollTo().assertIsDisplayed()
        assertEquals(emptyList<String>(), textFitProblems(width, scale))
    }
}
