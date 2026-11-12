package io.github.alalkipgen.autoflow.core.recap

import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.readTestResource
import io.github.alalkipgen.autoflow.core.workflow.Blocker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class RecapTest {
    @Test
    fun recapJsonKeepsEveryKey() {
        val fixture = readTestResource("fixtures/recap-v1.json")
        val r = CoreJson.decodeFromString(Recap.serializer(), fixture)
        assertEquals(listOf(Look.ZOOM, Look.MIRROR), r.looks)
        assertEquals(Json.parseToJsonElement(fixture), Json.parseToJsonElement(CoreJson.encodeToString(Recap.serializer(), r)))
    }

    @Test
    fun cutsBecomeScenesWithShortOnesMergedAndLongOnesSplit() {
        // 0–500 is short (joins the next), 500–3000, 3000–3400 short (joins previous), 3400–50000 long (3 parts).
        val s = Scenes.fromCuts(listOf(3000, 500, 3400, 99_999), 50_000)
        assertEquals(listOf(0L to 3400L, 3400L to 18_933L, 18_933L to 34_466L, 34_466L to 50_000L), s.map { it.startMs to it.endMs })
        assertTrue(s.all { it.durationMs <= Scenes.MAX_MS })
    }

    @Test
    fun refindingKeepsPicksThatStartAtTheSameTime() {
        val old = listOf(RecapScene(0, 2000, picked = true), RecapScene(2000, 5000, picked = true), RecapScene(5000, 9000))
        val s = Scenes.fromCuts(listOf(2000, 6000), 9000, old)
        assertEquals(listOf(true, true, false), s.map { it.picked })
    }

    @Test
    fun noDurationNoScenes() = assertTrue(Scenes.fromCuts(listOf(100), 0).isEmpty())

    @Test
    fun readsScdetLogLines() {
        assertEquals(12_345L, Scenes.cutMs("[scdet @ 0x7f] lavfi.scd.score: 23.110, lavfi.scd.time: 12.345"))
        assertNull(Scenes.cutMs("frame=  100 fps= 50 time=00:00:04.00"))
    }

    @Test
    fun offTargetIsTwentyPercent() {
        val base = Recap(targetMs = 100_000)
        assertFalse(Scenes.offTarget(base))
        assertFalse(Scenes.offTarget(base.copy(scenes = listOf(RecapScene(0, 110_000, true)))))
        assertTrue(Scenes.offTarget(base.copy(scenes = listOf(RecapScene(0, 130_000, true)))))
        assertTrue(Scenes.offTarget(base.copy(scenes = listOf(RecapScene(0, 70_000, true)))))
    }

    @Test
    fun srtRoundTripAndLenientRead() {
        val text = "\uFEFF1\r\n00:00:01,000 --> 00:00:02,500\r\n<i>Hello</i>\r\nthere\r\n\r\n2\r\n00:01:00.2 --> 00:01:03,000\r\nမင်္ဂလာပါ\r\n\r\nbroken\r\n"
        val cues = Srt.parse(text)
        assertEquals(listOf(SrtCue(1000, 2500, "Hello\nthere"), SrtCue(60_200, 63_000, "မင်္ဂလာပါ")), cues)
        assertEquals(cues, Srt.parse(Srt.write(cues)))
        assertEquals("1\n00:00:01,000 --> 00:00:02,500\nHello\nthere\n\n", Srt.write(cues.take(1)))
        assertEquals("Hello there", Srt.textIn(cues, 0, 3000, 100))
        assertEquals("Hello…", Srt.textIn(cues, 0, 3000, 6))
    }

    private val picked = listOf(RecapScene(0, 4000, true), RecapScene(4000, 6000), RecapScene(6000, 9000, true))
    private val recap = Recap(rightsConfirmed = true, movie = RecapMovie("source/movie.mp4", 1, 9000), scenes = picked)
    private val script = RecapScript(source = RecapScript.SOURCE_MANUAL, segments = listOf(RecapSegment(0, 4000, "က"), RecapSegment(6000, 9000, "ခ")))

    @Test
    fun scriptGates() {
        assertEquals(listOf(Blocker.NO_RIGHTS), RecapRules.canImportMovie(Recap()).blockers)
        assertEquals(listOf(Blocker.NO_MOVIE), RecapRules.canFindScenes(Recap(rightsConfirmed = true)).blockers)
        assertEquals(listOf(Blocker.NO_SCRIPT_SOURCE), RecapRules.canGenerateScript(recap, " ", "").blockers)
        assertTrue(RecapRules.canGenerateScript(recap, "", "1\n00:00:01,000 --> 00:00:02,000\nx").allowed)
        assertTrue(RecapRules.canApproveScript(recap, script).allowed)
        val outdated = recap.copy(scenes = picked.map { it.copy(picked = true) })
        assertEquals(listOf(Blocker.SCRIPT_OUTDATED), RecapRules.canApproveScript(outdated, script).blockers)
        val blank = script.copy(segments = script.segments.mapIndexed { i, s -> if (i == 1) s.copy(textMy = " ") else s })
        assertEquals(listOf(Blocker.BLANK_SEGMENT), RecapRules.canApproveScript(recap, blank).blockers)
        assertEquals(listOf(Blocker.SCRIPT_NOT_APPROVED), RecapRules.canVoice(recap, script).blockers)
        assertEquals(listOf(Blocker.NO_VOICE), RecapRules.canRender(recap.copy(scriptApproved = true), script).blockers)
    }

    @Test
    fun takesGoStaleWhenTheTextChangesAndFitExtendsLongTakes() {
        val take = VoiceTake(0, ArtifactId("au_000000000001"), VoiceSource.RECORDED, 5000, RecapRules.textHash("က"))
        val r = RecapRules.withTake(recap.copy(scriptApproved = true), take)
        assertTrue(RecapRules.canRender(r, script).allowed)
        val rows = RecapFit.rows(r, script)
        assertEquals(FitStatus.RUNS_ON, rows[0].status)
        assertEquals(5400L, rows[0].clipMs)
        assertEquals(1400L, rows[0].overMs)
        assertEquals(FitStatus.NO_VOICE, rows[1].status)
        assertEquals(5400L + 3000L, RecapFit.totalMs(rows))
        val edited = script.copy(segments = listOf(RecapSegment(0, 4000, "ကက"), script.segments[1]))
        assertTrue(RecapRules.isStale(r, edited, 0))
        assertEquals(FitStatus.STALE, RecapFit.rows(r, edited)[0].status)
        assertEquals(listOf(Blocker.NO_VOICE), RecapRules.canRender(r, edited).blockers)
        val short = RecapRules.withTake(r, take.copy(durationMs = 1000))
        assertEquals(FitStatus.FITS, RecapFit.rows(short, script)[0].status)
        assertEquals(1, short.takes.size)
    }
}
