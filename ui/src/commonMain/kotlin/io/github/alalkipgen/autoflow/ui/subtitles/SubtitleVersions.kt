package io.github.alalkipgen.autoflow.ui.subtitles

import io.github.alalkipgen.autoflow.core.CoreError
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.SubtitlesTool
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlin.time.Clock

/** `st` versions of the open Subtitles project (D-79): read one, or save a new one and move only the pointer. */
internal class SubtitleVersions(private val session: ProjectSession, private val fs: FileSystem, private val clock: Clock) {
    fun read(dir: String, id: ArtifactId): SubtitleDoc? =
        (ArtifactStore(fs, dir, clock).readBytes(id) as? CoreResult.Ok)?.value?.let {
            runCatching { CoreJson.decodeFromString(SubtitleDoc.serializer(), it.decodeToString()) }.getOrNull()
        }

    /** Saves [doc] after the current version (`derivedFrom`) and points `project.subtitles.current` at it. */
    fun save(dir: String, doc: SubtitleDoc): CoreResult<Pair<ArtifactId, SubtitleDoc>> {
        val open = session.open?.takeIf { it.dir == dir } ?: return CoreResult.Err(CoreError.InvalidInput(ProjectSession.OTHER_PROJECT))
        val next = doc.copy(derivedFrom = open.project.subtitles?.current)
        val bytes = CoreJson.encodeToString(SubtitleDoc.serializer(), next).encodeToByteArray()
        val id = when (val r = ArtifactStore(fs, dir, clock).put(bytes, ArtifactKind.SUBTITLES, "json")) {
            is CoreResult.Ok -> r.value.id
            is CoreResult.Err -> return r
        }
        return when (val r = session.update(dir, open.project.id) { it.copy(subtitles = (it.subtitles ?: SubtitlesTool()).copy(current = id)) }) {
            is CoreResult.Ok -> CoreResult.Ok(id to next)
            is CoreResult.Err -> r
        }
    }
}
