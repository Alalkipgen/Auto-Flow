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
import io.github.alalkipgen.autoflow.ai.TranscribeProvider
import io.github.alalkipgen.autoflow.ai.usage.UsageCounter
import io.github.alalkipgen.autoflow.core.subtitles.MediaChunks
import io.github.alalkipgen.autoflow.media.probe.MediaInfo
import io.github.alalkipgen.autoflow.ui.i18n.UiNotice
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.recap.SceneAudioCutter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.ui.export.RecapFrames
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import kotlinx.serialization.json.JsonElement

/**
 * An in-memory Subtitles project named [name] (a Dubbing project with [dub]): [lines] (ids c1, c2, …) over a [durationMs]
 * video. Nothing touches the disk.
 */
internal class SubtitlesFixture(lines: List<Line>, durationMs: Long, name: String = "Clip", private val scope: CoroutineScope = MainScope(),
    dub: Boolean = false, probe: suspend (String) -> MediaInfo? = { null },
    extract: (suspend (video: String, index: Int, out: String) -> UiNotice?)? = null) {
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
        null, scope, probe = probe, extract = extract ?: { _, _, _ -> UiNotice(UiText.MOVIE_FFMPEG_UNAVAILABLE) })
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

    /** PR 6: Translate over [text] (null = Gemini not set up), [config] sets the batch size. */
    fun translate(text: TextProvider?, config: ToolsConfig = ToolsConfig()): SubtitleTranslateModel {
        val job = SubtitleTranslateJob(session, fs, text?.let(::SubtitleTranslator), config)
        return SubtitleTranslateModel(session, job, DesktopAiJobRunner(AiJobStore(fs, "/app/ai-jobs"), scope,
            RecapJobProcessor({ null }, { null }, { job }), pacer = { RpmPacer(6_000) }), config, scope)
    }

    /**
     * Transcribe over [gemini] (each chunk's sound is "a-b" ms text), [pauses] found in the sound, the sound cut by
     * [cutter] (default: writes the chunk's ranges); [config] sets the chunk length.
     */
    fun transcribe(gemini: TranscribeProvider, config: ToolsConfig = ToolsConfig(),
        pauses: suspend (String, String) -> List<MediaChunks.Pause>? = { _, _ -> emptyList() },
        cutter: SceneAudioCutter = SceneAudioCutter { _, chunk, out, _ -> fs.writeDurably(out, chunk.ranges.joinToString { "${it.inMs}-${it.outMs}" }.encodeToByteArray()); null },
        usage: UsageCounter = UsageCounter(fs, "/app/usage.json")): SubtitleTranscribeModel {
        val transcriber = VideoTranscriber(session, fs, null, gemini, config, cutter, pauses)
        val runner = DesktopAiJobRunner(AiJobStore(fs, "/app/ai-jobs"), scope, RecapJobProcessor({ null }, { transcriber }), pacer = { RpmPacer(6_000) })
        return SubtitleTranscribeModel(session, transcriber, runner, usage, config, scope)
    }

    /** Export and burn that write nothing: a phone shares ([phone]), the desktop saves and opens the folder. */
    fun exporter(phone: Boolean) = SubtitleExportModel(session, fs, share(phone), scope)
    fun burner(phone: Boolean) = SubtitleBurnModel(session, fs, MediaTasks(object : RenderQueue {
        override fun enqueue(job: RenderJob) = "none"
        override fun observe(id: String) = emptyFlow<RenderState>()
        override fun cancel(id: String) {}
    }, scope), share = share(phone))
    /** PR 7: a render queue the shots move by hand (job1, job2, … start Queued; Cancel = Cancelled). */
    class Queue : RenderQueue {
        val jobs = mutableListOf<RenderJob>(); val states = mutableMapOf<String, MutableStateFlow<RenderState>>()
        override fun enqueue(job: RenderJob): String = "job${jobs.size + 1}".also { jobs += job; states[it] = MutableStateFlow(RenderState.Queued) }
        override fun observe(id: String): Flow<RenderState> = states.getValue(id)
        override fun cancel(id: String) { states.getValue(id).value = RenderState.Cancelled }
    }

    /** PR 7: Export's video part over [queue]; Check one frame runs [frames] (null = no ffmpeg); [font] = a Burmese font in Settings. */
    fun burner(phone: Boolean, queue: RenderQueue, frames: ToolRunner?, font: ByteArray?): SubtitleBurnModel {
        val settings = SettingsStore(fs, "/app/s.json")
        if (font != null) {
            fs.createDirectories("/fonts"); fs.writeDurably("/fonts/my.ttf", font)
            settings.update { it.copy(fontPaths = listOf("/fonts/my.ttf")) }
        }
        val locator = object : ToolLocator {
            override fun findFfmpeg() = "ffmpeg"
            override fun findVlc(): String? = null
            override fun isVlcDir(dir: String) = false
        }
        return SubtitleBurnModel(session, fs, MediaTasks(queue, scope), RecapFrames(session, settings, fs), MediaTools(settings, locator, frames),
            share = share(phone), audioCodec = { _, _ -> "aac" })
    }

    private fun share(phone: Boolean) = object : FileShare {
        override val sharesFiles = phone
        override fun open(path: String, mime: String) = PlatformResult.Ok(Unit)
        override fun share(path: String, mime: String, title: String) = PlatformResult.Ok(Unit)
        override suspend fun saveToGallery(path: String, mime: String, displayName: String) = PlatformResult.Ok("Movies/AutoFlow/$displayName")
        override suspend fun saveToDownloads(path: String, mime: String, displayName: String) = PlatformResult.Ok("Download/AutoFlow/$displayName")
        override fun openFolder(path: String) = PlatformResult.Ok(Unit)
    }
}
