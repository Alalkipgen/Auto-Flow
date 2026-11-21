package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.hash.Sha256
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.recap.RecapMovie
import io.github.alalkipgen.autoflow.media.probe.FrameGrab
import io.github.alalkipgen.autoflow.ui.components.decodeImage
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/** UI-only source frames, never export frames, artifacts or a persisted project field. */
internal class RecapThumbnails(private val fs: FileSystem, private val media: MediaTools?, private val cacheDir: String?) {
    data class Source(val dir: String, val projectId: String, val revision: String, val movie: RecapMovie) {
        fun key(fromMs: Long, toMs: Long): String = Sha256.hex(
            listOf(dir, projectId, revision, movie.toString(), fromMs.toString(), toMs.toString())
                .joinToString("") { "${it.length}:$it" }.encodeToByteArray())
    }
    val enabled: Boolean get() = cacheDir != null

    /**
     * One decoder at a time. Revision is deliberately conservative: the movie is not hashed (D-33), so even
     * same-size replacements cannot reuse an earlier saved revision. Cache collisions are checked, not trusted.
     * 64 replaceable app-cache slots, plus temporary work files; no project file is written.
     */
    suspend fun load(source: Source, fromMs: Long, toMs: Long, current: () -> Boolean = { true }): ImageBitmap? =
        withContext(Dispatchers.Default) {
            val cache = cacheDir ?: return@withContext null
            val movie = source.movie
            if (cache == source.dir || cache.startsWith("${source.dir}/") ||
                fromMs < 0 || toMs <= fromMs || toMs > movie.durationMs) return@withContext null
            mutex.withLock {
                ensureActive()
                if (!current()) return@withLock null
                val nonce = Random.nextLong().toString(16)
                val work = joinPath(cache, "working-$nonce.jpg")
                val pending = joinPath(cache, "pending-$nonce.bin")
                try {
                    val video = joinPath(source.dir, movie.file)
                    if (!fs.exists(video) || (movie.sizeBytes > 0 && fs.size(video) != movie.sizeBytes)) return@withLock null
                    val key = source.key(fromMs, toMs)
                    val prefix = "autoflow-scene-thumb-v1\n$key\n".encodeToByteArray()
                    val slot = joinPath(cache, "${key.take(2).toInt(16) % 64}.bin")
                    if (fs.exists(slot) && fs.size(slot) in (prefix.size + 1L)..(MAX_BYTES + prefix.size)) {
                        val bytes = fs.read(slot)
                        if (bytes.take(prefix.size).toByteArray().contentEquals(prefix)) {
                            decode(bytes.copyOfRange(prefix.size, bytes.size))?.let { return@withLock it.takeIf { current() } }
                        }
                    }
                    val runner = media?.runner ?: return@withLock null
                    val ffmpeg = media.ffmpeg() ?: return@withLock null
                    fs.createDirectories(cache)
                    // A failed/no-output process must never reuse the preceding request's work image.
                    fs.writeDurably(work, byteArrayOf())
                    val at = fromMs + (toMs - fromMs) / 2
                    if (FrameGrab(runner, ffmpeg).thumb(video, at, work, cache, 240) !is FrameGrab.Result.Ok) return@withLock null
                    ensureActive()
                    if (!current() || !fs.exists(work) || fs.size(work) !in 1L..MAX_BYTES) return@withLock null
                    val bytes = fs.read(work)
                    val bitmap = decode(bytes) ?: return@withLock null
                    // Key and JPEG are one atomically replaced record, never a mismatched sidecar pair.
                    fs.writeDurably(pending, prefix + bytes)
                    fs.atomicMove(pending, slot)
                    bitmap.takeIf { current() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null // Missing tool, bad media or unreadable cache keeps the real time-only row.
                } finally {
                    // Unique work paths also isolate two desktop processes. Recycle only our own temporary files;
                    // never touch another process's work or project data. Crash orphans are harmless app cache.
                    listOf(work, pending).forEach { file ->
                        runCatching { if (fs.exists(file)) fs.atomicMove(file, joinPath(cache, "discard.tmp")) }
                    }
                }
            }
        }

    private fun decode(bytes: ByteArray): ImageBitmap? = decodeImage(bytes)?.takeIf {
        it.width in 1..240 && it.height in 1..1024
    }

    companion object {
        private const val MAX_BYTES = 2_097_152L
        private val mutex = Mutex()
    }
}

/** The clipped viewport, not composition/pagination alone, starts IO. Offscreen bitmaps are released. */
@Composable
internal fun RecapThumbnail(model: RecapModel, state: RecapUiState, fromMs: Long, toMs: Long, tag: String) {
    if (!model.sceneThumbnails.enabled) return
    val source = model.thumbnailSource(state) ?: return
    key(source, fromMs, toMs) {
        var visible by remember { mutableStateOf(false) }
        val image by produceState<ImageBitmap?>(null, visible) {
            value = null
            if (visible) {
                value = model.sceneThumbnails.load(source, fromMs, toMs) { model.thumbnailIsCurrent(source) }
            }
        }
        // Stable from first measurement: late images above a scrolled row must not push its anchor offscreen.
        // The empty slot is not a fake picture; real time/controls remain when a thumbnail is unavailable.
        Box(Modifier.fillMaxWidth().height(72.dp).testTag("$tag-slot").onGloballyPositioned {
            visible = it.isAttached && !it.boundsInWindow().isEmpty
        }) {
            image?.let {
                // Decorative: the parent already announces the scene/block number, real time and actions.
                Image(it, null, Modifier.size(128.dp, 72.dp).clip(AutoFlowShapes.Card).testTag(tag), contentScale = ContentScale.Crop)
            }
        }
    }
}