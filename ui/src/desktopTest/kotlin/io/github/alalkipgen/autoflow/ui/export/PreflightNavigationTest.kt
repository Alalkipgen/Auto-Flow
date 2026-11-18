package io.github.alalkipgen.autoflow.ui.export

import io.github.alalkipgen.autoflow.core.recap.PreflightCheck
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.recap.QaRecapFixture
import io.github.alalkipgen.autoflow.ui.recap.RecapStep
import io.github.alalkipgen.autoflow.ui.shell.*
import kotlin.test.*

internal fun ExportHarness.preflightFixture(reason: PreflightCheck.Reason) {
    val original = fixture.project.recap!!
    val first = fixture.story.blocks.first().id
    assertTrue(recap.update { current ->
        current.copy(
            takes = when (reason) {
                PreflightCheck.Reason.UNVOICED -> original.takes.filter { it.block != first }
                PreflightCheck.Reason.STALE -> original.takes.map { if (it.block == first) it.copy(spokenHash = "preflight-old-text") else it }
                // Synthetic metadata fixture for the data-only report, not an audio-playback claim.
                PreflightCheck.Reason.RUNS_ON -> original.takes.map { if (it.block == first) it.copy(durationMs = 15_000) else it }
                else -> original.takes
            },
            chosenTakes = original.chosenTakes.filterKeys { reason != PreflightCheck.Reason.UNVOICED || it != first },
        )
    })
    model.refresh()
    assertEquals(PreflightCheck.check(recap.state.value.recap!!, recap.state.value.story, recap.state.value.lexicon),
        model.state.value.recap!!.preflight)
}

class PreflightNavigationTest {
    @Test fun explicitCoreDestinationsNotMessagesKindsCountsOrLocaleChooseTheStep() {
        for (kind in PreflightCheck.Kind.entries) for (reason in PreflightCheck.Reason.entries) {
            val voice = PreflightCheck.Item("Script / မြန်မာ / translated prose must not route", kind, "Voice", reason, 3)
            val saved = voice.copy()
            assertEquals(RecapStep.VOICE, preflightStep(voice))
            assertEquals(RecapStep.STORY, preflightStep(voice.copy(fix = "Script")))
            assertNull(preflightStep(voice.copy(fix = null)))
            assertNull(preflightStep(voice.copy(fix = "unknown-future-step")))
            assertNull(preflightStep(voice.copy(fix = "voice")))
            assertEquals(saved, voice, "routing never edits the core report")
        }
    }

    @Test fun realNativeRouterChangesOnlyTransientDestinationAndRetainsBackToPreviousStep() {
        val before = V2Nav(dest = StudioV2Dest.PROJECTS, projectScreen = "Export", recapStep = RecapStep.STYLE)
        for (step in listOf(RecapStep.VOICE, RecapStep.STORY)) {
            val after = v2PreflightFix(before, step)
            assertEquals(before.copy(projectScreen = "Recap", recapStep = step), after)
            assertEquals(step.previous(), v2Back(after, true)!!.recapStep)
        }
    }

    @Test fun fullProjectSnapshotAndCurrentCoreReportRejectStaleCallbacksWithoutWriting() {
        ExportHarness().use { h ->
            h.preflightFixture(PreflightCheck.Reason.UNVOICED)
            val a = h.model.projects.value!!
            val item = h.model.state.value.recap!!.preflight.first { it.reason == PreflightCheck.Reason.UNVOICED }
            val bytes = h.bytes()
            assertTrue(preflightNavigationAllowed(h.model, a, item))
            assertFalse(preflightNavigationAllowed(h.model, null, item))
            assertEquals(bytes, h.bytes())
            h.recap.update { it.copy(audio = it.audio.copy(movieVolume = .25)) }; h.model.refresh()
            assertFalse(preflightNavigationAllowed(h.model, a, item), "same folder, different Project snapshot")
            assertTrue(preflightNavigationAllowed(h.model, h.model.projects.value, item))
            h.preflightFixture(PreflightCheck.Reason.RIGHTS)
            assertFalse(preflightNavigationAllowed(h.model, h.model.projects.value, item), "warning no longer exists")
            val b = QaRecapFixture.create(h.root, "QA Preflight B")
            val beforeSwitch = h.bytes()
            h.current.set(OpenProject(b.dir.path, b.project))
            assertFalse(preflightNavigationAllowed(h.model, a, item))
            assertFalse(preflightNavigationAllowed(h.model, h.model.projects.value, item), "B open but report still A")
            h.model.refresh()
            assertFalse(preflightNavigationAllowed(h.model, h.model.projects.value, item))
            assertEquals(beforeSwitch, h.bytes())
        }
    }
}