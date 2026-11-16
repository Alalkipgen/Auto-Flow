package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.*
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.*
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.projects.*
import androidx.compose.ui.graphics.toPixelMap
import java.awt.image.BufferedImage
import java.io.*
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class NativePreviewAssetsTest {
    @Test fun contentHashAndRelativeImagesAreReadOnlyMissingAndOutsideFilesAreNeverSubstituted() {
        val root = Files.createTempDirectory("native-assets").toFile()
        try {
            val fs = JvmFs(); val dir = File(root, "a.autoflow").apply { mkdirs() }
            val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, 0xFF00FF) }, "png", it) }.toByteArray()
            val a = (ArtifactStore(fs, dir.path).put(png, ArtifactKind.IMAGE, "png") as CoreResult.Ok).value
            File(dir, "brand.png").writeBytes(png); File(root, "outside.png").writeBytes(png)
            val r = RecapV2(layers = listOf(Layer("image", LayerType.IMAGE, fromMs = 1000, params = LayerParams(image = a.id.value)),
                Layer("bad", LayerType.BACKGROUND, params = LayerParams(mode = BackgroundMode.IMAGE, image = "../outside.png"))), visual = RecapVisual(watermarkFile = "brand.png"))
            val loaded = NativePreviewAssets(fs).load(dir.path, r)
            assertEquals(setOf(a.id.value, "brand.png"), loaded.keys)
            assertTrue(loaded.getValue(a.id.value).toPixelMap()[0, 0].red > .9f)
            assertContentEquals(png, (ArtifactStore(fs, dir.path).readBytes(a.id) as CoreResult.Ok).value)
            assertEquals(emptyMap(), NativePreviewAssets(fs).load(dir.path, r.copy(layers = emptyList(), visual = RecapVisual())))
        } finally { root.deleteRecursively() }
    }

    @Test fun staleProjectAssetRequestCannotAttachToNewProject() {
        val root = Files.createTempDirectory("native-assets-stale").toFile()
        try {
            val fs = JvmFs(); val store = ProjectStore(fs); val current = CurrentProject()
            val session = ProjectSession(fs, store, ProjectIndex(fs, File(root, "index.json").path), current)
            val dir = File(root, "a.autoflow").path
            val p = (store.create(dir, "A", Mode.MOVIE_RECAP) as CoreResult.Ok).value
            val r = RecapV2(movie = RecapMovie("movie.mp4", 1, 1000, 320, 180))
            val saved = (store.save(dir, p.copy(recap = r)) as CoreResult.Ok).value
            current.set(OpenProject(dir, saved))
            val source = RecapPreviewSource(session, fs, null)
            val captured = assertNotNull(source.resolve(RecapUiState(projectDir = dir, recap = r, story = RecapStory(source = "manual", blocks = listOf(RecapBlock("a", listOf(RecapClip(0, 1000)), "text"))))))
            assertEquals(emptyMap(), source.images(captured, r))
            val b = File(root, "b.autoflow").path
            current.set(OpenProject(b, (store.create(b, "B", Mode.MOVIE_RECAP) as CoreResult.Ok).value))
            assertNull(source.images(captured, r))
        } finally { root.deleteRecursively() }
    }
}
