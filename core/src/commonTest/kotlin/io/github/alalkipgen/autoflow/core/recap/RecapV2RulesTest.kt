package io.github.alalkipgen.autoflow.core.recap

import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.workflow.Blocker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** v2 rules on the story itself (audit F05 / F07): multi-clip blocks, block ids, explicit chosen takes. */
class RecapV2RulesTest {
    private val movie = RecapMovie("source/movie.mp4", 1, durationMs = 10_000, width = 1920, height = 1080)
    private val a = RecapBlock("b_a", listOf(RecapClip(0, 1_000), RecapClip(3_000, 5_000)), "ပထမ")
    private val b = RecapBlock("b_b", listOf(RecapClip(6_000, 8_000)), "ဒုတိယ")
    private val story = RecapStory(source = RecapStory.SOURCE_MANUAL, blocks = listOf(a, b))

    private fun take(block: RecapBlock, audio: String, ms: Long, text: RecapBlock = block) =
        BlockTake(block.id, ArtifactId(audio), TakeSource.TTS, durationMs = ms, spokenHash = SpokenText.hash(text))

    // Picks that match nothing in the story: v2 does not compare blocks with picks (studio-v2 §2).
    private val recap = RecapV2(
        rightsConfirmed = true, movie = movie, story = ArtifactId("rs_aaaaaaaaaaaa"), storyApproved = true,
        scenes = listOf(RecapScene(0, 4_200, picked = true)),
    )

    @Test fun aMultiClipStoryIsCurrentWhileEveryClipIsInsideTheMovie() {
        assertTrue(RecapRulesV2.isCurrent(recap, story))
        assertTrue(RecapRulesV2.canApproveScript(recap, story).allowed)
        assertTrue(RecapRulesV2.canVoice(recap, story).allowed)
        // A new, shorter movie: a clip now lies outside it → outdated (and nothing can be voiced).
        val shorter = recap.copy(movie = movie.copy(durationMs = 4_000))
        assertFalse(RecapRulesV2.isCurrent(shorter, story))
        assertEquals(listOf(Blocker.SCRIPT_OUTDATED), RecapRulesV2.canApproveScript(shorter, story).blockers)
        assertEquals(listOf(Blocker.SCRIPT_OUTDATED), RecapRulesV2.canVoice(shorter, story).blockers)
        assertFalse(RecapRulesV2.isCurrent(recap.copy(movie = null), story))
        assertFalse(RecapRulesV2.isCurrent(recap, story.copy(blocks = listOf(a.copy(clips = emptyList())))))
    }

    @Test fun theChosenTakePlaysEvenWhenANewerOneExists() {
        val old = take(a, "va_old000000000", 1_000)
        val newer = take(a, "va_new000000000", 2_000)
        val r = recap.copy(takes = listOf(old, newer), chosenTakes = mapOf(a.id to old.audio))
        assertEquals(old, RecapRulesV2.currentTake(r, a))
        // No (valid) choice → the documented fallback: the newest.
        assertEquals(newer, RecapRulesV2.currentTake(r.copy(chosenTakes = emptyMap()), a))
        assertEquals(newer, RecapRulesV2.currentTake(r.copy(chosenTakes = mapOf(a.id to ArtifactId("va_gone00000000"))), a))
        // A chosen take for another text is stale: no voice (never silently the newest instead).
        val staleOld = take(a, "va_old000000000", 1_000, text = a.copy(textMy = "အဟောင်း"))
        val s = r.copy(takes = listOf(staleOld, newer))
        assertNull(RecapRulesV2.currentTake(s, a))
        assertTrue(RecapRulesV2.isStale(s, a))
    }

    @Test fun fitRowsAreByBlockIdWithEveryClip() {
        val r = recap.copy(
            takes = listOf(take(b, "va_b00000000000", 2_500)),
            fit = mapOf(b.id to FitChoice.SPEED.name),
        )
        val rows = RecapFit.rows(r, story)
        assertEquals(listOf("b_a", "b_b"), rows.map { it.block })
        assertEquals(3_000, rows[0].sceneMs, "both clips of the block: 1 s + 2 s")
        assertEquals(FitStatus.NO_VOICE, rows[0].status)
        // b's choice is read by its id, not by its position: SPEED fits the take into the 2 s block.
        assertEquals(FitStatus.FITS, rows[1].status)
        assertTrue(rows[1].voiceSpeed > 1f)
        // The same choice stays with b when the blocks are reordered.
        val swapped = RecapFit.rows(r, story.copy(blocks = listOf(b, a)))
        assertEquals("b_b", swapped[0].block)
        assertTrue(swapped[0].voiceSpeed > 1f)
        assertEquals(1f, swapped[1].voiceSpeed)
        assertEquals(FitChoice.SPEED, r.fitChoice(b.id))
        assertNull(r.fitChoice(a.id))
    }

    @Test fun canRenderUsesTheTakeThatPlays() {
        val stale = take(a, "va_old000000000", 1_000, text = a.copy(textMy = "အဟောင်း"))
        assertEquals(listOf(Blocker.NO_VOICE), RecapRulesV2.canRender(recap.copy(takes = listOf(stale)), story).blockers)
        assertTrue(RecapRulesV2.canRender(recap.copy(takes = listOf(take(a, "va_ok0000000000", 900))), story).allowed)
        assertEquals(listOf(Blocker.SCRIPT_NOT_APPROVED), RecapRulesV2.canRender(recap.copy(storyApproved = false), story).blockers)
    }

    @Test fun toV2IsLosslessForTheV1Shape() {
        val script = RecapScript(
            source = RecapScript.SOURCE_MANUAL, style = RecapStyle.COMMENTARY,
            segments = listOf(RecapSegment(0, 4_000, "က"), RecapSegment(5_000, 9_000, "ခ")),
        )
        val v1 = Recap(
            rightsConfirmed = true, movie = movie, script = ArtifactId("rs_aaaaaaaaaaaa"), scriptApproved = true,
            takes = listOf(
                VoiceTake(0, ArtifactId("va_000000000001"), VoiceSource.RECORDED, 1_000, RecapRules.textHash("က")),
                VoiceTake(1, ArtifactId("va_000000000002"), VoiceSource.TTS, 2_000, RecapRules.textHash("ခ")),
                VoiceTake(0, ArtifactId("va_000000000003"), VoiceSource.TTS, 3_000, RecapRules.textHash("က")),
            ),
            fit = mapOf(1 to FitChoice.SLOW_VIDEO),
            movieVolume = 0.5, burnCaptions = true,
        )
        val (v2, st) = v1.toV2(script)
        val ids = script.segments.mapIndexed { i, s -> migratedBlockId(i, s.startMs, s.endMs) }
        assertEquals(ids, st.blocks.map { it.id })
        assertEquals(listOf(listOf(RecapClip(0, 4_000)), listOf(RecapClip(5_000, 9_000))), st.blocks.map { it.clips })
        assertEquals(listOf(ids[0], ids[1], ids[0]), v2.takes.map { it.block })
        // v1 plays a segment's newest take; v2 without a choice too.
        assertEquals(ArtifactId("va_000000000003"), v2.takeFor(ids[0])?.audio)
        assertEquals(mapOf(ids[1] to "SLOW_VIDEO"), v2.fit)
        assertEquals(0.5, v2.audio.movieVolume)
        assertTrue(v2.captions.burn)
        // Same rules either way.
        assertEquals(RecapFit.rows(v1, script).map { it.copy(block = null) }, RecapFit.rows(v2, st).map { it.copy(block = null) })
        // And back: the takes and fit come back by block id.
        val back = v2.toV1(st)
        assertEquals(v1.takes.map { it.segment to it.audio }.toSet(), back.takes.map { it.segment to it.audio }.toSet())
        assertEquals(v1.fit, back.fit)
    }

    @Test fun toV1MapsTakesByBlockIdAndPlaysTheChosenOne() {
        val old = take(a, "va_old000000000", 1_000)
        val newer = take(a, "va_new000000000", 2_000)
        val orphan = BlockTake("b_gone", ArtifactId("va_orphan000000"), TakeSource.TTS, durationMs = 500, spokenHash = "x")
        val r = recap.copy(takes = listOf(orphan, old, newer), chosenTakes = mapOf(a.id to old.audio))
        val v1 = r.toV1(story)
        // The orphan is not mapped to block 0 by its list position.
        assertFalse(v1.takes.any { it.audio == orphan.audio })
        // v1 plays the last take of a segment: that must be the chosen one.
        assertEquals(old.audio, v1.takes.last { it.segment == 0 }.audio)
        assertTrue(r.toV1().takes.isEmpty(), "without the story no take can be placed")
    }
}
