package io.github.alalkipgen.autoflow.ui.i18n

import io.github.alalkipgen.autoflow.ui.textFitProblems
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.recap.FitStatus
import io.github.alalkipgen.autoflow.core.recap.MaskPreset
import io.github.alalkipgen.autoflow.core.recap.MaskStrength
import io.github.alalkipgen.autoflow.core.recap.RecapStyle
import io.github.alalkipgen.autoflow.core.recap.VoiceTab
import io.github.alalkipgen.autoflow.core.workflow.ShotStatus
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.export.EXPORT_BUTTON
import io.github.alalkipgen.autoflow.ui.export.PREVIEW_BUTTON
import io.github.alalkipgen.autoflow.ui.export.SAVE_BUTTON
import io.github.alalkipgen.autoflow.ui.export.SHARE
import io.github.alalkipgen.autoflow.ui.home.HomeScreen
import io.github.alalkipgen.autoflow.ui.home.styleLabel
import io.github.alalkipgen.autoflow.ui.jobs.JobsScreen
import io.github.alalkipgen.autoflow.ui.jobs.aiKindLabel
import io.github.alalkipgen.autoflow.ui.jobs.aiStateLabel
import io.github.alalkipgen.autoflow.ui.recap.EXACT_FRAME
import io.github.alalkipgen.autoflow.ui.recap.NARROWER
import io.github.alalkipgen.autoflow.ui.recap.RecapStep
import io.github.alalkipgen.autoflow.ui.recap.RecapStepHeader
import io.github.alalkipgen.autoflow.ui.recap.SHORTER
import io.github.alalkipgen.autoflow.ui.recap.TALLER
import io.github.alalkipgen.autoflow.ui.recap.WIDER
import io.github.alalkipgen.autoflow.ui.recap.label
import io.github.alalkipgen.autoflow.ui.recap.maskLabel
import io.github.alalkipgen.autoflow.ui.recap.strengthLabel
import io.github.alalkipgen.autoflow.ui.recap.transcribeConfirmText
import io.github.alalkipgen.autoflow.ui.recap.transcriptSummary
import io.github.alalkipgen.autoflow.ui.recap.voiceTabLabel
import io.github.alalkipgen.autoflow.ui.shell.MV_CLASSIC_SCREENS
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Dest
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Shell
import io.github.alalkipgen.autoflow.ui.shell.v2ProjectScreens
import io.github.alalkipgen.autoflow.ui.shots.badgeLabel
import io.github.alalkipgen.autoflow.ui.syllableCuts
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 5B-13 (D-41/D-65, DESIGN.md §6/§7): the Studio v2 labels are Burmese, the seven §6 strings are exact, and the
 * Burmese labels fit at font scale 1.0 / 1.3 / 2.0 with the app's own Burmese font (Pyidaungsu): inside the window,
 * 48 dp touch targets, never cut inside a syllable. The owner's phone pass stays a phone-QA step.
 */
@OptIn(ExperimentalTestApi::class)
class StudioBurmeseTest {
    private fun my(s: String) = s.any { it in '\u1000'..'\u109F' }

    /** English words kept as they are in Burmese UI copy (product names and the app's own terms). */
    private val loanwords = setOf(
        "Recap", "Movie", "Music", "Video", "Storyboard", "Shot", "Prompt", "Ref", "Flow", "AI", "Studio", "Brand", "Kit",
        "Publish", "Render", "TTS", "Gemini", "Labs", "Logo", "Preset", "Take", "Watermark", "Looks", "Auto", "Gallery",
        "Thumbnail", "Chapters", "Hashtag", "Batch", "Lexicon", "Script", "Fonts", "Clone", "Voice", "Settings",
    )

    @Test
    fun designCopyIsExact() {
        assertEquals("အတည်ပြုမယ်", My.APPROVE)
        assertEquals("ပြင်မယ်", My.EDIT)
        assertEquals("ပြန်ထုတ်မယ်", My.REGENERATE)
        assertEquals("Prompt ကူးမယ်", My.COPY_PROMPT)
        assertEquals("Flow မှာ လုပ်ရန်", My.NEEDS_FLOW)
        assertEquals("ပြန်ထုတ်ရန် လိုသည်", My.STALE)
        assertEquals("ဗွီဒီယို လက်ခံမယ်", My.ACCEPT_VIDEO)
        // Where the Music Video screens say them.
        assertTrue(ShotStatus.NEEDS_FLOW.badgeLabel(UiLanguage.BURMESE).contains(My.NEEDS_FLOW))
        assertTrue(ShotStatus.STALE.badgeLabel(UiLanguage.BURMESE).contains(My.STALE))
        val storyboard = source("storyboard/StoryboardScreen.kt")
        for (word in listOf(My.APPROVE, My.EDIT, My.REGENERATE, My.STALE)) assertTrue(storyboard.contains(word), word)
        assertTrue(source("handoff/FlowHandoffScreen.kt").contains("UiText.COPY_PROMPT") && source("handoff/FlowHandoffScreen.kt").contains("UiText.ACCEPT_VIDEO"))
    }

    @Test
    fun narrowLabelsBreakBetweenSyllables() {
        assertEquals("စာ\u200Bကြည့်\u200Bတိုက်", My.breakSyllables("စာကြည့်တိုက်"))
        assertEquals("အ\u200Bလုပ်\u200Bများ (3)", My.breakSyllables("အလုပ်များ (3)"))
        assertEquals("ပ\u200Bရော\u200Bဂျက်\u200Bများ", My.breakSyllables("ပရောဂျက်များ"))
        assertEquals("သက္က\u200Bရာဇ်", My.breakSyllables("သက္ကရာဇ်"), "no break before a stacked or a final consonant")
        assertEquals("Jobs (3)", My.breakSyllables("Jobs (3)"))
        assertEquals("ဒီ\u200Bမှာ ပေါ်\u200Bမယ့်\u200Bအ\u200Bတိုင်း", My.breakSyllables("ဒီမှာ ပေါ်မယ့်အတိုင်း"), "a vowel's asat (ပေါ်) is not a final")
        assertEquals("တစ်\u200Bယောက်\u200Bတည်း", My.breakSyllables("တစ်ယောက်တည်း"))
    }

    @Test
    fun navigationStepsAndProjectChipsAreBurmese() {
        StudioV2Dest.entries.forEach { assertTrue(my(it.textKey.my!!), "nav ${it.name}: ${it.textKey.my}") }
        RecapStep.entries.forEach { assertTrue(my(it.textKey.my!!), "step ${it.name}: ${it.textKey.my}") }
        val keys = v2ProjectScreens(true) + v2ProjectScreens(false) + MV_CLASSIC_SCREENS + "Classic Recap"
        keys.forEach { key -> val label = My.screen(key, UiLanguage.BURMESE); assertTrue(my(label) || label in loanwords, "chip $key: $label") }
    }

    @Test
    fun newPhase5LabelsAreBurmese() {
        val labels = buildList {
            MaskPreset.entries.forEach { add(maskLabel(it)) }
            MaskStrength.entries.forEach { add(strengthLabel(it)) }
            addAll(listOf(TALLER, SHORTER, WIDER, NARROWER, EXACT_FRAME, PREVIEW_BUTTON, EXPORT_BUTTON, SAVE_BUTTON, SHARE))
            VoiceTab.entries.forEach { add(voiceTabLabel(it, UiLanguage.BURMESE)) }
            AiJobKind.entries.filter { it != AiJobKind.PUBLISH_KIT }.forEach { add(aiKindLabel(it, UiLanguage.BURMESE)) }
            AiJobState.entries.forEach { add(aiStateLabel(it, UiLanguage.BURMESE)) }
            RecapStyle.entries.forEach { add(styleLabel(it, UiLanguage.BURMESE)) }
            add(transcriptSummary(2, 1, UiLanguage.BURMESE)); add(transcribeConfirmText(2, 1, UiLanguage.BURMESE))
            FitStatus.entries.forEach { s -> add(io.github.alalkipgen.autoflow.core.recap.FitRow(0, 1_000, 2_000, 2_000, s).label(UiLanguage.BURMESE)) }
        }
        labels.forEach { assertTrue(my(it), "not Burmese: $it") }
    }

    /** Every literal label on the Studio v2 screens (buttons, chips, cards, headings, text) carries Burmese. */
    @Test
    fun studioV2ScreensHaveNoEnglishOnlyLabels() {
        val files = listOf(
            "shell/StudioV2Nav.kt", "home/HomeScreen.kt", "recap/StudioRecapScreen.kt", "shotsheet/ShotSheetScreen.kt",
            "jobs/JobsScreen.kt", "library/LibraryScreen.kt", "recap/VoiceLockCard.kt", "recap/VoiceBlockCard.kt",
        )
        val call = Regex("""\bAf(Button|Chip|Card|H3|Text)\(\s*"((?:[^"\\]|\\.)*)"""")
        val bad = files.flatMap { f ->
            call.findAll(withoutTemplates(source(f))).map { it.groupValues[2] }.filter(::englishOnly).map { "$f: \"$it\"" }.toList()
        }
        assertEquals(emptyList(), bad)
    }

    /** Source with every `${...}` template (which may hold quotes of its own) replaced by `$X`. */
    private fun withoutTemplates(src: String): String = buildString {
        var i = 0
        while (i < src.length) {
            if (src.startsWith("\${", i)) {
                var depth = 0
                var j = i + 1
                while (j < src.length) { if (src[j] == '{') depth++ else if (src[j] == '}' && --depth == 0) break; j++ }
                append("\$X"); i = j + 1
            } else { append(src[i]); i++ }
        }
    }

    private fun englishOnly(s: String): Boolean {
        val words = s.replace(Regex("""\$\{[^}]*}|\$\w+"""), " ")
        return !my(words) && Regex("[A-Za-z]{2,}").findAll(words).any { it.value !in loanwords }
    }

    private fun source(path: String): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "ui/src/commonMain").isDirectory }
        return File(root, "ui/src/commonMain/kotlin/io/github/alalkipgen/autoflow/ui/$path").readText()
    }

    @Test fun phone360Font100() = shell(360, 1f)
    @Test fun phone360Font200() = shell(360, 2f)
    @Test fun phone412Font130() = shell(412, 1.3f)
    @Test fun phone412Font200() = shell(412, 2f)
    @Test fun rail600Font200() = shell(600, 2f)
    @Test fun wide840Font130() = shell(840, 1.3f)

    /** Nav (bottom bar or rail), the Recap step header and the Home screen with Burmese labels at [font]. */
    private fun shell(width: Int, font: Float) {
        startKoin { }
        try {
            runDesktopComposeUiTest(width = width, height = 1400) {
                setContent {
                    CompositionLocalProvider(io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage provides io.github.alalkipgen.autoflow.ui.i18n.UiLanguage.BURMESE, LocalDensity provides Density(1f, font), LocalUiFontFamily provides BurmeseTestFont.family) {
                        StudioV2Shell(StudioV2Dest.HOME, {}, 3, width.toFloat()) {
                            Column {
                                RecapStepHeader(RecapStep.VOICE, {}, {}, {})
                                HomeScreen()
                            }
                        }
                    }
                }
                waitForIdle()
                StudioV2Dest.entries.forEach { dest ->
                    val tab = onNode(hasContentDescription(dest.textKey.my!!)).fetchSemanticsNode().boundsInRoot
                    assertTrue(tab.height >= 48f, "${dest.name} tab is a 48 dp target at $width/$font")
                    assertTrue(tab.left >= -0.5f && tab.right <= width + 0.5f, "${dest.name} tab inside the window at $width/$font")
                }
                assertEquals(emptyList<String>(), textFitProblems(width, font))
                val dir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/studio-layout").apply { mkdirs() }
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "burmese-home-$width-font${(font * 100).toInt()}.png"))
            }
        } finally {
            stopKoin()
        }
    }
}
