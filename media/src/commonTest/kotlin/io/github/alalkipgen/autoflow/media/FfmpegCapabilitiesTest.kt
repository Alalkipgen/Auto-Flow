package io.github.alalkipgen.autoflow.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FfmpegCapabilitiesTest {
    private val version = """
        ffmpeg version 9.0.2 Copyright (c) 2000-2026 the FFmpeg developers
        built with Android (13989888, +pgo, +bolt, +lto, +mlgo, based on r563880c) clang version 21.0.0
        configuration: --target-os=android --arch=aarch64 --enable-gpl --enable-libx264 --enable-libass
        libavutil      60. 20.100 / 60. 20.100
    """.trimIndent()

    private val filters = """
        Filters:
          T.. = Timeline support
          .S. = Slice threading
          A = Audio input/output
          V = Video input/output
          N = Dynamic number and/or type of input/output
          | = Source or sink filter
         ... amix              N->A       Audio mixing.
         T.. ass               V->V       Render ASS subtitles onto input video using the libass library.
         TS. scale             V->V       Scale the input video size and/or convert the image format.
         .S. xfade             VV->V      Cross fade one video with another video.
         ... nullsrc           |->V       Null video source, return unprocessed video frames.
    """.trimIndent()

    private val encoders = """
        Encoders:
         V..... = Video
         A..... = Audio
         S..... = Subtitle
         ------
         V....D libx264              libx264 H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10 (codec h264)
         V....D png                  PNG (Portable Network Graphics) image
         A....D aac                  AAC (Advanced Audio Coding)
    """.trimIndent()

    @Test
    fun parsesVersionFiltersAndEncoders() {
        val c = FfmpegCapabilities.parse(version, filters, encoders)
        assertEquals("9.0.2", c.version)
        assertTrue(c.configuration!!.contains("--enable-libass"))
        assertEquals(setOf("amix", "ass", "scale", "xfade", "nullsrc"), c.filters)
        assertEquals(setOf("libx264", "png", "aac"), c.encoders)
        assertTrue(c.hasAss && c.hasXfade && c.hasLibx264 && c.hasAac)
        assertEquals(OverlayBackend.LIBASS, c.overlayBackend)
    }

    @Test
    fun withoutLibassTheOverlayBackendIsPng() {
        val c = FfmpegCapabilities.parse(version, filters.lines().filterNot { "ass " in it }.joinToString("\n"), "")
        assertFalse(c.hasAss)
        assertTrue(c.encoders.isEmpty())
        assertEquals(OverlayBackend.PNG_OVERLAY, c.overlayBackend)
    }

    @Test
    fun unknownOutputGivesEmptyCapabilities() {
        val c = FfmpegCapabilities.parse("sh: ffmpeg: not found", "", "garbage")
        assertNull(c.version)
        assertNull(c.configuration)
        assertTrue(c.filters.isEmpty() && c.encoders.isEmpty())
    }

    @Test
    fun benchmarkMaxRssTakesTheLastValue() {
        val err = "frame= 384 fps=40\nbench: utime=1.2s stime=0.1s rtime=3.4s\nbench: maxrss=183456KiB\n"
        assertEquals(183456L, FfmpegBenchmark.maxRssKb(err))
        assertEquals(1000L, FfmpegBenchmark.maxRssKb("bench: maxrss=1000kB"))
        assertNull(FfmpegBenchmark.maxRssKb("no benchmark here"))
    }
}
