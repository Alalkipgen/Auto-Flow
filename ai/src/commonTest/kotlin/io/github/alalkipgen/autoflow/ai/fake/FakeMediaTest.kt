package io.github.alalkipgen.autoflow.ai.fake

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.Availability
import io.github.alalkipgen.autoflow.ai.Transcript
import io.github.alalkipgen.autoflow.ai.VoiceRef
import io.github.alalkipgen.autoflow.ai.codec.Wav
import io.github.alalkipgen.autoflow.core.model.Ratio
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

class FakeMediaTest {
    private fun ByteArray.be32(i: Int) = (0 until 4).fold(0) { acc, k -> (acc shl 8) or (this[i + k].toInt() and 0xFF) }

    private suspend fun png(p: FakeImageProvider, prompt: String, ratio: Ratio = Ratio.R16_9) =
        assertIs<AiResult.Ok<ByteArray>>(p.image(prompt, ratio)).value

    @Test
    fun imageSizeFollowsRatio() = runTest {
        val p = FakeImageProvider(longSide = 320)
        val sizes = Ratio.entries.associateWith { png(p, "Shot 1", it).let { b -> b.be32(16) to b.be32(20) } }
        assertEquals(320 to 180, sizes[Ratio.R16_9])
        assertEquals(180 to 320, sizes[Ratio.R9_16])
        assertEquals(320 to 320, sizes[Ratio.R1_1])
        assertEquals(256 to 320, sizes[Ratio.R4_5])
        assertEquals(320 to 137, sizes[Ratio.R21_9])
    }

    @Test
    fun imageIsDeterministicPerPrompt() = runTest {
        val p = FakeImageProvider()
        assertContentEquals(png(p, "Shot 2: rain"), png(p, "Shot 2: rain"))
        assertFalse(png(p, "Shot 2: rain").contentEquals(png(p, "Shot 2: neon")))
        assertEquals("empty_prompt", assertIs<AiResult.Failed>(p.image(" ", Ratio.R1_1)).code)
        assertEquals(5, p.calls)
    }

    @Test
    fun shotLabelIsReadFromThePrompt() {
        assertEquals("3", FakeImageProvider.shotLabel("Shot 3 — subject: woman"))
        assertEquals("12", FakeImageProvider.shotLabel("reference for shot #12"))
        assertNull(FakeImageProvider.shotLabel("screenshot of 4 people"))
    }

    @Test
    fun placeholderDrawsDigitsOnlyWhenLabelled() {
        val bg = 0x445566
        assertTrue(Placeholder.render(60, 40, bg, "").all { it == bg })
        val drawn = Placeholder.render(60, 40, bg, "7")
        assertEquals(bg, drawn[0])
        assertTrue(drawn.count { it != bg } > 10)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun ttsReturnsWavWithLengthFromText() = runTest {
        val tts = FakeTtsProvider(delayMs = 250)
        val wav = assertIs<AiResult.Ok<ByteArray>>(tts.speak("မင်္ဂလာပါ ခင်ဗျာ", VoiceRef("Kore"), "my")).value
        assertEquals(250, currentTime) // virtual time: the fake latency is honoured
        val info = assertIs<Wav.Info>(Wav.parse(wav))
        assertEquals(FakeTtsProvider.SAMPLE_RATE, info.sampleRate)
        assertEquals(960, info.durationMs) // 16 chars × 60 ms
        assertEquals(Availability.UNAVAILABLE, tts.capabilities.replication)
        assertTrue(tts.capabilities.burmese)
        assertEquals("empty_text", assertIs<AiResult.Failed>(tts.speak("", VoiceRef("Kore"), "my")).code)
    }

    @Test
    fun transcriptCoversTheWholeAudio() = runTest {
        val wav = assertIs<AiResult.Ok<ByteArray>>(FakeTtsProvider(msPerChar = 1_000).speak("x".repeat(10), VoiceRef("v"), "en")).value
        val t = assertIs<AiResult.Ok<Transcript>>(FakeTranscribeProvider().transcribe(wav, "my")).value
        assertEquals(listOf(0L, 4_000L, 8_000L), t.segments.map { it.startMs })
        assertEquals(10_000, t.segments.last().endMs)
        assertTrue(t.segments.zipWithNext().all { (a, b) -> a.endMs == b.startMs })
        assertTrue(t.segments.all { s -> s.text.any { it in '\u1000'..'\u109F' } })
    }

    @Test
    fun transcriptUsesGivenLinesInOrderAndFallbackDuration() = runTest {
        val p = FakeTranscribeProvider(lines = listOf("one", "two"), segmentMs = 5_000, fallbackDurationMs = 12_000)
        val t = assertIs<AiResult.Ok<Transcript>>(p.transcribe(byteArrayOf(1, 2, 3), "en")).value
        assertEquals(listOf("one", "two", "one"), t.segments.map { it.text })
        assertEquals(12_000, t.segments.last().endMs)
        assertEquals("empty_audio", assertIs<AiResult.Failed>(p.transcribe(ByteArray(0), "en")).code)
    }
}
