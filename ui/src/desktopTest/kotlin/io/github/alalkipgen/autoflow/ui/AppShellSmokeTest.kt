package io.github.alalkipgen.autoflow.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.alalkipgen.autoflow.ui.projects.ProjectsScreen
import io.github.alalkipgen.autoflow.ui.shell.AppShell
import io.github.alalkipgen.autoflow.ui.shell.PROVIDER_STATUS
import io.github.alalkipgen.autoflow.ui.shell.SidebarSections
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class AppShellSmokeTest {
    @Test
    fun shellShowsTitleBarAndPipelineSidebar() = runComposeUiTest {
        setContent { AppShell() }
        onNodeWithText("Auto Flow").assertExists()
        onNodeWithText(PROVIDER_STATUS).assertExists()
        SidebarSections.flatMap { it.second }.forEach { onNodeWithText(it).assertExists() }
    }

    @Test
    fun projectsScreenRenders() = runComposeUiTest {
        setContent { ProjectsScreen() }
        onNodeWithText("Projects").assertExists()
    }
}
