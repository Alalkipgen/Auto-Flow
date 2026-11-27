package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.*
import io.github.alalkipgen.autoflow.core.model.*
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession

data class BlockVoiceRequest(val dir: String, val project: Project, val blockId: String, val spoken: String)
data class TakeHistoryEntry(val number: Int, val take: BlockTake, val selected: Boolean, val file: String?)

/** Presentation adapter only: read immutable history and guard confirmation before the existing speak action. */
class RecapVoiceInspector(private val session: ProjectSession, private val fs: FileSystem,
    private val lexicon: () -> Lexicon, private val speak: (Int) -> Unit,
) {
    fun begin(id: String): BlockVoiceRequest? {
        val open = session.open ?: return null
        val draft = BlockVoiceRequest(open.dir, open.project, id, "")
        val block = story(draft)?.blocks?.singleOrNull { it.id == id } ?: return null
        return draft.copy(spoken = SpokenText.of(block, lexicon())).takeIf(::current)
    }

    fun current(request: BlockVoiceRequest): Boolean =
        session.open?.let { it.dir == request.dir && it.project == request.project } == true &&
            session.projectAt(request.dir) == request.project

    fun confirm(request: BlockVoiceRequest): Boolean {
        if (!current(request)) return false
        val blocks = story(request)?.blocks ?: return false
        val index = blocks.indexOfFirst { it.id == request.blockId }.takeIf { it >= 0 } ?: return false
        if (SpokenText.of(blocks[index], lexicon()) != request.spoken) return false
        if (!current(request)) return false
        // No suspension between checking this project's revision and the existing action capturing its target.
        speak(index)
        return true
    }

    /** Called off the UI thread. A late read from a switched/edited project is discarded, not relabelled. */
    fun history(request: BlockVoiceRequest): List<TakeHistoryEntry>? {
        if (!current(request)) return null
        val recap = request.project.recap ?: return emptyList()
        val takes = recap.takes.filter { it.block == request.blockId }
        val selected = takes.indexOfLast { it == recap.takeFor(request.blockId) }
        val artifacts = ArtifactStore(fs, request.dir)
        val rows = takes.mapIndexed { i, take ->
            val file = (artifacts.get(take.audio) as? CoreResult.Ok)?.value?.file
                ?.let { joinPath(joinPath(request.dir, ArtifactStore.ARTIFACTS_DIR), it) }
                ?.takeIf { runCatching { fs.exists(it) }.getOrDefault(false) }
            TakeHistoryEntry(i + 1, take, i == selected, file)
        }
        return rows.takeIf { current(request) }
    }

    private fun story(request: BlockVoiceRequest): RecapStory? = request.project.recap?.story?.let {
        val bytes = (ArtifactStore(fs, request.dir).readBytes(it) as? CoreResult.Ok)?.value ?: return@let null
        runCatching { CoreJson.decodeFromString(RecapStory.serializer(), bytes.decodeToString()) }.getOrNull()
    }
}