package io.github.alalkipgen.autoflow.core.prompt

import io.github.alalkipgen.autoflow.core.CoreError
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.Character
import io.github.alalkipgen.autoflow.core.model.GlobalStyle
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.model.Shot
import io.github.alalkipgen.autoflow.core.model.ShotSpec
import io.github.alalkipgen.autoflow.core.workflow.Inputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class PromptCompilerTest {
    private val spec = ShotSpec(
        shot = 1, sceneMy = "မြန်မာ", subjectEn = "{{HERO}}", actionEn = "walks", settingEn = "a market",
        cameraEn = "tracking shot", moodEn = "calm", lightingEn = "noon sun", characters = listOf("HERO", "FRIEND"),
    )
    private val cast = listOf(
        Character(token = "HERO", descriptionEn = "tall woman in a red longyi"),
        Character(token = "FRIEND", descriptionEn = "old man with a cane"),
    )

    private fun compile(s: ShotSpec = spec, chars: List<Character> = cast, style: GlobalStyle = GlobalStyle(), ms: Long = 8_000) =
        PromptCompiler.compile(s, style, chars, Ratio.R9_16, ms)

    private fun ok(r: CoreResult<PromptIR>) = assertIs<CoreResult.Ok<PromptIR>>(r).value

    @Test
    fun expandsTokensAndKeepsOnlyNonInlinedCharacters() {
        val ir = ok(compile())
        assertEquals("tall woman in a red longyi", ir.subject)
        assertEquals(listOf("old man with a cane"), ir.characters)
        assertEquals("9:16", ir.ratio)
        assertTrue(VeoFlowDialect.render(ir).startsWith("Tall woman in a red longyi (old man with a cane) walks, a market."))
    }

    @Test
    fun emptyEnglishFieldsAndUnknownTokensFailWithDetails() {
        val err = assertIs<CoreError.CannotCompile>(
            assertIs<CoreResult.Err>(compile(spec.copy(actionEn = " ", moodEn = "", characters = listOf("GHOST")))).error,
        )
        assertEquals(1, err.shot)
        assertEquals(listOf("actionEn", "moodEn"), err.emptyFields)
        assertEquals(listOf("GHOST"), err.unknownTokens)
        assertIs<CoreResult.Err>(compile(chars = listOf(Character(token = "HERO", descriptionEn = ""), cast[1])))
    }

    @Test
    fun neverTranslatesBurmeseFields() {
        val ir = ok(compile())
        val all = listOf(ir.subject, ir.action, ir.setting, ir.camera, ir.lighting, ir.mood, ir.style) + ir.characters
        assertFalse(all.any { "မြန်မာ" in it })
    }

    @Test
    fun durationRoundsToWholeSeconds() {
        assertEquals(8, ok(compile(ms = 8_000)).durationS)
        assertEquals(10, ok(compile(ms = 9_999)).durationS)
        assertEquals(2, ok(compile(ms = 2_400)).durationS)
    }

    @Test
    fun outputIsPlainTextOnOneLine() {
        val ir = ok(compile(spec.copy(actionEn = "**walks**\nslowly.", settingEn = "# a `market`")))
        val text = VeoFlowDialect.render(ir)
        assertFalse(text.any { it in "*`#\n" }, text)
        assertTrue("walks slowly, a market." in text, text)
    }

    @Test
    fun longInputIsCutAtAWordAndKeepsTheAvoidClause() {
        val words = List(200) { "word$it" }
        val long = words.joinToString(" ")
        val ir = ok(compile(spec.copy(settingEn = long), style = GlobalStyle(negative = "text, watermark")))
        for (dialect in listOf(VeoFlowDialect, ImageDialect)) {
            val text = dialect.render(ir)
            assertTrue(text.length <= PromptDialect.MAX_CHARS, "${text.length}")
            assertTrue(text.endsWith(". Avoid: text, watermark."), text)
            assertTrue(text.substringBefore(". Avoid").substringAfterLast(' ') in words, text)
        }
    }

    @Test
    fun imageDialectReplacesCameraMotionWithStaticFraming() {
        assertEquals("medium close-up, shallow DOF", Dialect.staticFraming("slow push-in, shallow DOF"))
        assertEquals("wide shot", Dialect.staticFraming("slow pull back to reveal the street"))
        assertEquals("low angle", Dialect.staticFraming("handheld orbit around the singer, low angle"))
        assertEquals("macro lens", Dialect.staticFraming("slow pan across the ink clouds, macro lens"))
        assertEquals("medium shot", Dialect.staticFraming("tracking shot following her"))
        assertEquals("close-up, 85mm lens", Dialect.staticFraming("close-up, 85mm lens"))
        assertEquals("panorama of hills", Dialect.staticFraming("panorama of hills"))
        val image = ImageDialect.render(ok(compile()))
        assertTrue(image.contains("Medium shot, noon sun, calm mood, single frame, cinematic still."), image)
    }

    @Test
    fun inputsForRecordsSpecAndStyleHash() {
        val t = Instant.parse("2026-11-10T08:00:00Z")
        val project = Project(id = "p", name = "n", mode = Mode.MUSIC_VIDEO, createdAt = t, updatedAt = t,
            globalStyle = GlobalStyle(hash = "gs_aaaaaaaaaaaa"))
        val shot = Shot(number = 1, startMs = 0, durationMs = 8_000, spec = ArtifactId("sp_333333333333"))
        assertEquals(
            mapOf(Inputs.SPEC to "sp_333333333333", Inputs.GLOBAL_STYLE to "gs_aaaaaaaaaaaa"),
            PromptCompiler.inputsFor(project, shot),
        )
    }
}
