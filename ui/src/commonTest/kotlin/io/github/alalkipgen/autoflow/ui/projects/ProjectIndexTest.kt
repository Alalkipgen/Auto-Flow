package io.github.alalkipgen.autoflow.ui.projects

import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.model.Shot
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** Each call is one second later. */
class StepClock(private var seconds: Long = 1_000) : Clock {
    override fun now(): Instant = Instant.fromEpochSeconds(seconds++)
}

fun testProject(name: String, shots: Int = 0, accepted: Int = 0) = Project(
    id = "id-$name", name = name, mode = Mode.MUSIC_VIDEO, createdAt = Instant.fromEpochSeconds(0),
    updatedAt = Instant.fromEpochSeconds(0), settings = ProjectSettings(ratio = Ratio.R16_9),
    shots = List(shots) { i ->
        Shot(number = i + 1, startMs = i * 8_000L, durationMs = 8_000, acceptedVideo = if (i < accepted) ArtifactId("vid_$i") else null)
    },
)

class ProjectIndexTest {
    private val fs = InMemoryFileSystem()
    private val path = "/home/u/.autoflow/projects.json"

    @Test
    fun recordsProgressMostRecentFirstAndPersists() {
        val index = ProjectIndex(fs, path, StepClock())
        index.record("/p/a.autoflow", testProject("A"))
        index.record("/p/b.autoflow", testProject("B", shots = 4, accepted = 1))
        assertEquals(listOf("B", "A"), index.entries.value.map { it.name })
        val b = index.entries.value.first()
        assertEquals(Ratio.R16_9, b.ratio)
        assertEquals(1 to 4, b.acceptedShots to b.totalShots)

        // A save that is not an open refreshes the row but keeps the order.
        index.record("/p/a.autoflow", testProject("A2", shots = 2), opened = false)
        assertEquals(listOf("B", "A2"), index.entries.value.map { it.name })
        index.record("/p/a.autoflow", testProject("A2", shots = 2))
        assertEquals(listOf("A2", "B"), index.entries.value.map { it.name })

        index.remove("/p/b.autoflow")
        val reread = ProjectIndex(fs, path)
        assertEquals(listOf("/p/a.autoflow"), reread.entries.value.map { it.path })
        assertNull(reread.problem)
    }

    @Test
    fun unreadableIndexStartsEmptyWithAProblem() {
        fs.createDirectories("/home/u/.autoflow")
        fs.writeDurably(path, "{ not json".encodeToByteArray())
        val index = ProjectIndex(fs, path)
        assertTrue(index.entries.value.isEmpty())
        assertNotNull(index.problem)
        index.record("/p/a.autoflow", testProject("A"))
        assertNull(index.problem)
        assertEquals(1, ProjectIndex(fs, path).entries.value.size)
    }

    @Test
    fun folderNamesAreSafeOnWindows() {
        assertEquals("My Song.autoflow", projectFolderName("  My Song  "))
        assertEquals("a_b_c_.autoflow", projectFolderName("a/b:c?."))
        assertEquals("မိုး.autoflow", projectFolderName("မိုး"))
        assertEquals("project.autoflow", projectFolderName(" ... "))
    }
}
