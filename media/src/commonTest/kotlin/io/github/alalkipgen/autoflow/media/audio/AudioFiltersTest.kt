package io.github.alalkipgen.autoflow.media.audio

import io.github.alalkipgen.autoflow.core.recap.RecapAudio
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
    fun trimSilenceUsesGapAndKeep() {
        assertEquals(
            "silenceremove=start_periods=1:start_duration=0:start_threshold=-40dB:start_silence=0.200" +
                ":stop_periods=-1:stop_duration=0.350:stop_threshold=-40dB:stop_silence=0.200",
            AudioFilters.trimSilence(),
        )
        assertFailsWith<IllegalArgumentException> { AudioFilters.trimSilence(gapMs = 200, keepMs = 200) }
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

    @Test
    fun narrationFollowsRecapAudio() {
        assertEquals(AudioFilters.voicePolish(), AudioFilters.narration(RecapAudio()))
        assertNull(AudioFilters.narration(RecapAudio(polish = false)))
        assertEquals(
            AudioFilters.voicePolish() + "," + AudioFilters.trimSilence(),
            AudioFilters.narration(RecapAudio(trimSilence = true)),
        )
    }
}
