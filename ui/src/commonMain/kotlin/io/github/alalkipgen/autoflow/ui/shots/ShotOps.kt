package io.github.alalkipgen.autoflow.ui.shots

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.Shot
import io.github.alalkipgen.autoflow.core.prompt.ImageDialect
import io.github.alalkipgen.autoflow.core.prompt.PromptIR
import io.github.alalkipgen.autoflow.core.prompt.VeoFlowDialect
import io.github.alalkipgen.autoflow.core.workflow.Blocker
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession

/** Outcome of a shot action. [Done.note] is a warning or hint to show (e.g. ratio mismatch, "generate in AI Studio"). */
sealed interface ShotResult {
    data class Done(val note: String? = null) : ShotResult
    data class Blocked(val blockers: List<Blocker>) : ShotResult
    data class Failed(val message: String) : ShotResult
}

/** Ref A = `refImages[0]`, Ref B = `refImages[1]` (DESIGN.md §3 Shot detail). */
enum class RefSlot { A, B }

/** Shared by [ShotService] and [HandoffService]: one open project, one shot, one save per action. */
internal object ShotOps {
    const val PROVIDER_AI = "ai"
    const val PROVIDER_USER = "user"
    const val PROVIDER_COMPILER = "compiler"
    const val PROVIDER_FLOW = "flow"
    val noProject = ShotResult.Failed("No project is open")
    fun noShot(n: Int) = ShotResult.Failed("No shot $n")
    const val UNREADABLE_PROMPT = "The prompt of this shot cannot be read"

    fun prompt(session: ProjectSession, shot: Shot): PromptIR? =
        shot.promptIr?.let { (session.readJson(it, PromptIR.serializer()) as? CoreResult.Ok)?.value }
    fun flowPrompt(session: ProjectSession, shot: Shot): String? = prompt(session, shot)?.let(VeoFlowDialect::render)
    fun imagePrompt(session: ProjectSession, shot: Shot): String? = prompt(session, shot)?.let(ImageDialect::render)

    fun replace(p: Project, shot: Shot) = p.copy(shots = p.shots.map { if (it.number == shot.number) shot else it })

    inline fun withShot(session: ProjectSession, number: Int, block: (Project, Shot) -> ShotResult): ShotResult {
        val p = session.open?.project ?: return noProject
        val shot = p.shots.firstOrNull { it.number == number } ?: return noShot(number)
        return block(p, shot)
    }

    fun save(session: ProjectSession, p: Project): ShotResult = when (val r = session.save(p)) {
        is CoreResult.Ok -> ShotResult.Done()
        is CoreResult.Err -> ShotResult.Failed(r.error.message)
    }

    fun PlatformResult<Unit>.failure(): ShotResult? = when (this) {
        is PlatformResult.Ok -> null
        is PlatformResult.Unsupported -> ShotResult.Failed(reason)
        is PlatformResult.Failed -> ShotResult.Failed(reason)
    }
}
