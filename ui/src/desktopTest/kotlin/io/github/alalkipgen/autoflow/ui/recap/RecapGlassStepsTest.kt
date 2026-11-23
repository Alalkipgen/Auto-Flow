package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.*
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Group 2 A §2 glass details: subtitles row without emoji, Next without the arrow character, Voice filter chips and lock line. */
@OptIn(ExperimentalTestApi::class)
class RecapGlassStepsTest {
    private fun model(name: String, scope: CoroutineScope): Pair<RecapModel, File> {
        val fixture = QaRecapFixture.create(Files.createTempDirectory(name).toFile())
        val fs = JvmFs(); val store = ProjectStore(fs)
        val recap = fixture.project.recap!!.let { it.copy(scenes = listOf(RecapScene(0, 2_000, true), RecapScene(2_000, 4_000)),
            sources = it.sources.copy(subtitles = "source/subtitles.srt")) }
        val saved = (store.save(fixture.dir.path, fixture.project.copy(recap = recap)) as CoreResult.Ok).value
        val current = CurrentProject().apply { set(OpenProject(fixture.dir.path, saved)) }
        val session = ProjectSession(fs, store, ProjectIndex(fs, File(fixture.dir.parentFile, "index.json").path), current)
        return RecapModel(session, fs, null, null, scope) to fixture.dir
    }

    private fun tree(dir: File) = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes().toList() }

    @Test fun studioSourceShowsTheSubtitlesRowAndNoArrowOrEmojiCharacters() = runDesktopComposeUiTest(width = 412, height = 900) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val (model, dir) = model("glass-source", scope)
        try {
            val before = tree(dir)
            setContent { StudioRecapScreen(model, RecapStep.SOURCE, 412f, {}, RecapNavigation({}, {}, {})) }
            onNodeWithText(UiText.SUBTITLES_PRESENT.en).performScrollTo().assertIsDisplayed()
            assertEquals("subtitles.srt", UiText.SUBTITLES_PRESENT.en, "no emoji in the row any more")
            onAllNodes(hasText("📄", substring = true) or hasText("→", substring = true) or hasText("←", substring = true)).assertCountEquals(0)
            onNodeWithTag("recap-next").assertTextEquals(UiText.NEXT_STEP.en.replace("{step}", UiText.STORY.en))
            assertEquals(before, tree(dir), "looking at Source writes nothing")
        } finally { scope.cancel() }
    }

    @Test fun classicShowsTheSameSubtitlesRowInBurmese() = runDesktopComposeUiTest(width = 900, height = 1400) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val (model, _) = model("glass-classic", scope)
        try {
            setContent { CompositionLocalProvider(LocalUiLanguage provides UiLanguage.BURMESE) { RecapScreen(model) } }
            onNodeWithText(UiText.SUBTITLES_PRESENT.text(UiLanguage.BURMESE)).performScrollTo().assertIsDisplayed()
        } finally { scope.cancel() }
    }

    @Test fun voiceFilterChipsSelectOneAtATimeAndTheLockLineHasNoEmoji() = runDesktopComposeUiTest(width = 412, height = 900) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val (model, dir) = model("glass-voice", scope)
        try {
            val before = tree(dir)
            setContent { StudioRecapScreen(model, RecapStep.VOICE, 412f, {}, RecapNavigation({}, {}, {})) }
            fun state(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)
            fun chip(key: UiText) = onNode(hasText(key.en.substringBefore("{"), substring = true) and hasClickAction())
            onNodeWithText(UiText.VOICE_NOT_LOCKED.en).performScrollTo().assertIsDisplayed()
            onAllNodes(hasText("🔒", substring = true)).assertCountEquals(0)
            chip(UiText.V3_ALL_COUNT).performScrollTo().assert(state("selected"))
            chip(UiText.V3_ATTENTION_COUNT).assert(state("not selected")).performMouseInput { click() }
            chip(UiText.V3_ATTENTION_COUNT).assert(state("selected"))
            chip(UiText.V3_ALL_COUNT).assert(state("not selected"))
            chip(UiText.V3_VOICED_COUNT).assert(state("not selected"))
            assertEquals(before, tree(dir), "the filters and the lock line write nothing")
        } finally { scope.cancel() }
    }
}
