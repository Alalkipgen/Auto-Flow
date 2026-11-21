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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope

/** An in-memory Subtitles project named [name]: [lines] (ids c1, c2, …) over a [durationMs] video. Nothing touches the disk. */
internal class SubtitlesFixture(lines: List<Line>, durationMs: Long, name: String = "Clip", scope: CoroutineScope = MainScope()) {
    data class Line(val startMs: Long, val endMs: Long, val text: String, val my: String? = null)

    val fs = InMemoryFileSystem(); val store = ProjectStore(fs); val current = CurrentProject()
    val index = ProjectIndex(fs, "/app/projects.json")
    val dir = "/p/Clip.autoflow"
    val session = ProjectSession(fs, store, index, current)
    init {
        val doc = SubtitleOps.fromCues(lines.map { SrtCue(it.startMs, it.endMs, it.text) }, SubtitleDoc.SOURCE_SRT)
            .let { d -> d.copy(cues = d.cues.mapIndexed { i, c -> c.copy(textMy = lines[i].my) }) }
        val created = (store.create(dir, name, Mode.SUBTITLES) as CoreResult.Ok).value
        val st = (ArtifactStore(fs, dir).put(CoreJson.encodeToString(SubtitleDoc.serializer(), doc).encodeToByteArray(),
            ArtifactKind.SUBTITLES, "json") as CoreResult.Ok).value.id
        val video = SourceVideo("source/movie.mp4", 9, durationMs, 1920, 1080, 25.0)
        current.set(OpenProject(dir, (store.save(dir, created.copy(subtitles = SubtitlesTool(video = video, current = st))) as CoreResult.Ok).value))
    }
    val model = SubtitlesModel(session, fs, DashboardModel(fs, store, index, SettingsStore(fs, "/app/s.json"), FakePicker(), current),
        null, scope, probe = { null })
    val editor = SubtitleEditorModel(session, fs, scope)

    /** A paused decoder at the seek: a frame to draw the line on, no sound. */
    val paused: SubtitlePlayer = { _, req, m, report ->
        LaunchedEffect(req) { report(MovieSnapshot(positionMs = req.sourceMs, durationMs = durationMs, phase = MoviePhase.PAUSED)) }
        Box(m.background(AutoFlowColors.Card))
    }
}
