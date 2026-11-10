package io.github.alalkipgen.autoflow.ui.shots

import io.github.alalkipgen.autoflow.ai.ImageProvider
import io.github.alalkipgen.autoflow.ai.manual.ManualImageProvider
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.prompt.ImageDialect
import io.github.alalkipgen.autoflow.core.workflow.Blocker
import io.github.alalkipgen.autoflow.core.workflow.Inputs
import io.github.alalkipgen.autoflow.core.workflow.Stale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ShotServiceTest {
    @Test
    fun promptsCompileOnlyFromApprovedStoryboardsAndRecompileWhenStale() = runTest {
        val f = ShotFixture()
        f.open(approve = false)
        assertEquals(ShotResult.Blocked(listOf(Blocker.STORYBOARD_NOT_APPROVED)), f.service.compilePrompt(1))
        f.storyboard.approveAll()
        assertEquals(ShotResult.Done(), f.service.compileAll())
        assertTrue(f.project.shots.all { it.promptIr != null && !it.approvals.prompt })
        val pr = f.shot(1).promptIr!!
        assertEquals(f.shot(1).spec!!.value, f.session.lookup()(pr)!!.inputs[Inputs.SPEC])

        f.service.approvePrompt(1)
        f.service.compilePrompt(1)
        assertEquals(pr, f.shot(1).promptIr, "same spec → same content-hash id")
        assertTrue(f.shot(1).approvals.prompt, "recompiling to the same id keeps the approval")
        assertEquals(ShotResult.Done("Every approved shot already has a current prompt."), f.service.compileAll())

        val spec = f.storyboard.spec(f.shot(1))!!
        f.storyboard.editSpec(1, spec.copy(subjectEn = "a black cat"))
        f.storyboard.approve(1)
        assertTrue(Stale.prompt(f.project, f.shot(1), f.session.lookup()))
        f.service.approvePrompt(2)
        // The stale approval stays and shows as stale; approving again needs a recompile.
        f.service.approvePrompt(1, approved = false)
        assertEquals(ShotResult.Blocked(listOf(Blocker.PROMPT_STALE)), f.service.approvePrompt(1))
        f.service.compileAll()
        assertNotEquals(pr, f.shot(1).promptIr)
        assertFalse(f.shot(1).approvals.prompt)
        assertTrue(f.shot(2).approvals.prompt, "other shots are untouched")
        assertTrue(f.service.flowPrompt(f.shot(1))!!.contains("black cat"))
        assertTrue(f.service.imagePrompt(f.shot(1))!!.contains(ImageDialect.STILL))
    }

    @Test
    fun referenceImagesComeFromTheProviderOrAnImport() = runTest {
        val f = ShotFixture()
        f.open()
        f.service.compilePrompt(1)
        assertEquals(ShotResult.Blocked(listOf(Blocker.PROMPT_NOT_APPROVED)), f.service.requestRef(1, RefSlot.A))
        f.service.approvePrompt(1)
        assertEquals(ShotResult.Done(), f.service.requestRef(1, RefSlot.A))
        val a = f.shot(1).chosenRef!!
        assertEquals(listOf(a), f.shot(1).refImages)
        assertEquals(f.shot(1).promptIr!!.value, f.session.lookup()(a)!!.inputs[Inputs.PROMPT_IR])
        assertEquals(1, f.service.lastImagePromptShot)

        val square = f.png(Ratio.R1_1)
        val r = f.service.importRef(1, RefSlot.B, square, "PNG")
        assertEquals(ShotResult.Done("The image is 320×320, not 9:16 like the project."), r)
        val b = f.shot(1).refImages[1]
        assertEquals(a, f.shot(1).chosenRef, "importing B keeps the chosen A")
        assertIs<ShotResult.Failed>(f.service.importRef(1, RefSlot.B, "not an image".encodeToByteArray(), "png"))

        f.service.approveRef(1)
        assertTrue(f.shot(1).approvals.ref)
        f.service.chooseRef(1, b)
        assertEquals(b, f.shot(1).chosenRef)
        assertFalse(f.shot(1).approvals.ref, "a different reference needs approval again")
    }

    @Test
    fun manualImageModeCopiesThePromptAndWaitsForTheImport() = runTest {
        lateinit var f: ShotFixture
        f = ShotFixture(ManualImageProvider({ f.copied += it }, { f.opened += it }, "https://aistudio.google.com/"))
        f.open()
        f.service.compilePrompt(2)
        f.service.approvePrompt(2)
        assertEquals(ShotResult.Done(ManualImageProvider.INSTRUCTION), f.service.requestRef(2, RefSlot.A))
        assertTrue(f.copied.single().contains(ImageDialect.STILL))
        assertEquals(listOf("https://aistudio.google.com/"), f.opened)
        assertTrue(f.shot(2).refImages.isEmpty())
        assertEquals(2, f.service.lastImagePromptShot)
    }
}
