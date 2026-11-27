package io.github.alalkipgen.autoflow.ui.media

import androidx.compose.ui.graphics.ImageBitmap
import io.github.alalkipgen.autoflow.core.hash.Sha256
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.media.probe.FrameGrab
import io.github.alalkipgen.autoflow.ui.components.decodeImage
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/** One thumbnail ffmpeg at a time across the app (scene thumbnails and [MediaThumbnails]). */
internal val ThumbnailGate = Mutex()

/**
 * DESIGN.md §9.5 (D-99): one small frame (the scene thumbnails' 240 px `FrameGrab.thumb` JPEG) of a project's video,
 * image or output for the Home, Projects, Jobs and Library rows. App cache only — never the project folder,
 * `project.json` or an artifact; deleting it is always safe and a missing frame is made again on demand. Runs off the
 * main thread, one at a time with the scene thumbnails, and waits while a render or export runs ([busy]); a 30-item
 * in-memory LRU sits in front of 128 replaceable disk slots. A failure gives null, so the row keeps its placeholder.
 */
class MediaThumbnails(private val fs: FileSystem, private val media: MediaTools?, private val cacheDir: String?,
    private val busy: () -> Boolean = { false }) {
    /** [file] inside the project / library folder [dir] (or absolute); [revision] changes when that item is saved again. */
    data class Source(val dir: String, val file: String, val revision: String = "", val durationMs: Long? = null) {
        val path: String get() = if (file.startsWith("/") || file.getOrNull(1) == ':') file else joinPath(dir, file)
        val image: Boolean get() = file.substringAfterLast('.').lowercase() in IMAGES

        /** 10 % of the duration, clamped to 1–60 s and never past the middle; an image = its only frame; unknown = 1 s. */
        val atMs: Long get() = when {
            image -> 0
            durationMs == null || durationMs <= 0 -> 1_000
            else -> (durationMs / 10).coerceIn(1_000, 60_000).coerceAtMost(durationMs / 2)
        }
    }

    private class Entry(val image: ImageBitmap?)
    private val memory = LinkedHashMap<String, Entry>()
    private val memoryLock = Mutex()

    suspend fun load(source: Source): ImageBitmap? = withContext(Dispatchers.Default) {
        val cache = cacheDir ?: return@withContext null
        if (cache == source.dir || cache.startsWith("${source.dir}/")) return@withContext null
        val size = runCatching { fs.size(source.path).takeIf { fs.exists(source.path) } }.getOrNull() ?: return@withContext null
        val key = Sha256.hex(listOf(source.dir, source.path, source.revision, size.toString(), source.atMs.toString())
            .joinToString("") { "${it.length}:$it" }.encodeToByteArray())
        recall(key)?.let { return@withContext it.image }
        while (busy()) delay(BUSY_POLL_MS)
        ThumbnailGate.withLock {
            ensureActive()
            (recall(key) ?: make(cache, source, key)?.also { keep(key, it) })?.image
        }
    }

    internal suspend fun remembered(): Int = memoryLock.withLock { memory.size }

    private suspend fun recall(key: String): Entry? = memoryLock.withLock { memory.remove(key)?.also { memory[key] = it } }

    private suspend fun keep(key: String, entry: Entry) = memoryLock.withLock {
        memory[key] = entry
        while (memory.size > MEMORY) memory.remove(memory.keys.first())
    }

    /** Null = no tool yet (try again later); Entry(null) = this source has no frame. */
    private suspend fun make(cache: String, source: Source, key: String): Entry? {
        val prefix = "autoflow-media-thumb-v1\n$key\n".encodeToByteArray()
        val slot = joinPath(cache, "media-${key.take(2).toInt(16) % SLOTS}.bin")
        val nonce = Random.nextLong().toString(16)
        val work = joinPath(cache, "media-$nonce.jpg")
        val part = joinPath(cache, "media-$nonce.part")
        return try {
            if (fs.exists(slot) && fs.size(slot) in (prefix.size + 1L)..(MAX_BYTES + prefix.size)) {
                val bytes = fs.read(slot)
                if (bytes.copyOfRange(0, prefix.size).contentEquals(prefix)) decode(bytes.copyOfRange(prefix.size, bytes.size))?.let { return Entry(it) }
            }
            val runner = media?.runner ?: return null
            val ffmpeg = media.ffmpeg() ?: return null
            fs.createDirectories(cache)
            // A video shorter than the chosen time gives no frame: try its first frame once.
            for (at in listOf(source.atMs, 0L).distinct()) {
                fs.writeDurably(work, byteArrayOf())
                if (FrameGrab(runner, ffmpeg).thumb(source.path, at, work, cache, 240) !is FrameGrab.Result.Ok) continue
                currentCoroutineContext().ensureActive()
                if (!fs.exists(work) || fs.size(work) !in 1L..MAX_BYTES) continue
                val bytes = fs.read(work)
                val bitmap = decode(bytes) ?: continue
                // Key and JPEG are one record written through a .part file, then renamed.
                fs.writeDurably(part, prefix + bytes)
                fs.atomicMove(part, slot)
                return Entry(bitmap)
            }
            Entry(null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Entry(null)
        } finally {
            listOf(work, part).forEach { file -> runCatching { if (fs.exists(file)) fs.atomicMove(file, joinPath(cache, "discard.tmp")) } }
        }
    }

    private fun decode(bytes: ByteArray): ImageBitmap? = decodeImage(bytes)?.takeIf { it.width in 1..240 && it.height in 1..1024 }

    private companion object {
        const val MAX_BYTES = 2_097_152L
        const val SLOTS = 128
        const val MEMORY = 30
        const val BUSY_POLL_MS = 1_000L
        val IMAGES = setOf("jpg", "jpeg", "png", "webp")
    }
}
