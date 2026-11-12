package io.github.alalkipgen.autoflow.ai.gemini

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.TranscribeProvider
import io.github.alalkipgen.autoflow.ai.Transcript
import io.github.alalkipgen.autoflow.ai.TranscriptSegment
import io.github.alalkipgen.autoflow.ai.retry.Attempt
import io.github.alalkipgen.autoflow.ai.retry.ModelChain
import io.ktor.client.HttpClient
import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json

/**
 * Lyric timing with Gemini audio input (D-31): the song goes inline (`inlineData`, so at most [MAX_AUDIO_BYTES]) with
 * the known lyric lines, and the structured answer gives each line's start / end in seconds. Without lines it
 * transcribes into timed segments. An answer that misses or repeats a line is retried / falls back like invalid JSON.
 */
class GeminiTranscribeProvider(
    private val http: HttpClient,
    private val apiKey: suspend () -> String?,
    private val candidates: List<String>,
    private val chain: ModelChain = ModelChain(),
    private val baseUrl: String = GeminiTextProvider.DEFAULT_BASE_URL,
) : TranscribeProvider {
    override suspend fun transcribe(audio: ByteArray, lang: String, mime: String, lines: List<String>): AiResult<Transcript> {
        val key = apiKey()?.trim().orEmpty()
        if (key.isEmpty()) return AiResult.Failed("no_api_key", "Add a Gemini API key in Settings")
        if (audio.isEmpty()) return AiResult.Failed("empty_audio", "Audio is empty")
        if (audio.size > MAX_AUDIO_BYTES) {
            return AiResult.Failed("audio_too_big", "The song is too big to send (${audio.size / 1_000_000} MB, at most ${MAX_AUDIO_BYTES / 1_000_000} MB)")
        }
        val (prompt, schema) = if (lines.isEmpty()) transcribePrompt(lang) to SEGMENTS_SCHEMA else alignPrompt(lang, lines) to LINES_SCHEMA
        val body = GeminiWire.request(prompt, schema, system = null, temperature = null, media = mime to audio).toString()
        return chain.run<Transcript>(candidates) { model ->
            when (val a: Attempt<JsonElement> = GeminiWire.post(http, baseUrl, model, key, body)) {
                is Attempt.Success -> {
                    val segments = if (lines.isEmpty()) segments(a.value) else aligned(a.value, lines)
                    if (segments == null) {
                        Attempt.Transient("invalid_timings", "$model did not time every lyric line")
                    } else {
                        Attempt.Success(Transcript(lang, segments), a.promptTokens, a.outputTokens)
                    }
                }
                is Attempt.Transient -> a
                is Attempt.Limited -> a
                is Attempt.Unavailable -> a
                is Attempt.Fatal -> a
            }
        }
    }

    companion object {
        /** Inline request data is limited to 20 MB; base64 adds a third. */
        const val MAX_AUDIO_BYTES = 14_000_000

        internal fun alignPrompt(lang: String, lines: List<String>): String = buildString {
            append("The audio is a song (language: ").append(lang).append("). Below are its lyric lines, numbered, in the order they are sung; ")
            append("each line is sung once. For every line give when the singer starts it (start) and finishes it (end), ")
            append("in seconds from the beginning of the audio with one decimal (83.5 = 1:23.5). Listen to the vocals, ")
            append("not the instruments; lines never overlap and starts only increase. Answer every line number exactly once.\n\n")
            lines.forEachIndexed { i, line -> append(i + 1).append(". ").append(line).append('\n') }
        }

        internal fun transcribePrompt(lang: String): String =
            "Transcribe the vocals of this audio (language: $lang) line by line. For each sung line give its text and " +
                "when it starts and ends, in seconds from the beginning with one decimal."

        /** One segment per line in order, or null when a line is missing, repeated or has no valid start. */
        internal fun aligned(value: JsonElement, lines: List<String>): List<TranscriptSegment>? {
            val items = ((value as? JsonObject)?.get("lines") as? JsonArray) ?: return null
            val byLine = mutableMapOf<Int, Pair<Double, Double?>>()
            for (item in items) {
                val o = item as? JsonObject ?: return null
                val n = o.num("line")?.toInt() ?: return null
                val start = o.num("start")?.takeIf { it >= 0 } ?: return null
                if (n !in 1..lines.size || n in byLine) return null
                byLine[n] = start to o.num("end")
            }
            if (byLine.size != lines.size) return null
            return lines.indices.map { i ->
                val (start, end) = byLine.getValue(i + 1)
                val startMs = (start * 1000).roundToLong()
                TranscriptSegment(startMs, end?.let { (it * 1000).roundToLong() }?.takeIf { it > startMs } ?: startMs, lines[i])
            }
        }

        internal fun segments(value: JsonElement): List<TranscriptSegment>? {
            val items = ((value as? JsonObject)?.get("segments") as? JsonArray) ?: return null
            return items.mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val text = (o["text"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val start = o.num("start") ?: return@mapNotNull null
                val end = o.num("end") ?: start
                if (text.isEmpty() || start < 0) null else TranscriptSegment((start * 1000).roundToLong(), (maxOf(end, start) * 1000).roundToLong(), text)
            }.sortedBy { it.startMs }
        }

        private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.intOrNull?.toDouble() }

        private fun schema(text: String) = JsonSchema(Json.parseToJsonElement(text).jsonObject)

        internal val LINES_SCHEMA = schema(
            """{"type":"object","properties":{"lines":{"type":"array","items":{"type":"object","properties":{""" +
                """"line":{"type":"integer"},"start":{"type":"number"},"end":{"type":"number"}},"required":["line","start","end"]}}},"required":["lines"]}""",
        )
        internal val SEGMENTS_SCHEMA = schema(
            """{"type":"object","properties":{"segments":{"type":"array","items":{"type":"object","properties":{""" +
                """"text":{"type":"string"},"start":{"type":"number"},"end":{"type":"number"}},"required":["text","start","end"]}}},"required":["segments"]}""",
        )
    }
}
