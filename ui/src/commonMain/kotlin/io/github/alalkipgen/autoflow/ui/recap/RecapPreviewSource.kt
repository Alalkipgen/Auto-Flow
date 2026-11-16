package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.media.render.RecapRender
import io.github.alalkipgen.autoflow.media.render.RecapTimeline
import io.github.alalkipgen.autoflow.media.render.RenderPresets
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlinx.coroutines.flow.asStateFlow

/** Resolves only the captured open project's native-v2 references. No v1 adapter, writes or asynchronous attachment. */
class RecapPreviewSource(private val session: ProjectSession, private val fs: FileSystem, private val media: MediaTools?) {
    val projects get() = session.state
    data class Cursor(val projectKey: String, val outputMs: Long)
    private val mutableCursor = kotlinx.coroutines.flow.MutableStateFlow<Cursor?>(null)
    val cursor = mutableCursor.asStateFlow()
    fun currentPosition(): Long? = mutableCursor.value?.takeIf { it.projectKey == projectKey() }?.outputMs
    private fun projectKey(): String? = session.open?.let { "${it.dir}:${it.project.id}:${it.project.updatedAt}" }
    fun position(source: Source, outputMs: Long) {
        if (source.key == projectKey()) mutableCursor.value = Cursor(source.key, outputMs.coerceIn(0, (source.timeline.durationMs - 1).coerceAtLeast(0)))
    }
    data class Source(val key: String, val path: String, val timeline: RecapTimeline, val vlcDir: String?)
    fun resolve(state: RecapUiState): Source? {
        val open = session.open ?: return null
        if (open.dir != state.projectDir || open.project.recap != state.recap) return null
        val recap = open.project.recap ?: return null
        val movie = recap.movie ?: return null
        val story = state.story ?: return null
        val artifacts = ArtifactStore(fs, open.dir)
        val output = RenderPresets.preview(recap.visual.outputRatio ?: open.project.settings.ratio)
        val timeline = RecapRender.timeline(recap, story, { take ->
            val item = (artifacts.get(take.audio) as? CoreResult.Ok)?.value
            item?.let { joinPath(joinPath(open.dir, ArtifactStore.ARTIFACTS_DIR), it.file) }?.takeIf(fs::exists)
        }, output, state.lexicon)
        if (timeline.clips.isEmpty()) return null
        return Source("${open.dir}:${open.project.id}:${open.project.updatedAt}", joinPath(open.dir, movie.file), timeline, media?.vlcDir())
    }
}
