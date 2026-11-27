package io.github.alalkipgen.autoflow.media.audio

import io.github.alalkipgen.autoflow.media.render.MediaTaskJob
import kotlin.test.*

class VoiceJoinTest {
    private fun wav(rate: Int, vararg v: Int): ByteArray = PcmWav.encode(rate, FloatArray(v.size) { v[it] / 32767f })

    @Test fun takesAreJoinedInOrderWithTheGapAndSamplesKeepTheirValues() {
        val a = wav(1000, 100, -200, 32767); val b = wav(1000, -32767, 5)
        val joined = assertNotNull(VoiceJoin.join(listOf(a, b), gapMs = 3))
        val back = assertNotNull(PcmWav.parse(joined))
        assertEquals(1000, back.rate)
        val expect = PcmWav.parse(a)!!.samples.toList() + listOf(0f, 0f, 0f) + PcmWav.parse(b)!!.samples.toList()
        assertEquals(expect, back.samples.toList())
        assertContentEquals(a.copyOfRange(44, a.size), joined.copyOfRange(44, 44 + a.size - 44), "the first take's bytes are copied exactly")
        assertEquals(8L, back.durationMs)
    }

    @Test fun unreadableOrMixedRatesAreRefused() {
        assertNull(VoiceJoin.join(listOf(wav(1000, 1), wav(2000, 1)), 0))
        assertNull(VoiceJoin.join(listOf(wav(1000, 1), byteArrayOf(1, 2, 3)), 0))
        assertNull(VoiceJoin.join(emptyList(), 0))
        assertContentEquals(PcmWav.parse(wav(1000, 7))!!.samples, PcmWav.parse(VoiceJoin.join(listOf(wav(1000, 7)), 300)!!)!!.samples, "no gap after the last take")
    }

    @Test fun m4aArgsAndFileNames() {
        assertEquals(listOf("-y", "-nostdin", "-i", "artifacts/au_x.wav", "-vn", "-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart", "-f", "mp4",
            MediaTaskJob.PART), VoiceJoin.m4aArgs("artifacts/au_x.wav", "160k"))
        assertEquals("Voice-2026-11-20-10-00-voice-0123456789ab.m4a", VoiceJoin.fileName("Voice · 2026-11-20 10:00", "au_0123456789ab", "m4a"))
        assertEquals("မင်္ဂလာ-voice-0123456789ab.wav", VoiceJoin.fileName("မင်္ဂလာ!", "au_0123456789ab", "wav"))
    }
}
