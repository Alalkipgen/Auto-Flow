package io.github.alalkipgen.autoflow.ui.settings

import io.github.alalkipgen.autoflow.core.getOrNull
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.AppDirs
import io.github.alalkipgen.autoflow.platform.BackupProgress
import io.github.alalkipgen.autoflow.platform.BackupProject
import io.github.alalkipgen.autoflow.platform.BackupResult
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.platform.ProjectBackup
import io.github.alalkipgen.autoflow.platform.RestoredProject
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.StepClock
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Backup restore with Phase 7 projects (D-77, D-78). */
class ToolProjectRestoreTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val home = "/ext/projects"

    /** "Extracts" by having the folders already in place, like the real restore does before returning. */
    private class Restoring(val folders: List<String>) : ProjectBackup {
        override suspend fun backUp(projects: List<BackupProject>, onProgress: (BackupProgress) -> Unit): PlatformResult<BackupResult> =
            PlatformResult.Unsupported("not used")
        override fun share(result: BackupResult): PlatformResult<Unit> = PlatformResult.Ok(Unit)
        override suspend fun restore(zipPath: String, destDir: String): PlatformResult<List<RestoredProject>> =
            PlatformResult.Ok(folders.map { RestoredProject(it, it) })
    }

    @Test
    fun restoredSubtitlesProjectIsListedAndANewerOneIsKeptAndExplained() = runTest {
        store.create("$home/Clip.autoflow", "Clip", Mode.SUBTITLES).getOrNull()!!
        store.create("$home/Next.autoflow", "Next", Mode.MUSIC_VIDEO).getOrNull()!!
        val newer = fs.read("$home/Next.autoflow/project.json").decodeToString()
            .replace("\"schemaVersion\": 2", "\"schemaVersion\": 9").encodeToByteArray()
        fs.writeDurably("$home/Next.autoflow/project.json", newer)
        val index = ProjectIndex(fs, "/app/projects.json", StepClock())
        val model = BackupModel(Restoring(listOf("Clip.autoflow", "Next.autoflow")), index, fs, this,
            FakePicker(files = listOf("/dl/AutoFlow-backup.zip")), AppDirs("/app", home))
        model.restore()
        runCurrent()
        assertEquals(listOf(Mode.SUBTITLES to "Clip"), index.entries.value.map { it.mode to it.name })
        val message = model.state.value.message!!
        assertTrue(UiText.NEEDS_NEWER_APP.en in message && "Next.autoflow" in message, message)
        assertContentEquals(newer, fs.read("$home/Next.autoflow/project.json"))
    }
}
