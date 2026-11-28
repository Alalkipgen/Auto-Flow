package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.recap.CaptionSize
import io.github.alalkipgen.autoflow.core.recap.CaptionStyle
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

    /** Owner, 2026-11-21: three long lines covered a landscape dub; at most N lines at once, a long cue in timed parts. */
    @Test
    fun maxLinesShowsALongCueInTimedParts() {
        val text = "မင်းရဲ့ မင်းဆက်က ဒီနိုင်ငံတွေကို စုစည်းပြီး မှောင်မိုက်ခေတ်ကို တားဆီးနိုင်ပါ့မလား။"
        val cues = listOf(SrtCue(10_000, 16_000, text), SrtCue(20_000, 21_000, "Hi"))
        val all = SubtitleBurn.overlays(cues, 1920, 1080, "Sans", BurnLook.BOXED, BurnSize.MEDIUM, 30)
        assertEquals(2, all.size)
        assertTrue(all[0].text.count { it == '\n' } >= 2, "no limit: the whole cue at once, wrapped")
        val one = SubtitleBurn.overlays(cues, 1920, 1080, "Sans", BurnLook.BOXED, BurnSize.MEDIUM, 30, maxLines = 1)
        val parts = one.dropLast(1)
        assertTrue(parts.size >= 3, "${parts.map { it.text }}")
        assertTrue(parts.all { '\n' !in it.text && it.text.length <= 30 })
        assertEquals(10_000L, parts.first().startMs)
        assertEquals(16_000L, parts.last().endMs)
        parts.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs) }
        assertEquals(text.replace(" ", ""), parts.joinToString("") { it.text.replace(" ", "") })
        assertEquals(SrtCueLike(20_000, 21_000, "Hi"), one.last().let { SrtCueLike(it.startMs, it.endMs, it.text) })
        val two = SubtitleBurn.overlays(cues, 1920, 1080, "Sans", BurnLook.BOXED, BurnSize.MEDIUM, 30, maxLines = 2)
        assertTrue(two.dropLast(1).all { it.text.count { c -> c == '\n' } <= 1 })
        assertTrue(two.size < one.size)
        // The same choice in the .ass: one Dialogue per part.
        val ass = SubtitleBurn.ass(cues, 1920, 1080, "Sans", BurnLook.CLEAN, BurnSize.MEDIUM, 30, null, maxLines = 1)
        assertEquals(one.size, ass.lines().count { it.startsWith("Dialogue:") })
    }

    private data class SrtCueLike(val startMs: Long, val endMs: Long, val text: String)

    @Test
    fun autoFitFollowsTheFrame() {
        assertEquals(BurnFit(1, BurnLength.SHORT), BurnFit.auto(1920, 1080))
        assertEquals(BurnFit(1, BurnLength.SHORT), BurnFit.auto(1920, 800))
        assertEquals(BurnFit(2, BurnLength.SHORT), BurnFit.auto(1080, 1920))
        assertEquals(BurnFit(2, BurnLength.SHORT), BurnFit.auto(1080, 1080))
        assertEquals(30, SubtitleBurn.fitChars(1920, 1080, BurnSize.MEDIUM, 30))
        assertTrue(SubtitleBurn.fitChars(1080, 1920, BurnSize.LARGE, 42) < 42)
    }

    /** D-106: Dubbing's caption style; its default (white, Medium, boxed) burns exactly what BOXED / MEDIUM burned before. */
    @Test
    fun captionStyleKeepsTheOldDefaultAndDrawsColourOutlineAndSize() {
        val cues = listOf(SrtCue(1_000, 2_500, "မင်္ဂလာပါ ခင်ဗျာ"), SrtCue(6_000, 7_250, "Bye\nnow"))
        val boxed = CaptionStyle(box = true)
        for ((w, h) in listOf(1920 to 1080, 1080 to 1920, 1280 to 720)) {
            assertEquals(SubtitleBurn.overlays(cues, w, h, "Sans", BurnLook.BOXED, BurnSize.MEDIUM, 30, maxLines = 1),
                SubtitleBurn.overlays(cues, w, h, "Sans", boxed, 30, maxLines = 1))
            assertEquals(SubtitleBurn.ass(cues, w, h, "Sans", BurnLook.BOXED, BurnSize.MEDIUM, 30, 1.1, maxLines = 2),
                SubtitleBurn.ass(cues, w, h, "Sans", boxed, 30, 1.1, maxLines = 2))
        }
        assertEquals(BurnSize.SMALL.share, SubtitleBurn.share(CaptionStyle(size = CaptionSize.S)))
        assertEquals(BurnSize.LARGE.share, SubtitleBurn.share(CaptionStyle(size = CaptionSize.L)))
        assertEquals(BurnSize.MEDIUM.share * 1.4, SubtitleBurn.share(CaptionStyle(size = CaptionSize.L, customSizeFactor = 1.4f)), 1e-6)
        val yellow = SubtitleBurn.overlays(cues, 1280, 720, "Sans", CaptionStyle(colour = "#FFE14D", strokeWidth = 6, strokeColour = "#FF4444"), 30)[0]
        assertEquals("#FFE14D", yellow.color); assertFalse(yellow.box)
        assertEquals(4, yellow.strokeWidth, "6 px at 1080 → 4 px on a 720p video"); assertEquals("#FF4444", yellow.strokeColour)
        assertEquals(40, yellow.sizePx)
        val custom = SubtitleBurn.overlays(cues, 1920, 1080, "Sans", CaptionStyle(customColour = "#123456", box = true, strokeWidth = 3), 30)[0]
        assertEquals("#123456", custom.color); assertTrue(custom.box); assertEquals(0, custom.strokeWidth, "the box has no outline")
        assertEquals(0, SubtitleBurn.overlays(cues, 1920, 1080, "Sans", CaptionStyle(), 30)[0].strokeWidth, "Clean = the automatic thin outline")
        val big = SubtitleBurn.overlays(cues, 1920, 1080, "Sans", CaptionStyle(customSizeFactor = 3f), 42)
        assertTrue(big.all { o -> o.text.split('\n').all { it.length <= SubtitleBurn.fitChars(1920, 1080, BurnSize.LARGE, 42) } }, "a larger text keeps shorter lines")
        val onBand = SubtitleBurn.ass(cues, 1920, 1080, "Sans", CaptionStyle(colour = "#FFE14D", strokeWidth = 3), 30, null, centerY = .885f)
        assertTrue(onBand.lines().filter { it.startsWith("Dialogue:") }.all { "\\an5\\pos(960,955)" in it }, onBand)
        assertTrue("&H004DE1FF" in onBand, "the colour in the style line")
    }
}
