package io.github.alalkipgen.autoflow.core.recap

import io.github.alalkipgen.autoflow.core.workflow.Blocker
import io.github.alalkipgen.autoflow.core.workflow.Gate

/**
 * Movie Recap gates for v2 data (RecapV2 + RecapStory). Mirrors [RecapRules] for v1.
 * Pure functions; the UI only reflects their result.
 */
object RecapRulesV2 {
    fun canImportMovie(recap: RecapV2): Gate = Gate.of(recap.rightsConfirmed to Blocker.NO_RIGHTS)

    fun canFindScenes(recap: RecapV2): Gate = Gate.of(
        recap.rightsConfirmed to Blocker.NO_RIGHTS,
        (recap.movie != null) to Blocker.NO_MOVIE,
    )

    /** v2: picked scenes = scenes with picked=true */
    fun pickedScenes(recap: RecapV2): List<RecapScene> = recap.scenes.filter { it.picked }

    fun canGenerateScript(recap: RecapV2, summary: String, subtitles: String): Gate = Gate.of(
        pickedScenes(recap).isNotEmpty() to Blocker.NO_PICKED_SCENES,
        (summary.isNotBlank() || subtitles.isNotBlank()) to Blocker.NO_SCRIPT_SOURCE,
    )

    fun canWriteManually(recap: RecapV2): Gate = Gate.of(pickedScenes(recap).isNotEmpty() to Blocker.NO_PICKED_SCENES)

    /** True when story block clip ranges match the current picked-scene ranges. */
    fun isCurrent(recap: RecapV2, story: RecapStory): Boolean {
        val pickedRanges = pickedScenes(recap).map { it.startMs to it.endMs }
        val storyRanges = story.blocks.map { b -> b.clips.firstOrNull()?.let { it.inMs to it.outMs } }
        return storyRanges.map { it } == pickedRanges.map { it as Pair<Long, Long>? }
    }

    fun canApproveScript(recap: RecapV2, story: RecapStory?): Gate = when {
        story == null -> Gate(listOf(Blocker.NO_SCRIPT))
        !isCurrent(recap, story) -> Gate(listOf(Blocker.SCRIPT_OUTDATED))
        story.blocks.any { it.textMy.isBlank() } -> Gate(listOf(Blocker.BLANK_SEGMENT))
        else -> Gate.ALLOWED
    }

    fun canVoice(recap: RecapV2, story: RecapStory?): Gate = when {
        story == null -> Gate(listOf(Blocker.NO_SCRIPT))
        !recap.storyApproved -> Gate(listOf(Blocker.SCRIPT_NOT_APPROVED))
        else -> Gate.ALLOWED
    }

    fun canRender(recap: RecapV2, story: RecapStory?): Gate {
        val voice = canVoice(recap, story)
        if (!voice.allowed) return voice
        return Gate.of(story!!.blocks.any { recap.takeFor(it.id) != null } to Blocker.NO_VOICE)
    }

    /** The current take for block [blockId], or null if none. */
    fun currentTakeFor(recap: RecapV2, blockId: String): BlockTake? = recap.takeFor(blockId)
}
