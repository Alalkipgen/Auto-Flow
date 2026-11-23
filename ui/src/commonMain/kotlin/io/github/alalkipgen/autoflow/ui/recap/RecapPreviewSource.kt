package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.media.render.RecapRender
import io.github.alalkipgen.autoflow.media.render.RecapTimeline
import io.github.alalkipgen.autoflow.media.render.RecapTimelineClip
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
    /** F-5: the background music the render mixes under the voice, looped from the start, at its volume. */
    data class Music(val file: String, val volume: Float)
    data class Source(val key: String, val path: String, val timeline: RecapTimeline, val vlcDir: String?, val projectDir: String,
        val music: Music? = null)
    /** The result is discarded if this revision stops being current while disk IO/decoding is in flight. */
    internal fun images(source: Source, recap: io.github.alalkipgen.autoflow.core.recap.RecapV2): Map<String, androidx.compose.ui.graphics.ImageBitmap>? {
        if (source.key != projectKey()) return null
        val loaded = NativePreviewAssets(fs).load(source.projectDir, recap)
        return loaded.takeIf { source.key == projectKey() }
    }
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
        val music = recap.audio.bgm?.let { id -> (artifacts.get(id) as? CoreResult.Ok)?.value }
            ?.let { joinPath(joinPath(open.dir, ArtifactStore.ARTIFACTS_DIR), it.file) }?.takeIf(fs::exists)
            ?.let { Music(it, recap.audio.bgmVolume.toFloat()) }
        return Source("${open.dir}:${open.project.id}:${open.project.updatedAt}", joinPath(open.dir, movie.file), timeline, media?.vlcDir(), open.dir, music)
    }

    /**
     * Group 2 A §1 (PR 3): the Source step's top card — the movie itself on [PickedScenes.timeline] (no story needed, no voice
     * or music: the movie plays with its own sound). Null without a movie, or when [state] is not the open project.
     */
    fun scenes(state: RecapUiState): Source? {
        val open = session.open ?: return null
        if (open.dir != state.projectDir || open.project.recap != state.recap) return null
        val recap = open.project.recap ?: return null
        val movie = recap.movie ?: return null
        val timeline = PickedScenes.timeline(recap)
        if (timeline.clips.isEmpty()) return null
        return Source("${open.dir}:${open.project.id}:${open.project.updatedAt}", joinPath(open.dir, movie.file), timeline, media?.vlcDir(), open.dir)
    }
}

/**
 * The Source card's scene clock (as `MusicPreviewClock`): the picked scenes in order laid end to end, each at its own
 * speed from its own start; nothing picked yet = the whole movie as one interval. Clip ids are `scene-<index in scenes>`.
 */
internal object PickedScenes {
    private const val PREFIX = "scene-"
    const val MOVIE = "movie"

    fun timeline(recap: io.github.alalkipgen.autoflow.core.recap.RecapV2): RecapTimeline {
        val movie = recap.movie ?: return RecapTimeline(emptyList(), emptyList())
        val length = movie.durationMs.takeIf { it > 0 } ?: recap.scenes.maxOfOrNull { it.endMs } ?: 0L
        val picks = recap.scenes.withIndex().filter { it.value.picked && it.value.endMs > it.value.startMs }
        if (picks.isEmpty()) return RecapTimeline(if (length > 0) listOf(RecapTimelineClip(MOVIE, 0, 0, length, 0, 1f, length)) else emptyList(), emptyList())
        var at = 0L
        return RecapTimeline(picks.map { (index, scene) ->
            RecapTimelineClip("$PREFIX$index", 0, at, at + (scene.endMs - scene.startMs), scene.startMs, 1f, length).also { at = it.outputToMs }
        }, emptyList())
    }

    /** The scene (index in `recap.scenes`) a [timeline] clip plays, or null for the whole-movie interval. */
    fun index(clip: RecapTimelineClip): Int? = clip.blockId.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.toIntOrNull()

    /** The output time that shows movie time [movieMs] on [timeline], or null when no clip plays it. */
    fun outputAt(timeline: RecapTimeline, movieMs: Long): Long? = timeline.clips.firstOrNull { c ->
        movieMs >= c.sourceFromMs && movieMs < c.sourceFromMs + c.durationMs
    }?.outputAt(movieMs)
}
