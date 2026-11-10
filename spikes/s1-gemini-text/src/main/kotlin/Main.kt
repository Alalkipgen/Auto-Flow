// S1: two-stage Burmese storyboard (docs/specs/prompt-compiler.md §1) with structured JSON output.
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

val models = (System.getenv("S1_MODELS") ?: "gemini-3.8-flash,gemini-3.5-flash,gemini-flash-latest").split(",")
val outDir: Path = Path.of("results")
val json = Json { prettyPrint = true }

fun str() = buildJsonObject { put("type", "string") }
fun arr(items: JsonObject) = buildJsonObject { put("type", "array"); put("items", items) }
fun obj(vararg props: Pair<String, JsonObject>) = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
    putJsonArray("required") { props.forEach { add(it.first) } }
}

val analysisSchema = obj(
    "themeMy" to str(), "arcMy" to str(),
    "sections" to arr(obj("type" to buildJsonObject { put("type", "string"); putJsonArray("enum") { listOf("INTRO", "VERSE", "PRECHORUS", "CHORUS", "BRIDGE", "OUTRO").forEach { add(it) } } },
        "startMs" to buildJsonObject { put("type", "integer") }, "endMs" to buildJsonObject { put("type", "integer") }, "lyricsMy" to str())),
    "motifsEn" to arr(str()), "paletteEn" to str(), "charactersMy" to arr(str()),
)
val shotSchema = obj(
    "shot" to buildJsonObject { put("type", "integer") }, "sceneMy" to str(), "lyricsMy" to str(),
    "subjectEn" to str(), "actionEn" to str(), "settingEn" to str(), "cameraEn" to str(),
    "moodEn" to str(), "lightingEn" to str(), "characters" to arr(str()), "motifEn" to str(), "transitionEn" to str(),
)
val storyboardSchema = arr(shotSchema)

fun request(prompt: String, schema: JsonObject) = buildJsonObject {
    putJsonArray("contents") { addJsonObject { put("role", "user"); putJsonArray("parts") { addJsonObject { put("text", prompt) } } } }
    putJsonObject("generationConfig") {
        put("responseMimeType", "application/json")
        put("responseJsonSchema", schema)
        put("temperature", 0.7)
    }
}.toString()

fun modelText(body: String): String = Json.parseToJsonElement(body).jsonObject["candidates"]!!.jsonArray[0]
    .jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content ?: "" }

var chosenModel: String? = null

/** One call + one retry with the parse error appended (spec §1). Falls back to the next model on HTTP errors. */
fun stage(name: String, prompt: String, schema: JsonObject): JsonElement {
    for (model in chosenModel?.let { listOf(it) } ?: models) {
        var p = prompt
        for (attempt in 1..2) {
            val r = Gemini.generate(model, request(p, schema))
            Gemini.save(outDir, "$name-$model-attempt$attempt", r)
            if (r.status != 200) break
            try {
                val parsed = Json.parseToJsonElement(modelText(r.body))
                chosenModel = model
                return parsed
            } catch (e: Exception) {
                p = "$prompt\n\nYour previous output was invalid JSON: ${e.message}. Return only valid JSON matching the schema."
            }
        }
    }
    error("$name: no model produced valid JSON; not saving a partial storyboard")
}

fun main() {
    val lyrics = Files.readString(Path.of("../fixtures/lyrics-sample.txt"))
    println("models=$models")
    val analysis = stage("stageA-analysis", """
        You are a music-video director. Analyse these Burmese song lyrics for a 9:16 AI music video.
        Assume ~8 s per lyric line. Fields ending in My must be natural Burmese (Myanmar Unicode);
        fields ending in En must be concise English for image/video models. Characters: give short Burmese descriptions.
        Lyrics:
        $lyrics
    """.trimIndent(), analysisSchema)
    Files.writeString(outDir.resolve("analysis.json"), json.encodeToString(JsonElement.serializer(), analysis))

    val shots = (1..6).joinToString("\n") { "shot $it: ${(it - 1) * 16000}-${it * 16000} ms" }
    val storyboard = stage("stageB-storyboard", """
        Create one ShotSpec per shot for a 9:16 music video from this song analysis.
        Use the token HERO for the main character in "characters". *My fields in Burmese, *En fields in English.
        GlobalStyle: cinematic, moody, film grain, teal-and-amber grade.
        Shots:
        $shots
        Analysis:
        $analysis
    """.trimIndent(), storyboardSchema)
    Files.writeString(outDir.resolve("storyboard.json"), json.encodeToString(JsonElement.serializer(), storyboard))
    println("model=$chosenModel storyboard shots=${storyboard.jsonArray.size}")
}
