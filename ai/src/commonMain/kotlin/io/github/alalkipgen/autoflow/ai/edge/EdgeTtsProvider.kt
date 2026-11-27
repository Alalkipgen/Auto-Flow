package io.github.alalkipgen.autoflow.ai.edge

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.Availability
import io.github.alalkipgen.autoflow.ai.TtsCapabilities
import io.github.alalkipgen.autoflow.ai.TtsProvider
import io.github.alalkipgen.autoflow.ai.Usage
import io.github.alalkipgen.autoflow.ai.VoiceRef
import io.github.alalkipgen.autoflow.ai.codec.Wav
import io.github.alalkipgen.autoflow.ai.config.EdgeTtsConfig
import kotlin.random.Random
import kotlin.time.Clock

/** One frame the Edge service sent: text (`Path:turn.start`, `Path:turn.end`, …) or binary (`Path:audio` + mp3). */
sealed interface EdgeFrame {
    data class Text(val text: String) : EdgeFrame
    class Binary(val bytes: ByteArray) : EdgeFrame
}

/**
 * One synthesis turn: connect to [url] with [headers], send [frames] as text, return what arrived up to and including
 * `Path:turn.end`. Failures: `http_403` (refused), `http_5xx`, `timeout`, `network`. [KtorEdgeSocket] in the app;
 * tests use recorded frames.
 */
fun interface EdgeSocket {
    suspend fun turn(url: String, headers: Map<String, String>, frames: List<String>): AiResult<List<EdgeFrame>>
}

/**
 * Microsoft Edge "Read aloud" voices (V-1b, D-86, Q-45 → (a)): free, no API key, only the text is sent. The service
 * answers mp3 only, so [mp3ToWav] (ffmpeg, from the host) turns it into the PCM WAV every [TtsProvider] returns.
 * Unofficial: any refusal is a normal provider error; the voice lock never switches to Gemini by itself (D-35).
 * [style] (the tone sentence) is not used: Edge voices have no style prompt.
 */
class EdgeTtsProvider(
    private val config: EdgeTtsConfig,
    private val socket: EdgeSocket,
    private val mp3ToWav: suspend (ByteArray) -> ByteArray?,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val random: Random = Random.Default,
) : TtsProvider {
    override val capabilities = TtsCapabilities(replication = Availability.UNAVAILABLE, burmese = true)

    override suspend fun speak(text: String, voice: VoiceRef, lang: String, style: String?, lockedModel: String?): AiResult<ByteArray> {
        if (!config.available) return AiResult.Failed("edge_not_configured", "Edge voices are not set up in config/models.json")
        if (text.isBlank()) return AiResult.Failed("empty_text", "There is no text to speak")
        if (config.voices.none { it.id == voice.id }) return AiResult.Failed("unknown_voice", "\"${voice.id}\" is not an Edge voice")
        val escaped = escape(text.trim())
        if (escaped.encodeToByteArray().size > config.maxTextBytes) return AiResult.Failed("text_too_long", "This text is too long for one Edge request")
        val started = nowMs()
        val frames = when (val r = socket.turn(url(started), headers(), listOf(configFrame(started), ssmlFrame(started, voice.id, escaped)))) {
            is AiResult.Ok -> r.value
            is AiResult.Failed -> return r
            is AiResult.RateLimited -> return r
            is AiResult.Deferred -> return r
        }
        val mp3 = audio(frames)
        if (mp3.isEmpty()) return AiResult.Failed("empty_response", "Edge sent no audio")
        val wav = mp3ToWav(mp3) ?: return AiResult.Failed("no_mp3_decoder", "Edge voices need ffmpeg to read their audio")
        if (Wav.parse(wav) == null) return AiResult.Failed("invalid_audio", "Edge audio cannot be read")
        return AiResult.Ok(wav, Usage(config.model, latencyMs = nowMs() - started))
    }

    internal fun url(nowMs: Long): String =
        "${config.endpoint}?TrustedClientToken=${config.trustedClientToken}&ConnectionId=${hex()}" +
            "&Sec-MS-GEC=${EdgeToken.secMsGec(nowMs / 1000, config.trustedClientToken)}&Sec-MS-GEC-Version=${config.secMsGecVersion}"

    internal fun headers(): Map<String, String> = linkedMapOf(
        "Pragma" to "no-cache",
        "Cache-Control" to "no-cache",
        "Origin" to config.origin,
        "User-Agent" to config.userAgent,
        "Accept-Language" to "en-US,en;q=0.9",
        "Cookie" to "muid=${hex().uppercase()};",
    )

    private fun configFrame(nowMs: Long): String =
        "X-Timestamp:${timestamp(nowMs)}\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"}," +
            "\"outputFormat\":\"${config.outputFormat}\"}}}}\r\n"

    private fun ssmlFrame(nowMs: Long, voice: String, escaped: String): String =
        // The trailing "Z" after the timestamp is what Edge itself sends.
        "X-RequestId:${hex()}\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${timestamp(nowMs)}Z\r\nPath:ssml\r\n\r\n" +
            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'><voice name='$voice'>" +
            "<prosody pitch='+0Hz' rate='+0%' volume='+0%'>$escaped</prosody></voice></speak>"

    private fun hex(): String = (0 until 16).joinToString("") { random.nextInt(256).toString(16).padStart(2, '0') }

    companion object {
        /** The mp3 bytes of every `Path:audio` binary frame, in order (2-byte big-endian header length, header, data). */
        fun audio(frames: List<EdgeFrame>): ByteArray {
            var out = ByteArray(0)
            frames.filterIsInstance<EdgeFrame.Binary>().forEach { f ->
                val b = f.bytes
                if (b.size < 2) return@forEach
                val headerLength = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
                if (2 + headerLength > b.size) return@forEach
                if (!b.copyOfRange(2, 2 + headerLength).decodeToString().contains("Path:audio\r\n")) return@forEach
                out += b.copyOfRange(2 + headerLength, b.size)
            }
            return out
        }

        /** XML-escaped, with the control characters Edge rejects turned into spaces. */
        fun escape(text: String): String = buildString {
            text.forEach { c ->
                when {
                    c == '&' -> append("&amp;")
                    c == '<' -> append("&lt;")
                    c == '>' -> append("&gt;")
                    c == '"' -> append("&quot;")
                    c == '\'' -> append("&apos;")
                    c.code < 32 && c != '\n' -> append(' ')
                    else -> append(c)
                }
            }
        }

        private val DAYS = listOf("Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed")
        private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

        /** `Thu Nov 20 2026 16:16:00 GMT+0000 (Coordinated Universal Time)`, as Edge's JavaScript `Date.toString()`. */
        fun timestamp(nowMs: Long): String {
            val secs = nowMs.floorDiv(1000)
            val days = secs.floorDiv(86_400)
            val sod = secs - days * 86_400
            // Civil date from days since 1970-01-01 (H. Hinnant).
            val z = days + 719_468
            val era = z.floorDiv(146_097)
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = doy - (153 * mp + 2) / 5 + 1
            val m = if (mp < 10) mp + 3 else mp - 9
            val y = yoe + era * 400 + if (m <= 2) 1 else 0
            fun two(v: Long) = v.toString().padStart(2, '0')
            return "${DAYS[days.mod(7)]} ${MONTHS[(m - 1).toInt()]} ${two(d)} $y ${two(sod / 3600)}:${two(sod / 60 % 60)}:${two(sod % 60)} " +
                "GMT+0000 (Coordinated Universal Time)"
        }
    }
}
