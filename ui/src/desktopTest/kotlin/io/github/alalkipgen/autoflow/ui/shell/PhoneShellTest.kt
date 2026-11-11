package io.github.alalkipgen.autoflow.ui.shell

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.ai.registry.aiModule
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.platform.AppDirs
import io.github.alalkipgen.autoflow.ui.di.uiModule
import io.github.alalkipgen.autoflow.ui.fakePlatformModule
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

/** The phone layout (DESIGN.md §4): a window narrower than 600 dp gets bottom tabs instead of the sidebar. */
@OptIn(ExperimentalTestApi::class)
class PhoneShellTest {
    @AfterTest
    fun tearDown() = stopKoin()

    /** Android-like graph: projects live in a projects home, so there is no folder to pick (D-27). */
    private fun startPhoneKoin() = startKoin {
        allowOverride(true)
        modules(
            fakePlatformModule(InMemoryFileSystem()),
            module { single { AppDirs("/data/app", projectsHome = "/data/app/projects") } },
            uiModule,
            aiModule,
        )
    }.koin

    private fun tab(label: String) = hasText(label, substring = true) and isSelectable()

    @Test
    fun phoneWidthShowsBottomTabsAndCreatesAProject() = runDesktopComposeUiTest(width = 412, height = 900) {
        val koin = startPhoneKoin()
        setContent { App() }
        onNode(tab("Projects")).assertIsSelected()
        PhoneTabs.forEach { (_, label) -> onNode(tab(label)).assertExists() }
        // No sidebar: its section headers and the screens behind More are not on screen.
        onNodeWithText("MODE").assertDoesNotExist()
        onNodeWithText("Flow Handoff").assertDoesNotExist()
        onNodeWithText("Open folder…").assertDoesNotExist()

        onNodeWithText("New Music Video").performClick()
        waitForIdle()
        onNode(hasContentDescription("Project name") and hasSetTextAction()).performTextInput("Rain")
        onNodeWithText("Create").performClick()
        waitForIdle()
        assertEquals("Rain", koin.get<CurrentProject>().state.value?.project?.name)
        // A new project opens on Input, which lives behind More on a phone.
        onNode(tab(MORE)).assertIsSelected()
        onNodeWithText("Lyrics").assertExists()
        onNodeWithText("Step 1 of 6: Input").assertExists()
    }

    @Test
    fun lockedTabExplainsAndMoreOpensTheOtherScreens() = runDesktopComposeUiTest(width = 412, height = 900) {
        startPhoneKoin()
        setContent { App() }
        onNode(tab("Storyboard")).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "locked"))
        onNode(tab("Storyboard")).performClick()
        waitForIdle()
        onNode(tab("Projects")).assertIsSelected()
        onNodeWithText(ShellModel.NO_PROJECT_LOCK).assertExists()

        onNode(tab(MORE)).performClick()
        waitForIdle()
        onNode(tab(MORE)).assertIsSelected()
        onNode(tab("Projects")).assertIsNotSelected()
        MoreScreens.filter { it != "Settings" && it in ShellModel.SCREEN_STEPS }.forEach { onNodeWithText("$it $LOCK_GLYPH").assertExists() }
        onNodeWithText("Settings").performClick()
        waitForIdle()
        onNodeWithText("AI providers").assertExists()
        onNode(tab(MORE)).assertIsSelected()
    }

    @Test
    fun backStepsToMoreThenProjectsThenLeaves() = runDesktopComposeUiTest(width = 412, height = 900) {
        startPhoneKoin()
        var back: (() -> Unit)? = null
        setContent { App(backHandler = { enabled, onBack -> back = if (enabled) onBack else null }) }
        assertNull(back, "Back on Projects leaves the app")
        onNode(tab(MORE)).performClick()
        waitForIdle()
        onNodeWithText("Settings").performClick()
        waitForIdle()
        onNodeWithText("AI providers").assertExists()
        assertNotNull(back).invoke()
        waitForIdle()
        onNodeWithText("References $LOCK_GLYPH").assertExists()
        assertNotNull(back).invoke()
        waitForIdle()
        onNode(tab("Projects")).assertIsSelected()
        assertNull(back)
    }

    @Test
    fun desktopWidthKeepsTheSidebar() = runDesktopComposeUiTest(width = 1024, height = 768) {
        startPhoneKoin()
        setContent { App() }
        onNodeWithText("MODE").assertExists()
        onNode(hasText("Flow Handoff") and isSelectable()).assertExists()
        onNodeWithText("Inbox").assertDoesNotExist()
    }

    @Test
    fun backTargets() {
        assertNull(backTarget("Dashboard"))
        assertEquals(MORE, backTarget("Settings"))
        assertEquals(MORE, backTarget("Input"))
        assertEquals("Dashboard", backTarget("Storyboard"))
        assertEquals("Dashboard", backTarget(MORE))
    }
}
