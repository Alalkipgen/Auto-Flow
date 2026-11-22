package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.ui.i18n.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.*
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.projects.*
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Dest
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Shell
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test

/** Reachability, not the isolated semantics-action/error-message test in RecapLivePreviewTest. */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class ExactFrameReachabilityTest(
    private val width: Int,
    private val fontScale: Float,
    private val bundledFont: Boolean,
    private val classic: Boolean,
    private val language: UiLanguage,
) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "width={0}, font={1}, bundled={2}, classic={3}, language={4}")
        fun cases() = listOf(412, 1000).flatMap { width ->
            listOf(1f, 1.3f, 2f).flatMap { scale ->
                listOf(true, false).flatMap { bundled ->
                    listOf(false, true).flatMap { classic -> UiLanguage.entries.map { language -> arrayOf<Any>(width, scale, bundled, classic, language) } }
                }
            }
        }
    }

    @Test fun scrollDisplayAndPointerClickThroughProductionScreen() = runDesktopComposeUiTest(width = width, height = 760) {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val fs = InMemoryFileSystem()
            val store = ProjectStore(fs)
            val dir = "/p/reachable.autoflow"
            val p = (store.create(dir, "Reachable", Mode.MOVIE_RECAP) as CoreResult.Ok).value
            val story = RecapStory(source = "manual", blocks = listOf(
                RecapBlock("stable-block", listOf(RecapClip(0, 3_000)), "မင်္ဂလာပါ။ ဒီပုံကို အတိအကျ ကြည့်မယ်။"),
            ))
            val id = (ArtifactStore(fs, dir).put(CoreJson.encodeToString(RecapStory.serializer(), story).encodeToByteArray(),
                ArtifactKind.RECAP_SCRIPT, "json") as CoreResult.Ok).value.id
            val recap = RecapV2(rightsConfirmed = true, movie = RecapMovie("source/movie.mp4", 1, 8_000),
                scenes = listOf(RecapScene(0, 3_000, picked = true)), story = id, storyApproved = true)
            val saved = (store.save(dir, p.copy(recap = recap)) as CoreResult.Ok).value
            val current = CurrentProject().apply { set(OpenProject(dir, saved)) }
            val model = RecapModel(ProjectSession(fs, store, ProjectIndex(fs, "/index.json"), current), fs, null, null, scope)
            setContent {
                CompositionLocalProvider(LocalUiLanguage provides language, LocalDensity provides Density(1f, fontScale),
                    LocalUiFontFamily provides if (bundledFont) BurmeseTestFont.family else FontFamily.Default) {
                    StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat()) {
                        if (classic) RecapScreen(model) else StudioRecapScreen(model, RecapStep.STORY, width.toFloat(), {}, RecapNavigation({}, {}, {}))
                    }
                }
            }
            // performScrollTo traverses the real production scroll ancestors. No callback, semantics OnClick,
            // hidden-node action or oversized test viewport: the pointer must hit a displayed button.
            if (!classic) onNodeWithTag("block-select-0").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            // Group 2 A §1: on a phone the Exact frame is the top preview card's frame button (labelled for TalkBack).
            (if (!classic && width < 840) onNodeWithTag("timeline-exact").assert(hasContentDescription(UiText.EXACT_FRAME.text(language)))
                else onNodeWithText(UiText.EXACT_FRAME.text(language)))
                .performScrollTo().assertIsDisplayed().performMouseInput { click() }
            val failure = onNodeWithText("ffmpeg was not found", substring = true)
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("ffmpeg was not found", substring = true).fetchSemanticsNodes().isNotEmpty() }
            failure.performScrollTo().assertIsDisplayed()
        } finally {
            scope.cancel()
        }
    }
}
