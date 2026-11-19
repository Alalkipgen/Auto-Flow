package io.github.alalkipgen.autoflow.ui.projects

import androidx.compose.ui.test.*
import io.github.alalkipgen.autoflow.core.getOrNull
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.platform.JvmProjectFolderDeletion
import io.github.alalkipgen.autoflow.platform.ProjectFolderDeletion.*
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
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
        fun fresh() = DashboardModel(fs, projects, ProjectIndex(fs, indexPath), settings, FakePicker(), CurrentProject(),
            projectsHome = home, deletion = guard)
        fun target() = assertIs<Result.Ready>(guard.inspect(home, a)).target
        override fun close() { root.deleteRecursively() }
    }

    @Test fun refusesOutsideRootWrongIdentityProtectedFilesAndSymlinks() = Fixture().use { f ->
        val outside = File(f.root, "Outside.autoflow")
        assertNotNull(f.projects.create(outside.path, "Outside", Mode.MUSIC_VIDEO).getOrNull())
        val manifest = File(outside, "project.json")
        val bytes = manifest.readBytes()
        assertEquals(Result.Refused(Refusal.UNSUPPORTED), f.guard.inspect(f.home, outside.path))
        val target = f.target()
        assertEquals(Result.Refused(Refusal.CHANGED), f.guard.delete(f.home, target.copy(projectId = "unrelated")) { false })
        val artifact = File(f.a, "artifacts/immutable.dat").apply { writeText("keep me") }
        assertEquals(Result.Refused(Refusal.PROTECTED_FILES), f.guard.inspect(f.home, f.a))
        assertEquals("keep me", artifact.readText())
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

    @Test fun touchConfirmDeletesOnlyTheDisposableTarget() = runDesktopComposeUiTest(width = 412, height = 760) {
        Fixture().use { f ->
            val before = File(f.a, "project.json").readBytes()
            val model = f.fresh()
            setContent { DashboardScreen(model, {}) }
            onNodeWithText("A").performScrollTo().performTouchInput { longClick() }
            onNodeWithTag("project-delete-action").assertIsDisplayed().performMouseInput { click() }
            onNodeWithText("This can't be undone.").assertIsDisplayed()
            assertContentEquals(before, File(f.a, "project.json").readBytes())
            onNodeWithTag("project-delete-confirm").assertIsDisplayed().performMouseInput { click() }
            waitUntil(timeoutMillis = 5_000) { !File(f.a).exists() }
            onNodeWithText("A").assertDoesNotExist()
            onNodeWithText("B").performScrollTo().assertIsDisplayed()
        }
    }
}
