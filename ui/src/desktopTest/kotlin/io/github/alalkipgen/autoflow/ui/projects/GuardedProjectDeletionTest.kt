package io.github.alalkipgen.autoflow.ui.projects

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.getOrNull
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.platform.JvmProjectFolderDeletion
import io.github.alalkipgen.autoflow.platform.ProjectFolderDeletion.*
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class GuardedProjectDeletionTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("autoflow-disposable-delete").toFile()
        val home = File(root, "projects").apply { mkdirs() }.canonicalPath
        val a = "$home/A.autoflow"
        val b = "$home/B.autoflow"
        val fs = JvmFs()
        val projects = ProjectStore(fs)
        val first = assertNotNull(projects.create(a, "A", Mode.MUSIC_VIDEO).getOrNull())
        val second = assertNotNull(projects.create(b, "B", Mode.MUSIC_VIDEO).getOrNull())
        val settings = SettingsStore(fs, File(root, "settings.json").path)
        val indexPath = File(root, "projects.json").path
        val guard = JvmProjectFolderDeletion(fs)
        fun fresh(current: CurrentProject = CurrentProject()) = DashboardModel(fs, projects, ProjectIndex(fs, indexPath), settings, FakePicker(), current,
            projectsHome = home, deletion = guard)
        fun target() = assertIs<Result.Ready>(guard.inspect(home, a)).target
        override fun close() { root.deleteRecursively() }
    }

    @Test fun refusesOutsideRootWrongIdentityNestedProjectsAndSymlinks() = Fixture().use { f ->
        val outside = File(f.root, "Outside.autoflow")
        assertNotNull(f.projects.create(outside.path, "Outside", Mode.MUSIC_VIDEO).getOrNull())
        val manifest = File(outside, "project.json")
        val bytes = manifest.readBytes()
        assertEquals(Result.Refused(Refusal.UNSUPPORTED), f.guard.inspect(f.home, outside.path))
        val target = f.target()
        assertEquals(Result.Refused(Refusal.CHANGED), f.guard.delete(f.home, target.copy(projectId = "unrelated")) { false })
        val nestedProject = File(f.a, "source/Nested.autoflow")
        assertNotNull(f.projects.create(nestedProject.path, "Nested", Mode.MUSIC_VIDEO).getOrNull())
        val ownManifest = File(nestedProject, "project.json").readBytes()
        assertEquals(Result.Refused(Refusal.UNSUPPORTED), f.guard.inspect(f.home, f.a))
        assertContentEquals(ownManifest, File(nestedProject, "project.json").readBytes())
        // Only synthetic scratch. Windows runners without symlink privilege still cover all other refusals.
        val link = File(f.home, "Alias.autoflow").toPath()
        val supported = runCatching { Files.createSymbolicLink(link, outside.toPath()) }.isSuccess
        if (supported) {
            try { assertEquals(Result.Refused(Refusal.UNSUPPORTED), f.guard.inspect(f.home, link.toString())) }
            finally { Files.delete(link) }
            val nested = File(f.b, "external-link").toPath()
            Files.createSymbolicLink(nested, outside.toPath())
            try { assertEquals(Result.Refused(Refusal.UNSUPPORTED), f.guard.inspect(f.home, f.b)) }
            finally { Files.delete(nested) }
        }
        assertContentEquals(bytes, manifest.readBytes())
        assertTrue(File(f.a, "project.json").exists())
    }

    @Test fun workedOnProjectIsDeletedButBackupsAndExternalFilesAreNot() = runTest {
        Fixture().use { f ->
            File(f.a, "artifacts/au_synthetic.wav").writeText("generated take")
            File(f.a, "exports/final.mp4").writeText("local output")
            val files = listOf("source/Saved.ZIP", "packages/Saved.ZIP", RecapMigration.BACKUP_FILE, RecapMigration.V2_COPY_FILE)
            files.forEachIndexed { i, path -> File(f.a, path).writeText("backup-$i") }
            val external = File(f.root, "Downloads").apply { mkdirs() }.resolve("AutoFlow-backup.zip")
            external.writeText("external backup")
            val published = File(f.root, "Gallery").apply { mkdirs() }.resolve("final.mp4")
            published.writeText("external export")
            val sibling = File(f.b, "project.json").readBytes()
            val current = CurrentProject()
            val model = f.fresh(current)
            model.refresh()
            assertTrue(model.open(f.a))
            model.remove(f.a)
            val stale = f.target()
            assertNotNull(f.projects.save(f.a, f.first).getOrNull())
            assertEquals(UiText.PROJECT_DELETE_CHANGED, model.deleteProject(stale))
            val target = f.target()
            assertEquals(files.size, target.backups.size)
            assertEquals(File(f.a).walkTopDown().filter { it.isFile }.sumOf { it.length() } -
                files.sumOf { File(f.a, it).length() }, target.bytes)
            assertNull(model.deleteProject(target))
            assertFalse(File(f.a).exists())
            assertNull(current.state.value)
            assertTrue(model.state.value.removed.isEmpty())
            assertTrue(f.fresh().state.value.removed.isEmpty())
            val retained = File(f.home, "retained-backups").listFiles()!!.single()
            files.forEachIndexed { i, path -> assertEquals("backup-$i", File(retained, path).readText()) }
            assertEquals("external backup", external.readText())
            assertEquals("external export", published.readText())
            assertContentEquals(sibling, File(f.b, "project.json").readBytes())
        }
    }

    @Test fun unsafeBackupDestinationStopsBeforeDeletingAnything() = Fixture().use { f ->
        val backup = File(f.a, "Keep.zip").apply { writeText("keep") }
        val occupied = File(f.home, "retained-backups").apply { writeText("not a directory") }
        val manifest = File(f.a, "project.json").readBytes()
        assertEquals(Result.Refused(Refusal.KEEP_BACKUPS_FAILED), f.guard.delete(f.home, f.target()) { false })
        assertContentEquals(manifest, File(f.a, "project.json").readBytes())
        assertEquals("keep", backup.readText())
        assertEquals("not a directory", occupied.readText())
    }

    @Test fun queuedAndStillFinishingCancelledJobCannotRaceDeletionOrRecreateFolder() = runTest {
        Fixture().use { f ->
            val sibling = File(f.b, "project.json").readBytes()
            val store = AiJobStore(f.fs, File(f.root, "ai-jobs").path)
            val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            var calls = 0
            val executor = AiJobExecutor(store, AiItemProcessor { _, _ ->
                calls++
                started.complete(Unit); finish.await()
                File(f.a, "source/disposable.txt").writeText("final write")
            })
            val job = AiJob("real-writer", AiJobKind.TRANSCRIBE, f.a, listOf("item"))
            store.prepareEnqueue(job)
            assertEquals(Result.Refused(Refusal.BUSY), f.guard.delete(f.home, f.target()) { store.load(job.id)?.state == AiJobState.QUEUED })
            val running = async { executor.run(job.id) }
            started.await()
            store.update(job.id) { it.copy(state = AiJobState.CANCELLED) }
            assertEquals(Result.Refused(Refusal.BUSY), f.guard.delete(f.home, f.target()) { false })
            finish.complete(Unit)
            assertEquals(AiJobExecutor.Outcome.Cancelled, running.await())
            val dashboard = f.fresh()
            dashboard.refresh()
            assertEquals(UiText.PROJECT_DELETE_CHANGED, dashboard.deleteProject(f.target()))
            dashboard.remove(f.a)
            assertNull(dashboard.deleteProject(f.target()))
            assertFalse(File(f.a).exists())
            assertTrue(ProjectIndex(f.fs, f.indexPath).isDeleted(f.a, f.first.id))
            assertEquals(listOf(f.b), f.fresh().also { it.refresh() }.state.value.rows.map { it.entry.path })
            store.save(job.copy(id = "late", projectIdentity = f.first.id))
            assertEquals(AiJobExecutor.Outcome.Cancelled, executor.run("late"))
            assertEquals(1, calls)
            assertFalse(File(f.a).exists())
            assertContentEquals(sibling, File(f.b, "project.json").readBytes())
        }
    }

    @Test fun touchConfirmDeletesOnlyTheDisposableTarget() {
        val cases = UiLanguage.entries.flatMap { language -> listOf(1f, 2f).map { Triple(412, it, language) } } +
            Triple(360, 2f, UiLanguage.BURMESE)
        for ((width, font, language) in cases) runDesktopComposeUiTest(width = width, height = 760) {
            Fixture().use { f ->
                val before = File(f.a, "project.json").readBytes()
                val model = f.fresh()
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalCompact provides true,
                        LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) {
                        DashboardScreen(model, {})
                    }
                }
                fun clickTag(tag: String) = onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performMouseInput { click() }
                fun clickText(text: String) = onNodeWithText(text).performScrollTo().assertIsDisplayed().performMouseInput { click() }
                onNodeWithText("A").performScrollTo().performTouchInput { longClick() }
                onNodeWithTag("project-delete-action").assertDoesNotExist()
                onNodeWithTag("project-remove-action").assertIsDisplayed().performMouseInput { click() }
                onNodeWithText(UiText.REMOVE.text(language)).assertIsDisplayed().performMouseInput { click() }
                clickTag("projects-open-removed")
                clickText("A · A.autoflow")
                clickTag("project-delete-action")
                onNodeWithText(My.breakSyllables(UiText.PROJECT_DELETE_PERMANENT.text(language))).performScrollTo().assertIsDisplayed()
                assertContentEquals(before, File(f.a, "project.json").readBytes())
                clickTag("removed-projects-cancel")
                assertContentEquals(before, File(f.a, "project.json").readBytes())
                clickTag("project-delete-action")
                clickTag("project-delete-confirm")
                waitUntil(timeoutMillis = 5_000) { !File(f.a).exists() }
                onNodeWithText("A").assertDoesNotExist()
                onNodeWithTag("projects-open-removed").assertDoesNotExist()
                onNodeWithText("B").performScrollTo().assertIsDisplayed()
            }
        }
    }
}
