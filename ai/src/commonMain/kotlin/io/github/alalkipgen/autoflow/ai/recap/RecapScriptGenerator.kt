package io.github.alalkipgen.autoflow.ai.recap

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.TextProvider
import io.github.alalkipgen.autoflow.core.recap.RecapStyle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One picked scene for the script prompt: movie time and the subtitle lines inside it ("" when none). */
data class SceneBrief(val startMs: Long, val endMs: Long, val dialogue: String = "")

/** Everything the recap script prompt needs (docs/specs/recap.md §4). [summary] may be Burmese or English. */
data class RecapBrief(val title: String, val summary: String, val style: RecapStyle, val scenes: List<SceneBrief>)

/**
 * Burmese recap narration, one text per picked scene (spec §4), on any [TextProvider]. Like the storyboard: a
 * wrong count or shape is retried once with the error appended, then `Failed("invalid_output")`.
 */
class RecapScriptGenerator(private val text: TextProvider) {
    suspend fun write(brief: RecapBrief): AiResult<List<String>> {
        val base = RecapPrompts.prompt(brief)
        var p = base
        repeat(2) { attempt ->
            when (val r = text.json(p, RecapPrompts.schema(brief.scenes.size), RecapPrompts.SYSTEM)) {
                is AiResult.Ok -> {
                    val problem = try {
                        return AiResult.Ok(decode(r.value, brief.scenes.size), r.usage)
                    } catch (e: Exception) {
                        e.message ?: "invalid output"
                    }
                    if (attempt == 1) return AiResult.Failed(INVALID_OUTPUT, "The model returned an unusable script twice: $problem")
                    p = "$base\n\nYour previous output was invalid: $problem. Return only valid JSON that matches the schema."
                }
                is AiResult.RateLimited -> return r
                is AiResult.Failed -> return r
                is AiResult.Deferred -> return r
            }
        }
        return AiResult.Failed(INVALID_OUTPUT, "unreachable")
    }

    private fun decode(json: kotlinx.serialization.json.JsonElement, count: Int): List<String> {
        val items = (json as JsonObject)["segments"]?.jsonArray ?: error("missing \"segments\"")
        require(items.size == count) { "expected $count segments, got ${items.size}" }
        return items.map { item ->
            val t = (item.jsonObject["textMy"] as? JsonPrimitive)?.contentOrNull?.trim()
            require(!t.isNullOrEmpty()) { "a segment has no textMy" }
            t
        }
    }

    companion object {
        const val INVALID_OUTPUT = "invalid_output"
    }
}

/** Prompt and schema for [RecapScriptGenerator]. */
object RecapPrompts {
    const val SYSTEM = "You write Burmese narration for movie recap videos (a narrator retells a movie over its scenes). " +
        "Write natural, colloquial Myanmar Unicode Burmese, the everyday spoken register of Burmese recap channels, " +
        "not literary style. Never write English in textMy except names. Return only JSON that matches the schema."

    /** Burmese narration speed used to size each text (spec §4). */
    const val SYLLABLES_PER_SECOND = 4

    /** Subtitle text sent per scene (spec §4). */
    const val DIALOGUE_CHARS = 600

    fun schema(count: Int): JsonSchema = JsonSchema(
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("segments") {
                    put("type", "array")
                    put("minItems", count)
                    put("maxItems", count)
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("scene") { put("type", "integer") }
                            putJsonObject("textMy") { put("type", "string") }
                        }
                        putJsonArray("required") { add("scene"); add("textMy") }
                    }
                }
            }
            putJsonArray("required") { add("segments") }
        },
    )

    fun prompt(brief: RecapBrief): String = buildString {
        appendLine("Write the narration of a recap of the movie \"${brief.title}\": ${brief.scenes.size} segment(s), one per scene below, in this order.")
        appendLine(
            when (brief.style) {
                RecapStyle.PLAIN -> "Style: neutral storytelling — tell what happens and why it matters, like a calm narrator."
                RecapStyle.COMMENTARY -> "Style: commentary — tell what happens and add short reactions and opinions (surprise, humour, " +
                    "what the viewer should notice), like a popular recap channel. Keep it respectful."
            },
        )
        appendLine("Each textMy must take about the scene's length to read aloud (about $SYLLABLES_PER_SECOND Burmese syllables per second); never much longer.")
        appendLine("The segments together must tell the story in order; do not repeat the same sentence. scene = the number below.")
        if (brief.summary.isNotBlank()) {
            appendLine("Story summary from the owner (follow it; it may be Burmese or English):")
            appendLine(brief.summary.trim())
        }
        appendLine("Scenes:")
        brief.scenes.forEachIndexed { i, s ->
            val sec = (s.endMs - s.startMs + 500) / 1000
            append("scene ${i + 1}: ${clock(s.startMs)}-${clock(s.endMs)} ($sec s, about ${sec * SYLLABLES_PER_SECOND} syllables)")
            appendLine(if (s.dialogue.isBlank()) "" else " — dialogue: ${s.dialogue}")
        }
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return "${s / 3600}:${(s / 60 % 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}"
    }
}
