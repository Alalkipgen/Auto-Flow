package io.github.alalkipgen.autoflow.media.audio

import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.etaSeconds
import io.github.alalkipgen.autoflow.media.render.RealFfmpeg
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.*

/**
 * D-0 (specs/dubbing.md §7 c): mixing 50 / 150 / 300 dub cues (one 1.2 s take every 2 s, so 300 cues = a 10 min
 * video) with real ffmpeg — one `amix` with every cue as an input, and stems of `dubbing.stemGroup` (50) cues mixed
 * afterwards. Measures wall time and command length, checks every cue starts within ±40 ms of its line, and prints
 * `D0|…` rows for docs/perf/dubbing-d0.md. Also the request count and the rpm-paced time for the same line counts.
 */
class DubMixSpikeTest {
    private val sizes = listOf(50, 150, 300)
    private val group = 50
    private val gapMs = 2_000L
    private val takeMs = 1_200L
    private fun startOf(k: Int) = k * gapMs + 400

    private fun mixArgs(takes: List<String>, starts: List<Long>, totalMs: Long, out: String): List<String> {
        val t = "%.3f".format(totalMs / 1000.0)
        val chains = takes.indices.map { k -> "[$k:a]aformat=sample_rates=48000:channel_layouts=stereo,adelay=${starts[k]}|${starts[k]}[c$k]" } +
            "anullsrc=r=48000:cl=stereo:d=$t[bed]" +
            ("[bed]" + takes.indices.joinToString("") { "[c$it]" } + "amix=inputs=${takes.size + 1}:duration=first:normalize=0,atrim=duration=$t[aout]")
        return listOf("-hide_banner", "-y", "-nostdin") + takes.flatMap { listOf("-i", it) } +
            listOf("-filter_complex", chains.joinToString(";"), "-map", "[aout]", "-c:a", "pcm_s16le", out)
    }

    @Test fun mixFiftyHundredFiftyAndThreeHundredCues() {
        val (ffmpeg, ffprobe) = RealFfmpeg.orSkip("DubMixSpikeTest") ?: return
        val dir = Files.createTempDirectory("dub-d0").toFile()
        try {
            val tone = File(dir, "tone.wav")
            RealFfmpeg.ok(listOf(ffmpeg, "-hide_banner", "-y", "-f", "lavfi", "-i", "sine=f=600:d=${takeMs / 1000.0}:sample_rate=24000", "-c:a", "pcm_s16le", tone.path))
            File(dir, "takes").mkdirs()
            val takes = (0 until sizes.max()).map { k -> "takes/au-%016x.wav".format(k * 7919L + 1).also { tone.copyTo(File(dir, it)) } }
            fun timed(args: List<String>): Long {
                val t0 = System.nanoTime()
                val (code, log) = RealFfmpeg.run(listOf(ffmpeg) + args, dir, timeoutSec = 600)
                assertEquals(0, code, log.takeLast(2_000))
                return (System.nanoTime() - t0) / 1_000_000
            }
            fun onsets(f: File): List<Long> = Regex("silence_end: ([0-9.]+)").findAll(
                RealFfmpeg.run(listOf(ffmpeg, "-hide_banner", "-nostdin", "-i", f.path, "-af", "silencedetect=n=-40dB:d=0.25", "-f", "null", "-"), dir).second)
                .map { (it.groupValues[1].toDouble() * 1000).toLong() }.toList()
            for (n in sizes) {
                val totalMs = n * gapMs
                val starts = (0 until n).map(::startOf)
                val direct = mixArgs(takes.take(n), starts, totalMs, "direct-$n.wav")
                val directMs = timed(direct)
                var groupedMs = 0L; var longest = 0
                val stems = (0 until n step group).map { from ->
                    val idx = from until minOf(n, from + group)
                    val args = mixArgs(idx.map { takes[it] }, idx.map(::startOf), totalMs, "stem-$n-$from.wav")
                    longest = maxOf(longest, args.sumOf { it.length + 1 })
                    groupedMs += timed(args)
                    "stem-$n-$from.wav"
                }
                val final = mixArgs(stems, stems.map { 0L }, totalMs, "grouped-$n.wav")
                groupedMs += timed(final)
                longest = maxOf(longest, final.sumOf { it.length + 1 })
                for (name in listOf("direct-$n.wav", "grouped-$n.wav")) {
                    val f = File(dir, name)
                    assertTrue(abs(RealFfmpeg.durationMs(ffprobe, f) - totalMs) <= 40, "$name keeps the video length")
                    val found = onsets(f).filter { it < totalMs - 100 } // the trailing silence ends at the end of the file
                    assertEquals(n, found.size, "$name: one onset per cue")
                    found.zip(starts).forEach { (got, want) -> assertTrue(abs(got - want) <= 40, "$name: cue at $want ms started at $got ms") }
                }
                val requests = AiJob("d0", AiJobKind.TEXT_TTS, "p", (1..n).map { "c$it" }, rpm = 3)
                println("D0|$n cues|${totalMs / 1000} s video|direct ${directMs} ms, ${direct.size} args, ${direct.sumOf { it.length + 1 }} chars|" +
                    "grouped ${groupedMs} ms, longest ${longest} chars|$n TTS requests, ${requests.etaSeconds()!! / 60} min at 3 rpm")
                assertTrue(longest < 32_000, "a grouped command fits the Windows command line (32 767)")
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
