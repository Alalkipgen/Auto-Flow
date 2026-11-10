// Shared by S1/S2 spikes (throwaway). The API key is read from env and sent only as a header.
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

data class GeminiResult(val status: Int, val latencyMs: Long, val headers: Map<String, List<String>>, val body: String, val tries: Int = 1)

object Gemini {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

    fun apiKey(): String = System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
        ?: error("GEMINI_API_KEY is not set")

    private fun once(model: String, requestJson: String): GeminiResult {
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

    /** Retries transient 500/503 (and short 429 waits) with backoff; long 429 quota waits are returned as-is. */
    fun generate(model: String, requestJson: String, maxTries: Int = 4): GeminiResult {
        var r = once(model, requestJson)
        var tries = 1
        while (tries < maxTries) {
            val retryS = Regex("\"retryDelay\":\\s*\"(\\d+)s\"").find(r.body)?.groupValues?.get(1)?.toLongOrNull()
            val transient = r.status == 500 || r.status == 503 || (r.status == 429 && (retryS ?: 999) <= 60)
            if (!transient) break
            val waitS = retryS ?: (10L shl (tries - 1))
            println("[$model] HTTP ${r.status}; retry $tries in ${waitS}s")
            Thread.sleep(waitS * 1000)
            r = once(model, requestJson); tries++
        }
        return r.copy(tries = tries)
    }

    /** Writes raw body + metadata; the key never appears in either. */
    fun save(dir: Path, name: String, r: GeminiResult) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("$name.response.json"), r.body)
        val meta = buildString {
            appendLine("status: ${r.status}")
            appendLine("latencyMs: ${r.latencyMs}")
            appendLine("tries: ${r.tries}")
            appendLine("headers:")
            r.headers.toSortedMap().forEach { (k, v) -> appendLine("  $k: ${v.joinToString()}") }
        }
        Files.writeString(dir.resolve("$name.meta.txt"), meta)
        println("[$name] HTTP ${r.status} in ${r.latencyMs} ms (tries=${r.tries})")
    }
}
