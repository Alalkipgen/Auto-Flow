package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.model.CoreJson
import kotlinx.serialization.Serializable

/**
 * What a finished render left beside its mp4 as `<name>.result.json` (5B-4): the video length, how long the render
 * took and how its loudness target was met ([RenderState.Loudness]: two-pass numbers, or the one-pass fallback and
 * why). The Export screen shows it again after the app restarts or after WorkManager has forgotten the render (D-26:
 * finished work is pruned after a day). [bytes] is the mp4 size when the record was written: another size means the
 * mp4 is no longer this render's (e.g. the app stopped between the rename and this file), so the record is ignored
 * rather than shown for the wrong video.
 */
@Serializable
data class RenderResult(
    /** File name of the mp4, e.g. `export.mp4`. */
    val output: String,
    val durationMs: Long,
    val wallMs: Long,
    val bytes: Long,
    val loudness: RenderState.Loudness? = null,
) {
    fun encode(): ByteArray = CoreJson.encodeToString(serializer(), this).encodeToByteArray()

    companion object {
        const val SUFFIX = ".result.json"

        /** `exports/export.mp4` → `exports/export.result.json`. */
        fun path(mp4: String): String = mp4.removeSuffix(".mp4") + SUFFIX

        fun of(done: RenderState.Done, bytes: Long) =
            RenderResult(done.output.substringAfterLast('/'), done.durationMs, done.wallMs, bytes, done.loudness)

        /**
         * The record of the render that wrote [mp4], or null: no mp4 or no record, unreadable JSON, a record of
         * another file, or a size that no longer matches. Loudness numbers that are not finite are dropped, so a
         * damaged record never reads as two-pass.
         */
        fun read(fs: FileSystem, mp4: String): RenderResult? {
            val file = path(mp4)
            if (!fs.exists(mp4) || !fs.exists(file)) return null
            val r = runCatching { CoreJson.decodeFromString(serializer(), fs.read(file).decodeToString()) }.getOrNull() ?: return null
            if (r.output != mp4.substringAfterLast('/')) return null
            val size = runCatching { fs.size(mp4) }.getOrNull() ?: return null
            if (r.bytes != size) return null
            val l = r.loudness ?: return r
            val m = l.measured
            val finite = m == null || listOf(m.inputI, m.inputTp, m.inputLra, m.inputThresh, m.targetOffset).all { it.isFinite() }
            return when {
                !l.targetLufs.isFinite() -> r.copy(loudness = null)
                finite -> r
                else -> r.copy(loudness = l.copy(measured = null, note = UNREADABLE))
            }
        }

        const val UNREADABLE = "the loudness numbers of this render could not be read back"
    }
}
