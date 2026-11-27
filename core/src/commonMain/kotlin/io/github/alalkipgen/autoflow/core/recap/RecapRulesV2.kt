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

    /**
     * v2 "outdated" (studio-v2 §2, audit F05): only a clip outside the current movie (a new import) makes a story
     * outdated. Blocks may have several clips and need not follow the picks: picks only build the first story, so the
     * v1 rule "segments = picked scenes" (first clip only) does not apply.
     */
    fun isCurrent(recap: RecapV2, story: RecapStory): Boolean {
        val movie = recap.movie ?: return false
        return story.blocks.isNotEmpty() && story.blocks.all { b ->
            b.clips.isNotEmpty() && b.clips.all { it.inMs >= 0 && it.outMs > it.inMs && it.outMs <= movie.durationMs }
        }
    }

    fun canApproveScript(recap: RecapV2, story: RecapStory?): Gate = when {
        story == null -> Gate(listOf(Blocker.NO_SCRIPT))
        !isCurrent(recap, story) -> Gate(listOf(Blocker.SCRIPT_OUTDATED))
        story.blocks.any { it.textMy.isBlank() } -> Gate(listOf(Blocker.BLANK_SEGMENT))
        else -> Gate.ALLOWED
    }

    fun canVoice(recap: RecapV2, story: RecapStory?): Gate = when {
        story == null -> Gate(listOf(Blocker.NO_SCRIPT))
        !isCurrent(recap, story) -> Gate(listOf(Blocker.SCRIPT_OUTDATED))
        !recap.storyApproved -> Gate(listOf(Blocker.SCRIPT_NOT_APPROVED))
        else -> Gate.ALLOWED
    }

    /** Render needs an approved story with at least one block whose current take speaks its current text. */
    fun canRender(recap: RecapV2, story: RecapStory?, lexicon: Lexicon = Lexicon.EMPTY): Gate {
        val voice = canVoice(recap, story)
        if (!voice.allowed) return voice
        return Gate.of(story!!.blocks.any { currentTake(recap, it, lexicon) != null } to Blocker.NO_VOICE)
    }

    /** The chosen take for block [blockId], else its newest (spec §2), stale or not; null without takes. */
    fun currentTakeFor(recap: RecapV2, blockId: String): BlockTake? = recap.takeFor(blockId)

    /**
     * The take that plays for [block]: the chosen one, else the newest — when it speaks the block's current text
     * ([SpokenText]); a stale take plays as "no voice" until it is spoken again (as in v1).
     */
    fun currentTake(recap: RecapV2, block: RecapBlock, lexicon: Lexicon = Lexicon.EMPTY): BlockTake? =
        recap.takeFor(block.id)?.takeUnless { SpokenText.isStale(it, block, lexicon) }

    /** The block has a take, but it was made for another text (⚠ Stale). */
    fun isStale(recap: RecapV2, block: RecapBlock, lexicon: Lexicon = Lexicon.EMPTY): Boolean =
        recap.takeFor(block.id)?.let { SpokenText.isStale(it, block, lexicon) } == true
}
