package io.github.alalkipgen.autoflow.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.usage.UsageCounter
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.library.LibraryStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.library.*
import io.github.alalkipgen.autoflow.ui.projects.*
import io.github.alalkipgen.autoflow.ui.recap.QaRecapFixture
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class SettingsLayoutTest(val width: Int, val font: Float, val language: UiLanguage, val bundled: Boolean) {
    companion object {
        private val fixture by lazy { QaRecapFixture.create(Files.createTempDirectory("uiv3-settings").toFile()) }
        @JvmStatic @Parameterized.Parameters(name = "{0}px/{1}/{2}/bundled={3}")
        fun cases() = listOf(412, 840).flatMap { w -> listOf(1f, 1.3f, 2f).flatMap { f ->
            UiLanguage.entries.flatMap { l -> listOf(true, false).map { b -> arrayOf<Any>(w, f, l, b) } }
        } } + listOf(arrayOf<Any>(360, 2f, UiLanguage.BURMESE, true), arrayOf<Any>(1440, 1f, UiLanguage.ENGLISH, true))
    }
    @Test fun settingsSectionsDiagnosticsAndLexiconAreUserReachableAndReadOnlyOnBrowse() = runDesktopComposeUiTest(width = width, height = if (width == 1440) 900 else 760) {
        val before = File(fixture.dir, "project.json").readBytes()
        val fs = InMemoryFileSystem(); val settings = SettingsStore(fs, "/app/settings.json")
        settings.update { it.copy(uiLanguage = language.code) }
        val secrets = MemorySecureStorage(); val provider = ProviderStatusModel(settings, secrets)
        val model = SettingsModel(settings, secrets, provider, UsageCounter(fs, "/app/usage.json"),
            loadModelConfig(fs, null) { TEST_MODELS_JSON }, FakeTools(ffmpeg = "ffmpeg", bundled = width < 600),
            FakePicker(), { PlatformResult.Ok(Unit) }, { error("No provider call while browsing") }, bundledFeatures = """{"schemaVersion":1,"studioV2":true}""")
        var selfTests = 0; var back = 0; var libraryShown by mutableStateOf(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val projectFs = JvmFs(); val current = CurrentProject().apply { set(OpenProject(fixture.dir.path, fixture.project)) }
        val index = ProjectIndex(fs, "/app/index.json").apply { record(fixture.dir.path.replace('\\', '/'), fixture.project) }
        val session = ProjectSession(projectFs, ProjectStore(projectFs), index, current)
        val library = LibraryModel(LibraryStore(fs, "/app/library"), session, scope)
        val backup = if (width < 600) BackupModel(FakeBackup(), index, projectFs, scope) else null
        fun capture(name: String) {
            val dir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "settings").apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name-$width-${language.code}-$font-$bundled.png"))
        }
        try {
            setContent {
                CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                    LocalUiLanguage provides language, LocalUiFontFamily provides if (bundled) BurmeseTestFont.family else FontFamily.Default) {
                    StudioV2Shell(if (libraryShown) StudioV2Dest.LIBRARY else StudioV2Dest.HOME, {}, 0, width.toFloat(), ambient = !libraryShown) {
                        if (libraryShown) LibraryScreen(library, focusLexicon = true)
                        else SettingsScreen(model,
                            info = if (width < 600) AppInfo("QA fixture host") { selfTests++ } else AppInfo("QA fixture host"),
                            backup = backup,
                            onOpenLexicon = { libraryShown = true },
                            onBack = { back++ })
                    }
                }
            }
            // P9-17a: the main page, then every row's page (no clipping, no cut words), back to the main page each time.
            onNodeWithTag("settings-heading").assertIsDisplayed()
            assertEquals(emptyList(), textFitProblems(width, font, strict = true), "main")
            val rows = listOf("language", "gemini", "models", "defaults", "fonts", "tools", "labs") +
                if (backup != null) listOf("backup") else emptyList()
            rows.forEach { row ->
                onNodeWithTag("settings-row-$row").performScrollTo().assertIsDisplayed().performMouseInput { click() }
                onNodeWithTag("settings-page-title").assertIsDisplayed()
                if (row == "models") onNodeWithTag("models-more").performScrollTo().performMouseInput { click() }
                assertEquals(emptyList(), textFitProblems(width, font, strict = true, minNodes = 3), row)
                onNodeWithTag("settings-page-back").assertIsDisplayed().performMouseInput { click() }
                onNodeWithTag("settings-heading").assertIsDisplayed()
            }
            onNodeWithTag("settings-row-diagnostics").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("diagnostics-build").performScrollTo().assertIsDisplayed()
            val self = onNodeWithTag("settings-self-test").performScrollTo().assertIsDisplayed()
            if (width < 600) { self.assertIsEnabled().performMouseInput { click() }; assertEquals(1, selfTests) }
            else self.assertIsNotEnabled()
            assertEquals(emptyList(), textFitProblems(width, font, strict = true), "diagnostics")
            capture("10-diagnostics")
            onNodeWithTag("settings-page-back").performMouseInput { click() }
            onNodeWithTag("subscreen-back").assertIsDisplayed().performMouseInput { click() }
            assertEquals(1, back)
            onNodeWithTag("settings-heading").assertIsDisplayed()
            capture("10-settings")
            onNodeWithTag("settings-lexicon").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            waitForIdle()
            // An empty Library: Pronunciation shows every section and brings the Lexicon (its empty row) into view.
            onNodeWithTag("library-empty-lexicon").assertIsDisplayed()
            onNodeWithTag("library-categories").assertDoesNotExist()
            assertContentEquals(before, File(fixture.dir, "project.json").readBytes(), "Settings/Library browse must not save project")
        } finally { scope.cancel() }
    }
}
