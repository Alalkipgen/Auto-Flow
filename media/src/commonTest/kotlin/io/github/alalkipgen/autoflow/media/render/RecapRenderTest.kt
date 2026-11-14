package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.CaptionSize
import io.github.alalkipgen.autoflow.core.recap.CaptionStyle
import io.github.alalkipgen.autoflow.core.recap.FrameBackground
import io.github.alalkipgen.autoflow.core.recap.Look
import io.github.alalkipgen.autoflow.core.recap.MaskPreset
import io.github.alalkipgen.autoflow.core.recap.MaskStrength
import io.github.alalkipgen.autoflow.core.recap.RecapMask
import io.github.alalkipgen.autoflow.core.recap.RecapVisual
import io.github.alalkipgen.autoflow.core.recap.Recap
import io.github.alalkipgen.autoflow.core.recap.RecapAudio
import io.github.alalkipgen.autoflow.core.recap.RecapMovie
import io.github.alalkipgen.autoflow.core.recap.RecapRules
import io.github.alalkipgen.autoflow.core.recap.RecapScene
import io.github.alalkipgen.autoflow.core.recap.RecapScript
import io.github.alalkipgen.autoflow.core.recap.RecapSegment
import io.github.alalkipgen.autoflow.core.recap.VoiceSource
import io.github.alalkipgen.autoflow.core.recap.VoiceTake
import io.github.alalkipgen.autoflow.media.render.RenderCompiler.ClipMedia
import io.github.alalkipgen.autoflow.media.render.RenderCompiler.Compiled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/specs/recap.md §6: movie clips per segment, takes 0.2 s in, scene runs on, looks, captions, mix. */
class RecapRenderTest {
    private val script = RecapScript(
        source = RecapScript.SOURCE_MANUAL,
        segments = listOf(RecapSegment(10_000, 14_000, "ကောင်လေး"), RecapSegment(30_000, 35_000, "သူငယ်ချင်း")),
    )
    private val take0 = VoiceTake(0, ArtifactId("au_aaaaaaaaaaaa"), VoiceSource.RECORDED, 5_000, RecapRules.textHash("ကောင်လေး"))
    private val recap = Recap(
        rightsConfirmed = true,
        movie = RecapMovie("source/movie.mp4", 9, 120_000),
        scenes = listOf(RecapScene(10_000, 14_000, true), RecapScene(30_000, 35_000, true)),
        script = ArtifactId("rs_aaaaaaaaaaaa"),
        scriptApproved = true,
        takes = listOf(take0),
        looks = listOf(Look.MIRROR, Look.FILL, Look.WARM),
        movieVolume = 0.2,
    )
    private val files = { t: VoiceTake -> "artifacts/${t.audio.value}.m4a" }
    private val env = RenderCompiler.Env(
        resolve = { null }, songPath = null, assPath = "a.ass", fontsDir = "fonts", outputPath = "out.mp4",
        resolveSource = { ClipMedia("/p/$it", hasAudio = true) }, voicePath = { "/p/$it" },
    )

    @Test
    fun soundSettingsPolishTheVoiceDuckTheMusicAndEvenTheLoudness() {
        // Defaults (5B-4): polish + −14 LUFS, no music.
        val plain = RecapRender.graph(recap, script, Ratio.R9_16, Quality.PREVIEW, files, null)
        assertEquals(true, plain.voice?.polish)
        assertEquals(-14.0, plain.voice?.loudnessLufs)
        assertNull(plain.voice?.music)
        val fc = assertIs<Compiled.Ok>(RenderCompiler.compile(plain, env)).let { it.args[it.args.indexOf("-filter_complex") + 1].split(';') }
        assertTrue(fc.any { it.startsWith("[2:a]aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo,highpass=f=80,afftdn") && it.endsWith("adelay=200|200[c0]") }, fc.toString())
        assertTrue("[vbed][c0]amix=inputs=2:duration=first:normalize=0[vo]" in fc, fc.toString())
        assertTrue(fc.last().startsWith("[base][vo]amix=inputs=2:duration=first:normalize=0,atrim=duration=10.400,loudnorm=I=-14"), fc.last())
        assertTrue(fc.last().endsWith("[aout]"))

        // Music, ducked under the voice; loudness off.
        val audio = RecapAudio(polish = false, loudness = false, bgm = ArtifactId("au_bbbbbbbbbbbb"), bgmVolume = 0.1, duck = true)
        val g = RecapRender.graph(recap.copy(audio = audio), script, Ratio.R9_16, Quality.PREVIEW, files, null, bgmFile = "artifacts/au_bbbbbbbbbbbb.mp3")
        assertEquals(MusicBed("artifacts/au_bbbbbbbbbbbb.mp3", 0.1, true), g.voice?.music)
        val r = assertIs<Compiled.Ok>(RenderCompiler.compile(g, env))
        assertEquals(listOf("-i", "/p/artifacts/au_bbbbbbbbbbbb.mp3"), r.args.subList(21, 23))
        val mfc = r.args[r.args.indexOf("-filter_complex") + 1].split(';')
        assertTrue(mfc.any { it.startsWith("[3:a]aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo,aloop=loop=-1") && it.endsWith("volume=0.1,afade=t=in:st=0:d=1.500,afade=t=out:st=8.900:d=1.500[bgm]") }, mfc.toString())
        assertTrue(mfc.any { "sidechaincompress" in it }, mfc.toString())
        assertEquals("[base][vm]amix=inputs=2:duration=first:normalize=0,atrim=duration=10.400[aout]", mfc.last())
        // A missing music file is reported instead of a broken render.
        val missing = RenderCompiler.compile(g, env.copy(voicePath = { f -> "/p/$f".takeIf { "bbbb" !in f } }))
        assertEquals("the background music file artifacts/au_bbbbbbbbbbbb.mp3 is missing", assertIs<Compiled.Invalid>(missing).reason)
    }

    @Test
    fun graphRunsTheSceneOnForALongTakeAndCaptionsTheTake() {
        val g = RecapRender.graph(recap, script, Ratio.R9_16, Quality.PREVIEW, files, "Noto Sans Myanmar")
        // 5 s take + 0.4 s > 4 s scene → 5.4 s clip; segment 2 has no voice → its 5 s scene.
        assertEquals(listOf(10_000L to 15_400L, 30_000L to 35_000L), g.clips.map { it.trimInMs to it.trimOutMs })
        assertTrue(g.clips.all { it.artifact == null && it.source == "source/movie.mp4" })
        assertEquals(listOf(VoiceCue("artifacts/au_aaaaaaaaaaaa.m4a", 200)), g.voice?.cues)
        assertEquals(0.2, g.voice?.clipsVolume)
        assertEquals(listOf(Triple("ကောင်လေး", 200L, 5_200L)), g.overlays.map { Triple(it.text, it.startMs, it.endMs) })
        assertEquals(10_400L, g.durationMs)

        val srt = RecapRender.job(recap, script, Ratio.R9_16, "/p", Quality.PREVIEW, files, null, "fonts", "exports/r", "R").srt
        assertEquals("1\n00:00:00,200 --> 00:00:05,200\nကောင်လေး\n\n", srt)
        assertTrue(RecapRender.graph(recap.copy(burnCaptions = false), script, Ratio.R9_16, Quality.PREVIEW, files, "F").overlays.isEmpty())
    }

    @Test
    fun compilesLooksAndTheNarrationMix() {
        val g = RecapRender.graph(recap.copy(audio = RecapAudio(polish = false, loudness = false)), script, Ratio.R9_16, Quality.PREVIEW, files, null)
        val r = assertIs<Compiled.Ok>(RenderCompiler.compile(g, env))
        val args = r.args
        assertEquals(listOf("-threads", "2", "-ss", "10.000", "-t", "5.400", "-i", "/p/source/movie.mp4"), args.subList(3, 11))
        assertEquals(listOf("-i", "/p/artifacts/au_aaaaaaaaaaaa.m4a"), args.subList(19, 21))
        val fc = args[args.indexOf("-filter_complex") + 1].split(';')
        assertTrue(fc[0].startsWith("[0:v]hflip,scale=540:960:force_original_aspect_ratio=increase,crop=540:960,setsar=1,format=yuv420p,eq=saturation=1.15:contrast=1.05,tpad"), fc[0])
        assertTrue("[aseq]volume=0.2[base]" in fc, fc.toString())
        assertTrue("[2:a]aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo,adelay=200|200[c0]" in fc, fc.toString())
        assertEquals("[base][c0]amix=inputs=2:duration=first:normalize=0,atrim=duration=10.400[aout]", fc.last())

        // Movie sound off: silence under the narration, no clip audio chains.
        val off = assertIs<Compiled.Ok>(RenderCompiler.compile(g.copy(voice = g.voice!!.copy(clipsVolume = 0.0)), env))
        val ofc = off.args[off.args.indexOf("-filter_complex") + 1]
        assertTrue("anullsrc=r=48000:cl=stereo:d=10.400" in ofc && "[a0]" !in ofc, ofc)
        // A missing take file is reported.
        val missing = RenderCompiler.compile(g, env.copy(voicePath = { null }))
        assertEquals("narration 1: the file artifacts/au_aaaaaaaaaaaa.m4a is missing", assertIs<Compiled.Invalid>(missing).reason)
    }

    @Test
    fun pictureBlursTheMovieSubtitlesFillsAroundTheMovieAndPutsCaptionsOnTheBox() {
        val wide = recap.copy(movie = RecapMovie("source/movie.mp4", 9, 120_000, width = 1920, height = 1080), looks = listOf(Look.MIRROR))
        val visual = RecapVisual(
            masks = listOf(RecapMask(MaskPreset.BOTTOM, listOf(0f, 0.8f, 1f, 0.17f), MaskStrength.STRONG)),
            background = FrameBackground.BLUR,
        )
        val g = RecapRender.graph(wide.copy(visual = visual), script, Ratio.R9_16, Quality.PREVIEW, files, "Noto Sans Myanmar")
        assertEquals(visual.masks, g.masks)
        assertEquals(FrameBackground.BLUR, g.background)
        // 16:9 movie in a 540×960 frame: picture 540×304 from y 328; the box centre (0.885) lands at 0.622 of the height.
        val y = assertNotNull(g.overlays.first().centerY)
        assertTrue(y in 0.62f..0.625f, "$y")
        val fc = assertIs<Compiled.Ok>(RenderCompiler.compile(g, env)).let { it.args[it.args.indexOf("-filter_complex") + 1] }
        assertTrue("[0:v]split[mk0_0a][mk0_0b];[mk0_0b]crop=w=iw*1:h=ih*0.17:x=iw*0:y=ih*0.8,gblur=sigma=32[mk0_0c];[mk0_0a][mk0_0c]overlay=x=main_w*0:y=main_h*0.8[mk0_0]" in fc, fc)
        assertTrue("[mk0_0]hflip,split[bf0][bb0];[bb0]scale=134:240:force_original_aspect_ratio=increase,crop=134:240,gblur=sigma=12,scale=540:960,setsar=1[bbl0];" in fc, fc)
        assertTrue("[bbl0][bfs0]overlay=(main_w-overlay_w)/2:(main_h-overlay_h)/2,setsar=1,format=yuv420p,tpad" in fc, fc)
        // The ASS event is centred on the box.
        val ass = assertIs<Compiled.Ok>(RenderCompiler.compile(g, env)).ass.orEmpty()
        assertTrue("\\an5\\pos(270,${(960 * y).toInt()})" in ass, ass)

        // Colour background; captions back at the bottom when turned off; Fill frame crops (no background).
        val colour = RecapRender.graph(wide.copy(visual = visual.copy(background = FrameBackground.COLOUR, backgroundColour = "#2b1640", captionsOnMask = false)), script, Ratio.R9_16, Quality.PREVIEW, files, "Noto Sans Myanmar")
        assertNull(colour.overlays.first().centerY)
        val cfc = assertIs<Compiled.Ok>(RenderCompiler.compile(colour, env)).let { it.args[it.args.indexOf("-filter_complex") + 1] }
        assertTrue("pad=540:960:(ow-iw)/2:(oh-ih)/2:color=0x2B1640,setsar=1" in cfc, cfc)
        val fill = RecapRender.graph(wide.copy(visual = visual, looks = listOf(Look.FILL)), script, Ratio.R9_16, Quality.PREVIEW, files, "Noto Sans Myanmar")
        val ffc = assertIs<Compiled.Ok>(RenderCompiler.compile(fill, env)).let { it.args[it.args.indexOf("-filter_complex") + 1] }
        assertTrue("[mk0_0]scale=540:960:force_original_aspect_ratio=increase,crop=540:960,setsar=1" in ffc, ffc)
        assertTrue("[bf0]" !in ffc)
        // Fill frame on a 16:9 movie in 9:16 crops the box's centre out of view only when it leaves the frame: here it stays.
        assertEquals(RecapRender.captionY(wide.copy(visual = visual, looks = listOf(Look.FILL)), fill.output), fill.overlays.first().centerY)
    }

    @Test
    fun noPictureSettingsKeepTheOldChain() {
        val g = RecapRender.graph(recap, script, Ratio.R9_16, Quality.PREVIEW, files, "Noto Sans Myanmar")
        assertTrue(g.masks.isEmpty())
        assertNull(g.overlays.first().centerY)
        val fc = assertIs<Compiled.Ok>(RenderCompiler.compile(g, env)).let { it.args[it.args.indexOf("-filter_complex") + 1] }
        assertTrue("mk0" !in fc && "bb0" !in fc, fc)
    }

    @Test
    fun captionStyleSetsSizeColourBoxAndLineLength() {
        val style = CaptionStyle(size = CaptionSize.L, colour = "#FFE14D", box = true)
        val g = RecapRender.graph(recap.copy(captionStyle = style), script, Ratio.R9_16, Quality.PREVIEW, files, "Noto Sans Myanmar")
        val o = g.overlays.first()
        assertEquals(36, o.sizePx) // 540 / 18 = 30 × 1.2
        assertEquals("#FFE14D", o.color)
        assertTrue(o.box)
        assertEquals(18, RecapRender.lineChars(g.output, style)) // 22 / 1.2
        assertEquals(26, RecapRender.lineChars(g.output, CaptionStyle(size = CaptionSize.S)))
        val ass = assertIs<Compiled.Ok>(RenderCompiler.compile(g, env)).ass.orEmpty()
        assertTrue("Style: s1,Noto Sans Myanmar,36,&H004DE1FF,&H004DE1FF,&H60000000,&H60000000,0,0,0,0,100,100,0,0,3,6,0,2,0,0,0,1" in ass, ass)
        // Default: white outline as before.
        val plain = RecapRender.graph(recap, script, Ratio.R9_16, Quality.PREVIEW, files, "Noto Sans Myanmar")
        assertEquals(30, plain.overlays.first().sizePx)
        val pass = assertIs<Compiled.Ok>(RenderCompiler.compile(plain, env)).ass.orEmpty()
        assertTrue(",0,0,1,2,0,2,0,0,0,1" in pass, pass)
    }
}
