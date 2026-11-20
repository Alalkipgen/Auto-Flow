package io.github.alalkipgen.autoflow.ai.edge

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.VoiceRef
import io.github.alalkipgen.autoflow.ai.codec.Wav
import io.github.alalkipgen.autoflow.ai.config.EdgeTtsConfig
import io.github.alalkipgen.autoflow.ai.config.EdgeVoice
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Contract test on frames as the live service sent them on 2026-11-20 (headers copied, audio bytes shortened). */
class EdgeTtsProviderTest {
    private val config = EdgeTtsConfig(
        model = "edge-tts", endpoint = "wss://edge.test/v1", trustedClientToken = "6A5AA1D4EAFF4E9FB37E23D68491D6F4",
        secMsGecVersion = "2-143.0.3650.75", origin = "chrome-extension://x", userAgent = "UA", outputFormat = "audio-24khz-48kbitrate-mono-mp3",
        maxTextBytes = 200, voices = listOf(EdgeVoice("my-MM-ThihaNeural", "Thiha", "male", "my"), EdgeVoice("my-MM-NilarNeural", "Nilar", "female", "my")),
    )
    private val now = 1_795_191_365_000L // Fri Nov 20 2026 16:16:05 UTC

    private fun audioFrame(vararg data: Int): EdgeFrame.Binary {
        val header = "X-RequestId:76334f37c5784b1c9d46f757c3d9f5ac\r\nContent-Type:audio/mpeg\r\nX-StreamId:AF39D162166A4CA7B3520A0E31A0F153\r\nPath:audio\r\n".encodeToByteArray()
        return EdgeFrame.Binary(byteArrayOf((header.size shr 8).toByte(), header.size.toByte()) + header + ByteArray(data.size) { data[it].toByte() })
    }

    private fun text(path: String) = EdgeFrame.Text("X-RequestId:76334f37c5784b1c9d46f757c3d9f5ac\r\nContent-Type:application/json; charset=utf-8\r\nPath:$path\r\n\r\n{}")

    private val recorded = listOf(text("turn.start"), text("response"), audioFrame(1, 2, 3), audioFrame(4, 5), audioFrame(), text("audio.metadata"), text("turn.end"))

    private class Socket(val answer: AiResult<List<EdgeFrame>>) : EdgeSocket {
        val calls = mutableListOf<Triple<String, Map<String, String>, List<String>>>()
        override suspend fun turn(url: String, headers: Map<String, String>, frames: List<String>): AiResult<List<EdgeFrame>> {
            calls += Triple(url, headers, frames)
            return answer
        }
    }

    private val wav = Wav.pcm16Mono(ShortArray(2400), 24_000)

    private fun provider(socket: EdgeSocket, decode: suspend (ByteArray) -> ByteArray? = { wav }) =
        EdgeTtsProvider(config, socket, decode, nowMs = { now }, random = Random(1))

    @Test
    fun speaksTheTextAndJoinsTheAudioFramesIntoOneWav() = runTest {
        val socket = Socket(AiResult.Ok(recorded))
        var mp3: ByteArray? = null
        val r = assertIs<AiResult.Ok<ByteArray>>(provider(socket) { mp3 = it; wav }.speak("မင်္ဂလာပါ & <hi>", VoiceRef("my-MM-NilarNeural"), "my", "calm", "edge-tts"))
        assertContentEquals(wav, r.value)
        assertEquals("edge-tts", r.usage!!.model)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), mp3)

        val (url, headers, frames) = socket.calls.single()
        assertTrue(url.startsWith("wss://edge.test/v1?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4&ConnectionId="))
        assertTrue(url.endsWith("&Sec-MS-GEC=${EdgeToken.secMsGec(now / 1000, config.trustedClientToken)}&Sec-MS-GEC-Version=2-143.0.3650.75"))
        assertEquals("chrome-extension://x", headers["Origin"])
        assertEquals("UA", headers["User-Agent"])
        assertTrue(headers.getValue("Cookie").matches(Regex("muid=[0-9A-F]{32};")))
        assertTrue(frames[0].startsWith("X-Timestamp:Fri Nov 20 2026 16:16:05 GMT+0000 (Coordinated Universal Time)\r\n"))
        assertTrue(frames[0].contains("Path:speech.config\r\n\r\n") && frames[0].contains("\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\""))
        assertTrue(frames[1].contains("X-Timestamp:Fri Nov 20 2026 16:16:05 GMT+0000 (Coordinated Universal Time)Z\r\nPath:ssml\r\n\r\n"))
        assertTrue(frames[1].contains("<voice name='my-MM-NilarNeural'><prosody pitch='+0Hz' rate='+0%' volume='+0%'>မင်္ဂလာပါ &amp; &lt;hi&gt;</prosody>"))
    }

    @Test
    fun failuresAreNormalProviderErrors() = runTest {
        val ok = Socket(AiResult.Ok(recorded))
        assertEquals("unknown_voice", assertIs<AiResult.Failed>(provider(ok).speak("x", VoiceRef("Charon"), "my")).code)
        assertEquals("text_too_long", assertIs<AiResult.Failed>(provider(ok).speak("က".repeat(100), VoiceRef("my-MM-ThihaNeural"), "my")).code)
        assertEquals("empty_text", assertIs<AiResult.Failed>(provider(ok).speak("  ", VoiceRef("my-MM-ThihaNeural"), "my")).code)
        assertTrue(ok.calls.isEmpty(), "nothing is sent for a request that cannot work")

        val refused = AiResult.Failed("http_403", "refused")
        assertEquals(refused, provider(Socket(refused)).speak("x", VoiceRef("my-MM-ThihaNeural"), "my"))
        assertEquals("empty_response", assertIs<AiResult.Failed>(provider(Socket(AiResult.Ok(listOf(text("turn.end"))))).speak("x", VoiceRef("my-MM-ThihaNeural"), "my")).code)
        assertEquals("no_mp3_decoder", assertIs<AiResult.Failed>(provider(ok) { null }.speak("x", VoiceRef("my-MM-ThihaNeural"), "my")).code)
        assertEquals("invalid_audio", assertIs<AiResult.Failed>(provider(ok) { byteArrayOf(9) }.speak("x", VoiceRef("my-MM-ThihaNeural"), "my")).code)
        assertEquals("edge_not_configured", assertIs<AiResult.Failed>(EdgeTtsProvider(EdgeTtsConfig(), ok, { wav }).speak("x", VoiceRef("my-MM-ThihaNeural"), "my")).code)
    }

    @Test
    fun handshakeErrorsMapToCodes() {
        assertEquals("http_403", KtorEdgeSocket.failure("Handshake exception, expected status code 101 but was 403").code)
        assertEquals("http_503", KtorEdgeSocket.failure("expected status code 101 but was 503").code)
        assertEquals("network", KtorEdgeSocket.failure("Connection refused").code)
    }

    @Test
    fun timestampsAndEscaping() {
        assertEquals("Thu Feb 29 2024 00:00:00 GMT+0000 (Coordinated Universal Time)", EdgeTtsProvider.timestamp(1_709_164_800_000))
        assertEquals("Thu Jan 01 1970 00:00:00 GMT+0000 (Coordinated Universal Time)", EdgeTtsProvider.timestamp(0))
        assertEquals("a &quot;b&quot; &apos;c&apos; d", EdgeTtsProvider.escape("a \"b\" 'c'\u0007d"))
    }
}
