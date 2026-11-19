package io.github.alalkipgen.autoflow.ui.projects

import io.github.alalkipgen.autoflow.core.getOrNull
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.SubtitlesTool
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.platform.JvmProjectFolderDeletion
import io.github.alalkipgen.autoflow.platform.ProjectFolderDeletion.Result
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.test.runTest

/** A Subtitles project follows the same remove → restore → delete permanently rules as the others (D-76, D-77). */
class ToolProjectDeletionTest {
    @Test fun subtitlesProjectCanBeHiddenRestoredAndDeletedPermanently() = runTest {
        val root = Files.createTempDirectory("autoflow-tool-delete").toFile()
        try {
            val home = File(root, "projects").apply { mkdirs() }.canonicalPath
            val dir = "$home/Clip.autoflow"
            val fs = JvmFs()
            val store = ProjectStore(fs)
            val created = assertNotNull(store.create(dir, "Clip", Mode.SUBTITLES).getOrNull())
            val project = created.copy(subtitles = SubtitlesTool(video = SourceVideo("source/clip.mp4", 3, 1_000)))
            assertNotNull(store.save(dir, project).getOrNull())
            File(dir, "source").mkdirs(); File(dir, "source/clip.mp4").writeText("vid")
            val guard = JvmProjectFolderDeletion(fs)
            val model = DashboardModel(fs, store, ProjectIndex(fs, File(root, "projects.json").path),
                SettingsStore(fs, File(root, "settings.json").path), FakePicker(), CurrentProject(), projectsHome = home, deletion = guard)
            model.refresh()
            assertEquals(listOf(Mode.SUBTITLES), model.state.value.rows.map { it.entry.mode })

            model.remove(dir)
            val removed = model.state.value.removed.single()
            assertEquals("Clip" to created.id, removed.name to removed.id)
            assertNull(model.restoreRemoved(removed))
            assertEquals(listOf("Clip"), model.state.value.rows.map { it.entry.name })

            model.remove(dir)
            val target = assertIs<Result.Ready>(model.inspectDeletion(model.state.value.removed.single())).target
            assertNull(model.deleteProject(target))
            assertFalse(File(dir).exists())
            assertTrue(model.state.value.rows.isEmpty() && model.state.value.removed.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
