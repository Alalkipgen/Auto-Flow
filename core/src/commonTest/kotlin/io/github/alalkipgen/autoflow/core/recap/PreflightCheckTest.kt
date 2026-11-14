package io.github.alalkipgen.autoflow.core.recap

import io.github.alalkipgen.autoflow.core.model.ArtifactId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreflightCheckTest {
    private val scenes = listOf(
        RecapScene(0, 10_000, picked = true),
        RecapScene(10_000, 20_000, picked = true),
        RecapScene(20_000, 30_000, picked = true),
    )
    private val baseRecap = Recap(
        rightsConfirmed = true,
        movie = RecapMovie("source/movie.mp4", 9, 60_000),
        scenes = scenes,
        scriptApproved = true,
    )
    private val script = RecapScript(
        source = RecapScript.SOURCE_MANUAL,
        style = RecapStyle.PLAIN,
        segments = scenes.map { RecapSegment(it.startMs, it.endMs, "text ${it.startMs}") },
    )
    private fun take(i: Int, durationMs: Long = 5_000): VoiceTake = VoiceTake(
        i, ArtifactId("au_${i.toString().padStart(12, '0')}"), VoiceSource.TTS, durationMs,
        RecapRules.textHash(script.segments[i].textMy),
    )

    @Test
    fun noScriptReturnsEmpty() {
        assertTrue(PreflightCheck.check(baseRecap, null).isEmpty())
    }

    @Test
    fun unvoicedScenesGenerateWarning() {
        val items = PreflightCheck.check(baseRecap, script)
        assertTrue(items.any { "without a voice take" in it.message && it.kind == PreflightCheck.Kind.WARNING })
    }

    @Test
    fun staleTakeGeneratesWarning() {
        val stale = VoiceTake(0, ArtifactId("au_000000000000"), VoiceSource.TTS, 5_000, "oldHash123456")
        val recap = baseRecap.copy(takes = listOf(stale))
        val items = PreflightCheck.check(recap, script)
        assertTrue(items.any { "stale" in it.message && it.kind == PreflightCheck.Kind.WARNING })
    }

    @Test
    fun rightsIsInfo() {
        val recap = baseRecap.copy(takes = script.segments.indices.map { take(it) })
        val items = PreflightCheck.check(recap, script)
        assertEquals(1, items.size)
        assertEquals(PreflightCheck.Kind.INFO, items.single().kind)
    }

    @Test
    fun runsOnGeneratesWarning() {
        // A take 3 seconds longer than the scene triggers a runs-on warning
        val longTake = take(0, durationMs = 13_000) // scene = 10s, take = 13s → runs on 3s > 2s
        val recap = baseRecap.copy(takes = listOf(longTake))
        val items = PreflightCheck.check(recap, script)
        assertTrue(items.any { "longer than the clip" in it.message && it.kind == PreflightCheck.Kind.WARNING })
    }
}
