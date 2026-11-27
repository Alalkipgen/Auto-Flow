package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.ui.graphics.ImageBitmap
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.ui.components.decodeImage

/** Read-only asset snapshot from ONE captured project. No repair, file mutation, or best-effort omission. */
internal class NativePreviewAssets(private val fs: FileSystem) {
    fun load(projectDir: String, recap: RecapV2): Map<String, ImageBitmap> {
        val required = recap.layers.filter { it.type == LayerType.IMAGE || it.type == LayerType.BACKGROUND && it.params.mode == BackgroundMode.IMAGE }.mapNotNull { it.params.image } +
            listOfNotNull(recap.visual.watermarkFile, recap.visual.backgroundImageFile.takeIf { recap.visual.background == FrameBackground.IMAGE })
        val artifacts = ArtifactStore(fs, projectDir)
        return required.distinct().mapNotNull { ref ->
            val id = ArtifactId(ref)
            val relative = if (id.isWellFormed) (artifacts.get(id) as? CoreResult.Ok)?.value?.file?.let { joinPath(ArtifactStore.ARTIFACTS_DIR, it) } else ref
            // Legacy background / Brand Kit watermark references are project-relative, never an outside path.
            val safe = relative?.replace('\\', '/')?.takeIf { !it.startsWith('/') && ':' !in it && it.split('/').none { p -> p == ".." || p.isBlank() } }
            val bitmap = safe?.let { runCatching { decodeImage(fs.read(joinPath(projectDir, it))) }.getOrNull() }
            bitmap?.let { ref to it }
        }.toMap()
    }
}
