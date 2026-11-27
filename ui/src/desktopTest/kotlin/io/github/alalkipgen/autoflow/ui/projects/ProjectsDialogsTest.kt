package io.github.alalkipgen.autoflow.ui.projects

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.getOrNull
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.render.RenderJob
import io.github.alalkipgen.autoflow.media.render.RenderQueue
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.platform.ProjectFolderDeletion
import io.github.alalkipgen.autoflow.platform.ProjectFolderDeletion.Refusal
import io.github.alalkipgen.autoflow.platform.ProjectFolderDeletion.Result as Deletion
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.*
import kotlin.time.Instant

/**
 * The Projects dialogs on the shared glass dialog (DESIGN.md §9.10, owner's Projects dialogs README): Project actions →
 * Remove confirm → removed; Removed projects → one project → Restore; → Delete permanently (fake deletion); a refusal
 * hides Delete; deleting disables both buttons and blocks Back / outside taps; two-button rows stack at font 2.0.
 */
@OptIn(ExperimentalTestApi::class)
class ProjectsDialogsTest {
    /**
     * Ready for every folder (184.6 MB, one backup) unless [refusal]; [deleteResult] = what delete returns (counted). A
     * Deleted result moves project.json* to /trash, so the project no longer opens (like the real deletion).
     */
    private class FakeDeletion(val fs: InMemoryFileSystem, val ids: Map<String, String>) : ProjectFolderDeletion {
        var refusal: Refusal? = null
        var deleteResult: Deletion = Deletion.Deleted(null)
        var deletes = 0
        override fun inspect(root: String, folder: String): Deletion = refusal?.let { Deletion.Refused(it) }
            ?: Deletion.Ready(ProjectFolderDeletion.Target(folder, ids.getValue(folder), folder.substringAfterLast('/').removeSuffix(".autoflow"),
                184_600_000, Instant.fromEpochSeconds(0), listOf("$folder/Backup.zip")))
        override fun delete(root: String, target: ProjectFolderDeletion.Target, pending: () -> Boolean): Deletion {
            deletes++
            if (deleteResult is Deletion.Deleted) {
                fs.createDirectories("/trash")
                for (name in fs.list(target.folder).filter { it.startsWith("project.json") }) fs.atomicMove("${target.folder}/$name", "/trash/$deletes-$name")
            }
            return deleteResult
        }
    }

    /** No render jobs; while [gate] is set the pending check waits for it, so the delete confirm stays "deleting". */
    private class GateQueue : RenderQueue {
        var gate: CompletableDeferred<Boolean>? = null
        override fun enqueue(job: RenderJob): String = error("not used")
        override fun observe(id: String): Flow<RenderState> = emptyFlow()
        override fun cancel(id: String) = Unit
        override suspend fun hasPendingProject(key: String, canonical: (String) -> String) = gate?.await() ?: false
    }

    private class Fixture(managed: Boolean = true) {
        val fs = InMemoryFileSystem()
        val store = ProjectStore(fs)
        val home = "/ext/projects"
        val a = "$home/A.autoflow"
        val b = "$home/B.autoflow"
        val ids = listOf(a to "A", b to "B").associate { (dir, name) -> dir to assertNotNull(store.create(dir, name, Mode.MUSIC_VIDEO).getOrNull()).id }
        val deletion = FakeDeletion(fs, ids)
        val renders = GateQueue()
        val model = DashboardModel(fs, store, ProjectIndex(fs, "/h/index.json"), SettingsStore(fs, "/h/settings.json"), FakePicker(),
            CurrentProject(), projectsHome = if (managed) home else null, deletion = deletion, renders = renders)
            .also { if (!managed) { it.open(b); it.open(a) } }
    }

    private fun ComposeUiTest.show(model: DashboardModel, language: UiLanguage = UiLanguage.ENGLISH, font: Float = 1f) = setContent {
        CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalCompact provides true, LocalUiLanguage provides language,
            LocalUiFontFamily provides BurmeseTestFont.family) {
            DashboardScreen(model, {})
        }
    }
    private fun t(language: UiLanguage, key: UiText, vararg args: Pair<String, Any?>) = My.breakSyllables(key.text(language, *args))
    private fun ComposeUiTest.click(tag: String) = onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performMouseInput { click() }
    /** The whole box, also when part of it is scrolled out of the dialog. */
    private fun ComposeUiTest.bounds(tag: String): Rect = onNodeWithTag(tag).fetchSemanticsNode()
        .let { Rect(it.positionInRoot, androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat())) }
    /** A tap on the scrim outside the dialog (the dialog's own root covers the window). */
    private fun ComposeUiTest.tapOutside() { onAllNodes(isRoot())[1].performMouseInput { click(Offset(5f, 5f)) }; waitForIdle() }
    private fun ComposeUiTest.title() = onNodeWithTag("glass-dialog-title", useUnmergedTree = true)
    private fun ComposeUiTest.text(tag: String) = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        .config[SemanticsProperties.Text].joinToString("") { it.text }.replace("\u200B", "")
    /** Cancel at the start, the action at the end, equal widths. */
    private fun ComposeUiTest.assertRow(cancel: String, action: String) {
        onNodeWithTag("glass-dialog-buttons-row").assertExists()
        val c = bounds(cancel); val a = bounds(action)
        assertTrue(c.right <= a.left && kotlin.math.abs(c.top - a.top) < 1f, "Cancel $c before the action $a")
        assertEquals(c.width, a.width, 1f)
    }
    /** Stacked full width, top to bottom. */
    private fun ComposeUiTest.assertStacked(vararg tags: String) {
        onNodeWithTag("glass-dialog-buttons-stacked").assertExists()
        tags.map { bounds(it) }.zipWithNext().forEach { (upper, lower) ->
            assertTrue(upper.bottom <= lower.top, "$upper above $lower"); assertEquals(upper.width, lower.width, 1f)
        }
    }

    @Test fun projectActionsThenRemoveTakesTheProjectOffTheList() {
        for (language in UiLanguage.entries) runDesktopComposeUiTest(width = 360, height = 780) {
            val f = Fixture()
            show(f.model, language)
            click("project-more-A")
            title().assertTextEquals(t(language, UiText.PROJECT_ACTIONS))
            onNodeWithTag("glass-dialog-subtitle", useUnmergedTree = true).assertTextEquals("A")
            onNodeWithText(t(language, UiText.PROJECT_DELETE_UNSUPPORTED)).assertDoesNotExist()
            onNode(hasText(t(language, UiText.REMOVE_PROJECT_HINT)) and hasAnyAncestor(hasTestTag("project-remove-action")), useUnmergedTree = true)
                .assertIsDisplayed()
            onNodeWithTag("project-actions-cancel").assertTextEquals(t(language, UiText.CANCEL_KEEP))
            assertEquals(emptyList(), textFitProblems(360, 1f, strict = true))
            click("project-remove-action")
            title().assertTextEquals(t(language, UiText.REMOVE_PROJECT_QUESTION, "name" to "A"))
            onNodeWithText(t(language, UiText.REMOVE_PROJECT_HINT)).assertIsDisplayed()
            onNodeWithTag("remove-project-cancel").assertTextEquals(t(language, UiText.CANCEL_KEEP))
            onNodeWithTag("remove-project-confirm").assertTextEquals(t(language, UiText.REMOVE))
            assertRow("remove-project-cancel", "remove-project-confirm")
            assertEquals(emptyList(), textFitProblems(360, 1f, strict = true))
            click("remove-project-confirm")
            onNodeWithTag("glass-dialog").assertDoesNotExist()
            onNodeWithTag("project-row-A").assertDoesNotExist()
            runOnIdle { assertEquals(listOf(f.b), f.model.state.value.rows.map { it.entry.path }) }
            // One removed project: "1 project" (ONE_PROJECT), not "1 projects".
            onNode(hasText(t(language, UiText.ONE_PROJECT)) and hasAnyAncestor(hasTestTag("projects-open-removed")), useUnmergedTree = true)
                .assertIsDisplayed()
        }
    }

    @Test fun cancelAndBackCloseProjectActionsAndTheRemoveConfirmWithoutRemoving() = runDesktopComposeUiTest(width = 360, height = 780) {
        val f = Fixture()
        show(f.model)
        click("project-more-A")
        click("project-actions-cancel")
        onNodeWithTag("glass-dialog").assertDoesNotExist()
        click("project-more-A")
        click("project-remove-action")
        click("remove-project-cancel")
        onNodeWithTag("glass-dialog").assertDoesNotExist()
        runOnIdle { assertEquals(setOf(f.a, f.b), f.model.state.value.rows.map { it.entry.path }.toSet()) }
    }

    @Test fun removedListOpensOneProjectAndRestoreBringsItBack() = runDesktopComposeUiTest(width = 360, height = 780) {
        val f = Fixture()
        f.model.remove(f.a)
        show(f.model)
        click("projects-open-removed")
        title().assertTextEquals("Removed projects")
        onNodeWithTag("glass-dialog-subtitle", useUnmergedTree = true).assertTextEquals("1 project")
        onNode(hasText("A.autoflow") and hasAnyAncestor(hasTestTag("removed-project-0")), useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("removed-projects-cancel").assertTextEquals("Cancel")
        assertEquals(emptyList(), textFitProblems(360, 1f, strict = true))
        click("removed-project-0")
        title().assertTextEquals("A")
        assertEquals("A.autoflow", text("glass-path-name"))
        assertEquals("/ext/projects/", text("glass-path-parent"))
        onNodeWithTag("project-restore").assertTextEquals(UiText.PROJECT_RESTORE.en)
        onNodeWithTag("project-delete-action").assertTextEquals(UiText.PROJECT_DELETE_MENU.en)
        assertStacked("project-restore", "project-delete-action", "removed-projects-cancel")
        assertEquals(emptyList(), textFitProblems(360, 1f, strict = true))
        // Cancel steps back to the list.
        click("removed-projects-cancel")
        title().assertTextEquals("Removed projects")
        click("removed-project-0")
        click("project-restore")
        onNodeWithTag("glass-dialog").assertDoesNotExist()
        runOnIdle { assertEquals(setOf(f.a, f.b), f.model.state.value.rows.map { it.entry.path }.toSet()) }
        onNodeWithTag("projects-open-removed").assertDoesNotExist()
        assertEquals(0, f.deletion.deletes)
    }

    @Test fun aRefusalShowsItsReasonAndHidesDelete() {
        for (language in UiLanguage.entries) runDesktopComposeUiTest(width = 360, height = 780) {
            val f = Fixture()
            f.deletion.refusal = Refusal.BUSY
            f.model.remove(f.a)
            show(f.model, language)
            click("projects-open-removed")
            click("removed-project-0")
            onNodeWithText(t(language, UiText.PROJECT_DELETE_BUSY)).assertIsDisplayed()
            onNodeWithTag("project-delete-action").assertDoesNotExist()
            assertStacked("project-restore", "removed-projects-cancel")
            assertEquals(emptyList(), textFitProblems(360, 1f, strict = true))
        }
    }

    @Test fun notAppManagedShowsTheInfoCallout() = runDesktopComposeUiTest(width = 360, height = 780) {
        val f = Fixture(managed = false)
        show(f.model)
        click("project-more-A")
        onNodeWithText(UiText.PROJECT_DELETE_UNSUPPORTED.en).assertIsDisplayed()
        onNodeWithTag("project-remove-action").assertIsDisplayed()
        assertEquals(emptyList(), textFitProblems(360, 1f, strict = true))
    }

    @Test fun deleteConfirmShowsTheSizeAndBlocksDismissWhileDeleting() = runDesktopComposeUiTest(width = 360, height = 780) {
        val f = Fixture()
        f.model.remove(f.a); f.model.remove(f.b)
        show(f.model)
        click("projects-open-removed")
        onNodeWithTag("glass-dialog-subtitle", useUnmergedTree = true).assertTextEquals("2 projects")
        onNodeWithTag("removed-project-1").assertIsDisplayed()
        click("removed-project-0")
        val name = onNodeWithTag("glass-dialog-title", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        click("project-delete-action")
        title().assertTextEquals(UiText.PROJECT_DELETE_TITLE.text(UiLanguage.ENGLISH, "name" to name))
        // BackupModel.fileSize: whole MB from 100 MB up.
        onNodeWithText("Project files to delete: 184 MB").assertIsDisplayed()
        onNodeWithText(UiText.PROJECT_DELETE_KEEP_BACKUPS.en).performScrollTo().assertIsDisplayed()
        onNodeWithText(UiText.PROJECT_DELETE_PERMANENT.en).performScrollTo().assertIsDisplayed()
        // en "Delete permanently" needs two lines in a half-width button: stacked, the action on top.
        onNodeWithTag("project-delete-confirm").assertTextEquals(UiText.PROJECT_DELETE_CONFIRM.en)
        assertStacked("project-delete-confirm", "removed-projects-cancel")
        assertEquals(emptyList(), textFitProblems(360, 1f, strict = true))
        // A tap outside steps back one level (to the project) while idle.
        tapOutside()
        title().assertTextEquals(name)
        click("project-delete-action")
        f.renders.gate = CompletableDeferred()
        click("project-delete-confirm")
        onNodeWithTag("project-delete-confirm").assertIsNotEnabled()
        onNodeWithTag("removed-projects-cancel").assertIsNotEnabled()
        tapOutside()
        title().assertTextEquals(UiText.PROJECT_DELETE_TITLE.text(UiLanguage.ENGLISH, "name" to name))
        assertEquals(0, f.deletion.deletes)
        f.renders.gate!!.complete(false)
        waitUntil(timeoutMillis = 5_000) { f.deletion.deletes == 1 }
        onNodeWithTag("glass-dialog").assertDoesNotExist()
        onNodeWithTag("projects-open-removed").performScrollTo()
        onNode(hasText("1 project") and hasAnyAncestor(hasTestTag("projects-open-removed")), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun aDeleteErrorShowsACalloutAndEnablesTheButtonsAgain() = runDesktopComposeUiTest(width = 360, height = 780) {
        val f = Fixture()
        f.deletion.deleteResult = Deletion.Refused(Refusal.IO)
        f.model.remove(f.a)
        show(f.model)
        click("projects-open-removed")
        click("removed-project-0")
        click("project-delete-action")
        click("project-delete-confirm")
        onNodeWithText(UiText.PROJECT_DELETE_FAILED.en).performScrollTo().assertIsDisplayed()
        onNodeWithTag("project-delete-confirm").assertIsEnabled()
        onNodeWithTag("removed-projects-cancel").assertIsEnabled()
        assertEquals(1, f.deletion.deletes)
        assertEquals(emptyList(), textFitProblems(360, 1f, strict = true))
        click("removed-projects-cancel")
        title().assertTextEquals("A")
        onNodeWithText(UiText.PROJECT_DELETE_FAILED.en).assertDoesNotExist()
    }

    @Test fun fontTwoStacksTheTwoButtonRows() {
        for (language in UiLanguage.entries) for (font in listOf(1f, 2f)) runDesktopComposeUiTest(width = 360, height = 780) {
            val f = Fixture()
            f.model.remove(f.b)
            show(f.model, language, font)
            click("project-more-A")
            click("project-remove-action")
            if (font == 2f) assertStacked("remove-project-confirm", "remove-project-cancel") else assertRow("remove-project-cancel", "remove-project-confirm")
            assertEquals(emptyList(), textFitProblems(360, font, strict = true), "remove ${language.name} $font")
            click("remove-project-cancel")
            click("projects-open-removed")
            assertEquals(emptyList(), textFitProblems(360, font, strict = true), "list ${language.name} $font")
            click("removed-project-0")
            assertEquals(emptyList(), textFitProblems(360, font, strict = true), "project ${language.name} $font")
            click("project-delete-action")
            // en "Delete permanently" stacks at font 1.0 too; my fits one row at font 1.0.
            if (font == 2f || language == UiLanguage.ENGLISH) assertStacked("project-delete-confirm", "removed-projects-cancel")
            else assertRow("removed-projects-cancel", "project-delete-confirm")
            assertEquals(emptyList(), textFitProblems(360, font, strict = true), "delete ${language.name} $font")
        }
    }
}
