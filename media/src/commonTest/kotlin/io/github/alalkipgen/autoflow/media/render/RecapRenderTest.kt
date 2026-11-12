package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.Look
import io.github.alalkipgen.autoflow.core.recap.Recap
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
        val g = RecapRender.graph(recap, script, Ratio.R9_16, Quality.PREVIEW, files, null)
        val env = RenderCompiler.Env(
            resolve = { null }, songPath = null, assPath = "a.ass", fontsDir = "fonts", outputPath = "out.mp4",
            resolveSource = { ClipMedia("/p/$it", hasAudio = true) }, voicePath = { "/p/$it" },
        )
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
}
