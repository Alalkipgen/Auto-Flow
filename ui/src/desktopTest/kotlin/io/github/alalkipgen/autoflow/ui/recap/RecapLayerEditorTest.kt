package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.*
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.*
import io.github.alalkipgen.autoflow.platform.*
import io.github.alalkipgen.autoflow.ui.projects.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

/** Real serialized project files, folder locks and immutable artifacts, not setter mocks. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecapLayerEditorTest {
    private class Fixture(val scope: CoroutineScope, wait: suspend () -> Unit = {}) : AutoCloseable {
        val root = Files.createTempDirectory("layer-edit").toFile()
        val fs = JvmFs()
        val store = ProjectStore(fs)
        val current = CurrentProject()
        val dir = File(root, "one.autoflow").path.replace('\\', '/')
        val session = ProjectSession(fs, store, ProjectIndex(fs, File(root, "index.json").path), current)
        val image = File(root, "source.png").apply { ImageIO.write(BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB), "png", this) }
        val picker = object : FilePicker {
            override suspend fun pickFolder(title: String, startDir: String?) = PlatformResult.Ok(null)
            override suspend fun pickFiles(title: String, extensions: Set<String>, multiple: Boolean, startDir: String?): PlatformResult<List<String>> {
                wait(); return PlatformResult.Ok(listOf(image.path))
            }
        }
        val original: Project
        var refreshes = 0
        val editor = RecapLayerEditor(session, fs, picker, scope) { refreshes++ }
        init {
            val p = (store.create(dir, "One", Mode.MOVIE_RECAP) as CoreResult.Ok).value
            val take = BlockTake("stable-block", ArtifactId("au_0123456789ab"), TakeSource.RECORDED, durationMs = 1_000, spokenHash = "unchanged")
            val r = RecapV2(story = ArtifactId("rs_0123456789ab"), storyApproved = true, takes = listOf(take),
                chosenTakes = mapOf(take.block to take.audio), layers = listOf(Layer("existing", LayerType.BORDER)),
                visual = RecapVisual(masks = listOf(RecapMask(MaskPreset.BOTTOM, MaskPreset.BOTTOM.rect))))
            original = (store.save(dir, p.copy(recap = r)) as CoreResult.Ok).value
            current.set(OpenProject(dir, original))
        }
        fun saved() = (store.open(dir) as CoreResult.Ok).value
        fun draft() = editor.state.value.draft!!
        fun projectBytes() = File(dir, "project.json").readBytes()
        fun switchProject() {
            val otherDir = File(root, "two.autoflow").path.replace('\\', '/')
            val other = (store.create(otherDir, "Two", Mode.MOVIE_RECAP) as CoreResult.Ok).value
            current.set(OpenProject(otherDir, other))
        }
        override fun close() { root.deleteRecursively() }
    }

    @Test fun everyLayerTypeAndParametersSurviveSaveReopenAndOrderChanges() = runTest {
        Fixture(this).use { f ->
            val ids = mutableListOf("existing")
            for (type in LayerType.entries) {
                f.editor.add(type)
                if (type == LayerType.IMAGE || type == LayerType.BACKGROUND) f.editor.importImage()!!.join()
                val d = f.draft()
                val params = d.layer.params.copy(sigma = 12f, feather = 0.03f, textMy = "မင်္ဂလာပါ", style = "Pyidaungsu",
                    opacity = 0.42f, px = 8, colour = "#305070", mode = BackgroundMode.IMAGE)
                val layer = d.layer.copy(fromMs = 200, toMs = 2_500, params = params)
                assertTrue(f.editor.save(layer), f.editor.state.value.problem)
                ids += layer.id
                assertEquals(layer, f.saved().recap!!.layers.last())
            }
            assertEquals(ids, f.saved().recap!!.layers.map { it.id })
            f.editor.edit(ids.last()); f.editor.move(-1)
            assertTrue(f.editor.save(f.draft().layer))
            val expected = ids.toMutableList().apply { val last = removeAt(lastIndex); add(lastIndex, last) }
            val saved = f.saved()
            assertEquals(expected, saved.recap!!.layers.map { it.id })
            assertEquals(f.original.recap!!.copy(layers = saved.recap!!.layers), saved.recap, "takes, approvals, story and movie-mask space untouched")
            assertEquals(6, f.refreshes)
        }
    }

    @Test fun cancelAndRemoveDoNotMutateOrDeleteArtifacts() = runTest {
        Fixture(this).use { f ->
            f.editor.add(LayerType.IMAGE); f.editor.importImage()!!.join()
            val layer = f.draft().layer
            assertTrue(f.editor.save(layer))
            val artifacts = File(f.dir, "artifacts").listFiles()!!.associate { it.name to it.readBytes().toList() }
            val before = f.projectBytes().toList()
            f.editor.edit(layer.id); f.editor.move(-1); f.editor.cancel()
            assertEquals(before, f.projectBytes().toList())
            f.editor.edit(layer.id); assertTrue(f.editor.remove())
            assertEquals(listOf("existing"), f.saved().recap!!.layers.map { it.id })
            assertEquals(artifacts, File(f.dir, "artifacts").listFiles()!!.associate { it.name to it.readBytes().toList() })
            assertEquals(ArtifactKind.IMAGE, (ArtifactStore(f.fs, f.dir).get(ArtifactId(layer.params.image!!)) as CoreResult.Ok).value.kind)
        }
    }

    @Test fun externalRevisionConflictWritesNothing() = runTest {
        Fixture(this).use { f ->
            f.editor.edit("existing")
            assertIs<CoreResult.Ok<*>>(f.store.update(f.dir) { it.copy(name = "Other window") })
            val before = f.projectBytes().toList()
            assertFalse(f.editor.save(f.draft().layer.copy(params = LayerParams(px = 9))))
            assertTrue(f.editor.state.value.problem!!.contains("nothing was written"))
            assertEquals(before, f.projectBytes().toList())
        }
    }

    @Test fun interveningSessionSaveAndForgedIdentityAreRejected() = runTest {
        Fixture(this).use { f ->
            f.editor.edit("existing")
            assertFalse(f.editor.save(f.draft().layer.copy(id = "different")))
            assertEquals(f.original.recap, f.saved().recap)
            assertIs<CoreResult.Ok<*>>(f.session.save(f.session.open!!.project.copy(name = "New title")))
            assertFalse(f.editor.save(f.draft().layer))
            assertEquals("New title", f.saved().name)
        }
    }

    @Test fun projectSwitchCannotWriteIntoEitherProject() = runTest {
        Fixture(this).use { f ->
            f.editor.edit("existing"); val layer = f.draft().layer
            val before = f.projectBytes().toList()
            f.switchProject(); val otherBefore = File(f.session.open!!.dir, "project.json").readBytes().toList()
            assertFalse(f.editor.save(layer.copy(params = LayerParams(px = 15))))
            assertEquals(before, f.projectBytes().toList())
            assertEquals(otherBefore, File(f.session.open!!.dir, "project.json").readBytes().toList())
        }
    }

    @Test fun pickerCompletionAfterProjectSwitchOrCancelCannotAttachToNewTarget() = runTest {
        for (switch in listOf(true, false)) {
            val gate = CompletableDeferred<Unit>()
            Fixture(this) { gate.await() }.use { f ->
                f.editor.add(LayerType.IMAGE); val job = f.editor.importImage()!!
                runCurrent()
                if (switch) f.switchProject() else { f.editor.cancel(); f.editor.add(LayerType.TEXT) }
                gate.complete(Unit); job.join()
                assertEquals(listOf("existing"), f.saved().recap!!.layers.map { it.id })
                assertTrue(File(f.dir, "artifacts").listFiles().orEmpty().isEmpty(), "late result never creates an artifact in the wrong edit")
                if (!switch) assertNull(f.draft().layer.params.image)
            }
        }
    }

    @Test fun cancelledPickerAndInvalidGeometryNeverPersist() = runTest {
        val gate = CompletableDeferred<Unit>()
        Fixture(this) { gate.await() }.use { f ->
            val before = f.projectBytes().toList()
            f.editor.add(LayerType.IMAGE); val job = f.editor.importImage()!!; runCurrent(); job.cancelAndJoin()
            assertFalse(f.editor.state.value.importing)
            f.editor.cancel(); f.editor.add(LayerType.BLUR)
            assertFalse(f.editor.save(f.draft().layer.copy(rect = listOf(Float.NaN, 0f, 1f, 1f))))
            assertFalse(f.editor.save(f.draft().layer.copy(fromMs = 4, toMs = 3)))
            assertEquals(before, f.projectBytes().toList())
        }
    }
}
