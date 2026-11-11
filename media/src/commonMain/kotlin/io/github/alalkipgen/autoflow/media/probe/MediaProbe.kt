package io.github.alalkipgen.autoflow.media.probe

import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a media file holds (PLAN 1D / 1F MediaProbe): length, displayed video size (rotation applied), frame rate,
 * codecs. Read with ffprobe on both platforms (Android bundles it, D-23).
 */
data class MediaInfo(
    val durationMs: Long,
    val width: Int?,
    val height: Int?,
    val fps: Double?,
    val videoCodec: String?,
    val audioCodec: String?,
) {
    val hasVideo: Boolean get() = videoCodec != null
    val hasAudio: Boolean get() = audioCodec != null
}

class MediaProbe(private val tools: ToolRunner, private val ffprobe: String) {
    sealed interface Result {
        data class Ok(val info: MediaInfo) : Result

        data class Failed(val reason: String) : Result
    }

    suspend fun probe(file: String, workDir: String): Result {
        val r = tools.run(listOf(ffprobe) + args(file), workDir, TIMEOUT_MS, keepOutput = true) {}
        return when (r) {
            is ToolExec.CannotStart -> Result.Failed("ffprobe cannot start: ${r.message}")
            is ToolExec.Done -> {
                val info = if (r.exitCode == 0) parse(r.output) else null
                info?.let { Result.Ok(it) } ?: Result.Failed(r.tail.lastOrNull { it.isNotBlank() } ?: "ffprobe exit ${r.exitCode}")
            }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 60_000L
        private val Lenient = Json { ignoreUnknownKeys = true; isLenient = true }

        fun args(file: String): List<String> = listOf(
            "-v", "error", "-print_format", "json",
            "-show_entries", "format=duration:stream=codec_type,codec_name,width,height,avg_frame_rate,r_frame_rate,duration:stream_tags=rotate:stream_side_data=rotation",
            file,
        )

        /** ffprobe JSON (stderr lines around it are ignored) → [MediaInfo]; null when there is no usable stream. */
        fun parse(output: String): MediaInfo? = runCatching {
            val start = output.indexOf('{')
            val end = output.lastIndexOf('}')
            if (start < 0 || end < start) return null
            val root = Lenient.parseToJsonElement(output.substring(start, end + 1)).jsonObject
            val streams = (root["streams"] as? JsonArray).orEmpty().map { it.jsonObject }
            val video = streams.firstOrNull { it.str("codec_type") == "video" }
            val audio = streams.firstOrNull { it.str("codec_type") == "audio" }
            if (video == null && audio == null) return null
            val seconds = (root["format"] as? JsonObject)?.str("duration")?.toDoubleOrNull()
                ?: streams.mapNotNull { it.str("duration")?.toDoubleOrNull() }.maxOrNull() ?: 0.0
            val turned = video?.let { rotation(it) }?.let { abs(it) % 180 == 90 } == true
            val w = video?.get("width")?.jsonPrimitive?.intOrNull
            val h = video?.get("height")?.jsonPrimitive?.intOrNull
            MediaInfo(
                durationMs = (seconds * 1000).toLong(),
                width = if (turned) h else w,
                height = if (turned) w else h,
                fps = video?.let { rate(it.str("avg_frame_rate")) ?: rate(it.str("r_frame_rate")) },
                videoCodec = video?.str("codec_name"),
                audioCodec = audio?.str("codec_name"),
            )
        }.getOrNull()

        private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

        /** Display-matrix rotation (`side_data_list`) or the older `tags.rotate`. */
        private fun rotation(stream: JsonObject): Int? {
            val side = (stream["side_data_list"] as? JsonArray)?.firstNotNullOfOrNull {
                (it as? JsonObject)?.get("rotation")?.jsonPrimitive?.doubleOrNull?.toInt()
            }
            return side ?: (stream["tags"] as? JsonObject)?.str("rotate")?.toIntOrNull()
        }

        /** `"30000/1001"` → 29.97; `"0/0"` → null. */
        private fun rate(r: String?): Double? {
            val (n, d) = r?.split('/')?.takeIf { it.size == 2 }?.map { it.toDoubleOrNull() ?: return null } ?: return null
            return if (n > 0 && d > 0) n / d else null
        }
    }
}
