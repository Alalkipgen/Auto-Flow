package io.github.alalkipgen.autoflow.core.recap

import io.github.alalkipgen.autoflow.core.text.BurmeseSyllables
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun chapterTitlesDoNotStopAtAbbreviations() {
        // P9-G2-13 (Group 2 D §5): "Dr. Mann …" used to become the chapter "Dr".
        assertEquals("Dr. Mann lies about the ice planet", PublishKitText.chapterTitle("Dr. Mann lies about the ice planet. Then he runs."))
        assertEquals("Mr. Brand stays behind", PublishKitText.chapterTitle("Mr. Brand stays behind! Cooper leaves."))
        assertEquals("Mrs. Cooper meets Ms. Brand", PublishKitText.chapterTitle("Mrs. Cooper meets Ms. Brand. The end."))
        assertEquals("No. 5 vs. the Jr. team", PublishKitText.chapterTitle("No. 5 vs. the Jr. team. Later"))
        assertEquals("St. Louis and Sr. Cruz", PublishKitText.chapterTitle("St. Louis and Sr. Cruz. Next"))
        assertEquals("J. Smith and the U.S. team", PublishKitText.chapterTitle("J. Smith and the U.S. team. Next"))
        // A "." inside a number or a word is not a sentence end; "..." still is, and so are "။", "?" and "!".
        assertEquals("Version 2.0 is out", PublishKitText.chapterTitle("Version 2.0 is out. Next"))
        assertEquals("Wait", PublishKitText.chapterTitle("Wait... what? Yes"))
        assertEquals("Why", PublishKitText.chapterTitle("Why? Because."))
        assertEquals("Dr. Mann ရဲ့ ရေခဲဂြိုဟ်", PublishKitText.chapterTitle("Dr. Mann ရဲ့ ရေခဲဂြိုဟ်။ ပြီးတော့"))
        // Today's cases are unchanged.
        assertEquals("She wakes up", PublishKitText.chapterTitle("She wakes up. Then she runs!"))
        assertEquals("ကောင်လေး ရထားပေါ်တက်တယ်", PublishKitText.chapterTitle("ကောင်လေး ရထားပေါ်တက်တယ်။ ပြီးတော့ အိပ်ပျော်သွားတယ်။"))
        assertEquals("a".repeat(40) + "…", PublishKitText.chapterTitle("a".repeat(60)))
    }

    @Test
    fun longChapterTitlesAreCutBetweenWordsAndSyllables() {
        // The demo story's block (Group 2 D 23 · 1): whole sentence, cut at a syllable start within 40.
        assertEquals("Dr. Mann ရဲ့ ရေခဲဂြိုဟ်က သူတို့ မျှော်…",
            PublishKitText.chapterTitle("Dr. Mann ရဲ့ ရေခဲဂြိုဟ်က သူတို့ မျှော်လင့်ထားသလို မဟုတ်ခဲ့ဘူး။"))
        // Today: "… ကယ်တင်ဖို…" (the dot below of ဖို့ cut off) and "… စောင့်နေဆဲ ဖ…" (ဖြစ် cut after its consonant).
        assertEquals("ကမ္ဘာမြေအစာငတ်ဘေးကနေလူသားတွေကိုကယ်တင်…",
            PublishKitText.chapterTitle("ကမ္ဘာမြေအစာငတ်ဘေးကနေလူသားတွေကိုကယ်တင်ဖို့အာကာသခရီးကိုစတင်ခဲ့ကြတယ်။"))
        assertEquals("ကမ္ဘာမြေမှာ Murph က အဖေ့ကို စောင့်နေဆဲ…",
            PublishKitText.chapterTitle("ကမ္ဘာမြေမှာ Murph က အဖေ့ကို စောင့်နေဆဲ ဖြစ်ပြီး နာရီကို ကြည့်နေတယ်။"))
        // Burmese then Latin: the cut falls where the script changes, not inside "Cooper".
        assertEquals("ကမ္ဘာမြေအစာငတ်ဘေးကနေလူသားတွေကိုကယ်ဖို့…",
            PublishKitText.chapterTitle("ကမ္ဘာမြေအစာငတ်ဘေးကနေလူသားတွေကိုကယ်ဖို့Cooperရဲ့အာကာသခရီးစတင်ခဲ့တယ်။"))
        // English: at the last space within 40, not inside "wormhole".
        assertEquals("Endurance passes Saturn and enters the…", PublishKitText.chapterTitle("Endurance passes Saturn and enters the wormhole"))
        // Every cut keeps whole syllables: the next character starts one (or is a space).
        val long = "ရေခဲဂြိုဟ်ပေါ်မှာ အမှန်တရားကို ဖုံးကွယ်ထားခဲ့တယ်လို့ Brand က သိသွားတော့ အရမ်းစိတ်ဆိုးသွားတယ်"
        for (max in 10..60) {
            val cut = PublishKitText.chapterTitle(long, max)
            if (!cut.endsWith("…")) continue
            val kept = cut.removeSuffix("…")
            assertTrue(kept.length <= max && long.startsWith(kept), "$max: $cut")
            val next = long.substring(kept.length).first()
            assertTrue(next.isWhitespace() || BurmeseSyllables.startsAt(long, kept.length) || !BurmeseSyllables.isBurmese(next), "$max: $cut")
        }
    }
}
