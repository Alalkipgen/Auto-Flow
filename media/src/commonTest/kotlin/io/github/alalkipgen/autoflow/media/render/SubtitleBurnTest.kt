package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.recap.SrtCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** S-4c: the burn is one fixed ffmpeg command; cues keep their own times; long lines break, never mid-syllable. */
class SubtitleBurnTest {
    @Test
    fun argsKeepPictureAndCopySound() {
        assertEquals(
            listOf("-y", "-nostdin", "-i", "source/movie.mp4", "-vf", "ass=filename=.cache/burn/a.ass:fontsdir=.cache/fonts",
                "-map", "0:v:0", "-map", "0:a:0?", "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p",
                "-c:a", "copy", "-movflags", "+faststart", "-f", "mp4", MediaTaskJob.PART),
            SubtitleBurn.args("source/movie.mp4", ".cache/burn/a.ass", ".cache/fonts", "aac", "veryfast", 20))
        val opus = SubtitleBurn.args("source/movie.mkv", ".cache/burn/a.ass", ".cache/fonts", "opus", "veryfast", 20)
        assertEquals(listOf("-c:a", "aac", "-b:a", "192k"), opus.subList(opus.indexOf("-c:a"), opus.indexOf("-c:a") + 4))
        assertTrue(SubtitleBurn.args("s.mp4", "a.ass", "f", null, "fast", 18).containsAll(listOf("-c:a", "copy")))
        assertTrue(SubtitleBurn.copiesSound("AAC") && SubtitleBurn.copiesSound("mp3") && !SubtitleBurn.copiesSound("pcm_s16le"))
        // A Windows path or a quote survives the filter parser.
        assertTrue(SubtitleBurn.args("s.mp4", "C:\\p\\it's.ass", "f", null, "fast", 18)[5].startsWith("ass=filename=C\\\\:/p/it\\\\\\'s.ass"))
    }

    @Test
    fun frameArgsSeekWithCueTimes() {
        assertEquals(listOf("-y", "-nostdin", "-ss", "12.345", "-copyts", "-i", "s.mp4", "-vf", "ass=filename=a.ass:fontsdir=f",
            "-frames:v", "1", "-update", "1", "frame.png"), SubtitleBurn.frameArgs("s.mp4", "a.ass", "f", 12_345, "frame.png"))
    }

    @Test
    fun overlaysKeepTimesAndLook() {
        val cues = listOf(SrtCue(1_000, 2_500, "Hello"), SrtCue(3_000, 3_000, "zero"), SrtCue(4_000, 5_000, "  "), SrtCue(6_000, 7_250, "Bye\nnow"))
        val clean = SubtitleBurn.overlays(cues, 1920, 1080, "Sans", BurnLook.CLEAN, BurnSize.MEDIUM, 42)
        assertEquals(listOf(1_000L to 2_500L, 6_000L to 7_250L), clean.map { it.startMs to it.endMs })
        assertEquals(listOf("Hello", "Bye\nnow"), clean.map { it.text })
        assertEquals(59, clean[0].sizePx)
        assertFalse(clean[0].box); assertEquals(0, clean[0].strokeWidth)
        val boxed = SubtitleBurn.overlays(cues, 1920, 1080, "Sans", BurnLook.BOXED, BurnSize.SMALL, 42)[0]
        assertTrue(boxed.box); assertEquals(49, boxed.sizePx)
        val outline = SubtitleBurn.overlays(cues, 1920, 1080, "Sans", BurnLook.OUTLINE, BurnSize.LARGE, 42)[0]
        assertFalse(outline.box); assertEquals(76, outline.sizePx); assertEquals(7, outline.strokeWidth)
    }

    @Test
    fun longLinesBreakToFitTheWidth() {
        val long = "The quick brown fox jumps over the lazy dog and keeps on running far away"
        val wide = SubtitleBurn.overlays(listOf(SrtCue(0, 1_000, long)), 1920, 1080, "Sans", BurnLook.CLEAN, BurnSize.MEDIUM, 42)[0]
        assertTrue(wide.text.lines().size >= 2 && wide.text.lines().all { it.length <= 42 }, wide.text)
        assertEquals(long.split(' '), wide.text.split('\n', ' '))
        // Tall and narrow (9:16): fewer characters fit than the 42 maximum.
        val narrow = SubtitleBurn.overlays(listOf(SrtCue(0, 1_000, long)), 720, 1280, "Sans", BurnLook.CLEAN, BurnSize.LARGE, 42)[0]
        assertTrue(narrow.text.lines().size >= 4 && narrow.text.lines().all { it.length <= 12 }, narrow.text)
        // Burmese breaks between syllables, never inside one (a vowel sign or asat never starts a line).
        val my = "မင်္ဂလာပါ ခင်ဗျား ဒီနေ့ ရာသီဥတု အရမ်းကောင်းတယ် နော် ဒါကြောင့် အပြင်ထွက်ကြရအောင်"
        val mine = SubtitleBurn.overlays(listOf(SrtCue(0, 1_000, my)), 1080, 1920, "Pyidaungsu", BurnLook.CLEAN, BurnSize.MEDIUM, 42)[0]
        assertTrue(mine.text.lines().size >= 2, mine.text)
        assertTrue(mine.text.lines().drop(1).none { it.first() in '\u102B'..'\u103E' }, mine.text)
        assertEquals(my.replace(" ", ""), mine.text.replace("\n", "").replace(" ", ""))
    }

    @Test
    fun assUsesTheSourceSize() {
        val ass = SubtitleBurn.ass(listOf(SrtCue(1_000, 2_500, "Hi")), 1920, 800, "Sans", BurnLook.CLEAN, BurnSize.MEDIUM, 42, null)
        assertTrue("PlayResX: 1920" in ass && "PlayResY: 800" in ass, ass)
        assertTrue("0:00:01.00,0:00:02.50" in ass, ass)
    }
}
