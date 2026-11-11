package io.github.alalkipgen.autoflow.ui.projects

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class DashboardScreenTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val index = ProjectIndex(fs, "/h/.autoflow/projects.json")
    private val current = CurrentProject()
    private val picker = FakePicker(folder = "/videos")
    private val model = DashboardModel(fs, store, index, SettingsStore(fs, "/h/.autoflow/settings.json"), picker, current)

    @Test
    fun newMusicVideoIsCreatedAndOpened() = runComposeUiTest {
        var opened = 0
        setContent { DashboardScreen(model, onOpened = { opened++ }) }
        onNodeWithText("No projects yet", substring = true).assertExists()
        onNodeWithText("New Movie Recap (Phase 4)").assertIsNotEnabled()
        onNodeWithText("New Music Video").performClick()
        onNodeWithContentDescription("Project name").performTextInput("Rain")
        onNodeWithText("Browse…").performClick()
        waitForIdle()
        onNodeWithText("Creates /videos/Rain.autoflow").assertExists()
        onNodeWithText("Create").performClick()
        waitForIdle()
        assertEquals(1, opened)
        assertEquals("/videos/Rain.autoflow", current.state.value?.dir)
        assertTrue(fs.exists("/videos/Rain.autoflow/project.json"))
    }

    @Test
    fun rowsOpenAndMissingFoldersCanBeRemoved() = runComposeUiTest {
        store.create("/p/Here.autoflow", "Here", Mode.MUSIC_VIDEO)
        store.create("/usb/Gone.autoflow", "Gone", Mode.MUSIC_VIDEO)
        model.open("/usb/Gone.autoflow")
        model.open("/p/Here.autoflow")
        fs.atomicMove("/usb/Gone.autoflow/project.json", "/elsewhere.json")
        var opened = 0
        setContent { DashboardScreen(model, onOpened = { opened++ }) }
        onNodeWithText("0/0 shots").assertExists()
        onNodeWithText("Folder not found").assertExists()
        onNodeWithText("Here").performClick()
        waitForIdle()
        assertEquals(1, opened)
        onNodeWithText("Remove from list").performClick()
        waitForIdle()
        onNodeWithText("Gone").assertDoesNotExist()
        assertEquals(listOf("/p/Here.autoflow"), index.entries.value.map { it.path })
    }

    @Test
    fun onThePhoneProjectsGoToTheProjectsHomeWithoutAFolderChoice() = runComposeUiTest {
        val phone = DashboardModel(fs, store, index, SettingsStore(fs, "/h/.autoflow/settings.json"), picker, current, projectsHome = "/ext/projects")
        var opened = 0
        setContent { DashboardScreen(phone, onOpened = { opened++ }) }
        onNodeWithText("No projects yet. Create a music video.").assertExists()
        onNodeWithText("Open folder…").assertDoesNotExist()
        onNodeWithText("New Music Video").performClick()
        onNodeWithContentDescription("Folder to save it in").assertDoesNotExist()
        onNodeWithText("Browse…").assertDoesNotExist()
        onNodeWithText("Saved in this app's storage", substring = true).assertExists()
        onNodeWithContentDescription("Project name").performTextInput("Rain")
        onNodeWithText("Create").performClick()
        waitForIdle()
        assertEquals(1, opened)
        assertTrue(fs.exists("/ext/projects/Rain.autoflow/project.json"))
    }

    @Test
    fun relativeTimes() {
        val now = Instant.fromEpochSeconds(100_000_000)
        assertEquals("just now", relativeTime(now - 20.seconds, now))
        assertEquals("5 min ago", relativeTime(now - 5.minutes, now))
        assertEquals("3 h ago", relativeTime(now - 3.hours, now))
        assertEquals("yesterday", relativeTime(now - 30.hours, now))
        assertEquals("4 days ago", relativeTime(now - 4.days, now))
        assertEquals("1973-01-02", relativeTime(now - 60.days, now))
    }
}
