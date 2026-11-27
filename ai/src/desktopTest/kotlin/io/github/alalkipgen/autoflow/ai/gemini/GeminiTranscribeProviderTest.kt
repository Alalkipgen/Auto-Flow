package io.github.alalkipgen.autoflow.ai.gemini

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.SceneSpan
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

    // --- 5B-1 / D-44: scene mode ---------------------------------------------------------------------------------

    private val spans = listOf(SceneSpan(0, 42_480), SceneSpan(42_480, 80_100))

    @Test
    fun scenesModeListsEachSceneInAudioTimeAndReadsLinesPerScene() = runTest {
        val audio = byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112)
        val reply = answer(
            """{"scenes":[{"scene":2,"lines":[{"start":50.5,"end":53,"text":" ဟုတ်လား "},{"start":44,"end":46.2,"text":"Hello"}]},""" +
                """{"scene":1,"lines":[{"start":41.8,"end":42.8,"text":"edge"},{"start":3,"end":2,"text":""}]}]}""",
        )
        val r = provider(reply).transcribeScenes(audio, "audio/mp4", spans, lang = null)
        val perScene = assertIs<AiResult.Ok<List<List<TranscriptSegment>>>>(r).value
        assertEquals(
            listOf(
                listOf(TranscriptSegment(41_800, 42_480, "edge")),
                listOf(TranscriptSegment(44_000, 46_200, "Hello"), TranscriptSegment(50_500, 53_000, "ဟုတ်လား")),
            ),
            perScene,
            "scene order; lines sorted and kept inside their scene; empty text dropped",
        )
        val body = Json.parseToJsonElement((requests.single().body as TextContent).text).jsonObject
        val parts = body.getValue("contents").jsonArray[0].jsonObject.getValue("parts").jsonArray
        val inline = parts[0].jsonObject.getValue("inlineData").jsonObject
        assertEquals("audio/mp4", inline.getValue("mimeType").jsonPrimitive.content)
        assertContentEquals(audio, Base64.decode(inline.getValue("data").jsonPrimitive.content))
        val prompt = parts[1].jsonObject.getValue("text").jsonPrimitive.content
        assertTrue("scene 1: 0.0–42.5 s\nscene 2: 42.5–80.1 s\n" in prompt, prompt)
        assertTrue("do not translate" in prompt)
        assertEquals(GeminiTranscribeProvider.SCENES_SCHEMA.json, body.getValue("generationConfig").jsonObject.getValue("responseJsonSchema"))
    }

    @Test
    fun aMissingSceneIsAskedOnceMoreThenFails() = runTest {
        val missing = answer("""{"scenes":[{"scene":1,"lines":[]}]}""")
        val r = provider(missing).transcribeScenes(byteArrayOf(1), "audio/mp4", spans)
        assertEquals("invalid_scenes", assertIs<AiResult.Failed>(r).code)
        assertEquals(2, requests.size, "retry once, then fail")
    }

    @Test
    fun aLineOutsideItsSceneIsAskedOnceMoreAndASecondGoodAnswerWins() = runTest {
        val outside = answer("""{"scenes":[{"scene":1,"lines":[{"start":60,"end":61,"text":"late"}]},{"scene":2,"lines":[]}]}""")
        val good = answer("""{"scenes":[{"scene":1,"lines":[{"start":1,"end":2,"text":"a"}]},{"scene":2,"lines":[]}]}""")
        val r = provider(outside, good).transcribeScenes(byteArrayOf(1), "audio/mp4", spans)
        assertEquals(listOf(listOf(TranscriptSegment(1_000, 2_000, "a")), emptyList()), assertIs<AiResult.Ok<List<List<TranscriptSegment>>>>(r).value)
        assertEquals(2, requests.size)
    }

    @Test
    fun scenesModeChecksKeyScenesAndSizeBeforeSending() = runTest {
        assertEquals("no_api_key", assertIs<AiResult.Failed>(provider(answer("{}"), key = "").transcribeScenes(byteArrayOf(1), "audio/mp4", spans)).code)
        assertEquals("no_scenes", assertIs<AiResult.Failed>(provider(answer("{}")).transcribeScenes(byteArrayOf(1), "audio/mp4", emptyList())).code)
        val big = ByteArray(GeminiTranscribeProvider.MAX_AUDIO_BYTES + 1)
        assertEquals("audio_too_big", assertIs<AiResult.Failed>(provider(answer("{}")).transcribeScenes(big, "audio/mp4", spans)).code)
        assertTrue(requests.isEmpty())
        val repeated = Json.parseToJsonElement("""{"scenes":[{"scene":1,"lines":[]},{"scene":1,"lines":[]}]}""")
        assertEquals(null, GeminiTranscribeProvider.sceneLines(repeated, spans), "a repeated scene is not an answer")
    }
}
