package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.hash.Sha256
import io.github.alalkipgen.autoflow.core.io.*
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/** Read-only, full-scale mono amplitude overview. Never a transport, trim or selected-take write. */
internal class RecapTakeWaveforms(private val fs: FileSystem, private val media: MediaTools?, private val cacheDir: String?) {
    data class Source(val dir: String, val projectId: String, val revision: String, val blockId: String,
        val audioId: String, val file: String, val durationMs: Long,
    ) {
        fun key(bytes: Long): String = Sha256.hex(listOf(dir, projectId, revision, blockId, audioId, file,
            durationMs.toString(), bytes.toString()).joinToString("") { "${it.length}:$it" }.encodeToByteArray())

        companion object {
            fun selected(request: BlockVoiceRequest, entry: TakeHistoryEntry): Source? =
                entry.file?.takeIf { entry.selected && entry.take.block == request.blockId &&
                    request.project.recap?.takeFor(request.blockId) == entry.take }?.let {
                    Source(request.dir, request.project.id, request.project.updatedAt.toString(),
                        request.blockId, entry.take.audio.value, it, entry.take.durationMs)
                }
        }
    }

    data class Peak(val low: Int, val high: Int)
    data class Envelope(val samples: Int, val peaks: List<Peak>) {
        val durationMs: Long get() = samples * 1_000L / SAMPLE_RATE
    }

    /**
     * Decode the selected immutable artifact, not movie audio or a synthetic duration proxy. Bounded raw PCM
     * avoids image-filter/codec assumptions. One process at a time, 64 checked app-cache slots. Larger than
     * ten minutes / 9.6 MB PCM, missing tools/audio, corrupt output and timeout keep the duration fallback.
     */
    suspend fun load(source: Source, current: () -> Boolean = { true }): Envelope? = withContext(Dispatchers.Default) {
        val cache = cacheDir ?: return@withContext null
        if (inside(cache, source.dir) || !inside(source.file, joinPath(source.dir, "artifacts")) ||
            source.durationMs !in 1..MAX_DURATION_MS) return@withContext null
        mutex.withLock {
            val nonce = Random.nextLong().toString(16)
            val work = joinPath(cache, "working-$nonce.pcm")
            val pending = joinPath(cache, "pending-$nonce.bin")
            try {
                ensureActive()
                if (!current() || !fs.exists(source.file)) return@withLock null
                val bytes = fs.size(source.file).takeIf { it > 0 } ?: return@withLock null
                val key = source.key(bytes)
                val prefix = "autoflow-take-waveform-v1\n$key\n"
                val slot = joinPath(cache, "${key.take(2).toInt(16) % 64}.bin")
                fun stillCurrent() = current() && fs.exists(source.file) && fs.size(source.file) == bytes
                if (fs.exists(slot) && fs.size(slot) in 1..MAX_CACHE_BYTES) {
                    val text = fs.read(slot).decodeToString()
                    if (text.startsWith(prefix)) decodeRecord(text.removePrefix(prefix))?.let {
                        return@withLock it.takeIf { stillCurrent() }
                    }
                }
                val runner = media?.runner ?: return@withLock null
                val ffmpeg = media.ffmpeg() ?: return@withLock null
                fs.createDirectories(cache)
                fs.writeDurably(work, byteArrayOf()) // A no-output process can never reuse another take.
                val result = runner.run(listOf(ffmpeg, "-v", "error", "-nostdin", "-y", "-i", source.file,
                    "-map", "0:a:0", "-vn", "-sn", "-dn", "-ac", "1", "-ar", "$SAMPLE_RATE",
                    "-c:a", "pcm_s16le", "-t", "600.001", "-f", "s16le", work), cache, 30_000, false, {})
                ensureActive()
                if (result !is ToolExec.Done || result.exitCode != 0 || result.timedOut || !stillCurrent() ||
                    !fs.exists(work) || fs.size(work) !in 2..MAX_PCM_BYTES) return@withLock null
                val envelope = pcm(fs.read(work)) ?: return@withLock null
                ensureActive()
                if (!stillCurrent()) return@withLock null
                val record = "$prefix${envelope.samples}\n${envelope.peaks.joinToString("|") { "${it.low},${it.high}" }}"
                fs.writeDurably(pending, record.encodeToByteArray())
                fs.atomicMove(pending, slot)
                envelope.takeIf { stillCurrent() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } finally {
                // FileSystem deliberately has no delete. Recycle only our own app-cache temporary paths.
                listOf(work, pending).forEach { path -> runCatching {
                    if (fs.exists(path)) fs.atomicMove(path, joinPath(cache, "discard.tmp"))
                } }
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 8_000
        const val MAX_DURATION_MS = 600_000L
        private const val MAX_PCM_BYTES = 9_600_000L
        private const val MAX_CACHE_BYTES = 8_192L
        private const val BUCKETS = 128
        private val mutex = Mutex()

        private fun normalized(path: String): String {
            val parts = mutableListOf<String>()
            path.replace('\\', '/').split('/').forEach {
                when (it) { "", "." -> Unit; ".." -> { if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex) }; else -> parts += it }
            }
            return parts.joinToString("/").lowercase()
        }
        private fun inside(path: String, dir: String): Boolean =
            normalized(path).let { it == normalized(dir) || it.startsWith("${normalized(dir)}/") }

        internal fun pcm(bytes: ByteArray): Envelope? {
            if (bytes.isEmpty() || bytes.size % 2 != 0 || bytes.size > MAX_PCM_BYTES) return null
            val samples = bytes.size / 2
            val count = minOf(BUCKETS, samples)
            val peaks = List(count) { bucket ->
                var low = 32767; var high = -32768
                for (n in bucket * samples / count until (bucket + 1) * samples / count) {
                    val unsigned = (bytes[n * 2].toInt() and 255) or ((bytes[n * 2 + 1].toInt() and 255) shl 8)
                    val value = if (unsigned >= 32768) unsigned - 65536 else unsigned
                    low = minOf(low, value); high = maxOf(high, value)
                }
                Peak(low, high)
            }
            return Envelope(samples, peaks)
        }

        private fun decodeRecord(text: String): Envelope? {
            val lines = text.split('\n')
            if (lines.size != 2) return null
            val samples = lines[0].toIntOrNull()?.takeIf { it in 1..(MAX_PCM_BYTES / 2).toInt() } ?: return null
            val peaks = lines[1].split('|').map { pair ->
                val numbers = pair.split(',').map { it.toIntOrNull() ?: return null }
                if (numbers.size != 2 || numbers[0] !in -32768..32767 || numbers[1] !in numbers[0]..32767) return null
                Peak(numbers[0], numbers[1])
            }
            return Envelope(samples, peaks).takeIf { peaks.size == minOf(BUCKETS, samples) }
        }
    }
}

/** Stable viewport slot; only a visible selected take requests decode. No fabricated bars while unavailable. */
@Composable
internal fun SelectedTakeWaveform(model: RecapModel, request: BlockVoiceRequest, entry: TakeHistoryEntry,
    durationBar: @Composable () -> Unit,
) {
    val source = RecapTakeWaveforms.Source.selected(request, entry)
    key(source) {
        var visible by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(false) }
        val envelope by produceState<RecapTakeWaveforms.Envelope?>(null, source) {
            if (source != null) {
                snapshotFlow { visible }.first { it }
                loading = true
                // Start once on first visibility. A scroll/layout blip must not cancel and restart the same
                // bounded decode. The source/block/project/detail composition still owns cancellation.
                try { value = model.takeWaveforms.load(source) { model.voiceInspector.current(request) } }
                finally { loading = false }
            }
        }
        val loaded = envelope
        val caption = if (loaded == null) tr(if (loading) UiText.V3_WAVEFORM_LOADING else UiText.V3_WAVEFORM_UNAVAILABLE)
            else tr(UiText.V3_WAVEFORM_MONO, "duration" to RecapModel.seconds(loaded.durationMs))
        AfText(tr(UiText.V3_WAVEFORM), bold = true)
        Box(Modifier.fillMaxWidth().height(80.dp).testTag("take-waveform-slot").onGloballyPositioned {
            visible = it.isAttached && !it.boundsInWindow().isEmpty
        }, contentAlignment = Alignment.Center) {
            if (loaded == null) Box(Modifier.fillMaxWidth().testTag("take-waveform-fallback")) { durationBar() }
            else Canvas(Modifier.fillMaxSize().testTag("take-waveform-ready").semantics {
                contentDescription = caption
                // Deliberately no range/click actions: this is not the movie clock or a new audio transport.
            }) {
                val center = size.height / 2
                drawLine(AutoFlowColors.Line, Offset(0f, center), Offset(size.width, center), 1.dp.toPx())
                loaded.peaks.forEachIndexed { i, peak ->
                    if (peak.low != 0 || peak.high != 0) {
                        val x = (i + .5f) * size.width / loaded.peaks.size
                        drawLine(AutoFlowColors.Purple,
                            Offset(x, center - peak.high / 32768f * center),
                            Offset(x, center - peak.low / 32768f * center),
                            minOf(2.dp.toPx(), size.width / loaded.peaks.size), cap = StrokeCap.Round)
                    }
                }
            }
        }
        AfText(caption, Modifier.testTag("take-waveform-caption"), muted = true)
    }
}