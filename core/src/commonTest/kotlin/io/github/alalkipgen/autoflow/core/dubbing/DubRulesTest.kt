package io.github.alalkipgen.autoflow.core.dubbing

import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.DubOriginal
import io.github.alalkipgen.autoflow.core.model.DubbingTool
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.ProjectCompat
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.SubtitlesTool
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleCue
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** D-1 (specs/dubbing.md §2–§3, D-83): the Dub script gates — translated lines, the length limit, approval bound to the spoken lines. */
class DubRulesTest {
    private val max = 600_000L
    private val doc = SubtitleDoc(source = "srt", nextId = 4, cues = listOf(
        SubtitleCue("c1", 0, 1_500, "Hello.", textMy = "မင်္ဂလာပါ။"),
        SubtitleCue("c2", 2_000, 4_000, "How are you?", textMy = "နေကောင်းလား။"),
        SubtitleCue("c3", 4_500, 6_000, "Fine.", textMy = "ကောင်းပါတယ်။"),
    ))

    @Test fun spokenFollowsTheSubtitleTextUntilChanged() {
        assertEquals("မင်္ဂလာပါ။", DubRules.spoken(doc.cues[0]))
        val edited = DubRules.setSpoken(doc, "c2", "  နေကောင်းရဲ့လား  ")
        assertEquals("နေကောင်းရဲ့လား", edited.cues[1].spokenMy)
        assertEquals("နေကောင်းရဲ့လား", DubRules.spoken(edited.cues[1]))
        assertEquals("နေကောင်းလား။", edited.cues[1].textMy, "the subtitle text is never changed")
        assertNull(DubRules.setSpoken(edited, "c2", "နေကောင်းလား။").cues[1].spokenMy, "the subtitle text again = follow it")
        assertNull(DubRules.setSpoken(edited, "c2", " ").cues[1].spokenMy, "blank = use the subtitle text")
        assertEquals(edited, DubRules.setSpoken(edited, "c9", "x"))
    }

    @Test fun scriptOpensOnlyWhenEveryLineIsTranslatedAndTheVideoFits() {
        assertNull(DubRules.canOpenScript(60_000, max, doc))
        assertEquals(DubBlock.NO_VIDEO, DubRules.canOpenScript(null, max, doc))
        assertEquals(DubBlock.TOO_LONG, DubRules.canOpenScript(600_001, max, doc))
        assertNull(DubRules.canOpenScript(600_000, max, doc), "exactly the limit is allowed")
        assertEquals(DubBlock.NO_LINES, DubRules.canOpenScript(60_000, max, null))
        assertEquals(DubBlock.NO_LINES, DubRules.canOpenScript(60_000, max, doc.copy(cues = emptyList())))
        val missing = doc.copy(cues = doc.cues.mapIndexed { i, c -> if (i == 1) c.copy(textMy = " ") else c })
        assertEquals(DubBlock.NOT_TRANSLATED, DubRules.canOpenScript(60_000, max, missing))
        assertEquals(listOf("c2"), DubRules.notTranslated(missing))
        assertFalse(DubRules.tooLong(10_000_000, 0), "0 = no limit")
    }

    @Test fun approvalHoldsOnlyForTheSpokenLinesItWasGivenFor() {
        val tool = DubbingTool()
        assertEquals(DubBlock.NOT_APPROVED, DubRules.canSpeak(60_000, max, tool, doc))
        val approved = assertNotNull(DubRules.approve(tool, 60_000, max, doc, at = 1_000))
        assertEquals(1_000, approved.scriptApproved?.at)
        assertTrue(DubRules.isApproved(approved, doc))
        assertNull(DubRules.canSpeak(60_000, max, approved, doc))
        assertNull(DubRules.approve(tool, 60_000, max, doc.copy(cues = doc.cues.map { it.copy(textMy = null) }), 1), "untranslated cannot be approved")

        val spokenChanged = DubRules.setSpoken(doc, "c3", "ကောင်းတယ်")
        assertFalse(DubRules.isApproved(approved, spokenChanged), "editing a spoken line clears the approval")
        assertNull(DubRules.afterEdit(approved, spokenChanged).scriptApproved)
        assertEquals(approved, DubRules.afterEdit(approved, doc), "no change keeps it")
        val subtitleChanged = doc.copy(cues = doc.cues.map { if (it.id == "c1") it.copy(textMy = "ဟယ်လို။") else it })
        assertFalse(DubRules.isApproved(approved, subtitleChanged), "a line following its subtitle text changed too")
        val retimed = doc.copy(cues = doc.cues.map { if (it.id == "c1") it.copy(endMs = 1_400) else it })
        assertFalse(DubRules.isApproved(approved, retimed))
        val originalChanged = doc.copy(cues = doc.cues.map { if (it.id == "c1") it.copy(text = "Hi.") else it })
        assertTrue(DubRules.isApproved(approved, originalChanged), "the original text is not spoken")
    }

    @Test fun dubbingProjectRoundTripsAndOtherProjectsNeverWriteTheKey() {
        val p = Project(id = "id-d", name = "Dub", mode = Mode.DUBBING, createdAt = Instant.parse("2026-11-21T08:00:00Z"),
            updatedAt = Instant.parse("2026-11-21T08:00:00Z"), subtitles = SubtitlesTool(video = SourceVideo("source/movie.mp4", 9, 60_000, 1280, 720)),
            dubbing = DubbingTool(original = DubOriginal(DubOriginal.MUTE, 0.0)))
        val encoded = CoreJson.encodeToString(p)
        assertEquals(p, CoreJson.decodeFromString<Project>(encoded))
        assertFalse(ProjectCompat.madeByNewerApp(encoded.encodeToByteArray()))
        assertFalse("dubbing" in Json.parseToJsonElement(CoreJson.encodeToString(p.copy(dubbing = null))).jsonObject)
        assertEquals(0.15, DubbingTool().original.volume)
        assertEquals(DubOriginal.LOWER, DubbingTool().original.mode)
    }
}
