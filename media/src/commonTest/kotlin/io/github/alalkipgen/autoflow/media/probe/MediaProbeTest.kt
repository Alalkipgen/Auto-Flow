package io.github.alalkipgen.autoflow.media.probe

import io.github.alalkipgen.autoflow.media.tool.FakeToolRunner
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class MediaProbeTest {
    // Shape of ffprobe 7.0 output for MediaProbe.args (a Flow-like clip with sound).
    private val clip = """
        {
            "programs": [ ],
            "streams": [
                { "index": 0, "codec_name": "h264", "codec_type": "video", "width": 1080, "height": 1920,
                  "r_frame_rate": "24/1", "avg_frame_rate": "24/1", "duration": "8.000000" },
                { "index": 1, "codec_name": "aac", "codec_type": "audio", "r_frame_rate": "0/0", "avg_frame_rate": "0/0", "duration": "8.021333" }
            ],
            "format": { "duration": "8.021333" }
        }
    """.trimIndent()

    @Test
    fun readsLengthSizeRateAndCodecs() {
        assertEquals(MediaInfo(8_021, 1080, 1920, 24.0, "h264", "aac"), MediaProbe.parse(clip))
    }

    @Test
    fun rotatedPhoneVideoReportsTheDisplayedSize() {
        val json = """{"streams":[{"codec_type":"video","codec_name":"hevc","width":1920,"height":1080,"avg_frame_rate":"30000/1001",
            "side_data_list":[{"rotation":-90}]}],"format":{"duration":"3.5"}}"""
        val info = MediaProbe.parse("[mov @ 0x1] stray warning\n$json\n")!!
        assertEquals(listOf(1080, 1920), listOf(info.width, info.height))
        assertEquals(29.97, info.fps!!, 0.001)
        assertEquals(false, info.hasAudio)
        val old = """{"streams":[{"codec_type":"video","codec_name":"h264","width":640,"height":480,"tags":{"rotate":"270"}}],"format":{}}"""
        assertEquals(listOf(480, 640), MediaProbe.parse(old)!!.let { listOf(it.width, it.height) })
    }

    @Test
    fun audioOnlyAndJunk() {
        val mp3 = MediaProbe.parse("""{"streams":[{"codec_type":"audio","codec_name":"mp3"}],"format":{"duration":"20.0"}}""")!!
        assertEquals(MediaInfo(20_000, null, null, null, null, "mp3"), mp3)
        assertNull(MediaProbe.parse("not json"))
        assertNull(MediaProbe.parse("""{"streams":[],"format":{"duration":"1"}}"""))
    }

    @Test
    fun probeRunsFfprobeAndReportsFailures() = runTest {
        val ok = FakeToolRunner { ToolExec.Done(0, clip, emptyList(), 5, false) }
        assertEquals(MediaProbe.Result.Ok(MediaProbe.parse(clip)!!), MediaProbe(ok, "ffprobe").probe("a.mp4", "/w"))
        assertEquals(listOf("ffprobe") + MediaProbe.args("a.mp4"), ok.commands.single())
        val missing = FakeToolRunner { ToolExec.Done(1, "", listOf("a.mp4: No such file or directory"), 5, false) }
        assertEquals("a.mp4: No such file or directory", assertIs<MediaProbe.Result.Failed>(MediaProbe(missing, "ffprobe").probe("a.mp4", "/w")).reason)
    }

    @Test
    fun frameGrabCommands() = runTest {
        val tools = FakeToolRunner()
        assertEquals(FrameGrab.Result.Ok, FrameGrab(tools, "ffmpeg").last("v.mp4", "end.png", "/w"))
        assertEquals(FrameGrab.Result.Ok, FrameGrab(tools, "ffmpeg").at("v.mp4", 4_250, "t.png", "/w"))
        assertEquals(listOf("ffmpeg", "-hide_banner", "-y", "-nostdin", "-sseof", "-1", "-i", "v.mp4", "-map", "0:v:0", "-fps_mode", "passthrough", "-update", "1", "end.png"), tools.commands[0])
        assertEquals(listOf("-ss", "4.250"), tools.commands[1].subList(4, 6))
        val bad = FakeToolRunner { ToolExec.Done(1, "", listOf("v.mp4: Invalid data found when processing input"), 5, false) }
        assertIs<FrameGrab.Result.Failed>(FrameGrab(bad, "ffmpeg").last("v.mp4", "e.png", "/w"))
    }
}
