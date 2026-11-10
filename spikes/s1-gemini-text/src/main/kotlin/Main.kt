// S1: two-stage Burmese storyboard (docs/specs/prompt-compiler.md §1) with structured JSON output.
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

val model = System.getenv("S1_MODEL") ?: "gemini-2.5-flash"
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

/** One call + one retry with the parse error appended (spec §1). */
fun stage(name: String, prompt: String, schema: JsonObject): JsonElement {
    var p = prompt
    repeat(2) { attempt ->
        val r = Gemini.generate(model, request(p, schema))
        Gemini.save(outDir, "$name-attempt${attempt + 1}", r)
        if (r.status != 200) error("$name failed: HTTP ${r.status} (see results/$name-attempt${attempt + 1}.response.json)")
        try {
            return Json.parseToJsonElement(modelText(r.body))
        } catch (e: Exception) {
            p = "$prompt\n\nYour previous output was invalid JSON: ${e.message}. Return only valid JSON matching the schema."
        }
    }
    error("$name: invalid JSON after retry; not saving a partial storyboard")
}

fun main() {
    val lyrics = Files.readString(Path.of("../fixtures/lyrics-sample.txt"))
    println("model=$model")
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
    println("storyboard shots=${storyboard.jsonArray.size}")
}
