package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.*
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.*
import io.github.alalkipgen.autoflow.media.audio.PcmWav
import io.github.alalkipgen.autoflow.media.render.RecapRender
import io.github.alalkipgen.autoflow.media.render.RenderPresets
import io.github.alalkipgen.autoflow.platform.*
import kotlinx.coroutines.runBlocking
import org.junit.AssumptionViolatedException
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class QaRecapFixtureTest {
    @Test fun realBackupRestorePreservesNativeLayersTakesAndMappedTimeline() = runBlocking {
        val available = runCatching { ProcessBuilder(System.getenv("AUTOFLOW_FFMPEG") ?: "ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false)
        if (!available) {
            check(System.getenv("AUTOFLOW_EXPECT_FFMPEG") != "1") { "ffmpeg required by QA fixture gate" }
            throw AssumptionViolatedException("QA backup not executed: no ffmpeg")
        }
        val root = Files.createTempDirectory("qa-fixture-restore").toFile()
        try {
            val fixture = QaRecapFixture.create(root, ratio = Ratio.R1_1, looks = listOf(Look.ZOOM), background = BackgroundMode.IMAGE)
            val backup = File(root, "qa.zip")
            backup.outputStream().use { ProjectZip.write(ProjectZip.plan(listOf(BackupProject(fixture.dir.path, fixture.dir.name))), it, "2026-11-16T00:00:00Z") }
            val dest = File(root, "restored").apply { mkdirs() }
            val restored = backup.inputStream().use { ProjectZip.restore(it, dest) }.single()
            assertTrue(restored.folder.endsWith(".autoflow"))
            val restoredDir = File(dest, restored.folder)
            val fs = JvmFs(); val reopened = (ProjectStore(fs).open(restoredDir.path) as CoreResult.Ok).value
            assertEquals(fixture.project, reopened)
            val recap = reopened.recap!!
            assertEquals(LayerType.entries.toSet(), recap.layers.map { it.type }.toSet())
            val artifacts = ArtifactStore(fs, restoredDir.path)
            recap.takes.forEach { take ->
                val bytes = (artifacts.readBytes(take.audio) as CoreResult.Ok).value
                assertEquals(1_000L, PcmWav.parse(bytes)!!.durationMs)
            }
            val imageId = ArtifactId(recap.layers.first { it.type == LayerType.IMAGE }.params.image!!)
            val image = (artifacts.get(imageId) as CoreResult.Ok).value
            assertContentEquals(File(fixture.dir, "artifacts/${image.file}").readBytes(), File(restoredDir, "artifacts/${image.file}").readBytes())
            val timeline = RecapRender.timeline(recap, fixture.story,
                { take -> (artifacts.get(take.audio) as? CoreResult.Ok)?.value?.file?.takeIf { File(restoredDir, "artifacts/$it").isFile } }, RenderPresets.preview(Ratio.R1_1))
            assertEquals(5_500L, timeline.durationMs)
            assertEquals(listOf(2_000L, 6_000L, 4_000L), timeline.clips.map { it.sourceFromMs })
            assertEquals(listOf("FIRST", "SECOND"), timeline.captions.map { it.text })
            assertEquals(listOf(200L, 4_200L), timeline.captions.map { it.startMs })
        } finally { root.deleteRecursively() }
    }
}
