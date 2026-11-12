package io.github.alalkipgen.autoflow.ai.gemini

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.Transcript
import io.github.alalkipgen.autoflow.ai.TranscriptSegment
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class GeminiTranscribeProviderTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val lines = listOf("မိုးတွေရွာ", "ည", "မိုးတွေရွာ")

    /** A 200 answer whose model text is [json]. */
    private fun answer(json: String) = buildJsonObject {
        put("candidates", buildJsonArray {
            add(buildJsonObject {
                put("content", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", json) }) }) })
                put("finishReason", "STOP")
            })
        })
        put("usageMetadata", buildJsonObject { put("promptTokenCount", 7_000); put("candidatesTokenCount", 90) })
    }.toString()

    private fun TestScope.provider(vararg replies: String, key: String? = "k") = GeminiTranscribeProvider(
        http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { req ->
                    requests += req
                    respond(replies[minOf(requests.size - 1, replies.lastIndex)], HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        },
        apiKey = { key },
        candidates = listOf("gemini-3.5-flash", "gemini-flash-latest"),
    )

    @Test
    fun alignsEveryLineAndSendsTheAudioInline() = runTest {
        val audio = byteArrayOf(82, 73, 70, 70, 1, 2, 3)
        val reply = answer("""{"lines":[{"line":2,"start":4.5,"end":7},{"line":1,"start":1.2,"end":4.0},{"line":3,"start":9,"end":8}]}""")
        val r = provider(reply).transcribe(audio, "my", "audio/wav", lines)
        val t = assertIs<AiResult.Ok<Transcript>>(r).value
        assertEquals(
            listOf(TranscriptSegment(1_200, 4_000, "မိုးတွေရွာ"), TranscriptSegment(4_500, 7_000, "ည"), TranscriptSegment(9_000, 9_000, "မိုးတွေရွာ")),
            t.segments,
            "in line order; an end before the start collapses to the start",
        )
        val body = Json.parseToJsonElement((requests.single().body as TextContent).text).jsonObject
        val parts = body.getValue("contents").jsonArray[0].jsonObject.getValue("parts").jsonArray
        val inline = parts[0].jsonObject.getValue("inlineData").jsonObject
        assertEquals("audio/wav", inline.getValue("mimeType").jsonPrimitive.content)
        assertContentEquals(audio, Base64.decode(inline.getValue("data").jsonPrimitive.content))
        val prompt = parts[1].jsonObject.getValue("text").jsonPrimitive.content
        assertTrue("1. မိုးတွေရွာ\n2. ည\n3. မိုးတွေရွာ" in prompt)
        assertEquals(GeminiTranscribeProvider.LINES_SCHEMA.json, body.getValue("generationConfig").jsonObject.getValue("responseJsonSchema"))
    }

    @Test
    fun aMissingLineFallsBackToTheNextModel() = runTest {
        val partial = answer("""{"lines":[{"line":1,"start":1,"end":2},{"line":2,"start":3,"end":4}]}""")
        val full = answer("""{"lines":[{"line":1,"start":1,"end":2},{"line":2,"start":3,"end":4},{"line":3,"start":5,"end":6}]}""")
        val r = provider(partial, partial, partial, full).transcribe(byteArrayOf(1), "my", "audio/wav", lines)
        val ok = assertIs<AiResult.Ok<Transcript>>(r)
        assertEquals(3, ok.value.segments.size)
        assertTrue(requests.size >= 2)
    }

    @Test
    fun keyAndSizeAreCheckedBeforeSending() = runTest {
        assertEquals("no_api_key", assertIs<AiResult.Failed>(provider(answer("{}"), key = " ").transcribe(byteArrayOf(1), "my")).code)
        val big = ByteArray(GeminiTranscribeProvider.MAX_AUDIO_BYTES + 1)
        assertEquals("audio_too_big", assertIs<AiResult.Failed>(provider(answer("{}")).transcribe(big, "my")).code)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun transcribesSegmentsWithoutLines() {
        val v = Json.parseToJsonElement("""{"segments":[{"text":" b ","start":5,"end":6.25},{"text":"","start":1,"end":2},{"text":"a","start":0.5,"end":2}]}""")
        assertEquals(listOf(TranscriptSegment(500, 2_000, "a"), TranscriptSegment(5_000, 6_250, "b")), GeminiTranscribeProvider.segments(v))
        assertEquals(null, GeminiTranscribeProvider.aligned(JsonPrimitive(1), lines))
    }
}
