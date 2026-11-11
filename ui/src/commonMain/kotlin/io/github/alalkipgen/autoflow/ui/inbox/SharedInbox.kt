package io.github.alalkipgen.autoflow.ui.inbox

import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** [waiting] = shared files not yet in a project's inbox; [arrivals] counts hand-overs (the shell opens Inbox). */
data class SharedInboxState(val waiting: List<String> = emptyList(), val arrivals: Int = 0, val message: String? = null)

/**
 * Share-sheet import → Inbox (PLAN 1F): files shared to Auto Flow from another app (Gallery, Telegram, Files…)
 * are first copied by the platform into [holdDir] — next to the projects, so moving them is a rename — and then
 * moved into the open project's `inbox/`, where the Inbox screen attaches them to a shot. With no project open
 * they wait in [holdDir] (nothing is lost) until the user opens one and taps "Move here" on Inbox.
 */
class SharedInbox(private val fs: FileSystem, private val session: ProjectSession, val holdDir: String) {
    private val _state = MutableStateFlow(SharedInboxState(waiting = scan()))
    val state: StateFlow<SharedInboxState> = _state.asStateFlow()

    /**
     * The platform finished copying a share into [holdDir] ([failed] = items it could not read). Moves the files
     * into the open project's inbox when there is one, and bumps [SharedInboxState.arrivals] so Inbox opens.
     */
    fun received(failed: Int = 0) {
        val moved = if (session.open != null) moveAll() else null
        val waiting = scan()
        val notes = buildList {
            when {
                moved == null && waiting.isNotEmpty() -> add("${files(waiting.size)} shared: open a project, then tap \"$MOVE_BUTTON\" here.")
                moved != null && moved.first > 0 -> add("${files(moved.first)} shared into this project's inbox. Attach each one to a shot.")
            }
            moved?.second?.let { add(it) }
            if (failed > 0) add("${files(failed)} could not be read from the other app.")
        }
        _state.value = SharedInboxState(waiting, _state.value.arrivals + 1, notes.joinToString(" ").ifEmpty { null })
    }

    /** "Move here": the waiting files into the open project's inbox. */
    fun moveHere() {
        val (moved, error) = moveAll() ?: return say("Open a project first.")
        val waiting = scan()
        _state.value = _state.value.copy(
            waiting = waiting,
            message = error ?: if (moved > 0) "${files(moved)} moved into this project's inbox." else null,
        )
    }

    /** Re-reads [holdDir] (Inbox calls it when it opens). */
    fun refresh() {
        _state.value = _state.value.copy(waiting = scan())
    }

    fun dismissMessage() = say(null)

    private fun say(message: String?) {
        _state.value = _state.value.copy(message = message)
    }

    /** Moves every waiting file into `<project>/inbox/` under a free name; null when no project is open. */
    private fun moveAll(): Pair<Int, String?>? {
        val open = session.open ?: return null
        val inbox = joinPath(open.dir, InboxModel.INBOX_DIR)
        var moved = 0
        var error: String? = null
        try {
            fs.createDirectories(inbox)
            val taken = fs.list(inbox).toMutableSet()
            for (name in scan()) {
                val target = freeName(name, taken)
                try {
                    fs.atomicMove(joinPath(holdDir, name), joinPath(inbox, target))
                    taken += target
                    moved++
                } catch (e: Exception) {
                    error = "Could not move $name: ${e.message ?: e::class.simpleName}"
                }
            }
        } catch (e: Exception) {
            error = "Could not open the inbox: ${e.message ?: e::class.simpleName}"
        }
        return moved to error
    }

    /** Finished copies only: dot files are copies still being written. */
    private fun scan(): List<String> = try {
        if (fs.exists(holdDir)) fs.list(holdDir).filter { !it.startsWith(".") }.sorted() else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        const val DIR_NAME = ".shared-inbox"
        const val MOVE_BUTTON = "Move here"

        /** [name], else `name (2).ext`, `name (3).ext`… — never overwrites a file already in the inbox. */
        fun freeName(name: String, taken: Set<String>): String {
            if (name !in taken) return name
            val dot = name.lastIndexOf('.').takeIf { it > 0 }
            val base = if (dot == null) name else name.substring(0, dot)
            val ext = if (dot == null) "" else name.substring(dot)
            return generateSequence(2) { it + 1 }.map { "$base ($it)$ext" }.first { it !in taken }
        }

        /** A shared item's file name: path separators and a leading dot removed (dot files are skipped). */
        fun safeName(name: String): String =
            name.replace('/', '_').replace('\\', '_').trim().trimStart('.').trim().ifEmpty { "shared" }

        private fun files(n: Int) = if (n == 1) "1 file" else "$n files"
    }
}
