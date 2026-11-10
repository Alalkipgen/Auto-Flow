package io.github.alalkipgen.autoflow.core.workflow

import io.github.alalkipgen.autoflow.core.model.Approvals
import io.github.alalkipgen.autoflow.core.model.Artifact
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.GlobalStyle
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.model.Shot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class WorkflowRulesTest {
    private val t0 = Instant.parse("2026-11-10T08:00:00Z")
    private val sb1 = ArtifactId("sb_111111111111")
    private val sb2 = ArtifactId("sb_222222222222")
    private val sp = ArtifactId("sp_333333333333")
    private val sp2 = ArtifactId("sp_333333333334")
    private val pr = ArtifactId("pr_444444444444")
    private val pr2 = ArtifactId("pr_444444444445")
    private val im = ArtifactId("im_555555555555")
    private val vd = ArtifactId("vd_777777777777")
    private val gs = "gs_aaaaaaaaaaaa"

    private val artifacts = mutableMapOf<ArtifactId, Artifact>()
    private val lookup = ArtifactLookup { artifacts[it] }

    private fun record(id: ArtifactId, vararg inputs: Pair<String, String>) {
        artifacts[id] = Artifact(id = id, kind = id.kind!!, file = "$id.json", inputs = inputs.toMap(), createdAt = t0)
    }

    /** A shot that is accepted and fully consistent with the project. */
    private val fullShot = Shot(
        number = 1, startMs = 0, durationMs = 8_000, spec = sp, promptIr = pr, refImages = listOf(im), chosenRef = im,
        videos = listOf(vd), acceptedVideo = vd, approvals = Approvals(storyboard = true, prompt = true, ref = true),
    )

    private fun project(vararg shots: Shot, storyboard: ArtifactId = sb1, styleHash: String = gs) = Project(
        id = "p", name = "n", mode = Mode.MUSIC_VIDEO, createdAt = t0, updatedAt = t0,
        settings = ProjectSettings(durationMs = 16_000), globalStyle = GlobalStyle(hash = styleHash),
        currentStoryboard = storyboard, shots = shots.toList(),
    )

    init {
        record(sp, Inputs.STORYBOARD to sb1.value)
        record(pr, Inputs.SPEC to sp.value, Inputs.GLOBAL_STYLE to gs)
        record(im, Inputs.PROMPT_IR to pr.value)
        record(vd, Inputs.PROMPT_IR to pr.value, Inputs.REF to im.value)
    }

    @Test
    fun consistentShotIsNotStale() {
        assertFalse(Stale.any(project(fullShot), fullShot, lookup))
    }

    @Test
    fun newStoryboardMakesSpecStale() {
        assertTrue(Stale.spec(project(fullShot, storyboard = sb2), fullShot, lookup))
    }

    @Test
    fun specOrGlobalStyleChangeMakesPromptStale() {
        record(sp2, Inputs.STORYBOARD to sb1.value)
        assertTrue(Stale.prompt(project(fullShot), fullShot.copy(spec = sp2), lookup))
        assertTrue(Stale.prompt(project(fullShot, styleHash = "gs_bbbbbbbbbbbb"), fullShot, lookup))
    }

    @Test
    fun newPromptMakesRefAndVideoStale_newRefMakesVideoStale() {
        val newPrompt = fullShot.copy(promptIr = pr2)
        assertTrue(Stale.ref(newPrompt, lookup))
        assertTrue(Stale.video(newPrompt, lookup))
        val otherRef = ArtifactId("im_666666666666").also { record(it, Inputs.PROMPT_IR to pr.value) }
        val newRef = fullShot.copy(chosenRef = otherRef)
        assertFalse(Stale.ref(newRef, lookup))
        assertTrue(Stale.video(newRef, lookup))
    }

    @Test
    fun nullPointersAreNotStaleButMissingSidecarsAre() {
        val empty = Shot(number = 1, startMs = 0, durationMs = 8_000)
        assertFalse(Stale.any(project(empty), empty, lookup))
        artifacts.remove(pr)
        assertTrue(Stale.prompt(project(fullShot), fullShot, lookup))
    }

    @Test
    fun userProvidedArtifactsRecordNoInputsAndAreNeverStale() {
        val own = ArtifactId("im_888888888888").also { record(it) } // "Upload own": inputs = {}
        val shot = fullShot.copy(chosenRef = own, refImages = listOf(im, own), promptIr = pr2)
        assertFalse(Stale.ref(shot, lookup))
        assertTrue(Stale.ref(shot, lookup, ref = im)) // generated ref still tracks its prompt
        val ownVideo = ArtifactId("vd_999999999999").also { record(it, Inputs.PROMPT_IR to pr.value) } // ref not recorded
        assertFalse(Stale.video(fullShot.copy(acceptedVideo = ownVideo), lookup))
        assertTrue(Stale.video(fullShot.copy(acceptedVideo = ownVideo, promptIr = pr2), lookup))
    }

    @Test
    fun storyboardGate() {
        val p = project(fullShot)
        assertTrue(WorkflowRules.canGenerateStoryboard(p, "မိုးရွာနေတဲ့ ညမှာ").allowed)
        val blocked = WorkflowRules.canGenerateStoryboard(
            p.copy(settings = ProjectSettings(durationMs = 0, clipMs = 0)), "  ",
        )
        assertEquals(listOf(Blocker.EMPTY_LYRICS, Blocker.NO_DURATION, Blocker.INVALID_CLIP_LENGTH), blocked.blockers)
    }

    @Test
    fun shotGatesFollowTheSpec() {
        val draft = Shot(number = 1, startMs = 0, durationMs = 8_000)
        assertEquals(listOf(Blocker.NO_SPEC, Blocker.STORYBOARD_NOT_APPROVED), WorkflowRules.canCompilePrompt(draft).blockers)
        assertTrue(WorkflowRules.canCompilePrompt(fullShot).allowed)

        assertTrue(WorkflowRules.canGenerateRef(project(fullShot), fullShot, lookup).allowed)
        val stale = WorkflowRules.canGenerateRef(project(fullShot, styleHash = "gs_bbbbbbbbbbbb"), fullShot, lookup)
        assertEquals(listOf(Blocker.PROMPT_STALE), stale.blockers)
        val unapproved = fullShot.copy(approvals = Approvals(storyboard = true))
        assertEquals(listOf(Blocker.PROMPT_NOT_APPROVED), WorkflowRules.canGenerateRef(project(unapproved), unapproved, lookup).blockers)

        assertTrue(WorkflowRules.canHandoff(fullShot).allowed)
        assertEquals(listOf(Blocker.NO_CHOSEN_REF, Blocker.REF_NOT_APPROVED), WorkflowRules.canHandoff(draft).blockers)
        assertEquals(listOf(Blocker.NO_VIDEOS), WorkflowRules.canAccept(draft).blockers)
        assertTrue(WorkflowRules.canAccept(fullShot).allowed)
    }

    @Test
    fun renderNeedsOneAcceptedVideoAndListsPlaceholders() {
        val pending = Shot(number = 2, startMs = 8_000, durationMs = 8_000)
        assertEquals(listOf(Blocker.NO_ACCEPTED_VIDEOS), WorkflowRules.canRender(project(pending)).blockers)
        val p = project(fullShot, pending)
        assertTrue(WorkflowRules.canRender(p).allowed)
        assertEquals(listOf(2), WorkflowRules.placeholderShots(p))
    }

    @Test
    fun approveAllSkipsMissingAndStaleArtifacts() {
        val fresh = fullShot.copy(approvals = Approvals())
        val noSpec = Shot(number = 2, startMs = 8_000, durationMs = 8_000)
        record(sp2, Inputs.STORYBOARD to sb2.value) // spec from an older storyboard → stale
        val staleSpec = fresh.copy(number = 3, spec = sp2)
        val p = WorkflowRules.approveAll(project(fresh, noSpec, staleSpec), ApprovalStep.STORYBOARD, lookup)
        assertEquals(listOf(true, false, false), p.shots.map { it.approvals.storyboard })
        assertEquals(listOf(false, false, false), p.shots.map { it.approvals.prompt })

        val v = WorkflowRules.approveAll(project(fresh), ApprovalStep.VIDEO, lookup)
        assertTrue(v.shots.single().approvals.video)
    }

    @Test
    fun approveAllNeverClearsExistingApprovals() {
        val approvedButStale = fullShot.copy(approvals = Approvals(storyboard = true))
        val p = WorkflowRules.approveAll(project(approvedButStale, storyboard = sb2), ApprovalStep.STORYBOARD, lookup)
        assertTrue(p.shots.single().approvals.storyboard)
    }
}
