package io.github.alalkipgen.autoflow.core.recap

import kotlin.test.Test
import kotlin.test.assertEquals

/** Publish Kit text (5B-10, studio-v2 §9). */
class PublishKitTest {
    @Test
    fun blockStartsComeFromBlockLengths() {
        assertEquals(listOf(0L, 4_200L, 9_000L), PublishKitText.blockStartsMs(listOf(4_200, 4_800, 3_000)))
        assertEquals(emptyList<Long>(), PublishKitText.blockStartsMs(emptyList()))
    }

    @Test
    fun timestampsUseYoutubeFormat() {
        assertEquals("0:00", PublishKitText.timestamp(0))
        assertEquals("0:09", PublishKitText.timestamp(9_999))
        assertEquals("12:05", PublishKitText.timestamp(725_000))
        assertEquals("1:02:03", PublishKitText.timestamp(3_723_000))
    }

    @Test
    fun hashtagsAreNormalised() {
        assertEquals(listOf("#movierecap", "#ရုပ်ရှင်", "#NightTrain"),
            PublishKitText.hashtags(listOf(" movierecap", "#ရုပ်ရှင်", "Night Train", "", "#movierecap")))
    }

    @Test
    fun chaptersAreSortedAndStartAtZero() {
        val ch = PublishKitText.chapters(listOf(9_000, 2_000, 65_000), listOf("ဒုတိယ", "အစ", " "))
        assertEquals(listOf("0:00 အစ", "0:09 ဒုတိယ"), PublishKitText.chapterLines(ch))
    }

    @Test
    fun fileHasEveryItemUnderItsHeading() {
        val kit = PublishKit(
            title = " ညရထား ",
            description = "ရုပ်ရှင် အကျဉ်း။",
            hashtags = listOf("recap"),
            chapters = listOf(PublishChapter(0, "အစ"), PublishChapter(61_000, "အဆုံး")),
        )
        assertEquals(
            "# Title\nညရထား\n\n# Description\nရုပ်ရှင် အကျဉ်း။\n\n# Chapters\n0:00 အစ\n1:01 အဆုံး\n\n# Hashtags\n#recap\n",
            PublishKitText.file(kit),
        )
        assertEquals("ရုပ်ရှင် အကျဉ်း။\n\n0:00 အစ\n1:01 အဆုံး\n\n#recap", PublishKitText.description(kit))
        assertEquals("exports/night-train.publish.txt", PublishKitText.path("night-train"))
    }

    @Test
    fun chaptersComeFromTheBlocksWhenTheKitHasNone() {
        val auto = PublishKitText.chaptersFromBlocks(
            listOf(4_200, 65_000, 3_000),
            listOf("ကောင်လေး ရထားပေါ်တက်တယ်။ ပြီးတော့ အိပ်ပျော်သွားတယ်။", "  \nShe wakes up. Then she runs!", ""),
        )
        // Starts in the export, the first sentence of each block; a block without text gets no chapter.
        assertEquals(listOf(PublishChapter(0, "ကောင်လေး ရထားပေါ်တက်တယ်"), PublishChapter(4_200, "She wakes up")), auto)
        val own = PublishKit(chapters = listOf(PublishChapter(0, "Mine")))
        assertEquals(own, PublishKitText.withChapters(own, auto), "the kit's own chapters win")
        assertEquals(auto, PublishKitText.withChapters(PublishKit(), auto).chapters)
        assertEquals("0:00 ကောင်လေး ရထားပေါ်တက်တယ်\n0:04 She wakes up", PublishKitText.chapterLines(auto).joinToString("\n"))
    }

    @Test
    fun longChapterTitlesAreCut() {
        assertEquals("a".repeat(40) + "…", PublishKitText.chapterTitle("a".repeat(60)))
        assertEquals("short", PublishKitText.chapterTitle("short"))
        assertEquals("", PublishKitText.chapterTitle("   "))
        assertEquals("exports/export.thumb.jpg", PublishKitText.thumbPath("export"))
    }
}
