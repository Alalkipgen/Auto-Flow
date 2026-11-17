package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.ui.components.StudioStatus
import kotlin.test.*

class VoiceBlockViewsTest {
    private val blocks = listOf(
        RecapBlock("multi", listOf(RecapClip(2_000, 4_000), RecapClip(6_000, 8_000)), "first"),
        RecapBlock("other", listOf(RecapClip(8_000, 10_000)), "second"))
    private val story = RecapStory(source = "manual", blocks = blocks)
    private fun take(block: RecapBlock, audio: String, ms: Long) =
        BlockTake(block.id, ArtifactId(audio), TakeSource.RECORDED, durationMs = ms, spokenHash = SpokenText.hash(block))
    @Test fun nativeIdsSelectedTakeAndAllClipsDetermineTheRowsNotHistoryOrder() {
        val old = take(blocks[0], "au_old", 9_000)
        val selected = take(blocks[0], "au_selected", 3_600)
        val other = take(blocks[1], "au_other", 1_000)
        val recap = RecapV2(takes = listOf(other, selected, old), chosenTakes = mapOf(blocks[0].id to selected.audio))
        val rows = voiceBlockViews(RecapUiState(recap = recap, story = story))
        assertEquals(listOf("multi", "other"), rows.map { it.block.id })
        assertEquals(4_000L, rows[0].fit.sceneMs)
        assertEquals(StudioStatus.FITS, rows[0].status)
        assertEquals(0L, rows[0].deltaMs)
        assertEquals(StudioStatus.TIMING, rows[1].status)
        assertEquals(-600L, rows[1].deltaMs)
        assertTrue(rows.all { it.voiced })
    }
    @Test fun missingStaleAndFitSpeedUseCoreRulesAndTheLiveLexicon() {
        val original = take(blocks[0], "au_one", 4_500)
        val recap = RecapV2(takes = listOf(original), fit = mapOf("multi" to FitChoice.SPEED.name))
        val state = RecapUiState(recap = recap, story = story)
        assertEquals(StudioStatus.FITS, voiceBlockViews(state)[0].status)
        assertEquals(StudioStatus.NO_VOICE, voiceBlockViews(state)[1].status)
        val changed = state.copy(lexicon = Lexicon(listOf(LexiconEntry("first", "changed"))))
        val stale = voiceBlockViews(changed)[0]
        assertEquals(StudioStatus.SCRIPT_CHANGED, stale.status)
        assertFalse(stale.voiced)
        assertTrue(stale.attention)
    }
}