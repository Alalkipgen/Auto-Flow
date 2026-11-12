package io.github.alalkipgen.autoflow.media.audio

import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class SongAudioTest {
    @Test
    fun decodesToMonoPcmWavAndReportsFailures() = runTest {
        val seen = mutableListOf<List<String>>()
        var exec: ToolExec = ToolExec.Done(0, "", emptyList(), 10, timedOut = false)
        val runner = ToolRunner { command, _, _, _, _ -> seen += command; exec }
        assertNull(SongAudio.decodeMonoWav(runner, "ffmpeg", "source/song.mp3", ".cache/a.wav", "/p", 16_000))
        assertEquals(
            listOf("ffmpeg", "-hide_banner", "-nostdin", "-y", "-i", "source/song.mp3", "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", "-f", "wav", ".cache/a.wav"),
            seen.single(),
        )
        exec = ToolExec.Done(1, "", listOf("bad input", ""), 10, timedOut = false)
        assertEquals("bad input", SongAudio.decodeMonoWav(runner, "ffmpeg", "x", "y", "/p", 16_000))
        exec = ToolExec.CannotStart("missing")
        assertEquals("ffmpeg cannot start: missing", SongAudio.decodeMonoWav(runner, "ffmpeg", "x", "y", "/p", 16_000))
    }
}
