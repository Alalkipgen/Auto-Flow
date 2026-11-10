package io.github.alalkipgen.autoflow.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.TextProvider
import io.github.alalkipgen.autoflow.ai.registry.aiModule
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.platform.AppDirs
import io.github.alalkipgen.autoflow.platform.Clipboard
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.platform.SecureStorage
import io.github.alalkipgen.autoflow.platform.UrlOpener
import io.github.alalkipgen.autoflow.ui.di.uiModule
import io.github.alalkipgen.autoflow.ui.projects.ProjectsScreen
import io.github.alalkipgen.autoflow.ui.settings.BundledConfig
import io.github.alalkipgen.autoflow.ui.settings.MemorySecureStorage
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.settings.TEST_MODELS_JSON
import io.github.alalkipgen.autoflow.ui.shell.App
import io.github.alalkipgen.autoflow.ui.shell.AppShell
import io.github.alalkipgen.autoflow.ui.shell.SidebarSections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

/** Platform bindings with in-memory fakes, as an app module would provide them. */
fun fakePlatformModule(fs: InMemoryFileSystem = InMemoryFileSystem(), secrets: SecureStorage = MemorySecureStorage()) = module {
    single { AppDirs("/home/test/.autoflow") }
    single<FileSystem> { fs }
    single { secrets }
    single<Clipboard> { Clipboard { PlatformResult.Ok(Unit) } }
    single<UrlOpener> { UrlOpener { PlatformResult.Ok(Unit) } }
    single { BundledConfig { TEST_MODELS_JSON } }
}

@OptIn(ExperimentalTestApi::class)
class AppShellSmokeTest {
    @AfterTest
    fun tearDown() = stopKoin()

    @Test
    fun shellShowsTitleBarAndPipelineSidebar() = runComposeUiTest {
        setContent { AppShell(providerStatus = "status text") }
        onNodeWithText("Auto Flow").assertExists()
        onNodeWithText("status text").assertExists()
        SidebarSections.flatMap { it.second }.forEach { onNodeWithText(it).assertExists() }
    }

    @Test
    fun wiredAppStartsInFakeModeAndFollowsTheSetting() = runComposeUiTest {
        val koin = startKoin { modules(fakePlatformModule(), uiModule, aiModule) }.koin
        setContent { App() }
        onNodeWithText("AI ● Fake (offline) · Flow ● Manual").assertExists()
        koin.get<SettingsStore>().update { it.copy(useFakeProviders = false) }
        waitForIdle()
        onNodeWithText("Gemini API ● No key · Flow ● Manual").assertExists()
    }

    @Test
    fun sidebarSelectsAScreen() = runComposeUiTest {
        startKoin { modules(fakePlatformModule(), uiModule, aiModule) }
        setContent { App() }
        fun sidebar(name: String) = onNode(hasText(name) and isSelectable())
        sidebar("Dashboard").assertIsSelected()
        sidebar("Settings").performClick()
        waitForIdle()
        sidebar("Settings").assertIsSelected()
        sidebar("Dashboard").assertIsNotSelected()
        onAllNodesWithText("Settings").assertCountEquals(2)
    }

    @Test
    fun fakeTextProviderAnswersOfflineThroughTheGraph() = runTest {
        val koin = startKoin { modules(fakePlatformModule(), uiModule, aiModule) }.koin
        val schema = JsonSchema(buildJsonObject { put("type", "object") })
        assertIs<AiResult.Ok<*>>(koin.get<TextProvider>().json("hello", schema))
    }

    @Test
    fun projectsScreenRenders() = runComposeUiTest {
        setContent { ProjectsScreen() }
        onNodeWithText("Projects").assertExists()
    }
}
