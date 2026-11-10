package io.github.alalkipgen.autoflow.core.workflow

import io.github.alalkipgen.autoflow.core.model.Approvals
import io.github.alalkipgen.autoflow.core.model.Artifact
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.GlobalStyle
import io.github.alalkipgen.autoflow.core.model.Handoff
import io.github.alalkipgen.autoflow.core.model.HandoffState
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.Shot
import io.github.alalkipgen.autoflow.core.workflow.PipelineStep.ASSEMBLY
import io.github.alalkipgen.autoflow.core.workflow.PipelineStep.FLOW_VIDEO
import io.github.alalkipgen.autoflow.core.workflow.PipelineStep.INPUT
import io.github.alalkipgen.autoflow.core.workflow.PipelineStep.PROMPTS
import io.github.alalkipgen.autoflow.core.workflow.PipelineStep.REFERENCES
import io.github.alalkipgen.autoflow.core.workflow.PipelineStep.STORYBOARD
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class PipelineTest {
    private val t0 = Instant.parse("2026-11-10T08:00:00Z")
    private val sb = ArtifactId("sb_111111111111")
    private val sp = ArtifactId("sp_333333333333")
    private val pr = ArtifactId("pr_444444444444")
    private val im = ArtifactId("im_555555555555")
    private val vd = ArtifactId("vd_777777777777")
    private val gs = "gs_aaaaaaaaaaaa"
    private val artifacts = mutableMapOf<ArtifactId, Artifact>()
    private val lookup = ArtifactLookup { artifacts[it] }

    init {
        fun record(id: ArtifactId, vararg inputs: Pair<String, String>) {
            artifacts[id] = Artifact(id = id, kind = id.kind!!, file = "$id.json", inputs = inputs.toMap(), createdAt = t0)
        }
        record(sp, Inputs.STORYBOARD to sb.value)
        record(pr, Inputs.SPEC to sp.value, Inputs.GLOBAL_STYLE to gs)
        record(im, Inputs.PROMPT_IR to pr.value)
        record(vd, Inputs.PROMPT_IR to pr.value, Inputs.REF to im.value)
    }

    private fun shot(n: Int) = Shot(number = n, startMs = (n - 1) * 8_000L, durationMs = 8_000)
    private val drafted = shot(1).copy(spec = sp)
    private val approved = drafted.copy(approvals = Approvals(storyboard = true))
    private val prompted = approved.copy(promptIr = pr, approvals = Approvals(storyboard = true, prompt = true))
    private val withRef = prompted.copy(refImages = listOf(im), chosenRef = im, approvals = Approvals(true, true, true))
    private val accepted = withRef.copy(videos = listOf(vd), acceptedVideo = vd, handoff = Handoff(HandoffState.ACCEPTED))

    private fun project(vararg shots: Shot, storyboard: ArtifactId? = sb) = Project(
        id = "p", name = "n", mode = Mode.MUSIC_VIDEO, createdAt = t0, updatedAt = t0,
        globalStyle = GlobalStyle(hash = gs), currentStoryboard = storyboard, shots = shots.toList(),
    )

    private fun opens(p: Project) = PipelineStep.entries.filter { Pipeline.gate(p, it, lookup).allowed }

    @Test
    fun newProjectOpensOnlyInputAndStoryboard() {
        val p = project(storyboard = null)
        assertEquals(listOf(INPUT, STORYBOARD), opens(p))
        assertEquals(listOf(Blocker.NO_SPEC), Pipeline.gate(p, PROMPTS, lookup).blockers)
        assertEquals(listOf(Blocker.NO_PROMPT), Pipeline.gate(p, REFERENCES, lookup).blockers)
        assertEquals(listOf(Blocker.NO_CHOSEN_REF), Pipeline.gate(p, FLOW_VIDEO, lookup).blockers)
        assertEquals(listOf(Blocker.NO_ACCEPTED_VIDEOS), Pipeline.gate(p, ASSEMBLY, lookup).blockers)
        assertEquals(INPUT, Pipeline.current(p, lookup))
    }

    @Test
    fun stepsOpenAsSoonAsOneShotGetsThere() {
        assertEquals(listOf(INPUT, STORYBOARD, PROMPTS), opens(project(drafted, shot(2))))
        assertEquals(listOf(INPUT, STORYBOARD, PROMPTS, REFERENCES), opens(project(prompted, shot(2))))
        assertEquals(listOf(INPUT, STORYBOARD, PROMPTS, REFERENCES, FLOW_VIDEO), opens(project(withRef, shot(2))))
        assertEquals(PipelineStep.entries, opens(project(accepted, shot(2))))
    }

    @Test
    fun blockersNameTheMissingApproval() {
        val unapprovedPrompt = approved.copy(promptIr = pr)
        assertEquals(listOf(Blocker.PROMPT_NOT_APPROVED), Pipeline.gate(project(unapprovedPrompt), REFERENCES, lookup).blockers)
        val unapprovedRef = prompted.copy(chosenRef = im, refImages = listOf(im))
        assertEquals(listOf(Blocker.REF_NOT_APPROVED), Pipeline.gate(project(unapprovedRef), FLOW_VIDEO, lookup).blockers)
    }

    @Test
    fun workAlreadyDoneKeepsItsScreenOpen() {
        // A stale prompt blocks new references, but the imported reference keeps References open.
        val stalePrompt = withRef.copy(spec = ArtifactId("sp_999999999999"))
        assertTrue(Pipeline.gate(project(stalePrompt), REFERENCES, lookup).allowed)
        val sent = drafted.copy(handoff = Handoff(HandoffState.SENT_TO_FLOW))
        assertTrue(Pipeline.gate(project(sent), FLOW_VIDEO, lookup).allowed)
    }

    @Test
    fun currentIsTheFirstStepNotDoneByEveryShot() {
        assertEquals(STORYBOARD, Pipeline.current(project(drafted, approved), lookup))
        assertEquals(PROMPTS, Pipeline.current(project(approved, approved.copy(number = 2)), lookup))
        assertEquals(REFERENCES, Pipeline.current(project(prompted), lookup))
        assertEquals(FLOW_VIDEO, Pipeline.current(project(withRef), lookup))
        assertEquals(ASSEMBLY, Pipeline.current(project(accepted), lookup))
    }

    @Test
    fun staleWorkIsNotDone() {
        val p = project(accepted, storyboard = ArtifactId("sb_222222222222"))
        assertTrue(Pipeline.done(p, INPUT, lookup))
        assertFalse(Pipeline.done(p, STORYBOARD, lookup), "spec made from an older storyboard")
        assertEquals(STORYBOARD, Pipeline.current(p, lookup))
    }

    @Test
    fun shotStatusFollowsTheShotsProgressAndStaleWins() {
        fun status(s: Shot, p: Project = project(s)) = ShotStatus.of(p, s, lookup)
        assertEquals(ShotStatus.DRAFT, status(shot(1)))
        assertEquals(ShotStatus.DRAFT, status(approved))
        assertEquals(ShotStatus.PROMPT_READY, status(prompted))
        assertEquals(ShotStatus.NEEDS_FLOW, status(withRef))
        assertEquals(ShotStatus.NEEDS_FLOW, status(prompted.copy(handoff = Handoff(HandoffState.SENT_TO_FLOW))))
        assertEquals(ShotStatus.IMPORTED, status(withRef.copy(videos = listOf(vd), handoff = Handoff(HandoffState.IMPORTED))))
        assertEquals(ShotStatus.ACCEPTED, status(accepted))
        assertEquals(ShotStatus.STALE, status(accepted, project(accepted).copy(globalStyle = GlobalStyle(hash = "gs_bbbbbbbbbbbb"))))
    }
}
