package io.github.alalkipgen.autoflow.ai.fake

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.TextProvider
import io.github.alalkipgen.autoflow.ai.Usage
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Offline [TextProvider]: builds JSON that satisfies the given schema, deterministic from a hash of the inputs.
 * Keys ending in `My` get Burmese text and keys ending in `En` get English; `shot` counts 1..n inside arrays,
 * `startMs`/`endMs` are consecutive 10 s spans, and `characters` arrays are empty unless the schema requires items.
 * [failFirst] calls return `Failed("http_503")` to exercise error UI.
 */
class FakeTextProvider(
    private val delayMs: Long = 0,
    private var failFirst: Int = 0,
) : TextProvider {
    var calls: Int = 0
        private set

    override suspend fun json(prompt: String, schema: JsonSchema, system: String?): AiResult<JsonElement> {
        calls++
        if (delayMs > 0) delay(delayMs)
        if (failFirst > 0) {
            failFirst--
            return AiResult.Failed("http_503", "Fake: model overloaded")
        }
        val seed = fakeSeed(system.orEmpty(), prompt, schema.json.toString())
        val value = SchemaFaker(Random(seed)).value(schema.json, key = "", index = 0)
        return AiResult.Ok(value, Usage(model = MODEL, latencyMs = delayMs))
    }

    companion object {
        const val MODEL = "fake-text"
    }
}

internal class SchemaFaker(private val random: Random) {
    fun value(schema: JsonObject, key: String, index: Int): JsonElement {
        schema["enum"]?.jsonArray?.let { options -> if (options.isNotEmpty()) return options[random.nextInt(options.size)] }
        return when (typeOf(schema)) {
            "object" -> JsonObject(
                (schema["properties"] as? JsonObject).orEmpty().mapValues { (k, v) -> value(v.jsonObject, k, index) },
            )
            "array" -> {
                val item = schema["items"] as? JsonObject ?: JsonObject(emptyMap())
                val min = schema["minItems"]?.jsonPrimitive?.intOrNull
                val max = schema["maxItems"]?.jsonPrimitive?.intOrNull
                val n = when {
                    min != null -> min
                    key == "characters" -> 0
                    else -> minOf(max ?: 3, 1 + random.nextInt(3))
                }
                JsonArray(List(n) { value(item, key, it) })
            }
            "integer", "number" -> JsonPrimitive(
                when (key) {
                    "shot" -> index + 1L
                    "startMs" -> index * 10_000L
                    "endMs" -> (index + 1) * 10_000L
                    else -> random.nextLong(0, 100)
                },
            )
            "boolean" -> JsonPrimitive(random.nextBoolean())
            "null" -> JsonNull
            else -> JsonPrimitive(text(key))
        }
    }

    private fun typeOf(schema: JsonObject): String {
        val type = schema["type"] ?: return if (schema["properties"] != null) "object" else "string"
        val first = (type as? JsonArray)?.firstOrNull { it.jsonPrimitive.contentOrNull != "null" } ?: type
        return first.jsonPrimitive.contentOrNull?.lowercase() ?: "string"
    }

    private fun text(key: String): String = when {
        key.endsWith("My") -> FakeFixtures.BURMESE.random(random)
        else -> FakeFixtures.ENGLISH.random(random)
    }
}

private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

