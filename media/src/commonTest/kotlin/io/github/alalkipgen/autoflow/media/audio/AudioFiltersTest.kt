package io.github.alalkipgen.autoflow.media.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioFiltersTest {
    @Test
    fun polishCleansBeforeCompressing() {
        val f = AudioFilters.voicePolish()
        assertTrue(f.indexOf("highpass") < f.indexOf("afftdn"))
        assertTrue(f.indexOf("afftdn") < f.indexOf("acompressor"))
    }

    @Test
    fun silenceTrimKeepsShortPausesAndCutsLongOnesTo200ms() {
        // silencedetect (d = 0.35 s) on: 0.6 s lead, speech, 1.0 s pause, speech, 0.4 s pause, speech, 0.6 s tail.
        val log = """
            Duration: 00:00:05.00, bitrate: 768 kb/s
            [silencedetect @ 0x1] silence_start: 0
            [silencedetect @ 0x1] silence_end: 0.600021 | silence_duration: 0.600021
            [silencedetect @ 0x1] silence_start: 1.1
            [silencedetect @ 0x1] silence_end: 2.100021 | silence_duration: 1.000021
            [silencedetect @ 0x1] silence_start: 2.6
            [silencedetect @ 0x1] silence_end: 2.95 | silence_duration: 0.35
            [silencedetect @ 0x1] silence_start: 3.4
            [silencedetect @ 0x1] silence_end: 3.8 | silence_duration: 0.4
            [silencedetect @ 0x1] silence_start: 4.4
            [silencedetect @ 0x1] silence_end: 5 | silence_duration: 0.6
        """.trimIndent()
        val silences = AudioFilters.parseSilences(log)
        assertEquals(5.0, AudioFilters.parseInputDuration(log))
        val kept = AudioFilters.keptRanges(silences, 5.0)
        // Lead: its last 0.2 s; 1.0 s pause → 0.1 s + 0.1 s; exactly 0.35 s stays; 0.4 s → 0.2 s; tail: its first 0.2 s.
        val expected = listOf(0.400021 to 1.2, 2.000021 to 3.5, 3.7 to 4.6)
        assertEquals(expected.size, kept.size, kept.toString())
        kept.zip(expected).forEach { (k, e) ->
            assertTrue(kotlin.math.abs(k.first - e.first) < 1e-6 && kotlin.math.abs(k.second!! - e.second) < 1e-6, "$k vs $e")
        }
        // A tail without an end (older ffmpeg) also keeps only its first 0.2 s.
        val ms = { r: List<Pair<Double, Double?>> -> r.map { (a, b) -> kotlin.math.round(a * 1000).toLong() to b?.let { kotlin.math.round(it * 1000).toLong() } } }
        assertEquals(listOf(0L to 4_600L), ms(AudioFilters.keptRanges(listOf(AudioFilters.Silence(4.4, null)))))
        // Nothing long enough: one range, the whole take.
        assertEquals(listOf(0.0 to null), AudioFilters.keptRanges(listOf(AudioFilters.Silence(1.0, 1.3))))
        assertFailsWith<IllegalArgumentException> { AudioFilters.keptRanges(emptyList(), gapMs = 200, keepMs = 200) }
        val args = AudioFilters.cutArgs("in.wav", "out.wav", kept)
        assertEquals(
            "[0:a]asplit=3[k0][k1][k2];[k0]atrim=start=0.400021:end=1.200000,asetpts=PTS-STARTPTS[c0];" +
                "[k1]atrim=start=2.000021:end=3.500000,asetpts=PTS-STARTPTS[c1];[k2]atrim=start=3.700000:end=4.600000,asetpts=PTS-STARTPTS[c2];" +
                "[c0][c1][c2]concat=n=3:v=0:a=1[out]",
            args[args.indexOf("-filter_complex") + 1],
        )
    }

    @Test
    fun bgmLoopsTrimsAndFades() {
        assertEquals(
            "aloop=loop=-1:size=2147483647,atrim=duration=60.000,asetpts=PTS-STARTPTS,volume=0.18" +
                ",afade=t=in:st=0:d=1.500,afade=t=out:st=58.500:d=1.500",
            AudioFilters.bgm(60_000, 0.18),
        )
    }

    @Test
    fun bgmShortVideoShrinksFadesAndPadsWithoutLoop() {
        val f = AudioFilters.bgm(3_000, 0.2, loop = false)
        assertTrue(f.startsWith("apad,atrim=duration=3.000"))
        assertTrue(f.endsWith("afade=t=out:st=2.000:d=1.000"))
    }

    @Test
    fun duckingUsesVoiceAsSidechainKey() {
        val chains = AudioFilters.mixWithMusic("voice", "bgm", "aout", duck = true)
        assertEquals("[voice]asplit=2[voice_mix][voice_key]", chains[0])
        assertTrue(chains[1].startsWith("[bgm][voice_key]sidechaincompress"))
        assertTrue(chains[2].endsWith("[aout]"))
        assertEquals(
            listOf("[voice][bgm]amix=inputs=2:duration=first:normalize=0[aout]"),
            AudioFilters.mixWithMusic("voice", "bgm", "aout", duck = false),
        )
    }

    @Test
    fun loudnormTwoPass() {
        val stderr = """
            [Parsed_loudnorm_0 @ 0x55] 
            {
            	"input_i" : "-23.54",
            	"input_tp" : "-7.12",
            	"input_lra" : "6.30",
            	"input_thresh" : "-34.10",
            	"output_i" : "-14.02",
            	"output_tp" : "-1.50",
            	"output_lra" : "5.10",
            	"output_thresh" : "-24.50",
            	"normalization_type" : "dynamic",
            	"target_offset" : "0.02"
            }
        """.trimIndent()
        val m = AudioFilters.parseMeasurement(stderr)!!
        assertEquals(LoudnessMeasurement(-23.54, -7.12, 6.3, -34.1, 0.02), m)
        assertEquals("loudnorm=I=-14:TP=-1.5:LRA=11:print_format=json", AudioFilters.loudnormMeasure())
        assertEquals(
            "loudnorm=I=-14:TP=-1.5:LRA=11:measured_I=-23.54:measured_TP=-7.12:measured_LRA=6.3" +
                ":measured_thresh=-34.1:offset=0.02:linear=true",
            AudioFilters.loudnormApply(m),
        )
    }

    @Test
    fun measurementIsNullForSilenceOrGarbage() {
        assertNull(AudioFilters.parseMeasurement("no json here"))
        assertNull(AudioFilters.parseMeasurement("""{ "input_i" : "-inf", "input_tp" : "-inf", "input_lra" : "0.00", "input_thresh" : "-70.00", "target_offset" : "0.00" }"""))
        assertNull(AudioFilters.parseMeasurement("""{ "input_i" : "-20" }"""))
    }
}
