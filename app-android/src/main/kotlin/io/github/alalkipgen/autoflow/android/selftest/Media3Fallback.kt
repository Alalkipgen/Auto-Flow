package io.github.alalkipgen.autoflow.android.selftest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import io.github.alalkipgen.autoflow.media.selftest.Engine
import io.github.alalkipgen.autoflow.media.selftest.RenderOutcome
import io.github.alalkipgen.autoflow.media.selftest.RenderResult
import io.github.alalkipgen.autoflow.media.selftest.SelfTestRenders
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * D-23 fallback: the same S6 composition with Media3 Transformer (hardware codecs, no exec) — concat of the two
 * clips, the Burmese lines as a bitmap overlay (Android's text stack shapes Myanmar), the mp3 mixed in.
 */
@OptIn(UnstableApi::class)
internal object Media3Fallback {
    data class Spec(val id: String, val title: String, val height: Int)

    val SPECS = listOf(
        Spec("m1-media3-540p", "Media3: concat + Burmese overlay + mp3 → 540p", 960),
        Spec("m2-media3-1080p", "Media3: concat + Burmese overlay + mp3 → 1080p", 1920),
    )

    private val CLIP1_LINES = listOf(
        Line("ကျွန်တော် မိုးရွာနေတဲ့ ညမှာ တစ်ယောက်တည်း", 860f, Color.WHITE),
        Line("မြန်မာ စာလုံးဆင့် ကမ္ဘာ ကြွက် ညှိ ဉာဏ်", 980f, Color.WHITE),
    )
    private val CLIP2_LINES = listOf(Line("ကျွန်တော် မိုးရွာနေတဲ့ ညမှာ တစ်ယောက်တည်း", 1200f, Color.rgb(0xFF, 0xD7, 0x00)))

    private class Line(val text: String, val centerY1920: Float, val color: Int)

    suspend fun render(
        context: Context,
        inputs: SelfTestRenders.Inputs,
        spec: Spec,
        framesDir: File,
        onProgress: (Int) -> Unit,
    ): RenderResult {
        val out = File(inputs.outDir, "${spec.id}.mp4").apply { delete() }
        val width = spec.height * 9 / 16
        val font = File(inputs.fontsDir).listFiles()?.firstOrNull { it.name.endsWith(".ttf") }
        val rssReset = ProcStatus.resetPeak()
        var sampledPeak = ProcStatus.rssKb() ?: 0L
        val t0 = SystemClock.elapsedRealtime()
        val error: String? = try {
            val composition = composition(inputs, width, spec.height, font)
            val done = CompletableDeferred<ExportException?>()
            val transformer = withContext(Dispatchers.Main) {
                Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            done.complete(null)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            done.complete(exportException)
                        }
                    })
                    .build()
                    .also { it.start(composition, out.path) }
            }
            try {
                val holder = ProgressHolder()
                val finished = withTimeoutOrNull(TIMEOUT_MS) {
                    while (!done.isCompleted) {
                        val state = withContext(Dispatchers.Main) { transformer.getProgress(holder) }
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                        ProcStatus.rssKb()?.let { sampledPeak = maxOf(sampledPeak, it) }
                        withTimeoutOrNull(POLL_MS) { done.await() }
                    }
                    done.await()
                }
                when {
                    !done.isCompleted -> {
                        withContext(NonCancellable + Dispatchers.Main) { transformer.cancel() }
                        "timed out after ${TIMEOUT_MS / 60_000} min"
                    }
                    finished != null -> "${finished.errorCodeName}: ${finished.message}" + (finished.cause?.let { " (cause: $it)" } ?: "")
                    else -> null
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(NonCancellable + Dispatchers.Main) { transformer.cancel() }
                throw e
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "${e::class.simpleName}: ${e.message}"
        }
        val wall = SystemClock.elapsedRealtime() - t0
        val hwm = ProcStatus.peakKb()
        val (peak, note) = if (rssReset && hwm != null) hwm to "app VmHWM" else sampledPeak to "app VmRSS sampled"
        val outcome = if (error == null && out.length() > 0) {
            val png = File(framesDir, "${spec.id}.png")
            RenderOutcome.Ok(out.length(), probe(out), if (frame(out, png)) png.path else null)
        } else {
            RenderOutcome.Failed(error ?: "no output file", null, "Media3 Transformer → ${spec.id} (${width}x${spec.height})", null)
        }
        return RenderResult(spec.id, spec.title, Engine.MEDIA3, wall, peak.takeIf { it > 0 }, note, outcome)
    }

    private fun composition(inputs: SelfTestRenders.Inputs, width: Int, height: Int, font: File?): Composition {
        fun clip(path: String, lines: List<Line>) = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(File(path))))
            .setEffects(
                Effects(
                    emptyList(),
                    listOf(
                        Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT),
                        OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(textBitmap(width, height, font, lines)))),
                    ),
                ),
            )
            .build()
        val video = EditedMediaItemSequence.withAudioAndVideoFrom(listOf(clip(inputs.clip1, CLIP1_LINES), clip(inputs.clip2, CLIP2_LINES)))
        val song = MediaItem.Builder()
            .setUri(Uri.fromFile(File(inputs.song)))
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(MUSIC_MS).build())
            .build()
        val music = EditedMediaItemSequence.withAudioFrom(listOf(EditedMediaItem.Builder(song).build()))
        return Composition.Builder(listOf(video, music)).build()
    }

    /** Full-frame transparent bitmap with the lines centred like the .ass `\pos(540, y)` (PlayRes 1080×1920). */
    private fun textBitmap(width: Int, height: Int, font: File?, lines: List<Line>): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val scale = height / 1920f
        val typeface = font?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() } ?: Typeface.DEFAULT
        val margin = 40f * scale
        for (line in lines) {
            val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; textSize = 64f * scale; color = line.color }
            val outline = TextPaint(fill).apply { style = Paint.Style.STROKE; strokeWidth = 6f * scale; color = Color.BLACK }
            for (paint in listOf(outline, fill)) {
                val layout = StaticLayout.Builder.obtain(line.text, 0, line.text.length, paint, (width - 2 * margin).toInt())
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .build()
                canvas.save()
                canvas.translate(margin, line.centerY1920 * scale - layout.height / 2f)
                layout.draw(canvas)
                canvas.restore()
            }
        }
        return bitmap
    }

    private fun probe(video: File): String? = retriever(video) { r ->
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        val audio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        "video ${w}x$h" + (if (audio) " + audio" else " (no audio)") + (ms?.let { ", ${it / 1000}.${it % 1000 / 100} s" } ?: "")
    }

    private fun frame(video: File, png: File): Boolean = retriever(video) { r ->
        val bmp = r.getFrameAtTime(SelfTestRenders.FRAME_AT_S * 1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST) ?: return@retriever false
        png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    } ?: false

    private fun <T> retriever(video: File, block: (MediaMetadataRetriever) -> T): T? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(video.path)
            block(r)
        } catch (_: RuntimeException) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private const val MUSIC_MS = 16_000L
    private const val TIMEOUT_MS = 20 * 60_000L
    private const val POLL_MS = 500L
}

/** `/proc/self/status` peak and current RSS of the app process (the Media3 path runs in-process). */
internal object ProcStatus {
    fun rssKb(): Long? = field("VmRSS")

    fun peakKb(): Long? = field("VmHWM")

    /** Writing 5 to clear_refs resets VmHWM to the current RSS (Linux ≥ 4.0); SELinux may refuse it. */
    fun resetPeak(): Boolean = runCatching { File("/proc/self/clear_refs").writeText("5"); true }.getOrDefault(false)

    private fun field(name: String): Long? = runCatching {
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("$name:") }
            ?.removePrefix("$name:")?.trim()?.removeSuffix("kB")?.trim()?.toLongOrNull()
    }.getOrNull()
}
