package io.github.alalkipgen.autoflow.platform

import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.io.File
import javax.swing.JFileChooser
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [FilePicker] with the native AWT file dialog for files and Swing's chooser for folders (AWT has no folder
 * dialog on Windows). Dialogs are modal to [owner] (the Compose window) and run on the AWT event thread.
 */
class SwingFilePicker(
    private val owner: () -> Frame? = { null },
    private val headless: () -> Boolean = GraphicsEnvironment::isHeadless,
) : FilePicker {
    override suspend fun pickFolder(title: String, startDir: String?): PlatformResult<String?> {
        if (headless()) return PlatformResult.Unsupported(NO_DIALOGS)
        return guarded {
            onEdt {
                val chooser = JFileChooser(startDir?.let(::File)).apply {
                    dialogTitle = title
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    isAcceptAllFileFilterUsed = false
                }
                val picked = chooser.showOpenDialog(owner()) == JFileChooser.APPROVE_OPTION
                PlatformResult.Ok(chooser.selectedFile?.takeIf { picked }?.path?.toPortablePath())
            }
        }
    }

    override suspend fun pickFiles(
        title: String,
        extensions: Set<String>,
        multiple: Boolean,
        startDir: String?,
    ): PlatformResult<List<String>> {
        if (headless()) return PlatformResult.Unsupported(NO_DIALOGS)
        return guarded {
            val picked = onEdt {
                val dialog = FileDialog(owner(), title, FileDialog.LOAD).apply {
                    isMultipleMode = multiple
                    startDir?.let { directory = File(it).path }
                    if (extensions.isNotEmpty()) {
                        filenameFilter = java.io.FilenameFilter { _, name -> hasExtension(name, extensions) }
                        // Windows ignores filenameFilter but treats a wildcard file name as the type filter.
                        file = extensions.joinToString(";") { "*.$it" }
                    }
                }
                try {
                    dialog.isVisible = true
                    dialog.files.map { it.path.toPortablePath() }
                } finally {
                    dialog.dispose()
                }
            }
            filterPicked(picked, extensions)
        }
    }

    private suspend fun <T> guarded(block: suspend () -> PlatformResult<T>): PlatformResult<T> = try {
        block()
    } catch (e: java.awt.HeadlessException) {
        PlatformResult.Unsupported(NO_DIALOGS)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        PlatformResult.Failed("Could not open the file dialog: ${e.message ?: e::class.simpleName}")
    }

    private companion object {
        const val NO_DIALOGS = "File dialogs are not available in this session"
    }
}

/** Keeps files with an allowed extension; `Failed` when the user picked only files of other types. */
internal fun filterPicked(picked: List<String>, extensions: Set<String>): PlatformResult<List<String>> {
    val kept = picked.filter { hasExtension(it, extensions) }
    if (picked.isNotEmpty() && kept.isEmpty()) {
        return PlatformResult.Failed("Choose a ${extensions.sorted().joinToString(" / ") { ".$it" }} file")
    }
    return PlatformResult.Ok(kept)
}

private suspend fun <T> onEdt(block: () -> T): T {
    if (EventQueue.isDispatchThread()) return block()
    return suspendCancellableCoroutine { cont ->
        EventQueue.invokeLater {
            runCatching(block).fold({ cont.resume(it) }, { cont.resumeWithException(it) })
        }
    }
}
