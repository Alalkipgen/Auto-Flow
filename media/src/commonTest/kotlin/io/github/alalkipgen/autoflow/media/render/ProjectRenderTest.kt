package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.lyrics.LyricLine
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.model.Shot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ProjectRenderTest {
    private val v1 = ArtifactId("vd_111111111111")
    private val v3 = ArtifactId("vd_333333333333")
    private val gone = ArtifactId("vd_999999999999")
    private val onDisk = mapOf(v1 to "artifacts/vd_111111111111.mp4", v3 to "artifacts/vd_333333333333.mp4")
    private val font = "Noto Sans Myanmar"

    private fun project(ratio: Ratio = Ratio.R9_16) = Project(
        id = "p1", name = "Rain", mode = Mode.MUSIC_VIDEO,
        createdAt = Instant.parse("2026-11-11T08:00:00Z"), updatedAt = Instant.parse("2026-11-11T08:00:00Z"),
        settings = ProjectSettings(ratio = ratio, durationMs = 21_000),
        shots = listOf(
            Shot(3, 16_000, 5_000, lyricsMy = "နောက်ဆုံး", acceptedVideo = v3),
            Shot(1, 0, 8_000, lyricsMy = "မိုးတွေရွာနေတယ်\n\n  ည  ", acceptedVideo = v1),
            Shot(2, 8_000, 8_000, lyricsMy = "", videos = listOf(gone)),
            Shot(4, 21_000, 0, lyricsMy = "zero length"),
        ),
    )

    @Test
    fun shotsBecomeClipsInOrderWithPlaceholdersSongAndLyrics() {
        val g = ProjectRender.graph(project(), Quality.EXPORT, "source/song.mp3", { onDisk[it] }, font)
        assertEquals(
            listOf(
                Clip(v1, trimOutMs = 8_000),
                Clip(null, trimOutMs = 8_000, label = "#02 — pending"),
                Clip(v3, trimOutMs = 5_000),
            ),
            g.clips,
        )
        assertEquals(21_000, g.durationMs)
        assertEquals(AudioTrack("source/song.mp3"), g.audio)
        assertTrue(g.audio!!.muteClips)
        assertEquals(RenderPresets.export(Ratio.R9_16), g.output)
        assertEquals(
            listOf(
                Overlay("မိုးတွေရွာနေတယ်\nည", 0, 8_000, font, 72, anim = Anim.FADE),
                Overlay("နောက်ဆုံး", 16_000, 21_000, font, 72, anim = Anim.FADE),
            ),
            g.overlays,
        )
    }

    @Test
    fun syncedTimingsGiveOneOverlayPerLineCutAtTheEnd() {
        val timings = listOf(
            LyricLine("မိုးတွေ", 1_200, 4_000),
            LyricLine("ရွာနေတယ်", 4_000, 4_000),
            LyricLine("ည", 9_500, 15_000),
            LyricLine("နောက်ဆုံး", 19_000, 25_000),
            LyricLine("after the video", 21_000, 23_000),
        )
        val g = ProjectRender.graph(project(), Quality.EXPORT, null, { onDisk[it] }, font, timings)
        assertEquals(
            listOf(
                Overlay("မိုးတွေ", 1_200, 4_000, font, 72, anim = Anim.FADE),
                Overlay("ည", 9_500, 15_000, font, 72, anim = Anim.FADE),
                Overlay("နောက်ဆုံး", 19_000, 21_000, font, 72, anim = Anim.FADE),
            ),
            g.overlays,
            "per-line times replace per-shot lyrics; empty spans and lines after the end are dropped",
        )
        assertEquals(emptyList(), ProjectRender.graph(project(), Quality.EXPORT, null, { onDisk[it] }, null, timings).overlays)
        assertEquals(2, ProjectRender.graph(project(), Quality.EXPORT, null, { onDisk[it] }, font, emptyList()).overlays.size, "no lines → per shot")
    }

    @Test
    fun missingFilesFontAndSongDegradeGracefully() {
        val p = project().let { it.copy(shots = it.shots.map { s -> if (s.number == 1) s.copy(acceptedVideo = gone) else s }) }
        val g = ProjectRender.graph(p, Quality.PREVIEW, null, { onDisk[it] }, font = null)
        assertEquals(listOf(null, null, v3), g.clips.map { it.artifact })
        assertEquals("#01 — pending", g.clips[0].label)
        assertNull(g.audio)
        assertEquals(emptyList(), g.overlays, "no font → no lyric overlays")
        assertEquals(540, g.output.width)
    }

    @Test
    fun jobListsOnlyTheVideosItUsesAndCompiles() {
        val job = ProjectRender.job(
            project(), "/p/Rain.autoflow", Quality.PREVIEW, "source/song.mp3", { onDisk[it] }, font,
            fontsDir = ".cache/fonts", outputName = "exports/rain-preview", title = "Rain · Preview",
        )
        assertEquals(mapOf(v1.value to onDisk[v1], v3.value to onDisk[v3]), job.files)
        assertEquals("source/song.mp3", job.songPath)
        assertEquals(font, job.placeholderFont)
        assertEquals(36, job.graph.overlays.first().sizePx)
        val env = RenderCompiler.Env(
            resolve = { id -> job.files[id.value]?.let { RenderCompiler.ClipMedia(it, hasAudio = true) } },
            songPath = job.songPath,
            assPath = "exports/rain-preview.ass",
            fontsDir = job.fontsDir,
            outputPath = "exports/rain-preview.part.mp4",
            placeholderFont = job.placeholderFont,
        )
        val ok = assertIs<RenderCompiler.Compiled.Ok>(RenderCompiler.compile(job.graph, env))
        assertEquals(21_000, ok.durationMs)
        assertTrue(ok.ass!!.contains("#02 — pending"))
        assertTrue(ok.ass.contains("နောက်ဆုံး"))
    }

    @Test
    fun lyricSizeAndLineLengthFollowTheOutput() {
        assertEquals(72, ProjectRender.lyricSizePx(RenderPresets.export(Ratio.R16_9)))
        assertEquals(22, ProjectRender.lineChars(RenderPresets.export(Ratio.R9_16)))
        assertEquals(28, ProjectRender.lineChars(RenderPresets.export(Ratio.R1_1)))
        assertEquals(40, ProjectRender.lineChars(RenderPresets.export(Ratio.R16_9)))
    }

    @Test
    fun wrapBreaksLongLinesAtSpacesOnly() {
        assertEquals("one two\nthree four\nfive", ProjectRender.wrap("one two three four five", 10))
        val burmese = "မိုးတွေရွာနေတယ်မိုးတွေရွာနေတယ်မိုးတွေရွာနေတယ်"
        assertEquals(burmese, ProjectRender.wrap(burmese, 10), "no spaces → never split inside the text")
        assertEquals("a\nb", ProjectRender.wrap(" a \n\n b ", 10))
        assertEquals("", ProjectRender.wrap(" \n ", 10))
    }
}
