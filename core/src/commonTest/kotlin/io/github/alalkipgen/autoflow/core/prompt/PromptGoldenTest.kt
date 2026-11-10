package io.github.alalkipgen.autoflow.core.prompt

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.Character
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.GlobalStyle
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.model.ShotSpec
import io.github.alalkipgen.autoflow.core.readTestResource
import io.github.alalkipgen.autoflow.core.updateGolden
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable

/** Golden tests (specs/prompt-compiler.md §3): `golden/<case>.spec.json` → `.veo.txt` and `.image.txt`. */
class PromptGoldenTest {
    @Serializable
    private data class Case(
        val spec: ShotSpec,
        val globalStyle: GlobalStyle,
        val characters: List<Character>,
        val ratio: Ratio,
        val durationMs: Long,
    )

    private fun check(name: String) {
        val case = CoreJson.decodeFromString<Case>(readTestResource("golden/$name.spec.json"))
        val ir = assertIs<CoreResult.Ok<PromptIR>>(
            PromptCompiler.compile(case.spec, case.globalStyle, case.characters, case.ratio, case.durationMs),
        ).value
        for ((ext, dialect) in listOf("veo" to VeoFlowDialect, "image" to ImageDialect)) {
            val actual = dialect.render(ir) + "\n"
            val path = "golden/$name.$ext.txt"
            if (updateGolden(path, actual)) continue
            assertEquals(readTestResource(path), actual, "$path (regenerate with UPDATE_GOLDEN=1 if intended)")
            assertTrue(actual.trimEnd().length <= PromptDialect.MAX_CHARS)
        }
    }

    @Test fun narrative() = check("narrative")

    @Test fun performance() = check("performance")

    @Test fun abstract() = check("abstract")
}
