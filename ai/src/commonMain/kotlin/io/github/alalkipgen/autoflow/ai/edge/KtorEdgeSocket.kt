package io.github.alalkipgen.autoflow.ai.edge

import io.github.alalkipgen.autoflow.ai.AiResult
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** [EdgeSocket] over the Ktor client WebSockets plugin (part of ktor-client-core, D-86); one connection per turn. */
class KtorEdgeSocket(
    private val client: Lazy<HttpClient> = lazy { edgeHttpClient() },
    private val timeoutMs: Long = 60_000,
) : EdgeSocket {
    override suspend fun turn(url: String, headers: Map<String, String>, frames: List<String>): AiResult<List<EdgeFrame>> = try {
        withTimeout(timeoutMs) {
            val got = mutableListOf<EdgeFrame>()
            client.value.webSocket(urlString = url, request = { headers.forEach { (k, v) -> header(k, v) } }) {
                frames.forEach { send(Frame.Text(it)) }
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val text = frame.readText()
                            got += EdgeFrame.Text(text)
                            if (text.contains("Path:turn.end")) break
                        }
                        is Frame.Binary -> got += EdgeFrame.Binary(frame.data)
                        else -> Unit
                    }
                }
            }
            if (got.none { it is EdgeFrame.Text && it.text.contains("Path:turn.end") }) AiResult.Failed("timeout", "Edge closed before the end of the speech")
            else AiResult.Ok(got)
        }
    } catch (e: TimeoutCancellationException) {
        AiResult.Failed("timeout", "Edge did not answer in time")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failure(e.message.orEmpty())
    }

    fun close() {
        if (client.isInitialized()) client.value.close()
    }

    companion object {
        /**
         * A handshake error names the HTTP status ("expected status code 101 but was 403"). 429 = Edge is busy (F-3):
         * [AiResult.RateLimited], so the voice job pauses and Resume goes on instead of failing.
         */
        fun failure(message: String): AiResult<Nothing> {
            val status = Regex("\\b([45]\\d\\d)\\b").find(message)?.groupValues?.get(1)
            return when {
                status == "429" -> AiResult.RateLimited(60_000, "Edge is busy (429)")
                status == "403" -> AiResult.Failed("http_403", "Edge refused the request (Microsoft may have changed the free service). Use a Gemini voice.")
                status != null -> AiResult.Failed("http_$status", "Edge answered $status")
                else -> AiResult.Failed("network", "Edge cannot be reached: ${message.ifBlank { "no connection" }}")
            }
        }
    }
}

fun edgeHttpClient(): HttpClient = HttpClient {
    expectSuccess = false
    install(WebSockets)
    install(HttpTimeout) {
        connectTimeoutMillis = 20_000
        socketTimeoutMillis = 60_000
    }
}
