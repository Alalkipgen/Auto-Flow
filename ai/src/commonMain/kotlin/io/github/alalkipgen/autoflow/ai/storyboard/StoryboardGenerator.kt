package io.github.alalkipgen.autoflow.ai.storyboard

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.TextProvider
import io.github.alalkipgen.autoflow.ai.Usage
import io.github.alalkipgen.autoflow.core.model.Character
import io.github.alalkipgen.autoflow.core.model.GlobalStyle
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.model.Shot
import io.github.alalkipgen.autoflow.core.model.ShotSpec
import io.github.alalkipgen.autoflow.core.model.SongAnalysis
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

/** What both stages need from the project. Lyrics live in `source/lyrics.txt`, not in project.json. */
data class StoryboardBrief(
    val lyricsMy: String,
    val settings: ProjectSettings,
    val globalStyle: GlobalStyle = GlobalStyle(),
    val characters: List<Character> = emptyList(),
)

/**
 * Stage A (analysis) and Stage B (one [ShotSpec] per shot) on any [TextProvider] (specs/prompt-compiler.md §1).
 * Output that does not decode, or has the wrong number of shots, is retried once with the error appended; a second
 * failure is `Failed("invalid_output")`, so a partial storyboard is never returned. Other results pass through.
 */
class StoryboardGenerator(private val text: TextProvider) {
    suspend fun analyze(brief: StoryboardBrief): AiResult<SongAnalysis> =
        call(StoryboardPrompts.analysisPrompt(brief), StoryboardPrompts.analysisSchema) { json ->
            val a = lenient.decodeFromJsonElement(SongAnalysis.serializer(), json)
            val end = brief.settings.durationMs
            // Timings are estimates: keep sections inside the song, in order.
            a.copy(
                sections = a.sections.filter { it.startMs in 0 until end && it.endMs > it.startMs }
                    .map { it.copy(endMs = minOf(it.endMs, end)) }
                    .sortedBy { it.startMs },
            )
        }

    /** Specs for [shots], in the same order, with `shot` set to each shot's number. [notes]: sceneMy to keep. */
    suspend fun storyboard(
        brief: StoryboardBrief,
        analysis: SongAnalysis,
        shots: List<Shot>,
        notes: Map<Int, String> = emptyMap(),
    ): AiResult<List<ShotSpec>> {
        val analysisJson = lenient.encodeToString(SongAnalysis.serializer(), analysis)
        val prompt = StoryboardPrompts.storyboardPrompt(brief, analysisJson, shots, notes)
        return call(prompt, StoryboardPrompts.storyboardSchema(shots.size)) { json ->
            val items = (json as JsonObject)["shots"]?.jsonArray ?: error("missing \"shots\"")
            require(items.size == shots.size) { "expected ${shots.size} shots, got ${items.size}" }
            val specs = items.map { lenient.decodeFromJsonElement(ShotSpec.serializer(), it) }
            val byNumber = specs.associateBy { it.shot }
            // Use the model's numbering when it is exactly the asked set; otherwise trust the order we asked for.
            val numbered = byNumber.size == shots.size && byNumber.keys == shots.map { it.number }.toSet()
            shots.mapIndexed { i, shot -> (if (numbered) byNumber.getValue(shot.number) else specs[i]).copy(shot = shot.number) }
        }
    }

    private suspend fun <T> call(prompt: String, schema: JsonSchema, decode: (JsonElement) -> T): AiResult<T> {
        var p = prompt
        var usage: Usage? = null
        repeat(2) { attempt ->
            when (val r = text.json(p, schema, StoryboardPrompts.SYSTEM)) {
                is AiResult.Ok -> {
                    usage = r.usage
                    val problem = try {
                        return AiResult.Ok(decode(r.value), usage)
                    } catch (e: Exception) { // SerializationException, IllegalArgumentException, ClassCastException
                        e.message ?: e::class.simpleName ?: "invalid output"
                    }
                    if (attempt == 1) return AiResult.Failed(INVALID_OUTPUT, "The model returned an unusable storyboard twice: $problem")
                    p = "$prompt\n\nYour previous output was invalid: $problem. Return only valid JSON that matches the schema."
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
        private val lenient = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = false }
    }
}
