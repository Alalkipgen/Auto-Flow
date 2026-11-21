package io.github.alalkipgen.autoflow.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.ai.usage.UsageCounter
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.home.HomeScreen
import io.github.alalkipgen.autoflow.ui.media.thumbSource
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.jobs.JobsActions
import io.github.alalkipgen.autoflow.ui.jobs.JobsScreen
import io.github.alalkipgen.autoflow.ui.jobs.jobRows
import io.github.alalkipgen.autoflow.ui.jobs.jobsBadgeValue
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.DashboardModel
import io.github.alalkipgen.autoflow.ui.projects.DashboardScreen
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.recap.QaRecapFixture
import io.github.alalkipgen.autoflow.ui.recap.RecapModel
import io.github.alalkipgen.autoflow.ui.recap.RecapNavigation
import io.github.alalkipgen.autoflow.ui.recap.RecapStep
import io.github.alalkipgen.autoflow.ui.recap.StudioRecapScreen
import io.github.alalkipgen.autoflow.ui.settings.*
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Dest
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Shell
import io.github.alalkipgen.autoflow.ui.shell.ShellActions
import io.github.alalkipgen.autoflow.ui.shell.ShellShortcut
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.shell.V2SubBar
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Phase 9 before / after screenshots for every PR (DESIGN.md §9.6): a 360 dp phone in Burmese at font 1.0 and 2.0
 * (drawn at 2× so the PNG reads like the phone), a 760 dp medium window (rail + centred phone layout) and a 1440 × 900
 * desktop window. Runs only when
 * `AUTOFLOW_PR_SHOTS_DIR` is set; writes `<screen>-<case>.png`. Fixture data only — nothing is saved.
 */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class PrShotsTest(val width: Int, val font: Float) {
    companion object {
        private val fixture by lazy { QaRecapFixture.create(Files.createTempDirectory("pr-shots").toFile()) }
        @JvmStatic @Parameterized.Parameters(name = "{0}dp/{1}")
        fun cases() = listOf(arrayOf<Any>(360, 1f), arrayOf<Any>(360, 2f), arrayOf<Any>(760, 1f), arrayOf<Any>(1440, 1f))
    }
    private val out = System.getenv("AUTOFLOW_PR_SHOTS_DIR")?.let(::File)
    private val phone get() = width < 600
    private val case get() = if (phone) "phone-$font" else if (width < 840) "medium" else "desktop"
    private val language = UiLanguage.BURMESE
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    // The app's sidebar actions (AppShell): New project, Settings and the two always-on create shortcuts.
    private val actions = ShellActions({}, {}, listOf(
        ShellShortcut(UiText.MOVIE_RECAP, AfIcons.Duotone.Film, AutoFlowColors.PrimaryFill, "nav-create-recap") {},
        ShellShortcut(UiText.MUSIC_VIDEO, AfIcons.Duotone.Music, AutoFlowColors.Info, "nav-create-mv") {},
    ))

    private fun shot(screen: String, after: (ComposeUiTest.() -> Unit)? = null, content: @Composable () -> Unit) {
        val dir = out ?: return
        val scale = if (phone) 2f else 1f
        runDesktopComposeUiTest(width = (width * scale).toInt(), height = ((if (phone) 760 else 900) * scale).toInt()) {
            setContent {
                CompositionLocalProvider(LocalCompact provides phone, LocalDensity provides Density(scale, font),
                    LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) { content() }
            }
            waitForIdle()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir.apply { mkdirs() }, "$screen-$case.png"))
            // A second state of the same screen (e.g. a selected desktop card), written as `<screen>-selected-<case>.png`.
            after?.let {
                it()
                waitForIdle()
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$screen-selected-$case.png"))
            }
        }
    }

    private fun index() = ProjectIndex(JvmFs(), File(fixture.dir.parentFile, "index-shots-$width-$font.json").path)
        .apply { record(fixture.dir.path, fixture.project) }
    private fun jobs() = jobRows(null, null, null, listOf(
        AiJob("running", AiJobKind.TTS_ALL, fixture.dir.path, listOf(fixture.story.blocks.last().id), AiJobState.RUNNING),
        AiJob("paused", AiJobKind.TTS_ALL, fixture.dir.path, fixture.story.blocks.map { it.id }, AiJobState.PAUSED, "QA model busy"),
        AiJob("failed", AiJobKind.TRANSCRIBE, fixture.dir.path, listOf(fixture.story.blocks.first().id), AiJobState.FAILED, "QA failure"),
        AiJob("done", AiJobKind.TTS_ALL, fixture.dir.path, emptyList(), AiJobState.DONE),
    ), language, mapOf(fixture.dir.path to fixture.project.name))

    @Test fun home() {
        val index = index(); val rows = jobs()
        shot("home") {
            StudioV2Shell(StudioV2Dest.HOME, {}, jobsBadgeValue(rows), width.toFloat(), actions = actions) {
                HomeScreen(recent = index.entries.value, jobs = rows, thumbSource = { dir -> ProjectStore(JvmFs()).thumbSource(dir) })
            }
        }
    }

    /** Projects: the recap fixture (with its running / failed jobs), a music video and a subtitles project. */
    @Test fun projects() {
        val fs = JvmFs()
        val index = ProjectIndex(fs, File(fixture.dir.parentFile, "index-projects-$width-$font.json").path)
        index.record(fixture.dir.path, fixture.project)
        listOf(Triple("ချစ်သူ့ ကမ္ဘာ", "mv", Mode.MUSIC_VIDEO), Triple("Interview", "subs", Mode.SUBTITLES)).forEach { (name, folder, mode) ->
            val dir = File(fixture.dir.parentFile, "shots-$folder.autoflow").path
            val project = (ProjectStore(fs).open(dir) as? CoreResult.Ok)?.value ?: (ProjectStore(fs).create(dir, name, mode) as CoreResult.Ok).value
            index.record(dir, project)
        }
        val model = DashboardModel(fs, ProjectStore(fs), index,
            SettingsStore(fs, File(fixture.dir.parentFile, "settings-shots.json").path), FakePicker(), CurrentProject())
        val rows = jobs()
        // Desktop D2: click the recap card, so the detail pane shows (single click waits out the double-click time).
        val select: (ComposeUiTest.() -> Unit)? = if (width < 1440) null else ({
            onNodeWithTag("project-card-click-${fixture.project.name}").performMouseInput { click() }
            mainClock.advanceTimeBy(1_000)
        })
        shot("projects", select) {
            StudioV2Shell(StudioV2Dest.PROJECTS, {}, jobsBadgeValue(rows), width.toFloat(), actions = actions) {
                DashboardScreen(model, {}, jobs = rows, thumbSource = { dir -> ProjectStore(JvmFs()).thumbSource(dir) })
            }
        }
    }

    @Test fun jobsList() {
        val rows = jobs()
        // Desktop list–detail: select the failed job, so the detail pane shows its reason and Retry.
        val select: (ComposeUiTest.() -> Unit)? = if (width < 1440) null else ({
            onNodeWithTag("job-card-failed").performMouseInput { click() }
        })
        shot("jobs", select) {
            StudioV2Shell(StudioV2Dest.JOBS, {}, jobsBadgeValue(rows), width.toFloat(), actions = actions) {
                JobsScreen(rows, scope, {}, actions = JobsActions(scope), thumbSource = { dir -> ProjectStore(JvmFs()).thumbSource(dir) })
            }
        }
    }

    /** A project editor (top bar, step bar, sticky actions): Movie Recap's Story step in the editor shell. */
    @Test fun recapStory() {
        val fs = JvmFs(); val current = CurrentProject().apply { set(OpenProject(fixture.dir.path, fixture.project)) }
        val model = RecapModel(ProjectSession(fs, ProjectStore(fs), index(), current), fs, null, null, scope)
        shot("recap-story") {
            StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat(), editor = true, actions = actions) {
                StudioRecapScreen(model, RecapStep.STORY, width.toFloat(), {}, RecapNavigation({}, {}, {}))
            }
        }
    }

    @Test fun settings() {
        val fs = InMemoryFileSystem(); val settings = SettingsStore(fs, "/app/settings.json")
        settings.update { it.copy(uiLanguage = language.code) }
        val secrets = MemorySecureStorage(); val provider = ProviderStatusModel(settings, secrets)
        val model = SettingsModel(settings, secrets, provider, UsageCounter(fs, "/app/usage.json"),
            loadModelConfig(fs, null) { TEST_MODELS_JSON }, FakeTools(ffmpeg = "ffmpeg", bundled = phone),
            FakePicker(), { PlatformResult.Ok(Unit) }, { error("No provider call in screenshots") },
            bundledFeatures = """{"schemaVersion":1,"studioV2":true}""")
        shot("settings") {
            StudioV2Shell(StudioV2Dest.HOME, {}, 0, width.toFloat(), actions = actions) {
                Column(Modifier.fillMaxSize()) {
                    V2SubBar(UiText.BACK.text(language), provider.current()) {}
                    Box(Modifier.weight(1f)) { SettingsScreen(model, info = AppInfo("QA fixture host")) }
                }
            }
        }
    }
}
