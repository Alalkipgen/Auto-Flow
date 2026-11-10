package io.github.alalkipgen.autoflow.ai.gemini

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.retry.ModelChain
import io.github.alalkipgen.autoflow.ai.usage.UsageCounter
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.SongAnalysis
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

@OptIn(ExperimentalCoroutinesApi::class)
class GeminiTextProviderTest {
    private fun fixture(name: String) =
        checkNotNull(javaClass.getResource("/gemini/$name")) { "missing fixture $name" }.readText()

    private val schema = JsonSchema(Json.parseToJsonElement("""{"type":"object","properties":{"themeMy":{"type":"string"}}}""").jsonObject)
    private val requests = mutableListOf<HttpRequestData>()

    /**
     * Answers each request with the next (status, fixture) pair; the last one repeats. The engine runs on the test
     * scheduler so backoff and the 120 s attempt timeout use virtual time.
     */
    private fun TestScope.provider(vararg replies: Pair<Int, String>, key: String? = "test-key", chain: ModelChain = ModelChain()) =
        GeminiTextProvider(
            http = HttpClient(MockEngine) {
                engine {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { req ->
                        requests += req
                        val (status, file) = replies[minOf(requests.size - 1, replies.lastIndex)]
                        if (file == "!io") throw IOException("network is unreachable")
                        respond(fixture(file), HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
            },
            apiKey = { key },
            candidates = listOf("gemini-3.5-flash", "gemini-flash-latest"),
            chain = chain,
        )

    private fun modelOf(req: HttpRequestData) = req.url.encodedPath.substringAfterLast("/models/").substringBefore(':')

    @Test
    fun sendsStructuredOutputRequestWithHeaderKey() = runTest {
        val r = provider(200 to "ok-analysis.json").json("lyrics", schema, system = "You are a director")
        val ok = assertIs<AiResult.Ok<*>>(r)
        val req = requests.single()
        assertEquals("/v1beta/models/gemini-3.5-flash:generateContent", req.url.encodedPath)
        assertEquals("test-key", req.headers["x-goog-api-key"])
        assertTrue("key" !in req.url.parameters.names(), "key must not be in the URL")
        val body = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
        val config = body.getValue("generationConfig").jsonObject
        assertEquals("application/json", config.getValue("responseMimeType").toString().trim('"'))
        assertEquals(schema.json, config.getValue("responseJsonSchema"))
        assertTrue("systemInstruction" in body)
        assertEquals(272, ok.usage!!.promptTokens)
        assertEquals(585, ok.usage.outputTokens)
    }

    @Test
    fun realS1AnswerDecodesIntoSongAnalysis() = runTest {
        val ok = assertIs<AiResult.Ok<*>>(provider(200 to "ok-analysis.json").json("lyrics", schema))
        val analysis = CoreJson.decodeFromString<SongAnalysis>(ok.value.toString())
        assertEquals(3, analysis.sections.size)
        assertTrue(analysis.themeMy.any { it in '\u1000'..'\u109F' })
    }

    @Test
    fun overloadIsRetriedThenSucceeds() = runTest {
        val r = provider(503 to "503.json", 503 to "503.json", 200 to "ok-analysis.json").json("x", schema)
        assertEquals(3, assertIs<AiResult.Ok<*>>(r).usage!!.attempts)
        assertEquals(3_000, currentTime)
    }

    @Test
    fun zeroQuotaAndRetiredModelsFallThroughToTheNextCandidate() = runTest {
        val r = provider(429 to "429-limit-0.json", 200 to "ok-analysis.json").json("x", schema)
        assertEquals("gemini-flash-latest", assertIs<AiResult.Ok<*>>(r).usage!!.model)
        assertEquals(listOf("gemini-3.5-flash", "gemini-flash-latest"), requests.map(::modelOf))
        assertEquals(0, currentTime)

        requests.clear()
        val gone = provider(404 to "404.json", 200 to "ok-analysis.json").json("x", schema)
        assertEquals("gemini-flash-latest", assertIs<AiResult.Ok<*>>(gone).usage!!.model)
    }

    @Test
    fun shortRetryDelayIsHonoured() = runTest {
        val r = provider(429 to "429-retry.json", 200 to "ok-analysis.json").json("x", schema)
        assertEquals("gemini-3.5-flash", assertIs<AiResult.Ok<*>>(r).usage!!.model)
        assertEquals(21_500, currentTime)
    }

    @Test
    fun badKeyMissingKeyAndNetworkFailFast() = runTest {
        assertEquals("invalid_api_key", assertIs<AiResult.Failed>(provider(400 to "400-key.json").json("x", schema)).code)
        assertEquals(1, requests.size)
        assertEquals("no_api_key", assertIs<AiResult.Failed>(provider(200 to "ok-analysis.json", key = " ").json("x", schema)).code)
        assertEquals(1, requests.size)
        assertEquals("network", assertIs<AiResult.Failed>(provider(0 to "!io").json("x", schema)).code)
    }

    @Test
    fun everyHttpAttemptIsCounted() = runTest {
        val usage = UsageCounter(InMemoryFileSystem(), "/u/.autoflow/usage.json", nowMs = { 0 })
        provider(503 to "503.json", 503 to "503.json", 200 to "ok-analysis.json", chain = ModelChain(usage = usage)).json("x", schema)
        assertEquals(mapOf("gemini-3.5-flash" to 3), usage.today())
    }
}
