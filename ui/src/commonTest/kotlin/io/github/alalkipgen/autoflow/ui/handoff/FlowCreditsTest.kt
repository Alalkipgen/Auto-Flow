package io.github.alalkipgen.autoflow.ui.handoff

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.CreditEntry
import io.github.alalkipgen.autoflow.core.model.FlowCredits
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Pass
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.model.Shot
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Manual Flow credit counter (specs/flow-handoff.md §Credit log, DESIGN.md §6) and the Draft/Final default. */
class FlowCreditsTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val dir = "/p/Rain.autoflow"

    /** 2026-11-12 10:00 UTC = 2026-11-12 in Pacific time. */
    private var now = 1_794_477_600_000L

    private val model = FlowHandoffModel(
        session, { PlatformResult.Ok(Unit) }, { PlatformResult.Ok(Unit) }, FakePicker(),
        creditHintFast = 20, creditHintQuality = 100, nowMs = { now },
    )

    private fun open(log: List<CreditEntry> = emptyList()) {
        val p = (store.create(dir, "Rain", Mode.MUSIC_VIDEO, ProjectSettings()) as CoreResult.Ok).value
        current.set(OpenProject(dir, p))
        session.save(p.copy(shots = listOf(Shot(1, 0, 8_000), Shot(2, 8_000, 8_000)), flowCredits = FlowCredits(log)))
    }

    private fun importVideo(number: Int) {
        val p = session.open!!.project
        model.importVideoBytes(p, p.shots.first { it.number == number }, byteArrayOf(number.toByte(), p.shots.sumOf { it.videos.size }.toByte()), "mp4")
    }

    private fun row(number: Int) = model.current().rows.first { it.number == number }

    @Test
    fun afterAnImportTheCreditQuestionIsAskedOnceAndTheLogIsSaved() {
        open()
        assertFalse(row(1).askCredits, "nothing imported yet")
        importVideo(1)
        assertTrue(row(1).askCredits)
        assertEquals(20, row(1).creditDefault, "Draft → Fast hint")

        model.logCredits(1, 20)
        assertFalse(row(1).askCredits)
        assertEquals(20, row(1).creditsLogged)
        assertEquals(listOf(CreditEntry("2026-11-12", 20, 1)), session.open!!.project.flowCredits.manualLog)
        assertEquals("Logged 20 credits for Shot 1.", model.current().message)

        importVideo(1)
        assertTrue(row(1).askCredits, "a second generation asks again")
    }

    @Test
    fun skipHidesTheQuestionUntilTheNextImport() {
        open()
        importVideo(2)
        model.skipCredits(2)
        assertFalse(row(2).askCredits)
        assertTrue(session.open!!.project.flowCredits.manualLog.isEmpty())
        importVideo(2)
        assertTrue(row(2).askCredits)
    }

    @Test
    fun finalPassSuggestsTheQualityHint() {
        open()
        model.setPass(2, Pass.FINAL)
        assertEquals(100, row(2).creditDefault)
        assertEquals(20, row(1).creditDefault)
    }

    @Test
    fun counterShowsTodayAndThisMonthAndAWrongLineCanBeRemoved() {
        open(
            listOf(
                CreditEntry("2026-10-31", 100, 1),
                CreditEntry("2026-11-01", 100, 2),
                CreditEntry("2026-11-12", 20, 1),
                CreditEntry("2026-11-12", 20, null),
            ),
        )
        val s = model.current()
        assertEquals(40, s.creditsToday)
        assertEquals(140, s.creditsMonth)
        assertEquals(listOf(3, 2, 1), s.creditLog.map { it.index }, "this month only, newest first")
        assertEquals("Flow credits — today 40 · this month 140", creditSummary(s.creditsToday, s.creditsMonth))

        model.removeCredit(2)
        val after = model.current()
        assertEquals(20, after.creditsToday)
        assertEquals(3, session.open!!.project.flowCredits.manualLog.size)
        assertEquals(CreditEntry("2026-10-31", 100, 1), session.open!!.project.flowCredits.manualLog[0], "other lines kept")
    }

    @Test
    fun aNewQuotaDayStartsAtZero() {
        open(listOf(CreditEntry("2026-11-12", 20, 1)))
        now += 24 * 3_600_000L
        assertEquals(0, model.current().creditsToday)
        assertEquals(20, model.current().creditsMonth)
    }

    @Test
    fun outOfRangeCreditsAreRejected() {
        open()
        model.logCredits(1, 0)
        model.logCredits(1, FlowHandoffModel.MAX_CREDITS + 1)
        assertTrue(session.open!!.project.flowCredits.manualLog.isEmpty())
        assertEquals("Credits must be a number from 1 to ${FlowHandoffModel.MAX_CREDITS}", model.current().message)
    }
}
