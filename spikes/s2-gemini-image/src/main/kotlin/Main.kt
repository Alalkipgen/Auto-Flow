// S2: one 9:16 reference image from an English prompt via Gemini native image output.
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO

val models = (System.getenv("S2_MODELS") ?: "gemini-2.5-flash-image,gemini-2.0-flash-preview-image-generation").split(",")
const val PROMPT = "Young Burmese woman gazing through a rain-streaked café window at night, neon street outside, " +
    "warm interior vs cold blue exterior, melancholic, cinematic still, film grain, vertical 9:16 composition."

fun main() {
    val outDir = Path.of("results")
    for (model in models) {
        val body = buildJsonObject {
            putJsonArray("contents") { addJsonObject { putJsonArray("parts") { addJsonObject { put("text", PROMPT) } } } }
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add("TEXT"); add("IMAGE") }
                putJsonObject("imageConfig") { put("aspectRatio", "9:16") }
            }
        }.toString()
        val r = Gemini.generate(model, body)
        val parts = runCatching {
            Json.parseToJsonElement(r.body).jsonObject["candidates"]!!.jsonArray[0].jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray
        }.getOrNull()
        val img = parts?.firstNotNullOfOrNull { it.jsonObject["inlineData"]?.jsonObject }
        // Strip base64 from the saved body to keep the repo small.
        Gemini.save(outDir, model, r.copy(body = if (img != null) r.body.replace(img["data"]!!.jsonPrimitive.content, "<stripped>") else r.body))
        if (img != null) {
            val bytes = Base64.getDecoder().decode(img["data"]!!.jsonPrimitive.content)
            val file = outDir.resolve("$model.png")
            Files.write(file, bytes)
            val bi = ImageIO.read(file.toFile())
            println("[$model] mime=${img["mimeType"]} bytes=${bytes.size} size=${bi?.width}x${bi?.height}")
            Files.writeString(outDir.resolve("$model.image.txt"), "mime=${img["mimeType"]}\nbytes=${bytes.size}\nsize=${bi?.width}x${bi?.height}\n")
            return
        }
    }
    error("No model returned an image (see results/*.response.json)")
}
