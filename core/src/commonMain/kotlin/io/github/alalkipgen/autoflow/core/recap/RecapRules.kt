package io.github.alalkipgen.autoflow.core.recap

import io.github.alalkipgen.autoflow.core.hash.Sha256
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.workflow.Blocker
import io.github.alalkipgen.autoflow.core.workflow.Gate

/** Movie Recap gates (docs/specs/recap.md, D-33). Pure functions; the UI only reflects their result. */
object RecapRules {
    fun canImportMovie(recap: Recap): Gate = Gate.of(recap.rightsConfirmed to Blocker.NO_RIGHTS)

    fun canFindScenes(recap: Recap): Gate = Gate.of(
        recap.rightsConfirmed to Blocker.NO_RIGHTS,
        (recap.movie != null) to Blocker.NO_MOVIE,
    )

    /** Gemini needs something to retell: the typed summary or subtitles. */
    fun canGenerateScript(recap: Recap, summary: String, subtitles: String): Gate = Gate.of(
        recap.picked.isNotEmpty() to Blocker.NO_PICKED_SCENES,
        (summary.isNotBlank() || subtitles.isNotBlank()) to Blocker.NO_SCRIPT_SOURCE,
    )

    fun canWriteManually(recap: Recap): Gate = Gate.of(recap.picked.isNotEmpty() to Blocker.NO_PICKED_SCENES)

    /** The script's segments are exactly the picked scenes (same times, same order). */
    fun isCurrent(recap: Recap, script: RecapScript?): Boolean =
        script != null && script.segments.map { it.startMs to it.endMs } == recap.picked.map { it.startMs to it.endMs }

    fun canApproveScript(recap: Recap, script: RecapScript?): Gate = when {
        script == null -> Gate(listOf(Blocker.NO_SCRIPT))
        !isCurrent(recap, script) -> Gate(listOf(Blocker.SCRIPT_OUTDATED))
        script.segments.any { it.textMy.isBlank() } -> Gate(listOf(Blocker.BLANK_SEGMENT))
        else -> Gate.ALLOWED
    }

    fun canVoice(recap: Recap, script: RecapScript?): Gate = when {
        script == null -> Gate(listOf(Blocker.NO_SCRIPT))
        !isCurrent(recap, script) -> Gate(listOf(Blocker.SCRIPT_OUTDATED))
        !recap.scriptApproved -> Gate(listOf(Blocker.SCRIPT_NOT_APPROVED))
        else -> Gate.ALLOWED
    }

    /** Render needs a voiced, approved, current script. */
    fun canRender(recap: Recap, script: RecapScript?): Gate {
        val voice = canVoice(recap, script)
        if (!voice.allowed) return voice
        return Gate.of(script!!.segments.indices.any { currentTake(recap, script, it) != null } to Blocker.NO_VOICE)
    }

    /** The take of segment [i] when it was recorded for the segment's current text, else null (missing or stale). */
    fun currentTake(recap: Recap, script: RecapScript, i: Int): VoiceTake? {
        val text = script.segments.getOrNull(i)?.textMy ?: return null
        return recap.takes.lastOrNull { it.segment == i }?.takeIf { it.textHash == textHash(text) }
    }

    fun isStale(recap: Recap, script: RecapScript, i: Int): Boolean =
        recap.takes.any { it.segment == i } && currentTake(recap, script, i) == null

    /** First 12 hex of SHA-256 of the segment text. */
    fun textHash(text: String): String = Sha256.hex(text.trim().encodeToByteArray()).take(12)

    /** [recap] with [take] as the newest take of its segment. */
    fun withTake(recap: Recap, take: VoiceTake): Recap =
        recap.copy(takes = recap.takes.filter { it.segment != take.segment } + take)
}

/**
 * One fit-check row (spec §6). [clipMs] is the output clip length; [overMs] > 0 when the scene runs on.
 * [voiceSpeed] > 1.0 when FitChoice.SPEED is selected: the voice is atempo-filtered to fit the scene (5B-6).
 * [videoSlow] < 1.0 when FitChoice.SLOW_VIDEO: setpts slows the picture to match the take (5B-6).
 */
data class FitRow(val segment: Int, val sceneMs: Long, val takeMs: Long?, val clipMs: Long, val status: FitStatus,
                  val voiceSpeed: Float = 1f, val videoSlow: Float = 1f, val block: String? = null) {
    val overMs: Long get() = if (takeMs == null) 0 else maxOf(0, clipMs - sceneMs)
}

enum class FitStatus { FITS, RUNS_ON, NO_VOICE, STALE }

/** Narration-to-scene fit (spec §6): clip = max(scene, take + [TAIL_MS]); past the movie end the last frame holds. */
object RecapFit {
    const val LEAD_MS = 200L
    const val TAIL_MS = 400L

    fun rows(recap: Recap, script: RecapScript, maxVoiceSpeed: Float = 1.5f, maxVideoSlow: Float = 0.7f): List<FitRow> =
        script.segments.mapIndexed { i, seg ->
            row(i, seg.durationMs, RecapRules.currentTake(recap, script, i)?.durationMs, recap.fit[i],
                RecapRules.isStale(recap, script, i), maxVoiceSpeed, maxVideoSlow)
        }

    /**
     * v2 rows (audit F07): one per story block, in story order; the scene is the block's clips together, the take
     * is [RecapRulesV2.currentTake] (chosen, else newest, when not stale) and the choice is `recap.fit[block.id]`.
     */
    fun rows(recap: RecapV2, story: RecapStory, lexicon: Lexicon = Lexicon.EMPTY, maxVoiceSpeed: Float = 1.5f, maxVideoSlow: Float = 0.7f): List<FitRow> =
        story.blocks.mapIndexed { i, b ->
            row(i, b.durationMs, RecapRulesV2.currentTake(recap, b, lexicon)?.durationMs, recap.fitChoice(b.id),
                RecapRulesV2.isStale(recap, b, lexicon), maxVoiceSpeed, maxVideoSlow).copy(block = b.id)
        }

    private fun row(i: Int, scene: Long, takeMs: Long?, choice: FitChoice?, stale: Boolean, maxVoiceSpeed: Float, maxVideoSlow: Float): FitRow =
        when {
            takeMs != null -> {
                val want = maxOf(scene, takeMs + TAIL_MS)
                val runsOn = want > scene
                when {
                    // 5B-6 SmartFit SPEED: speed up voice to fit in the scene.
                    runsOn && choice == FitChoice.SPEED && takeMs > 0 -> {
                        val rawSpeed = (takeMs + TAIL_MS).toFloat() / scene.toFloat()
                        val speed = rawSpeed.coerceIn(1.0f, maxVoiceSpeed.coerceAtLeast(1.01f))
                        FitRow(i, scene, takeMs, scene, FitStatus.FITS, voiceSpeed = speed)
                    }
                    // 5B-6 SmartFit SLOW_VIDEO: slow down the picture so it fills the take duration.
                    runsOn && choice == FitChoice.SLOW_VIDEO && scene > 0 -> {
                        val rawSlow = scene.toFloat() / (takeMs + TAIL_MS).toFloat()
                        val slow = rawSlow.coerceIn(maxVideoSlow.coerceAtMost(0.99f), 1.0f)
                        val newClip = takeMs + TAIL_MS
                        FitRow(i, scene, takeMs, newClip, FitStatus.RUNS_ON, videoSlow = slow)
                    }
                    else -> FitRow(i, scene, takeMs, want, if (runsOn) FitStatus.RUNS_ON else FitStatus.FITS)
                }
            }
            stale -> FitRow(i, scene, null, scene, FitStatus.STALE)
            else -> FitRow(i, scene, null, scene, FitStatus.NO_VOICE)
        }

    fun totalMs(rows: List<FitRow>): Long = rows.sumOf { it.clipMs }
}

// RecapV2 overloads for backward-compat UI code
fun RecapRules.canImportMovie(recap: RecapV2): Gate = canImportMovie(recap.toV1())
fun RecapRules.canFindScenes(recap: RecapV2): Gate = canFindScenes(recap.toV1())
fun RecapRules.canGenerateScript(recap: RecapV2, summary: String, subtitles: String): Gate =
    canGenerateScript(recap.toV1(), summary, subtitles)
fun RecapRules.canWriteManually(recap: RecapV2): Gate = canWriteManually(recap.toV1())

// The story / take v2 overloads (isCurrent / canApproveScript / canVoice / currentTake / isStale / canRender /
// withTake / RecapFit.rows with a RecapScript) were removed (audit F05 / F06 / F07): through `toV1()` they compared
// only first clips with the picks and mapped takes by list position, which is wrong for multi-clip blocks and once a
// block keeps a take history. Use RecapRulesV2 and RecapFit.rows(RecapV2, RecapStory).
