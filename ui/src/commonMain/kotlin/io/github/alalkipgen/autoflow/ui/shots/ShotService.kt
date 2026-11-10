package io.github.alalkipgen.autoflow.ui.shots

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.ImageProvider
import io.github.alalkipgen.autoflow.core.CoreError
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.image.ImageHeader
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.Shot
import io.github.alalkipgen.autoflow.core.model.ShotSpec
import io.github.alalkipgen.autoflow.core.prompt.PromptCompiler
import io.github.alalkipgen.autoflow.core.prompt.PromptIR
import io.github.alalkipgen.autoflow.core.workflow.ApprovalStep
import io.github.alalkipgen.autoflow.core.workflow.Blocker
import io.github.alalkipgen.autoflow.core.workflow.Inputs
import io.github.alalkipgen.autoflow.core.workflow.Stale
import io.github.alalkipgen.autoflow.core.workflow.WorkflowRules
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.shots.ShotOps.noProject
import io.github.alalkipgen.autoflow.ui.shots.ShotOps.noShot
import io.github.alalkipgen.autoflow.ui.shots.ShotOps.replace

/**
 * Per-shot steps after the storyboard (ARCHITECTURE.md §3): compile and approve the prompt, and the reference images
 * (D-14: copy the image prompt, generate by hand, import). Gates come from [WorkflowRules]; a new pointer clears only
 * that step's approval and leaves the rest to show as stale (D-21). The Flow side is [HandoffService].
 */
class ShotService(
    private val session: ProjectSession,
    private val images: ImageProvider,
) {
    /** Shot whose image prompt was copied last: the Inbox suggests it for the next image (specs/flow-handoff.md). */
    var lastImagePromptShot: Int? = null
        private set

    fun prompt(shot: Shot): PromptIR? = ShotOps.prompt(session, shot)
    fun flowPrompt(shot: Shot): String? = ShotOps.flowPrompt(session, shot)
    fun imagePrompt(shot: Shot): String? = ShotOps.imagePrompt(session, shot)

    /** Compiles shot [number]'s approved spec into a `pr` artifact (no AI, deterministic). */
    fun compilePrompt(number: Int): ShotResult = withShot(number) { p, shot ->
        val gate = WorkflowRules.canCompilePrompt(shot)
        if (!gate.allowed) return@withShot ShotResult.Blocked(gate.blockers)
        when (val r = compiled(p, shot)) {
            is CoreResult.Ok -> save(replace(p, r.value))
            is CoreResult.Err -> ShotResult.Failed(r.error.message)
        }
    }

    /** Compiles every shot that may compile and has no prompt yet or a stale one; one save. */
    fun compileAll(): ShotResult {
        val p = session.open?.project ?: return noProject
        val lookup = session.lookup()
        val todo = p.shots.filter { WorkflowRules.canCompilePrompt(it).allowed && (it.promptIr == null || Stale.prompt(p, it, lookup)) }
        if (todo.isEmpty()) return ShotResult.Done("Every approved shot already has a current prompt.")
        var next = p
        val failed = mutableListOf<Int>()
        todo.forEach { shot ->
            when (val r = compiled(next, shot)) {
                is CoreResult.Ok -> next = replace(next, r.value)
                is CoreResult.Err -> failed += shot.number
            }
        }
        val saved = save(next)
        return if (saved is ShotResult.Done && failed.isNotEmpty()) {
            ShotResult.Done("Could not compile shot ${failed.joinToString { "#" + it.toString().padStart(2, '0') }}: fill in its English fields on Storyboard.")
        } else {
            saved
        }
    }

    fun approvePrompt(number: Int, approved: Boolean = true) = approve(number, ApprovalStep.PROMPT, approved)
    fun approveAllPrompts(): ShotResult {
        val p = session.open?.project ?: return noProject
        return save(WorkflowRules.approveAll(p, ApprovalStep.PROMPT, session.lookup()))
    }

    /**
     * Reference image for [slot] (D-14). Manual mode copies the image prompt and opens AI Studio ([AiResult.Deferred]:
     * the user imports the file later); a provider that returns bytes (fake mode) imports them at once.
     */
    suspend fun requestRef(number: Int, slot: RefSlot): ShotResult {
        val p = session.open?.project ?: return noProject
        val shot = p.shots.firstOrNull { it.number == number } ?: return noShot(number)
        val gate = WorkflowRules.canGenerateRef(p, shot, session.lookup())
        if (!gate.allowed) return ShotResult.Blocked(gate.blockers)
        val text = imagePrompt(shot) ?: return ShotResult.Failed(ShotOps.UNREADABLE_PROMPT)
        lastImagePromptShot = number
        return when (val r = images.image(text, p.settings.ratio, emptyList())) {
            is AiResult.Ok -> importRef(number, slot, r.value, "png", provider = ShotOps.PROVIDER_AI)
            is AiResult.Deferred -> ShotResult.Done(r.instruction)
            is AiResult.RateLimited -> ShotResult.Failed("The image limit is reached; try again later")
            is AiResult.Failed -> ShotResult.Failed(r.msg)
        }
    }

    /** Import image (file or inbox) as Ref A/B. Records the prompt it was made for; warns when the ratio differs. */
    fun importRef(number: Int, slot: RefSlot, bytes: ByteArray, ext: String, provider: String = ShotOps.PROVIDER_USER): ShotResult =
        withShot(number) { p, shot ->
            val info = ImageHeader.read(bytes) ?: return@withShot ShotResult.Failed("Not a PNG or JPEG image")
            val inputs = shot.promptIr?.let { mapOf(Inputs.PROMPT_IR to it.value) }.orEmpty()
            val artifacts = session.artifacts() ?: return@withShot noProject
            val e = ext.lowercase().let { if (it == "jpeg") "jpg" else it }
            val id = when (val r = artifacts.put(bytes, ArtifactKind.IMAGE, e, number, inputs, provider)) {
                is CoreResult.Ok -> r.value.id
                is CoreResult.Err -> return@withShot ShotResult.Failed(r.error.message)
            }
            val refs = shot.refImages.toMutableList().apply { if (slot.ordinal < size) set(slot.ordinal, id) else add(id) }.distinct()
            val chosen = shot.chosenRef?.takeIf { it in refs } ?: id
            val next = shot.copy(refImages = refs, chosenRef = chosen, approvals = shot.approvals.copy(ref = shot.approvals.ref && chosen == shot.chosenRef))
            val saved = save(replace(p, next))
            val ratio = p.settings.ratio
            if (saved is ShotResult.Done && !info.matches(ratio)) ShotResult.Done("The image is ${info.width}×${info.height}, not ${ratio.label} like the project.") else saved
        }

    /** Picks Ref A or B for the handoff; a different choice needs approval again. */
    fun chooseRef(number: Int, id: ArtifactId): ShotResult = withShot(number) { p, shot ->
        if (id !in shot.refImages) return@withShot ShotResult.Failed("$id is not a reference of this shot")
        save(replace(p, if (shot.chosenRef == id) shot else shot.copy(chosenRef = id, approvals = shot.approvals.copy(ref = false))))
    }

    fun approveRef(number: Int, approved: Boolean = true) = approve(number, ApprovalStep.REF, approved)

    private fun compiled(p: Project, shot: Shot): CoreResult<Shot> {
        val gate = WorkflowRules.canCompilePrompt(shot)
        if (!gate.allowed) return CoreResult.Err(CoreError.InvalidInput(gate.blockers.joinToString()))
        val spec = when (val r = session.readJson(shot.spec!!, ShotSpec.serializer())) {
            is CoreResult.Ok -> r.value
            is CoreResult.Err -> return r
        }
        val ir = when (val r = PromptCompiler.compile(spec, p.globalStyle, p.characters, p.settings.ratio, shot.durationMs)) {
            is CoreResult.Ok -> r.value
            is CoreResult.Err -> return r
        }
        val artifacts = session.artifacts() ?: return CoreResult.Err(CoreError.InvalidInput("No project is open"))
        val json = CoreJson.encodeToString(PromptIR.serializer(), ir).encodeToByteArray()
        return when (val r = artifacts.put(json, ArtifactKind.PROMPT_IR, "json", shot.number, PromptCompiler.inputsFor(p, shot), ShotOps.PROVIDER_COMPILER)) {
            is CoreResult.Ok -> CoreResult.Ok(
                if (shot.promptIr == r.value.id) shot else shot.copy(promptIr = r.value.id, approvals = shot.approvals.copy(prompt = false)),
            )
            is CoreResult.Err -> r
        }
    }

    /** Approving follows `WorkflowRules.approveAll` eligibility (pointer present, not stale). */
    private fun approve(number: Int, step: ApprovalStep, approved: Boolean): ShotResult = withShot(number) { p, shot ->
        val prompt = step == ApprovalStep.PROMPT
        if (!approved) {
            return@withShot save(replace(p, shot.copy(approvals = if (prompt) shot.approvals.copy(prompt = false) else shot.approvals.copy(ref = false))))
        }
        val next = WorkflowRules.approveAll(p.copy(shots = listOf(shot)), step, session.lookup()).shots.single()
        when {
            prompt && !next.approvals.prompt -> ShotResult.Blocked(listOf(if (shot.promptIr == null) Blocker.NO_PROMPT else Blocker.PROMPT_STALE))
            !prompt && !next.approvals.ref ->
                if (shot.chosenRef == null) ShotResult.Blocked(listOf(Blocker.NO_CHOSEN_REF)) else ShotResult.Failed(STALE_REF)
            else -> save(replace(p, next))
        }
    }

    private inline fun withShot(number: Int, block: (Project, Shot) -> ShotResult) = ShotOps.withShot(session, number, block)
    private fun save(p: Project) = ShotOps.save(session, p)

    companion object {
        const val STALE_REF = "This reference was made for an older prompt: copy the image prompt again and import a new one"
    }
}
