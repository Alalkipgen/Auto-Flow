package io.github.alalkipgen.autoflow.ui.subtitles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.*
import io.github.alalkipgen.autoflow.core.recap.SrtCue
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleOps
import io.github.alalkipgen.autoflow.media.playback.MoviePhase
import io.github.alalkipgen.autoflow.media.playback.MovieSnapshot
import io.github.alalkipgen.autoflow.ui.projects.*
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.TextProvider
import io.github.alalkipgen.autoflow.ai.subtitles.SubtitleTranslator
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.recap.AiJobStore
import io.github.alalkipgen.autoflow.core.recap.RpmPacer
import io.github.alalkipgen.autoflow.media.render.RenderJob
import io.github.alalkipgen.autoflow.media.render.RenderQueue
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.platform.FileShare
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.jobs.DesktopAiJobRunner
import io.github.alalkipgen.autoflow.ui.jobs.RecapJobProcessor
import io.github.alalkipgen.autoflow.ui.media.MediaTasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonElement

/**
 * An in-memory Subtitles project named [name] (a Dubbing project with [dub]): [lines] (ids c1, c2, …) over a [durationMs]
 * video. Nothing touches the disk.
 */
internal class SubtitlesFixture(lines: List<Line>, durationMs: Long, name: String = "Clip", private val scope: CoroutineScope = MainScope(),
    dub: Boolean = false) {
    data class Line(val startMs: Long, val endMs: Long, val text: String, val my: String? = null)

    val fs = InMemoryFileSystem(); val store = ProjectStore(fs); val current = CurrentProject()
    val index = ProjectIndex(fs, "/app/projects.json")
    val dir = "/p/Clip.autoflow"
    val session = ProjectSession(fs, store, index, current)
    init {
        val doc = SubtitleOps.fromCues(lines.map { SrtCue(it.startMs, it.endMs, it.text) }, SubtitleDoc.SOURCE_SRT)
            .let { d -> d.copy(cues = d.cues.mapIndexed { i, c -> c.copy(textMy = lines[i].my) }) }
        val created = (store.create(dir, name, if (dub) Mode.DUBBING else Mode.SUBTITLES) as CoreResult.Ok).value
        val st = (ArtifactStore(fs, dir).put(CoreJson.encodeToString(SubtitleDoc.serializer(), doc).encodeToByteArray(),
            ArtifactKind.SUBTITLES, "json") as CoreResult.Ok).value.id
        val video = SourceVideo("source/movie.mp4", 9, durationMs, 1920, 1080, 25.0)
        current.set(OpenProject(dir, (store.save(dir, created.copy(subtitles = SubtitlesTool(video = video, current = st),
            dubbing = if (dub) DubbingTool() else created.dubbing)) as CoreResult.Ok).value))
    }
    val model = SubtitlesModel(session, fs, DashboardModel(fs, store, index, SettingsStore(fs, "/app/s.json"), FakePicker(), current),
        null, scope, probe = { null })
    val editor = SubtitleEditorModel(session, fs, scope)

    /** A paused decoder at the seek: a frame to draw the line on, no sound. */
    val paused: SubtitlePlayer = { _, req, m, report ->
        LaunchedEffect(req) { report(MovieSnapshot(positionMs = req.sourceMs, durationMs = durationMs, phase = MoviePhase.PAUSED)) }
        Box(m.background(AutoFlowColors.Card))
    }

    /** Translate whose provider is never called: the confirm card sends nothing. */
    val translate by lazy {
        val never = object : TextProvider {
            override suspend fun json(prompt: String, schema: JsonSchema, system: String?): AiResult<JsonElement> = error("nothing is sent")
        }
        val job = SubtitleTranslateJob(session, fs, SubtitleTranslator(never), ToolsConfig())
        SubtitleTranslateModel(session, job, DesktopAiJobRunner(AiJobStore(fs, "/app/ai-jobs"), scope,
            RecapJobProcessor({ null }, { null }, { job }), pacer = { RpmPacer(6_000) }), ToolsConfig(), scope)
    }

    /** Export and burn that write nothing: a phone shares ([phone]), the desktop saves and opens the folder. */
    fun exporter(phone: Boolean) = SubtitleExportModel(session, fs, share(phone), scope)
    fun burner(phone: Boolean) = SubtitleBurnModel(session, fs, MediaTasks(object : RenderQueue {
        override fun enqueue(job: RenderJob) = "none"
        override fun observe(id: String) = emptyFlow<RenderState>()
        override fun cancel(id: String) {}
    }, scope), share = share(phone))
    private fun share(phone: Boolean) = object : FileShare {
        override val sharesFiles = phone
        override fun open(path: String, mime: String) = PlatformResult.Ok(Unit)
        override fun share(path: String, mime: String, title: String) = PlatformResult.Ok(Unit)
        override suspend fun saveToGallery(path: String, mime: String, displayName: String) = PlatformResult.Ok(displayName)
        override fun openFolder(path: String) = PlatformResult.Ok(Unit)
    }
}
