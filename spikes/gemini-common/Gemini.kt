// Shared by S1/S2 spikes (throwaway). The API key is read from env and sent only as a header.
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

data class GeminiResult(val status: Int, val latencyMs: Long, val headers: Map<String, List<String>>, val body: String)

object Gemini {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

    fun apiKey(): String = System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
        ?: error("GEMINI_API_KEY is not set")

    fun generate(model: String, requestJson: String): GeminiResult {
        val req = HttpRequest.newBuilder(URI.create("$BASE$model:generateContent"))
            .timeout(Duration.ofSeconds(180))
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", apiKey())
            .POST(HttpRequest.BodyPublishers.ofString(requestJson))
            .build()
        val t0 = System.nanoTime()
        val res = client.send(req, HttpResponse.BodyHandlers.ofString())
        val ms = (System.nanoTime() - t0) / 1_000_000
        val headers = res.headers().map().filterKeys { !it.equals("set-cookie", true) }
        return GeminiResult(res.statusCode(), ms, headers, res.body())
    }

    /** Writes raw body + metadata; the key never appears in either. */
    fun save(dir: Path, name: String, r: GeminiResult) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("$name.response.json"), r.body)
        val meta = buildString {
            appendLine("status: ${r.status}")
            appendLine("latencyMs: ${r.latencyMs}")
            appendLine("headers:")
            r.headers.toSortedMap().forEach { (k, v) -> appendLine("  $k: ${v.joinToString()}") }
        }
        Files.writeString(dir.resolve("$name.meta.txt"), meta)
        println("[$name] HTTP ${r.status} in ${r.latencyMs} ms")
    }
}
