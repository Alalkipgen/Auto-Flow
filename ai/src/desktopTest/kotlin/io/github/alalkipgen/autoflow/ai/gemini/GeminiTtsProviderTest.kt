package io.github.alalkipgen.autoflow.ai.gemini

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.config.TtsCandidate
import io.github.alalkipgen.autoflow.ai.VoiceRef
import io.github.alalkipgen.autoflow.ai.codec.Wav
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** D-34: Gemini TTS request shapes per model family, WAV passthrough, raw PCM wrapped, fallback on 404. */
class GeminiTtsProviderTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val wav = Wav.pcm16Mono(ShortArray(2400) { (it % 100).toShort() }, 24_000)

    private fun audio(mime: String, bytes: ByteArray) =
        """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"$mime","data":"${Base64.encode(bytes)}"}}]},"finishReason":"STOP"}]}"""

    private fun TestScope.provider(vararg replies: Pair<HttpStatusCode, String>) = GeminiTtsProvider(
        http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { req ->
                    requests += req
                    val (status, body) = replies[minOf(requests.size - 1, replies.lastIndex)]
                    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        },
        apiKey = { "k" },
        allCandidates = listOf(
            TtsCandidate("gemini-3.8-flash-tts", langs = listOf("my", "en")),
            TtsCandidate("gemini-2.5-flash-preview-tts", langs = listOf("my", "en")),
        ),
    )

    private fun body(i: Int) = Json.parseToJsonElement((requests[i].body as TextContent).text).jsonObject

    @Test
    fun v38GetsVoiceAndStyleAndItsWavIsKept() = runTest {
        val r = provider(HttpStatusCode.OK to audio("audio/wav", wav)).speak("မင်္ဂလာပါ", VoiceRef("Charon"), "my", "calm narrator")
        assertContentEquals(wav, assertIs<AiResult.Ok<ByteArray>>(r).value)
        assertTrue(requests[0].url.encodedPath.endsWith("/models/gemini-3.8-flash-tts:generateContent"))
        val b = body(0)
        val gen = b["generationConfig"]!!.jsonObject
        assertEquals("AUDIO", gen["responseModalities"].toString().trim('[', ']', '"'))
        assertEquals("Charon", gen["speechConfig"]!!.jsonObject["voiceConfig"]!!.jsonObject["voice"]!!.jsonPrimitive.content)
        assertTrue("calm narrator" in b.toString())
    }

    @Test
    fun olderModelGetsPrebuiltVoiceAndRawPcmIsWrapped() = runTest {
        val pcm = ByteArray(4800) { (it % 7).toByte() }
        val r = provider(
            HttpStatusCode.NotFound to """{"error":{"code":404,"message":"model not found"}}""",
            HttpStatusCode.OK to audio("audio/L16;codec=pcm;rate=24000", pcm),
        ).speak("က", VoiceRef("Kore"), "my", "style")
        val out = assertIs<AiResult.Ok<ByteArray>>(r).value
        val info = assertNotNull(Wav.parse(out))
        assertEquals(24_000, info.sampleRate)
        assertEquals(100L, info.durationMs)
        assertEquals("gemini-2.5-flash-preview-tts", r.usage?.model)
        val voice = body(1)["generationConfig"]!!.jsonObject["speechConfig"]!!.jsonObject["voiceConfig"]!!.jsonObject
        assertEquals("Kore", voice["prebuiltVoiceConfig"]!!.jsonObject["voiceName"]!!.jsonPrimitive.content)
        assertTrue("speech_metadata" !in body(1).toString())
    }

    @Test
    fun unreadableAudioIsNull() {
        assertNull(GeminiTtsProvider.wav(Json.parseToJsonElement("""{"mimeType":"audio/mp3","data":"AAAA"}""")))
        assertNull(GeminiTtsProvider.wav(Json.parseToJsonElement("""{"mimeType":"audio/wav","data":"!!"}""")))
    }
}
