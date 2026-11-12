package io.github.alalkipgen.autoflow.core.workflow

import io.github.alalkipgen.autoflow.core.hash.CharacterHash
import io.github.alalkipgen.autoflow.core.model.Artifact
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.Shot

/** Looks up an artifact sidecar by id; `null` when it is missing or unreadable. */
fun interface ArtifactLookup {
    operator fun invoke(id: ArtifactId): Artifact?
}

/** Names of [Artifact.inputs] entries. Producers must record exactly these keys. */
object Inputs {
    const val STORYBOARD = "storyboard"
    const val SPEC = "spec"
    const val GLOBAL_STYLE = "globalStyle"
    const val PROMPT_IR = "promptIr"
    const val REF = "ref"
    const val CHARACTERS = "characters"
}

/**
 * Stale = a recorded input no longer matches the current pointer (specs/workflow-and-approval.md "Stale",
 * ARCHITECTURE.md §5). Computed at read time, never stored. A null pointer is never stale (nothing to compare).
 * Only recorded inputs are compared: an artifact with no value for a key is never stale on that key, so
 * user-provided artifacts (`inputs = {}`) are never stale (Q-08). A pointer whose sidecar cannot be found counts as
 * stale, so downstream generation stays blocked until it is fixed.
 */
object Stale {
    fun spec(project: Project, shot: Shot, lookup: ArtifactLookup): Boolean =
        mismatch(shot.spec, lookup, Inputs.STORYBOARD to project.currentStoryboard?.value)

    fun prompt(project: Project, shot: Shot, lookup: ArtifactLookup): Boolean {
        val recorded = shot.promptIr?.let { lookup(it) }?.inputs?.get(Inputs.CHARACTERS)
        val characters = recorded?.let { CharacterHash.input(project.characters, CharacterHash.tokens(it)) }
        return mismatch(
            shot.promptIr, lookup, Inputs.SPEC to shot.spec?.value, Inputs.GLOBAL_STYLE to project.globalStyle.hash,
            Inputs.CHARACTERS to characters,
        )
    }

    /** [ref] defaults to the chosen reference; any entry of [Shot.refImages] can be checked. */
    fun ref(shot: Shot, lookup: ArtifactLookup, ref: ArtifactId? = shot.chosenRef): Boolean =
        mismatch(ref, lookup, Inputs.PROMPT_IR to shot.promptIr?.value)

    /** [video] defaults to the accepted video; any entry of [Shot.videos] can be checked. */
    fun video(shot: Shot, lookup: ArtifactLookup, video: ArtifactId? = shot.acceptedVideo): Boolean = mismatch(
        video, lookup, Inputs.PROMPT_IR to shot.promptIr?.value, Inputs.REF to shot.chosenRef?.value,
    )

    /** True when any of the shot's current pointers is stale (amber badge). */
    fun any(project: Project, shot: Shot, lookup: ArtifactLookup): Boolean =
        spec(project, shot, lookup) || prompt(project, shot, lookup) || ref(shot, lookup) || video(shot, lookup)

    private fun mismatch(id: ArtifactId?, lookup: ArtifactLookup, vararg expected: Pair<String, String?>): Boolean {
        if (id == null) return false
        val artifact = lookup(id) ?: return true
        return expected.any { (key, current) -> artifact.inputs[key].let { it != null && it != current } }
    }
}
