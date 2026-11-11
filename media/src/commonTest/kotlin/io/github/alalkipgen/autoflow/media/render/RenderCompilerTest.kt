package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.media.render.RenderCompiler.ClipMedia
import io.github.alalkipgen.autoflow.media.render.RenderCompiler.Compiled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Golden commands (specs/render-graph.md): the exact ffmpeg arguments for three timeline shapes, plus validation. */
class RenderCompilerTest {
    private val a = ArtifactId("vd_aaaaaaaaaaaa")
    private val b = ArtifactId("vd_bbbbbbbbbbbb")
    private val files = mapOf(a to ClipMedia("artifacts/vd_aaaaaaaaaaaa.mp4", hasAudio = true), b to ClipMedia("artifacts/vd_bbbbbbbbbbbb.mp4", hasAudio = false))
    private val env = RenderCompiler.Env(
        resolve = { files[it] },
        songPath = "audio/song.mp3",
        assPath = "exports/mv.ass",
        fontsDir = "fonts",
        outputPath = "exports/mv.mp4",
        assScale = { if (it == "Noto Sans Myanmar") 2.184 else null },
        placeholderFont = "Noto Sans Myanmar",
    )
    private val fit = "scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:(ow-iw)/2:(oh-ih)/2,setsar=1,format=yuv420p"

    private fun ok(g: RenderGraph): Compiled.Ok = assertIs<Compiled.Ok>(RenderCompiler.compile(g, env))

    private fun graphOf(args: List<String>) = args[args.indexOf("-filter_complex") + 1].split(';')

    @Test
    fun cutsWithSongAndLyricsExport() {
        val g = RenderGraph(
            clips = listOf(Clip(a, trimInMs = 500, trimOutMs = 8_500), Clip(b, trimOutMs = 8_000)),
            audio = AudioTrack("audio/song.mp3", offsetMs = -1_500, fadeInMs = 500, fadeOutMs = 2_000),
            overlays = listOf(Overlay("မိုးရွာနေတဲ့ ည", 1_000, 4_000, "Noto Sans Myanmar", 64)),
            output = RenderPresets.export(Ratio.R9_16),
        )
        val r = ok(g)
        assertEquals(16_000, r.durationMs)
        assertEquals(
            listOf(
                "-hide_banner", "-y", "-nostdin",
                "-ss", "0.500", "-t", "8.000", "-i", "artifacts/vd_aaaaaaaaaaaa.mp4",
                "-t", "8.000", "-i", "artifacts/vd_bbbbbbbbbbbb.mp4",
                "-ss", "1.500", "-i", "audio/song.mp3",
                "-filter_complex",
                listOf(
                    "[0:v]$fit,tpad=stop_mode=clone:stop_duration=8.000,trim=duration=8.000,setpts=PTS-STARTPTS,fps=24[v0]",
                    "[1:v]$fit,tpad=stop_mode=clone:stop_duration=8.000,trim=duration=8.000,setpts=PTS-STARTPTS,fps=24[v1]",
                    "[v0][v1]concat=n=2:v=1:a=0[vseq]",
                    "[vseq]ass=filename=exports/mv.ass:fontsdir=fonts[vout]",
                    "[2:a]aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo,afade=t=in:st=0.000:d=0.500," +
                        "afade=t=out:st=14.000:d=2.000,apad=whole_dur=16.000,atrim=duration=16.000[aout]",
                ).joinToString(";"),
                "-map", "[vout]", "-map", "[aout]",
                "-c:v", "libx264", "-preset", "slow", "-crf", "18", "-pix_fmt", "yuv420p", "-r", "24",
                "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2",
                "-t", "16.000", "-movflags", "+faststart", "exports/mv.mp4",
            ),
            r.args,
        )
        check("Dialogue: 0,0:00:01.00,0:00:04.00,s1,,0,0,0,,{\\an2\\pos(540,1767)}မိုးရွာနေတဲ့ ည" in r.ass!!) { r.ass }
    }

    @Test
    fun crossfadesPlaceholderAndClipSoundPreview() {
        val g = RenderGraph(
            clips = listOf(
                Clip(a, trimOutMs = 8_000),
                Clip(null, trimOutMs = 4_000, transition = Transition.CROSSFADE, transitionMs = 500, label = "#02 — pending"),
                Clip(b, trimOutMs = 8_000),
                Clip(a, trimInMs = 1_000, trimOutMs = 5_000, transition = Transition.CROSSFADE, transitionMs = 1_000),
            ),
            output = RenderPresets.preview(Ratio.R9_16),
        )
        val r = ok(g)
        assertEquals(8_000L + 3_500 + 8_000 + 3_000, r.durationMs)
        val chains = graphOf(r.args)
        // The pending card and the silence are source filters in the graph, not `-f lavfi` inputs (no input devices on Android).
        assertEquals("color=c=0x1A1A1A:s=540x960:r=24:d=4.000,scale=540:960:force_original_aspect_ratio=decrease,pad=540:960:(ow-iw)/2:(oh-ih)/2,setsar=1,format=yuv420p,tpad=stop_mode=clone:stop_duration=4.000,trim=duration=4.000,setpts=PTS-STARTPTS,fps=24[v1]", chains[2])
        assertEquals("anullsrc=r=48000:cl=stereo:d=4.000,aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo,apad=whole_dur=4.000,atrim=duration=4.000,asetpts=PTS-STARTPTS[a1]", chains[3])
        assertEquals("[1:v]", chains[4].take(5))
        assertEquals("anullsrc=r=48000:cl=stereo:d=8.000,aformat", chains[5].substringBefore("=sample_fmts"))
        assertEquals(listOf("[2:v]", "[2:a]"), chains.subList(6, 8).map { it.take(5) })
        assertEquals(
            listOf(
                "[v0][v1]xfade=transition=fade:duration=0.500:offset=7.500[vj1]",
                "[vj1][v2]concat=n=2:v=1:a=0,fps=24[vj2]",
                "[vj2][v3]xfade=transition=fade:duration=1.000:offset=18.500[vseq]",
                "[a0][a1]acrossfade=d=0.500[aj1]",
                "[aj1][a2]concat=n=2:v=0:a=1[aj2]",
                "[aj2][a3]acrossfade=d=1.000[aseq]",
                "[vseq]ass=filename=exports/mv.ass:fontsdir=fonts[vout]",
                "[aseq]anull[aout]",
            ),
            chains.drop(8),
        )
        val inputs = r.args.subList(3, r.args.indexOf("-filter_complex"))
        assertEquals(
            listOf(
                "-t", "8.000", "-i", "artifacts/vd_aaaaaaaaaaaa.mp4",
                "-t", "8.000", "-i", "artifacts/vd_bbbbbbbbbbbb.mp4",
                "-ss", "1.000", "-t", "4.000", "-i", "artifacts/vd_aaaaaaaaaaaa.mp4",
            ),
            inputs,
        )
        check("lavfi" !in r.args) { r.args.toString() }
        // The pending card's label starts where the crossfade into it starts.
        check("Dialogue: 0,0:00:07.50,0:00:11.50,s1,,0,0,0,,{\\an5\\pos(270,480)}#02 — pending" in r.ass!!) { r.ass }
        assEqualsPreset(r.args, "ultrafast", "23", "128k")
    }

    @Test
    fun songOverClipSoundWithDelayAndNoOverlays() {
        val g = RenderGraph(
            clips = listOf(Clip(a, trimOutMs = 6_000)),
            audio = AudioTrack("audio/song.mp3", offsetMs = 2_000, muteClips = false, clipsVolume = 0.25),
            output = RenderPresets.export(Ratio.R16_9),
        )
        val r = ok(g)
        assertNull(r.ass)
        assertEquals(
            listOf(
                "[0:v]scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2,setsar=1,format=yuv420p,tpad=stop_mode=clone:stop_duration=6.000,trim=duration=6.000,setpts=PTS-STARTPTS,fps=24[v0]",
                "[0:a]aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo,apad=whole_dur=6.000,atrim=duration=6.000,asetpts=PTS-STARTPTS[a0]",
                "[v0]null[vseq]",
                "[a0]anull[aseq]",
                "[vseq]null[vout]",
                "[1:a]aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo,adelay=2000|2000,apad=whole_dur=6.000,atrim=duration=6.000[song]",
                "[aseq]volume=0.25[clips]",
                "[clips][song]amix=inputs=2:duration=first:normalize=0[aout]",
            ),
            graphOf(r.args),
        )
    }

    @Test
    fun rejectsBrokenTimelines() {
        val out = RenderPresets.preview(Ratio.R9_16)
        fun reason(g: RenderGraph) = assertIs<Compiled.Invalid>(RenderCompiler.compile(g, env)).reason
        assertEquals("the timeline has no clips", reason(RenderGraph(clips = emptyList(), output = out)))
        assertEquals("clip 1 is shorter than one frame", reason(RenderGraph(clips = listOf(Clip(a, trimOutMs = 10)), output = out)))
        assertEquals("clip 1: the video vd_cccccccccccc is missing", reason(RenderGraph(clips = listOf(Clip(ArtifactId("vd_cccccccccccc"), trimOutMs = 8_000)), output = out)))
        val long = listOf(Clip(a, trimOutMs = 2_000), Clip(b, trimOutMs = 8_000, transition = Transition.CROSSFADE, transitionMs = 2_000))
        assertEquals("clip 2: the crossfade is longer than a clip", reason(RenderGraph(clips = long, output = out)))
        val zero = listOf(Clip(a, trimOutMs = 2_000), Clip(b, trimOutMs = 8_000, transition = Transition.CROSSFADE))
        assertEquals("clip 2: the crossfade needs a length", reason(RenderGraph(clips = zero, output = out)))
        val backwards = RenderGraph(clips = listOf(Clip(a, trimOutMs = 2_000)), overlays = listOf(Overlay("x", 900, 900, "F", 10)), output = out)
        assertEquals("overlay 1 ends before it starts", reason(backwards))
        val song = RenderGraph(clips = listOf(Clip(a, trimOutMs = 2_000)), audio = AudioTrack("audio/x.mp3"), output = out)
        assertEquals("the song file is missing", assertIs<Compiled.Invalid>(RenderCompiler.compile(song, env.copy(songPath = null))).reason)
    }

    @Test
    fun filterPathsSurviveBothEscapingLevels() {
        assertEquals("/storage/emulated/0/Android/data/x/files/fonts", RenderCompiler.filterPath("/storage/emulated/0/Android/data/x/files/fonts"))
        assertEquals("C\\\\:/Users/me/mv.ass", RenderCompiler.filterPath("C:\\Users\\me\\mv.ass"))
        assertEquals("a\\,b\\\\\\'s\\[1\\]\\;", RenderCompiler.filterPath("a,b's[1];"))
        assertEquals("0.040", RenderCompiler.sec(40))
        assertEquals("125.000", RenderCompiler.sec(125_000))
    }

    private fun assEqualsPreset(args: List<String>, preset: String, crf: String, audio: String) {
        assertEquals(preset, args[args.indexOf("-preset") + 1])
        assertEquals(crf, args[args.indexOf("-crf") + 1])
        assertEquals(audio, args[args.indexOf("-b:a") + 1])
    }
}
