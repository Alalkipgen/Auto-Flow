package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.model.*
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI draft only. Existing ArtifactStore/ProjectSession retain ownership of immutable data, locks and saves. */
data class BlockDraft(val token: Long, val dir: String, val project: Project, val story: RecapStory, val block: RecapBlock)
data class BlockEditorState(val draft: BlockDraft? = null, val problem: UiNotice? = null, val editingClips: Boolean = false)

class RecapBlockEditor(private val session: ProjectSession, private val fs: FileSystem, private val changed: () -> Unit) {
    private val mutable = MutableStateFlow(BlockEditorState())
    val state = mutable.asStateFlow()
    private var token = 0L

    fun edit(id: String, clips: Boolean = false) {
        cancel()
        val open = session.open ?: return
        val artifact = open.project.recap?.story ?: return
        val story = (session.readJson(artifact, RecapStory.serializer()) as? CoreResult.Ok)?.value ?: return
        val block = story.blocks.singleOrNull { it.id == id } ?: return
        mutable.value = BlockEditorState(BlockDraft(++token, open.dir, open.project, story, block), editingClips = clips)
    }
    fun cancel() { mutable.value = BlockEditorState() }

    fun save(text: String, speakAs: String): Boolean {
        val draft = state.value.draft ?: return false
        if (state.value.editingClips) return false
        return saveBlock(draft.block.copy(textMy = text.trim(), speakMy = speakAs.trim().takeIf { it.isNotEmpty() }))
    }
    fun saveClips(clips: List<RecapClip>): Boolean {
        val draft = state.value.draft ?: return false
        if (!state.value.editingClips) return false
        if (!ClipRangeInput.valid(clips, draft.project.recap?.movie?.durationMs ?: 0))
            return fail(UiNotice(UiText.V3_CLIP_INVALID))
        return saveBlock(draft.block.copy(clips = clips.toList()))
    }
    private fun saveBlock(block: RecapBlock): Boolean {
        val draft = state.value.draft ?: return false
        val open = session.open ?: return fail(UiNotice(UiText.LAYER_PROJECT_CLOSED))
        if (open.dir != draft.dir || open.project.id != draft.project.id) return fail(UiNotice(UiText.LAYER_PROJECT_CHANGED))
        if (open.project.updatedAt != draft.project.updatedAt) return fail(UiNotice(UiText.V3_BLOCK_REVISION_CHANGED))
        if (block == draft.block) { cancel(); return true }
        val story = draft.story.copy(blocks = draft.story.blocks.map { if (it.id == block.id) block else it })
        val bytes = CoreJson.encodeToString(RecapStory.serializer(), story).encodeToByteArray()
        val artifact = when (val result = ArtifactStore(fs, draft.dir).put(bytes, ArtifactKind.RECAP_SCRIPT, "json", provider = story.source)) {
            is CoreResult.Ok -> result.value.id
            is CoreResult.Err -> return fail(UiNotice(UiText.RESULT_DETAIL, "detail" to result.error.message))
        }
        // Never apply an index against a newly opened project. A failed revision save may leave only an immutable orphan.
        val recap = draft.project.recap ?: return false
        return when (val result = session.save(draft.project.copy(recap = recap.copy(story = artifact, storyApproved = false)))) {
            is CoreResult.Err -> fail(UiNotice(UiText.RESULT_DETAIL, "detail" to result.error.message))
            is CoreResult.Ok -> { cancel(); changed(); true }
        }
    }
    private fun fail(problem: UiNotice): Boolean { mutable.value = state.value.copy(problem = problem); return false }
}
