package io.github.alalkipgen.autoflow.core.workflow

import io.github.alalkipgen.autoflow.core.CoreError
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.AnalysisSection
import io.github.alalkipgen.autoflow.core.model.Section
import io.github.alalkipgen.autoflow.core.model.Shot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ShotSplitterTest {
    private fun split(duration: Long, clip: Long = 8_000, sections: List<AnalysisSection>? = null): List<Shot> =
        assertIs<CoreResult.Ok<List<Shot>>>(ShotSplitter.split(duration, clip, sections)).value

    private fun List<Shot>.spans() = map { it.startMs to it.durationMs }

    @Test
    fun exactMultipleGivesEqualClips() {
        val shots = split(24_000)
        assertEquals(listOf(0L to 8_000L, 8_000L to 8_000L, 16_000L to 8_000L), shots.spans())
        assertEquals(listOf(1, 2, 3), shots.map { it.number })
        assertNull(shots.first().section)
    }

    @Test
    fun remainderOfTwoSecondsOrMoreIsItsOwnShot() {
        assertEquals(listOf(0L to 8_000L, 8_000L to 2_000L), split(10_000).spans())
        assertEquals(25, split(200_000).size) // 25 × 8 s exactly
        assertEquals(26, split(202_000).size)
    }

    @Test
    fun shorterRemainderMergesIntoThePreviousShot() {
        assertEquals(listOf(0L to 8_000L, 8_000L to 9_999L), split(17_999).spans())
    }

    @Test
    fun songShorterThanOneClipIsOneShot() {
        assertEquals(listOf(0L to 1_500L), split(1_500).spans())
        assertEquals(listOf(0L to 5_000L), split(5_000).spans())
    }

    @Test
    fun cutsSnapToSectionStartsWithinWindow() {
        val sections = listOf(
            AnalysisSection(Section.INTRO, 0, 9_000),
            AnalysisSection(Section.VERSE, 9_000, 22_600),
            AnalysisSection(Section.CHORUS, 22_600, 40_000),
        )
        val shots = split(40_000, sections = sections)
        // 8000 → 9000 (1.0 s away), 16000 stays, 24000 → 22600 (1.4 s away), 32000 stays
        assertEquals(listOf(0L, 9_000L, 16_000L, 22_600L, 32_000L), shots.map { it.startMs })
        assertEquals(40_000L, shots.sumOf { it.durationMs })
        assertEquals(listOf(Section.INTRO, Section.VERSE, Section.VERSE, Section.CHORUS, Section.CHORUS), shots.map { it.section })
    }

    @Test
    fun cutsOutsideWindowDoNotSnap() {
        val sections = listOf(AnalysisSection(Section.VERSE, 0, 9_600), AnalysisSection(Section.CHORUS, 9_600, 16_000))
        assertEquals(listOf(0L, 8_000L), split(16_000, sections = sections).map { it.startMs })
    }

    @Test
    fun snappingNeverReordersOrEmptiesShots() {
        // 2 s clips: cuts 2000 and 4000 are both within 1.5 s of the 3000 boundary; only the first may take it.
        val sections = listOf(AnalysisSection(Section.VERSE, 0, 3_000), AnalysisSection(Section.CHORUS, 3_000, 6_000))
        val shots = split(6_000, clip = 2_000, sections = sections)
        assertEquals(listOf(0L, 3_000L, 4_000L), shots.map { it.startMs })
        assertEquals(6_000L, shots.sumOf { it.durationMs })
        assertEquals(listOf(Section.VERSE, Section.CHORUS, Section.CHORUS), shots.map { it.section })
    }

    @Test
    fun invalidInputIsAnError() {
        assertIs<CoreError.InvalidInput>(assertIs<CoreResult.Err>(ShotSplitter.split(0, 8_000)).error)
        assertIs<CoreError.InvalidInput>(assertIs<CoreResult.Err>(ShotSplitter.split(10_000, 0)).error)
    }
}
