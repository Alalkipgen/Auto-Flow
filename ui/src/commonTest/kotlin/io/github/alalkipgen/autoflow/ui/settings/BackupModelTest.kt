package io.github.alalkipgen.autoflow.ui.settings

import io.github.alalkipgen.autoflow.core.getOrNull
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.BackupProgress
import io.github.alalkipgen.autoflow.platform.BackupProject
import io.github.alalkipgen.autoflow.platform.BackupResult
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.platform.ProjectBackup
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.StepClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class FakeBackup : ProjectBackup {
    val calls = mutableListOf<List<BackupProject>>()
    var gate = CompletableDeferred<PlatformResult<BackupResult>>()
    val shared = mutableListOf<BackupResult>()
    var shareResult: PlatformResult<Unit> = PlatformResult.Ok(Unit)

    override suspend fun backUp(projects: List<BackupProject>, onProgress: (BackupProgress) -> Unit): PlatformResult<BackupResult> {
        calls += projects
        onProgress(BackupProgress(250, 1_000))
        return gate.await()
    }

    override fun share(result: BackupResult): PlatformResult<Unit> {
        shared += result
        return shareResult
    }

    override fun restore(zipPath: String, destDir: String): PlatformResult<List<String>> =
        PlatformResult.Ok(listOf("Test.autoflow"))
}

class BackupModelTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val index = ProjectIndex(fs, "/app/projects.json", StepClock())
    private val backup = FakeBackup()
    private val home = "/ext/projects"
    private val result = BackupResult("Download/AutoFlow/AutoFlow-backup-20261111-100000.zip", 14_480_000, 2, 9, "content://x/1")

    private fun listed(name: String) {
        val dir = "$home/$name.autoflow"
        index.record(dir, store.create(dir, name, Mode.MUSIC_VIDEO).getOrNull()!!)
    }

    @Test
    fun backsUpTheListedProjectsThatStillExistAndShares() = runTest {
        listed("Rain")
        listed("မိုး")
        index.record("/gone/Old.autoflow", store.create("/tmp/x.autoflow", "Old", Mode.MUSIC_VIDEO).getOrNull()!!)
        val model = BackupModel(backup, index, fs, this)
        assertTrue(model.available)
        model.refresh()
        assertEquals(2, model.state.value.projects)

        model.start()
        runCurrent()
        assertEquals(listOf(BackupProject("$home/မိုး.autoflow", "မိုး.autoflow"), BackupProject("$home/Rain.autoflow", "Rain.autoflow")), backup.calls.single())
        assertTrue(model.state.value.running)
        assertEquals(0.25f, model.state.value.progress!!.fraction)
        model.start() // a second tap while running does nothing
        runCurrent()
        assertEquals(1, backup.calls.size)

        backup.gate.complete(PlatformResult.Ok(result))
        runCurrent()
        val s = model.state.value
        assertFalse(s.running)
        assertEquals(result, s.last)
        assertEquals("Saved Download/AutoFlow/AutoFlow-backup-20261111-100000.zip (14.5 MB, 2 projects)", s.message)
        model.share()
        assertEquals(listOf(result), backup.shared)
    }

    @Test
    fun failuresCancelAndNothingToBackUp() = runTest {
        val model = BackupModel(backup, index, fs, this)
        model.start()
        assertEquals("No projects to back up yet", model.state.value.message)
        assertTrue(backup.calls.isEmpty())

        listed("Rain")
        model.start()
        runCurrent()
        backup.gate.complete(PlatformResult.Failed("Not enough free space: the backup needs about 900 MB, the phone has 300 MB free"))
        runCurrent()
        assertEquals("Not enough free space: the backup needs about 900 MB, the phone has 300 MB free", model.state.value.message)
        assertNull(model.state.value.last)

        backup.gate = CompletableDeferred()
        model.start()
        runCurrent()
        assertTrue(model.state.value.running)
        model.cancel()
        runCurrent()
        assertFalse(model.state.value.running)
        assertEquals("Backup cancelled; nothing was saved", model.state.value.message)
        model.share() // no finished backup yet: nothing to share
        assertTrue(backup.shared.isEmpty())
    }

    @Test
    fun shareProblemsAreShownAndDesktopHasNoBackup() = runTest {
        listed("Rain")
        val model = BackupModel(backup, index, fs, this)
        model.start()
        runCurrent()
        backup.gate.complete(PlatformResult.Ok(result))
        runCurrent()
        backup.shareResult = PlatformResult.Unsupported("No app can receive the backup")
        model.share()
        assertEquals("No app can receive the backup", model.state.value.message)

        val none = BackupModel(null, index, fs, this)
        assertFalse(none.available)
        none.start()
        assertFalse(none.state.value.running)
    }

    @Test
    fun sizesLikeTheFilesApp() {
        assertEquals("899 B", BackupModel.fileSize(899))
        assertEquals("1 KB", BackupModel.fileSize(1_000))
        assertEquals("12 KB", BackupModel.fileSize(12_400))
        assertEquals("100 KB", BackupModel.fileSize(99_900))
        assertEquals("0.1 MB", BackupModel.fileSize(100_000))
        assertEquals("0.4 MB", BackupModel.fileSize(420_000))
        assertEquals("14.5 MB", BackupModel.fileSize(14_480_000))
        assertEquals("1203 MB", BackupModel.fileSize(1_203_400_000))
    }
}
