package io.github.alalkipgen.autoflow.core.prompt

import io.github.alalkipgen.autoflow.core.CoreError
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.hash.CharacterHash
import io.github.alalkipgen.autoflow.core.model.Character
import io.github.alalkipgen.autoflow.core.model.GlobalStyle
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.model.Shot
import io.github.alalkipgen.autoflow.core.model.ShotSpec
import io.github.alalkipgen.autoflow.core.workflow.Inputs

/**
 * ShotSpec → PromptIR (specs/prompt-compiler.md §2, §4). Deterministic, no model call, never translates.
 * `{{TOKEN}}` in a `*En` field is replaced by that character's `descriptionEn`.
 */
object PromptCompiler {
    private val TOKEN = Regex("""\{\{([A-Za-z0-9_]+)\}\}""")

    /** The `*En` fields a prompt is built from; each must be non-empty. */
    val REQUIRED_FIELDS = listOf("subjectEn", "actionEn", "settingEn", "cameraEn", "moodEn", "lightingEn")

    fun compile(
        spec: ShotSpec,
        globalStyle: GlobalStyle,
        characters: List<Character>,
        ratio: Ratio,
        durationMs: Long,
    ): CoreResult<PromptIR> {
        val fields = mapOf(
            "subjectEn" to spec.subjectEn, "actionEn" to spec.actionEn, "settingEn" to spec.settingEn,
            "cameraEn" to spec.cameraEn, "moodEn" to spec.moodEn, "lightingEn" to spec.lightingEn,
        )
        val byToken = characters.associateBy { it.token }
        val inlined = fields.values.flatMap { text -> TOKEN.findAll(text).map { it.groupValues[1] } }.toSet()
        val unknown = (inlined + spec.characters).filter { byToken[it]?.descriptionEn.isNullOrBlank() }.distinct()
        val empty = REQUIRED_FIELDS.filter { fields.getValue(it).isBlank() }
        if (empty.isNotEmpty() || unknown.isNotEmpty()) {
            return CoreResult.Err(CoreError.CannotCompile(spec.shot, empty, unknown))
        }

        fun expand(text: String) = Text.clean(TOKEN.replace(text) { byToken.getValue(it.groupValues[1]).descriptionEn })
        return CoreResult.Ok(
            PromptIR(
                shot = spec.shot,
                subject = expand(spec.subjectEn),
                action = expand(spec.actionEn),
                setting = expand(spec.settingEn),
                camera = expand(spec.cameraEn),
                lighting = expand(spec.lightingEn),
                mood = expand(spec.moodEn),
                style = Text.join(globalStyle.visual, globalStyle.palette, globalStyle.lighting, globalStyle.camera),
                characters = spec.characters.distinct().filter { it !in inlined }
                    .map { Text.clean(byToken.getValue(it).descriptionEn) },
                negative = Text.clean(globalStyle.negative),
                ratio = ratio.label,
                durationS = ((durationMs + 500) / 1000).toInt(),
            ),
        )
    }

    /** Character tokens a prompt from [spec] uses: `{{TOKEN}}`s in the `*En` fields plus `spec.characters`. */
    fun usedTokens(spec: ShotSpec): Set<String> {
        val texts = listOf(spec.subjectEn, spec.actionEn, spec.settingEn, spec.cameraEn, spec.moodEn, spec.lightingEn)
        return texts.flatMap { text -> TOKEN.findAll(text).map { it.groupValues[1] } }.toSet() + spec.characters
    }

    /**
     * `inputs` to record on the `pr` artifact, so `Stale.prompt` can detect later changes. With the shot's [spec], the
     * characters it uses are recorded too (Q-10: a changed description marks the prompt stale).
     */
    fun inputsFor(project: Project, shot: Shot, spec: ShotSpec? = null): Map<String, String> = buildMap {
        shot.spec?.let { put(Inputs.SPEC, it.value) }
        put(Inputs.GLOBAL_STYLE, project.globalStyle.hash)
        spec?.let { CharacterHash.input(project.characters, usedTokens(it)) }?.let { put(Inputs.CHARACTERS, it) }
    }
}

/** Plain-text helpers shared by the compiler and dialects. Output never contains markdown or line breaks. */
internal object Text {
    private val MARKDOWN = Regex("""[*`#]+""")
    private val SPACE = Regex("""\s+""")

    fun clean(text: String): String =
        text.replace(MARKDOWN, " ").replace(SPACE, " ").trim().trimEnd('.', ',', ';', ':', ' ')

    fun join(vararg parts: String, separator: String = ", "): String =
        parts.map(::clean).filter { it.isNotEmpty() }.joinToString(separator)

    fun sentence(text: String): String =
        if (text.isEmpty()) "" else text.replaceFirstChar { it.uppercaseChar() } + "."
}
