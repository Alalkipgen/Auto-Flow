package io.github.alalkipgen.autoflow.ui.storyboard

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.storyboard.StoryboardBrief
import io.github.alalkipgen.autoflow.ai.storyboard.StoryboardGenerator
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.Artifact
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.Shot
import io.github.alalkipgen.autoflow.core.model.ShotSpec
import io.github.alalkipgen.autoflow.core.model.SongAnalysis
import io.github.alalkipgen.autoflow.core.model.StoryboardShot
import io.github.alalkipgen.autoflow.core.model.StoryboardVersion
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.workflow.ApprovalStep
import io.github.alalkipgen.autoflow.core.workflow.Blocker
import io.github.alalkipgen.autoflow.core.workflow.Inputs
import io.github.alalkipgen.autoflow.core.workflow.ShotSplitter
import io.github.alalkipgen.autoflow.core.workflow.WorkflowRules
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession

/** Outcome of a storyboard action. Nothing in the project changes unless it is [Done]. */
sealed interface StoryboardResult {
    data object Done : StoryboardResult
    data class Blocked(val blockers: List<Blocker>) : StoryboardResult
    data class Failed(val message: String) : StoryboardResult
}

/**
 * Storyboard pipeline for the open project (ARCHITECTURE.md §3): analyse → split → one spec per shot, stored as
 * `an` / `sb` / `sp` artifacts, then one project save. A shot whose spec changes loses only its storyboard approval
 * (D-21); downstream pointers stay and turn stale. Call from one coroutine at a time (the UI disables actions while
 * one runs); cancelling a running call leaves the project unchanged.
 */
class StoryboardService(private val session: ProjectSession, private val generator: StoryboardGenerator) {
    /** Generates a new storyboard version for the whole song ("Storyboard ထုတ်မယ်", regenerate all). */
    suspend fun generateAll(): StoryboardResult {
        val project = session.open?.project ?: return noProject
        val lyrics = session.readLyrics()
        val gate = WorkflowRules.canGenerateStoryboard(project, lyrics)
        if (!gate.allowed) return StoryboardResult.Blocked(gate.blockers)
        val brief = brief(project, lyrics)
        val analysis = when (val r = generator.analyze(brief)) {
            is AiResult.Ok -> r
            else -> return failed(r)
        }
        val shots = when (val r = ShotSplitter.split(project.settings.durationMs, project.settings.clipMs, analysis.value.sections)) {
            is CoreResult.Ok -> r.value
            is CoreResult.Err -> return StoryboardResult.Failed(r.error.message)
        }
        val specs = when (val r = generator.storyboard(brief, analysis.value, shots)) {
            is AiResult.Ok -> r
            else -> return failed(r)
        }
        val artifacts = session.artifacts() ?: return noProject
        val an = put(artifacts, CoreJson.encodeToString(SongAnalysis.serializer(), analysis.value), ArtifactKind.ANALYSIS, model = analysis.usage?.model)
            ?: return writeFailed
        val version = StoryboardVersion(
            analysis = an.id,
            shots = shots.zip(specs.value) { s, spec -> StoryboardShot(s.number, s.startMs, s.durationMs, s.section, spec) },
        )
        val sb = put(artifacts, CoreJson.encodeToString(StoryboardVersion.serializer(), version), ArtifactKind.STORYBOARD, model = specs.usage?.model)
            ?: return writeFailed
        val next = applyVersion(project, artifacts, sb.id, version, specs.usage?.model) ?: return writeFailed
        return save(next.copy(analysis = an.id))
    }

    /** New take for one shot. [keepScene]: keep the user's sceneMy and only rewrite the English fields to match. */
    suspend fun regenerateShot(number: Int, keepScene: Boolean = false): StoryboardResult {
        val project = session.open?.project ?: return noProject
        val shot = project.shots.firstOrNull { it.number == number } ?: return StoryboardResult.Failed("No shot $number")
        val analysisId = project.analysis ?: return StoryboardResult.Failed("Generate the storyboard first")
        val analysis = when (val r = session.readJson(analysisId, SongAnalysis.serializer())) {
            is CoreResult.Ok -> r.value
            is CoreResult.Err -> return StoryboardResult.Failed(r.error.message)
        }
        val notes = if (keepScene) spec(shot)?.sceneMy?.takeIf { it.isNotBlank() }?.let { mapOf(number to it) }.orEmpty() else emptyMap()
        return when (val r = generator.storyboard(brief(project, session.readLyrics()), analysis, listOf(shot), notes)) {
            is AiResult.Ok -> {
                val spec = r.value.single().let { s -> notes[number]?.let { s.copy(sceneMy = it) } ?: s }
                replaceSpec(project, shot, spec, provider = PROVIDER_AI, model = r.usage?.model)
            }
            else -> failed(r)
        }
    }

    /** Saves the user's edit of [spec] (Burmese scene and/or English fields) as a new `sp` artifact. */
    fun editSpec(number: Int, spec: ShotSpec): StoryboardResult {
        val project = session.open?.project ?: return noProject
        val shot = project.shots.firstOrNull { it.number == number } ?: return StoryboardResult.Failed("No shot $number")
        return replaceSpec(project, shot, spec, provider = PROVIDER_USER, model = null)
    }

    /** ✓ Approve / un-approve one shot. Approving follows `WorkflowRules.approveAll` eligibility (spec present, not stale). */
    fun approve(number: Int, approved: Boolean = true): StoryboardResult {
        val project = session.open?.project ?: return noProject
        val shots = project.shots.map { shot ->
            when {
                shot.number != number -> shot
                !approved -> shot.copy(approvals = shot.approvals.copy(storyboard = false))
                else -> WorkflowRules.approveAll(project.copy(shots = listOf(shot)), ApprovalStep.STORYBOARD, session.lookup()).shots.single()
            }
        }
        return save(project.copy(shots = shots))
    }

    fun approveAll(): StoryboardResult {
        val project = session.open?.project ?: return noProject
        return save(WorkflowRules.approveAll(project, ApprovalStep.STORYBOARD, session.lookup()))
    }

    /** Makes an earlier version current again: its timings and specs (re-put, same ids) replace the shots' specs. */
    fun switchVersion(id: ArtifactId): StoryboardResult {
        val project = session.open?.project ?: return noProject
        if (id !in project.storyboards) return StoryboardResult.Failed("Unknown storyboard version $id")
        val version = when (val r = session.readJson(id, StoryboardVersion.serializer())) {
            is CoreResult.Ok -> r.value
            is CoreResult.Err -> return StoryboardResult.Failed(r.error.message)
        }
        val artifacts = session.artifacts() ?: return noProject
        val next = applyVersion(project, artifacts, id, version, model = null) ?: return writeFailed
        return save(next.copy(analysis = version.analysis ?: project.analysis))
    }

    /** The spec a shot points at, or `null` (none yet, or unreadable). */
    fun spec(shot: Shot): ShotSpec? = shot.spec?.let { (session.readJson(it, ShotSpec.serializer()) as? CoreResult.Ok)?.value }

    /**
     * Shots follow the version's timings; existing shots keep their downstream pointers. Specs are content-addressed,
     * so a spec identical to one from another version keeps that version's `storyboard` input (rare; shows as stale).
     */
    private fun applyVersion(project: Project, artifacts: ArtifactStore, sb: ArtifactId, version: StoryboardVersion, model: String?): Project? {
        val old = project.shots.associateBy { it.number }
        val shots = version.shots.map { s ->
            val spec = s.spec.copy(shot = s.number)
            val sp = put(artifacts, CoreJson.encodeToString(ShotSpec.serializer(), spec), ArtifactKind.SHOT_SPEC, s.number,
                mapOf(Inputs.STORYBOARD to sb.value), model = model) ?: return null
            val base = old[s.number] ?: Shot(number = s.number, startMs = s.startMs, durationMs = s.durationMs)
            withSpec(base.copy(startMs = s.startMs, durationMs = s.durationMs, section = s.section), spec, sp.id)
        }
        return project.copy(storyboards = (project.storyboards + sb).distinct(), currentStoryboard = sb, shots = shots)
    }

    private fun replaceSpec(project: Project, shot: Shot, spec: ShotSpec, provider: String, model: String?): StoryboardResult {
        val artifacts = session.artifacts() ?: return noProject
        val inputs = project.currentStoryboard?.let { mapOf(Inputs.STORYBOARD to it.value) }.orEmpty()
        val fixed = spec.copy(shot = shot.number)
        val sp = put(artifacts, CoreJson.encodeToString(ShotSpec.serializer(), fixed), ArtifactKind.SHOT_SPEC, shot.number, inputs, provider, model)
            ?: return writeFailed
        return save(project.copy(shots = project.shots.map { if (it.number == shot.number) withSpec(it, fixed, sp.id) else it }))
    }

    /** D-21: a different spec id clears this shot's storyboard approval; everything downstream is kept. */
    private fun withSpec(shot: Shot, spec: ShotSpec, id: ArtifactId): Shot = shot.copy(
        spec = id,
        lyricsMy = spec.lyricsMy,
        approvals = if (shot.spec == id) shot.approvals else shot.approvals.copy(storyboard = false),
    )

    private fun put(
        artifacts: ArtifactStore, json: String, kind: ArtifactKind, shot: Int? = null,
        inputs: Map<String, String> = emptyMap(), provider: String? = PROVIDER_AI, model: String? = null,
    ): Artifact? = (artifacts.put(json.encodeToByteArray(), kind, "json", shot, inputs, provider, model) as? CoreResult.Ok)?.value

    private fun save(project: Project): StoryboardResult = when (val r = session.save(project)) {
        is CoreResult.Ok -> StoryboardResult.Done
        is CoreResult.Err -> StoryboardResult.Failed(r.error.message)
    }

    private fun brief(project: Project, lyrics: String) = StoryboardBrief(lyrics, project.settings, project.globalStyle, project.characters)

    private fun failed(r: AiResult<*>): StoryboardResult.Failed = StoryboardResult.Failed(
        when (r) {
            is AiResult.RateLimited -> "The free-tier limit is reached" + (r.retryAfterMs?.let { "; try again in ${it / 1000} s" } ?: "; try again later")
            is AiResult.Failed -> r.msg
            is AiResult.Deferred -> r.instruction
            is AiResult.Ok -> "unexpected"
        },
    )

    private companion object {
        const val PROVIDER_AI = "ai"
        const val PROVIDER_USER = "user"
        val noProject = StoryboardResult.Failed("No project is open")
        val writeFailed = StoryboardResult.Failed("Could not write to the project's artifacts folder")
    }
}
