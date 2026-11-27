package io.github.alalkipgen.autoflow.ai.subtitles

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.TextProvider
import io.github.alalkipgen.autoflow.core.recap.AiTimingStage
import io.github.alalkipgen.autoflow.core.recap.timedAiStage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One batch to translate (specs/quick-tools.md §8): [lines] in order, the lines before them as [context]. */
data class TranslateBrief(
    /** The original language when known (`en`, `ko`, …), null = not known. */
    val lang: String?,
    /** `spoken` (everyday, default, Q-39) or `formal`. */
    val style: String,
    /** Names to keep the same: original → Burmese. */
    val names: List<Pair<String, String>> = emptyList(),
    /** The previous lines: original and Burmese when there is one. */
    val context: List<Pair<String, String?>> = emptyList(),
    /** id → original text. */
    val lines: List<Pair<String, String>>,
)

/**
 * Subtitle lines to natural Burmese on any [TextProvider] (D-81): line-aligned JSON `{lines:[{id, textMy}]}`. Valid =
 * the same ids, each once, in order, non-empty text. Invalid output is asked again once with the reason, then the
 * batch fails alone (`Failed("invalid_output")`); busy / failed requests are returned as they are.
 */
class SubtitleTranslator(private val text: TextProvider) {
    suspend fun translate(brief: TranslateBrief): AiResult<List<Pair<String, String>>> {
        val base = prompt(brief)
        var p = base
        repeat(2) { attempt ->
            when (val r = text.json(p, schema(brief.lines.size), system(brief.style))) {
                is AiResult.Ok -> {
                    val problem = try {
                        return AiResult.Ok(timedAiStage(AiTimingStage.PARSE) { decode(r.value, brief.lines.map { it.first }) }, r.usage)
                    } catch (e: Exception) {
                        e.message ?: "invalid output"
                    }
                    if (attempt == 1) return AiResult.Failed(INVALID_OUTPUT, "The model returned unusable lines twice: $problem")
                    p = "$base\n\nYour previous output was invalid: $problem. Return only valid JSON that matches the schema."
                }
                is AiResult.RateLimited -> return r
                is AiResult.Failed -> return r
                is AiResult.Deferred -> return r
            }
        }
        return AiResult.Failed(INVALID_OUTPUT, "unreachable")
    }

    companion object {
        const val INVALID_OUTPUT = "invalid_output"
        const val STYLE_SPOKEN = "spoken"
        const val STYLE_FORMAL = "formal"

        fun system(style: String): String = "You translate film subtitles into natural Burmese (Myanmar Unicode) for on-screen reading. " +
            (if (style == STYLE_FORMAL) "Use formal written Burmese (စာစကား). " else "Use everyday spoken Burmese (ပြောစကား) as people really talk. ") +
            "Keep each line short like a subtitle and keep its meaning and tone. Use the given Burmese for the listed names. " +
            "Keep numbers as they are. Return one Burmese line per given id, in the same order, with no notes or explanations."

        fun prompt(brief: TranslateBrief): String = buildJsonObject {
            put("lang", brief.lang ?: "unknown")
            put("style", brief.style)
            putJsonArray("names") { brief.names.forEach { (from, my) -> addJsonObject { put("from", from); put("my", my) } } }
            putJsonArray("context") { brief.context.forEach { (t, my) -> addJsonObject { put("text", t); my?.let { put("textMy", it) } } } }
            putJsonArray("lines") { brief.lines.forEach { (id, t) -> addJsonObject { put("id", id); put("text", t) } } }
        }.toString()

        fun schema(count: Int): JsonSchema = JsonSchema(buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("lines") {
                    put("type", "array"); put("minItems", count); put("maxItems", count)
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("id") { put("type", "string") }
                            putJsonObject("textMy") { put("type", "string") }
                        }
                        putJsonArray("required") { add("id"); add("textMy") }
                    }
                }
            }
            putJsonArray("required") { add("lines") }
        })

        /** The answer's lines when they are exactly [ids] in order with text; else why not. */
        fun decode(json: JsonElement, ids: List<String>): List<Pair<String, String>> {
            val items = (json as? JsonObject)?.get("lines")?.jsonArray ?: error("missing \"lines\"")
            require(items.size == ids.size) { "expected ${ids.size} lines, got ${items.size}" }
            return items.mapIndexed { i, item ->
                val id = (item.jsonObject["id"] as? JsonPrimitive)?.contentOrNull
                require(id == ids[i]) { "line ${i + 1} has id ${id ?: "none"}, expected ${ids[i]} (same ids, in order)" }
                val my = (item.jsonObject["textMy"] as? JsonPrimitive)?.contentOrNull?.trim()
                require(!my.isNullOrEmpty()) { "line ${ids[i]} has no textMy" }
                id to my
            }
        }
    }
}
