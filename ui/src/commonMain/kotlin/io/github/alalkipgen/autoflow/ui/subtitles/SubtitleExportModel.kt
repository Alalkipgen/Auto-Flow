package io.github.alalkipgen.autoflow.ui.subtitles

import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.subtitles.ExportBlock
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleChecks
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleExport
import io.github.alalkipgen.autoflow.core.subtitles.TextChoice
import io.github.alalkipgen.autoflow.platform.FileShare
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.i18n.UiNotice
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The export dialog: the last written [file], what happened ([notice], [ok]), and a Downloads copy in progress. */
data class SubtitleExportUi(val file: String? = null, val notice: UiNotice? = null, val ok: Boolean = false, val busy: Boolean = false)

/**
 * `.srt` export of a saved subtitle version (specs/quick-tools.md §5 Export, S-4a): written once to
 * `<project>/exports/<name>.srt` through `<name>.srt.part` and a rename; the same version and language reuse the
 * file (versions are immutable), so nothing is overwritten. Then Share / Save to Downloads where the platform
 * shares files ([FileShare.sharesFiles]), else the path and Open folder (desktop).
 */
class SubtitleExportModel(
    private val session: ProjectSession,
    private val fs: FileSystem,
    private val share: FileShare?,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SubtitleExportUi())
    val state: StateFlow<SubtitleExportUi> = _state.asStateFlow()
    val sharesFiles: Boolean get() = share?.sharesFiles ?: false

    fun clear() { _state.value = SubtitleExportUi() }

    /** Writes the file if it is not there yet; null with a notice when the export is blocked or the write failed. */
    fun write(doc: SubtitleDoc, version: ArtifactId?, choice: TextChoice): String? {
        val open = session.open ?: return null
        SubtitleChecks.exportBlocks(doc).firstOrNull()?.let { b ->
            say(UiNotice(if (b == ExportBlock.NO_LINES) UiText.SUB_EXPORT_NO_LINES else UiText.SUB_EXPORT_BAD_TIMES)); return null
        }
        val dir = joinPath(open.dir, SubtitleExport.EXPORTS_DIR)
        val path = joinPath(dir, SubtitleExport.fileName(open.project.name, choice, version?.value.orEmpty()))
        return try {
            if (!fs.exists(path)) {
                fs.createDirectories(dir)
                fs.writeDurably("$path.part", SubtitleExport.srt(doc, choice).encodeToByteArray())
                fs.atomicMove("$path.part", path)
            }
            _state.value = _state.value.copy(file = path)
            path
        } catch (e: Exception) {
            say(UiNotice(UiText.RESULT_DETAIL, "detail" to (e.message ?: e::class.simpleName.orEmpty()))); null
        }
    }

    /** Desktop Save: the file and where it is. */
    fun save(doc: SubtitleDoc, version: ArtifactId?, choice: TextChoice) {
        val path = write(doc, version, choice) ?: return
        say(UiNotice(UiText.SUB_EXPORT_SAVED, "path" to path), ok = true)
    }

    fun share(doc: SubtitleDoc, version: ArtifactId?, choice: TextChoice, title: String) {
        val path = write(doc, version, choice) ?: return
        result(share?.share(path, SubtitleExport.SRT_MIME, title) ?: unsupported(path))
    }

    fun openFolder(doc: SubtitleDoc, version: ArtifactId?, choice: TextChoice) {
        val path = write(doc, version, choice) ?: return
        val dir = path.substringBeforeLast('/')
        result(share?.openFolder(dir) ?: unsupported(path))
    }

    fun saveToDownloads(doc: SubtitleDoc, version: ArtifactId?, choice: TextChoice) {
        if (_state.value.busy) return
        val path = write(doc, version, choice) ?: return
        val to = share ?: return result(unsupported(path))
        _state.value = _state.value.copy(busy = true, notice = null)
        scope.launch {
            val r = to.saveToDownloads(path, SubtitleExport.SRT_MIME, path.substringAfterLast('/'))
            _state.value = _state.value.copy(busy = false)
            when (r) {
                is PlatformResult.Ok -> say(UiNotice(UiText.SUB_EXPORT_DOWNLOADED, "path" to r.value), ok = true)
                is PlatformResult.Failed -> say(UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason))
                is PlatformResult.Unsupported -> say(UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason))
            }
        }
    }

    private fun unsupported(path: String) = PlatformResult.Unsupported("The file is in $path")

    private fun result(r: PlatformResult<Unit>) = when (r) {
        is PlatformResult.Ok -> say(null)
        is PlatformResult.Failed -> say(UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason))
        is PlatformResult.Unsupported -> say(UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason))
    }

    private fun say(notice: UiNotice?, ok: Boolean = false) { _state.value = _state.value.copy(notice = notice, ok = ok) }
}
