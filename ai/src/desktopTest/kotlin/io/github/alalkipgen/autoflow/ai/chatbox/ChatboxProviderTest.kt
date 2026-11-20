package io.github.alalkipgen.autoflow.ai.chatbox

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.SceneSpan
import io.github.alalkipgen.autoflow.ai.config.ChatboxConfig
import io.github.alalkipgen.autoflow.ai.config.ModelConfig
import io.github.alalkipgen.autoflow.ai.config.TextModels
import io.github.alalkipgen.autoflow.ai.gemini.GeminiTextProvider
import io.github.alalkipgen.autoflow.ai.gemini.GeminiTranscribeProvider
import io.github.alalkipgen.autoflow.ai.registry.AiHost
import io.github.alalkipgen.autoflow.ai.registry.AiService
import io.github.alalkipgen.autoflow.ai.registry.ProviderRegistry
import io.github.alalkipgen.autoflow.ai.retry.Attempt
import io.github.alalkipgen.autoflow.ai.retry.ModelChain
import io.github.alalkipgen.autoflow.ai.retry.RetryPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** F-4 (D-93): Chatbox AI answers text and Transcribe calls as OpenAI-compatible chat completions with the license key. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatboxProviderTest {
    private val schema = JsonSchema(Json.parseToJsonElement("""{"type":"object","properties":{"themeMy":{"type":"string"}}}""").jsonObject)
    private val sent = mutableListOf<Pair<String, String>>()
    private val auth = mutableListOf<String?>()

    private fun answer(content: String) =
        """{"choices":[{"message":{"role":"assistant","content":${JsonPrimitive(content)}},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":3}}"""

    private fun TestScope.http(vararg replies: Pair<Int, String>) = HttpClient(MockEngine) {
        engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { req ->
                sent += req.url.toString() to (req.body as TextContent).text
                auth += req.headers[HttpHeaders.Authorization]
                val (status, body) = replies[minOf(sent.size - 1, replies.lastIndex)]
                respond(body, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
    }

    private fun TestScope.text(vararg replies: Pair<Int, String>, key: String? = "LIC-1234"): GeminiTextProvider {
        val client = http(*replies)
        return GeminiTextProvider(client, { key }, listOf("gemini-2.5-flash", "gpt-5-mini"), ModelChain(RetryPolicy(baseDelayMs = 1)),
            wire = ChatCompletionsWire(client, "https://ai.chatboxai.app/v1"))
    }

    @Test fun textGoesAsAChatCompletionWithTheLicenseKeyAndTheSchemaInThePrompt() = runTest {
        val client = http(200 to answer("""{"themeMy":"ချစ်ခြင်း"}"""))
        val provider = GeminiTextProvider(client, { " LIC-1234 " }, listOf("gemini-2.5-flash"), wire = ChatCompletionsWire(client, "https://ai.chatboxai.app/v1/"))
        val r = assertIs<AiResult.Ok<*>>(provider.json("Write a theme", schema, system = "You are a recap writer"))
        assertEquals("ချစ်ခြင်း", (r.value as JsonObject)["themeMy"]!!.jsonPrimitive.content)
        assertEquals("gemini-2.5-flash", r.usage!!.model)
        assertEquals("https://ai.chatboxai.app/v1/chat/completions", sent.single().first)
        assertEquals("Bearer LIC-1234", auth.single())
        val body = Json.parseToJsonElement(sent.single().second).jsonObject
        assertEquals("gemini-2.5-flash", body["model"]!!.jsonPrimitive.content)
        assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse("temperature" in body, "GPT-5 models take only their own temperature")
        val messages = body["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        val prompt = messages[1].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(prompt.startsWith("Write a theme") && "\"themeMy\"" in prompt, "the schema is in the prompt")
    }

    @Test fun noKeyAsksForTheChatboxKeyWithoutARequest() = runTest {
        val r = assertIs<AiResult.Failed>(text(200 to answer("{}"), key = " ").json("p", schema))
        assertEquals("no_api_key", r.code)
        assertEquals("Add a Chatbox AI license key in Settings", r.msg)
        assertTrue(sent.isEmpty())
    }

    @Test fun fencedJsonIsReadAndARefusedResponseFormatIsSentOnceMoreWithoutIt() = runTest {
        val r = text(
            400 to """{"error":{"message":"Unsupported parameter: response_format"}}""",
            200 to answer("Here it is:\n```json\n{\"themeMy\":\"x\"}\n```"),
        ).json("p", schema)
        assertEquals("x", ((assertIs<AiResult.Ok<*>>(r).value) as JsonObject)["themeMy"]!!.jsonPrimitive.content)
        assertEquals(2, sent.size)
        assertTrue("response_format" in sent[0].second)
        assertFalse("response_format" in sent[1].second)
        assertEquals("gemini-2.5-flash", Json.parseToJsonElement(sent[1].second).jsonObject["model"]!!.jsonPrimitive.content)
    }

    @Test fun errorsAreClassifiedForTheModelChain() = runTest {
        val wire = ChatCompletionsWire(http(), "https://x/v1")
        fun c(status: Int, msg: String) = wire.classifyNow(status, """{"error":{"message":"$msg"}}""", null, "m")
        assertEquals("invalid_api_key", assertIs<Attempt.Fatal>(c(401, "Invalid license")).code)
        assertEquals("no_points", assertIs<Attempt.Fatal>(c(402, "Payment required")).code)
        assertEquals("no_points", assertIs<Attempt.Fatal>(c(429, "Insufficient points")).code)
        assertIs<Attempt.Limited>(c(429, "Too many requests"))
        assertEquals("model_unavailable", assertIs<Attempt.Unavailable>(c(400, "The model gpt-5 is not in your plan")).code)
        assertEquals("model_unavailable", assertIs<Attempt.Unavailable>(c(404, "Not found")).code)
        assertIs<Attempt.Transient>(c(503, "Overloaded"))
        assertEquals("bad_request", assertIs<Attempt.Fatal>(c(400, "messages is required")).code)
        assertEquals("Chatbox AI: Invalid license", assertIs<Attempt.Fatal>(c(401, "Invalid license")).msg)
        assertEquals("blocked", assertIs<Attempt.Fatal>(wire.classifyNow(200, """{"choices":[{"message":{"content":""},"finish_reason":"content_filter"}]}""", null, "m")).code)
        assertIs<Attempt.Transient>(wire.classifyNow(200, answer("not json at all"), null, "m"))
    }

    @Test fun aBusyFirstModelFallsToTheNext() = runTest {
        val r = text(429 to """{"error":{"message":"Too many requests"}}""", 200 to answer("""{"themeMy":"y"}""")).json("p", schema)
        // A 429 without Retry-After goes to the next candidate (D-15).
        assertEquals("gpt-5-mini", assertIs<AiResult.Ok<*>>(r).usage!!.model)
    }

    @Test fun transcribeSendsTheSoundAsInputAudioWithItsFormat() = runTest {
        val reply = answer("""{"scenes":[{"scene":1,"lines":[{"start":0.5,"end":1.5,"text":"Hello"}]}]}""")
        val client = http(200 to reply)
        val provider = GeminiTranscribeProvider(client, { "LIC" }, listOf("gemini-2.5-flash"), wire = ChatCompletionsWire(client, "https://ai.chatboxai.app/v1"))
        val r = provider.transcribeScenes(byteArrayOf(1, 2, 3), "audio/mp4", listOf(SceneSpan(0, 2_000)), "en")
        assertEquals("Hello", assertIs<AiResult.Ok<List<List<io.github.alalkipgen.autoflow.ai.TranscriptSegment>>>>(r).value.single().single().text)
        val parts = Json.parseToJsonElement(sent.single().second).jsonObject["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        val audio = parts[0].jsonObject["input_audio"]!!.jsonObject
        assertEquals("AQID", audio["data"]!!.jsonPrimitive.content)
        assertEquals("m4a", audio["format"]!!.jsonPrimitive.content)
        assertEquals("text", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("wav", ChatCompletionsWire.audioFormat("audio/wav"))
        assertEquals("mp3", ChatCompletionsWire.audioFormat("audio/mpeg"))
    }

    private class Host(var text: String, var transcribe: String = AiService.GEMINI) : AiHost {
        override fun useFakeProviders() = false
        override suspend fun apiKey() = "gemini-key"
        override suspend fun chatboxKey() = "LIC"
        override fun textService() = text
        override fun transcribeService() = transcribe
        override fun copyToClipboard(text: String) {}
        override fun openUrl(url: String) {}
    }

    @Test fun settingsChooseTheServicePerCall() = runTest {
        val gemini = """{"candidates":[{"content":{"parts":[{"text":"{\"themeMy\":\"g\"}"}]}}]}"""
        val client = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { req ->
                    sent += req.url.toString() to ""
                    respond(if ("chatboxai" in req.url.host) answer("""{"themeMy":"c"}""") else gemini, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        val config = ModelConfig(text = TextModels(listOf("gemini-3.5-flash")),
            chatbox = ChatboxConfig("https://ai.chatboxai.app/v1", listOf("gemini-2.5-flash"), listOf("gemini-2.5-flash")))
        val host = Host(AiService.GEMINI)
        val registry = ProviderRegistry(host, config, http = lazy { client })
        assertEquals("gemini-3.5-flash", assertIs<AiResult.Ok<*>>(registry.text.json("p", schema)).usage!!.model)
        host.text = AiService.CHATBOX
        assertEquals("gemini-2.5-flash", assertIs<AiResult.Ok<*>>(registry.text.json("p", schema)).usage!!.model)
        assertTrue("chatboxai" in sent.last().first)
        assertIs<AiResult.Ok<Unit>>(registry.testChatboxKey())
        registry.close()
    }

    @Test fun theBundledConfigSetsUpChatbox() {
        val text = java.io.File("../config/models.json").takeIf { it.exists() }?.readText() ?: return
        val c = assertIs<AiResult.Ok<ModelConfig>>(ModelConfig.parse(text)).value.chatbox
        assertTrue(c.available)
        assertEquals("https://ai.chatboxai.app/v1", c.baseUrl)
        assertTrue(c.transcribeModels.isNotEmpty())
    }
}
