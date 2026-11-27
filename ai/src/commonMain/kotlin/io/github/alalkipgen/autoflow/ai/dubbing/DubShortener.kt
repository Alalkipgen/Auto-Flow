package io.github.alalkipgen.autoflow.ai.dubbing

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

/** One spoken line that is too long for its time: [id], its Burmese [spokenMy], and at most [maxChars] for the new one. */
data class ShortenLine(val id: String, val spokenMy: String, val maxChars: Int)

/**
 * Shorten (specs/dubbing.md §5, D-2c) on any [TextProvider]: `{lines:[{id, spokenMy, maxChars}]}` →
 * `{lines:[{id, spokenMy}]}`. Valid = the same ids, each once, in order, non-empty text. An answer that is invalid,
 * or has a line over its `maxChars`, is asked again once with the reason; then invalid → `Failed("invalid_output")`,
 * while a line still over its limit is returned as it is (the owner sees it and decides per line).
 */
class DubShortener(private val text: TextProvider) {
    suspend fun shorten(lines: List<ShortenLine>): AiResult<List<Pair<String, String>>> {
        val base = prompt(lines)
        var p = base
        repeat(2) { attempt ->
            when (val r = text.json(p, schema(lines.size), SYSTEM)) {
                is AiResult.Ok -> {
                    val problem = try {
                        val out = timedAiStage(AiTimingStage.PARSE) { decode(r.value, lines.map { it.id }) }
                        val over = lines.zip(out).filter { (l, o) -> o.second.length > l.maxChars }
                        if (over.isEmpty() || attempt == 1) return AiResult.Ok(out, r.usage)
                        over.joinToString("; ") { (l, o) -> "line ${l.id} has ${o.second.length} characters, at most ${l.maxChars}" }
                    } catch (e: Exception) {
                        e.message ?: "invalid output"
                    }
                    if (attempt == 1) return AiResult.Failed(INVALID_OUTPUT, "The model returned unusable lines twice: $problem")
                    p = "$base\n\nYour previous output was not usable: $problem. Return only valid JSON that matches the schema."
                }
                is AiResult.RateLimited, is AiResult.Failed, is AiResult.Deferred -> return r
            }
        }
        return AiResult.Failed(INVALID_OUTPUT, "unreachable")
    }

    companion object {
        const val INVALID_OUTPUT = "invalid_output"

        const val SYSTEM = "You shorten Burmese (Myanmar Unicode) dub lines so each can be spoken in the time of its line. " +
            "Keep the meaning, the tone and everyday spoken Burmese (ပြောစကား). Keep names and numbers. Each new line must have " +
            "at most maxChars characters. Return one line per given id, in the same order, with no notes or explanations."

        fun prompt(lines: List<ShortenLine>): String = buildJsonObject {
            putJsonArray("lines") { lines.forEach { l -> addJsonObject { put("id", l.id); put("spokenMy", l.spokenMy); put("maxChars", l.maxChars) } } }
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
                            putJsonObject("spokenMy") { put("type", "string") }
                        }
                        putJsonArray("required") { add("id"); add("spokenMy") }
                    }
                }
            }
            putJsonArray("required") { add("lines") }
        })

        fun decode(json: JsonElement, ids: List<String>): List<Pair<String, String>> {
            val items = (json as? JsonObject)?.get("lines")?.jsonArray ?: error("missing \"lines\"")
            require(items.size == ids.size) { "expected ${ids.size} lines, got ${items.size}" }
            return items.mapIndexed { i, item ->
                val id = (item.jsonObject["id"] as? JsonPrimitive)?.contentOrNull
                require(id == ids[i]) { "line ${i + 1} has id ${id ?: "none"}, expected ${ids[i]} (same ids, in order)" }
                val my = (item.jsonObject["spokenMy"] as? JsonPrimitive)?.contentOrNull?.trim()
                require(!my.isNullOrEmpty()) { "line ${ids[i]} has no spokenMy" }
                id to my
            }
        }
    }
}
