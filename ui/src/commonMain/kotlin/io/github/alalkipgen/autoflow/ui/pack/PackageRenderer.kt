package io.github.alalkipgen.autoflow.ui.pack

import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.library.LibraryStore
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleExport
import io.github.alalkipgen.autoflow.core.subtitles.TextChoice
import io.github.alalkipgen.autoflow.media.render.BurnFit
import io.github.alalkipgen.autoflow.media.render.BurnLength
import io.github.alalkipgen.autoflow.media.render.BurnLook
import io.github.alalkipgen.autoflow.media.render.BurnSize
import io.github.alalkipgen.autoflow.media.render.Quality
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.ui.dubbing.DubExportModel
import io.github.alalkipgen.autoflow.ui.export.ExportModel
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiNotice
import io.github.alalkipgen.autoflow.ui.projects.DashboardModel
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.subtitles.SubtitleBurnModel
import io.github.alalkipgen.autoflow.ui.subtitles.SubtitleVersions
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** How a headless render of a package ended. */
sealed interface PackageRenderResult {
    /** [output] = the finished file (absolute). */
    data class Done(val output: String) : PackageRenderResult
    data class Failed(val reason: String) : PackageRenderResult
}

/**
 * F-7 (D-96) render without the window (`AutoFlow --render`): opens a project folder (an unpacked render package) as
 * the app does, adds the package's pronunciation fixes to the Library, applies the export choices of its
 * `render.json` and runs the same export the screen runs — Movie Recap / Music Video [ExportModel.render], Dubbing
 * [DubExportModel.start], Subtitles [SubtitleBurnModel.start] — waiting for it to finish. Without `render.json`
 * (any project folder) the saved choices or the defaults are used.
 */
class PackageRenderer(
    private val dashboard: DashboardModel,
    private val session: ProjectSession,
    private val fs: FileSystem,
    private val export: ExportModel,
    private val dub: DubExportModel,
    private val burn: SubtitleBurnModel,
    private val library: LibraryStore? = null,
    clock: Clock = Clock.System,
) {
    private val versions = SubtitleVersions(session, fs, clock)

    /** [quality] overrides the package's (Movie Recap / Music Video); [onProgress] gets 0–1 while it runs. */
    suspend fun render(dir: String, quality: Quality? = null, onProgress: (Double) -> Unit = {}): PackageRenderResult {
        val plan = RenderPlan.read(fs, dir)
        plan?.let { p -> library?.let { RenderPlan.mergeLexicon(it, p.lexicon) } }
        if (!dashboard.open(dir)) return fail(dashboard.state.value.notice, dashboard.state.value.message ?: "Cannot open the project in $dir.")
        val project = session.open?.project ?: return PackageRenderResult.Failed("Cannot open the project in $dir.")
        return when (project.mode) {
            Mode.MOVIE_RECAP, Mode.MUSIC_VIDEO -> renderExport(quality ?: plan?.quality?.let { enumOrNull<Quality>(it) } ?: Quality.EXPORT, onProgress)
            Mode.DUBBING -> renderDub(dir, plan, onProgress)
            Mode.SUBTITLES -> renderBurn(dir, plan, onProgress)
            Mode.VOICE_AUDIO -> PackageRenderResult.Failed("A Text → voice project has no video to render.")
        }
    }

    private suspend fun renderExport(quality: Quality, onProgress: (Double) -> Unit): PackageRenderResult {
        val before = export.state.value.run?.id
        export.render(quality)
        val s = export.state.value
        val run = s.run?.takeIf { it.id != before }
            ?: return fail(s.notice, s.message ?: s.unavailable ?: s.recap?.blocker ?: "The project cannot be rendered yet.")
        val end = export.state.mapNotNull { it.run?.takeIf { r -> r.id == run.id }?.state }
            .onEach { (it as? RenderState.Running)?.let { r -> onProgress(r.fraction) } }
            .first { it.finished }
        return ended(end)
    }

    private suspend fun renderDub(dir: String, plan: RenderPlan?, onProgress: (Double) -> Unit): PackageRenderResult {
        plan?.size?.let { enumOrNull<BurnSize>(it) }?.let(dub::setSize)
        fit(plan)?.let(dub::setFit)
        if (plan?.wav == true) dub.setWav(true)
        val doc = doc(dir, plan) ?: return PackageRenderResult.Failed("The project has no subtitles to dub.")
        val job = dub.start(doc) ?: return fail(dub.state.value.notice, "The dub cannot be made yet.")
        coroutineScope {
            val watch = launch { while (true) { (dub.current()?.state as? RenderState.Running)?.let { onProgress(it.fraction) }; delay(PROGRESS_MS) } }
            job.join()
            watch.cancel()
        }
        dub.done()?.let { return PackageRenderResult.Done(it) }
        return dub.current()?.state?.let(::ended) ?: fail(dub.state.value.notice, "The dub was not made.")
    }

    private suspend fun renderBurn(dir: String, plan: RenderPlan?, onProgress: (Double) -> Unit): PackageRenderResult {
        plan?.look?.let { enumOrNull<BurnLook>(it) }?.let(burn::setLook)
        plan?.size?.let { enumOrNull<BurnSize>(it) }?.let(burn::setSize)
        fit(plan)?.let(burn::setFit)
        val doc = doc(dir, plan) ?: return PackageRenderResult.Failed("The project has no subtitles to burn.")
        val version = plan?.docVersion?.let(::ArtifactId) ?: session.open?.project?.subtitles?.current
        val choice = plan?.text?.let { enumOrNull<TextChoice>(it) } ?: SubtitleExport.defaultChoice(doc)
        burn.start(doc, version, choice)
        val run = burn.run() ?: return fail(burn.state.value.notice, "The video cannot be made yet.")
        val end = burn.runs.mapNotNull { runs -> runs.firstOrNull { it.id == run.id }?.state }
            .onEach { (it as? RenderState.Running)?.let { r -> onProgress(r.fraction) } }
            .first { it.finished }
        return ended(end)
    }

    /** The document the export showed (in the package), else the project's current version. */
    private fun doc(dir: String, plan: RenderPlan?): SubtitleDoc? =
        plan?.doc ?: session.open?.project?.subtitles?.current?.let { versions.read(dir, it) }

    private fun fit(plan: RenderPlan?): BurnFit? {
        val lines = plan?.lines ?: return null
        val length = plan.length?.let { enumOrNull<BurnLength>(it) } ?: return null
        return BurnFit(lines, length)
    }

    private fun ended(state: RenderState): PackageRenderResult = when (state) {
        is RenderState.Done -> PackageRenderResult.Done(state.output)
        is RenderState.Failed -> PackageRenderResult.Failed(listOf(state.reason).plus(state.tail.takeLast(TAIL_LINES)).joinToString("\n"))
        is RenderState.Cancelled -> PackageRenderResult.Failed("The render was cancelled.")
        else -> PackageRenderResult.Failed("The render stopped ($state).")
    }

    private fun fail(notice: UiNotice?, fallback: String) = PackageRenderResult.Failed(notice?.text(UiLanguage.ENGLISH) ?: fallback)

    private companion object {
        const val PROGRESS_MS = 500L
        const val TAIL_LINES = 6
    }
}

private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? = enumValues<T>().firstOrNull { it.name == name }
